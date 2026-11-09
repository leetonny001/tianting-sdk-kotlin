// 图片和文件（PRD-chat 第 6 节 F-FILE-01、03、04、07，第 13.1 节）：/v1/files 下的接口、对象存储的上传下载。
// 图片处理（压缩、缩略图、去 EXIF）不在 SDK（R-CF-24）：App 处理好写成文件再交给 sendImage。
// 预签名地址和文件名不进日志、不进异常信息、不持久化（F-FILE-04 规则 5，第 13.1 节）。语义同 sdk-ts 的 files.ts。
package com.tianting.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.pow
import kotlin.math.roundToLong

/** 一个本地文件和它的声明类型（签进 PUT 地址，RC2-08）。 */
public class LocalFile(public val file: File, public val mime: String)

/**
 * App 处理好的图片（F-FILE-02 规则 2～4）：原图和缩略图都已重新编码、去掉 EXIF，写成了文件；GIF 不重新编码。
 * SDK 先把两个文件复制进自己的沙盒（规则 2），之后 App 可以删掉自己的。name 只存服务端数据库，默认 "image"。
 */
public class ProcessedImage(
    public val original: LocalFile,
    /** 长边 ≤ 480，≤ 512 KiB（F-FILE-02 规则 3）。 */
    public val thumb: LocalFile,
    public val width: Int,
    public val height: Int,
    public val name: String? = null,
)

/** 要发送的文件（F-FILE-03）：内容不重新编码。App 从 SAF 读出来写成文件（或直接给路径），SDK 复制进沙盒。 */
public class FileInput(public val file: File, public val name: String, public val mime: String = "application/octet-stream")

public enum class FileVariant(public val wire: String) { ORIGINAL("original"), THUMB("thumb") }

public data class FileUrlItem(val fileId: String, val variant: FileVariant)

/** 一项的结果：有地址，或者 code（4048：不可访问，和不存在相同）。地址只在内存里用，不要持久化、打日志。 */
public class FileUrlResult(
    public val fileId: String,
    public val variant: FileVariant,
    public val url: String? = null,
    public val expiresAt: Long? = null,
    public val code: Int? = null,
) {
    override fun toString(): String = "FileUrlResult($fileId ${variant.wire} ${if (url != null) "url" else "code=$code"})"
}

public class PresignedPut(public val url: String, public val headers: Map<String, String>, public val expiresAt: Long) {
    override fun toString(): String = "PresignedPut(…)"
}

public class UploadRequest(
    public val kind: String,
    public val name: String,
    public val size: Long,
    public val mime: String,
    public val thumbSize: Long? = null,
    public val thumbMime: String? = null,
)

public class UploadResponse(public val fileId: String, public val put: PresignedPut, public val thumbPut: PresignedPut?)

@Serializable
public data class CompleteResponse(val fileId: String, val size: Long, val mime: String, val width: Int? = null, val height: Int? = null)

/**
 * 带 Bearer 调 /v1 下的接口。AccountClient 实现了它（4011 按 F-ACC-03 规则 7 重试）；演示模式用 StaticTokenAuth。
 * 失败时抛 AccountError（有错误码）或 AccountNetworkError（没有拿到错误码）。
 */
public interface FileAuth {
    public suspend fun authorized(method: String, path: String, body: JsonElement?): JsonElement
}

/** 演示模式（connect(token)）的 FileAuth：固定 token，没有刷新。服务端的文件接口不要求 sid。 */
public class StaticTokenAuth(
    baseUrl: String,
    private val token: String,
    private val http: HttpTransport = OkHttpHttpTransport(),
) : FileAuth {
    private val base = baseUrl.trimEnd('/')

    override suspend fun authorized(method: String, path: String, body: JsonElement?): JsonElement {
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/json"
        headers["Authorization"] = "Bearer $token"
        if (body != null) headers["Content-Type"] = "application/json"
        val done = CompletableDeferred<HttpResponse?>()
        val call = http.request(
            HttpRequest(method, base + path, headers, body?.toString()),
            object : HttpCallback {
                override fun onResponse(response: HttpResponse) { done.complete(response) }
                override fun onFailure() { done.complete(null) }
            },
        )
        val r = try {
            done.await()
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } ?: throw AccountNetworkError()
        val data = try {
            if (r.text.isEmpty()) JsonObject(emptyMap()) else ImJson.parseToJsonElement(r.text)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (r.status in 200..299 && data != null) return data
        val e = data as? JsonObject
        val code = (e?.get("code") as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: throw AccountNetworkError("status ${r.status}")
        throw AccountError(
            r.status,
            code,
            (e["msg"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "",
            (e["reason"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
            e["details"] as? JsonObject,
        )
    }
}

/** 对象存储一次传输的结果回调。只回调一次，可以在任意线程。失败不带原因（异常信息里可能有地址）。 */
public interface ObjectCallback {
    public fun onDone(status: Int)
    public fun onFailure()
}

/**
 * 直接和对象存储收发（F-FILE-01 规则 2、F-FILE-03 规则 3）：生产用 OkHttpObjectTransport，测试用 FakeObjects。
 * onProgress 是已传的字节数，可以在任意线程回调。GET 只有 2xx 时把内容写进 dest。
 */
public interface ObjectTransport {
    public fun put(url: String, headers: Map<String, String>, file: File, onProgress: (Long) -> Unit, callback: ObjectCallback): HttpCall
    public fun get(url: String, dest: File, onProgress: (Long) -> Unit, callback: ObjectCallback): HttpCall
}

/**
 * OkHttp 的对象存储传输：不重试、不跟随重定向（同 OkHttpHttpTransport，K4），不装日志拦截器（地址里有签名，F-FILE-04 规则 5）。
 * 请求体 one-shot，按块读文件并报告进度。没有总超时：大文件慢网要传很久，读写各 60 秒没有进展才算失败。
 */
public class OkHttpObjectTransport(client: OkHttpClient = OkHttpClient()) : ObjectTransport {
    private val client: OkHttpClient = client.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun put(url: String, headers: Map<String, String>, file: File, onProgress: (Long) -> Unit, callback: ObjectCallback): HttpCall {
        val type = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.toMediaTypeOrNull()
        val body = object : RequestBody() {
            override fun contentType(): MediaType? = type
            override fun contentLength(): Long = file.length()
            override fun isOneShot(): Boolean = true
            override fun writeTo(sink: BufferedSink) {
                file.source().use { src ->
                    var sent = 0L
                    val buf = okio.Buffer()
                    while (true) {
                        val n = src.read(buf, 64 * 1024)
                        if (n < 0) break
                        sink.write(buf, n)
                        sent += n
                        onProgress(sent)
                    }
                }
            }
        }
        val call = try {
            val b = Request.Builder().url(url).put(body)
            // Content-Length 由请求体决定（签进地址的值和文件大小相同）；Host 由 OkHttp 设置。
            headers.forEach { (k, v) -> if (!k.equals("Content-Length", true) && !k.equals("Host", true)) b.header(k, v) }
            client.newCall(b.build())
        } catch (_: IllegalArgumentException) {
            callback.onFailure()
            return HttpCall { }
        }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) = callback.onFailure()
                override fun onResponse(call: Call, response: Response) {
                    val code = response.use { it.code }
                    callback.onDone(code)
                }
            },
        )
        return HttpCall { call.cancel() }
    }

    override fun get(url: String, dest: File, onProgress: (Long) -> Unit, callback: ObjectCallback): HttpCall {
        val call = try {
            client.newCall(Request.Builder().url(url).get().build())
        } catch (_: IllegalArgumentException) {
            callback.onFailure()
            return HttpCall { }
        }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) = callback.onFailure()
                override fun onResponse(call: Call, response: Response) {
                    val ok = try {
                        response.use { r ->
                            if (r.code in 200..299) {
                                dest.sink().buffer().use { out ->
                                    val src = r.body!!.source()
                                    var got = 0L
                                    while (true) {
                                        val n = src.read(out.buffer, 64 * 1024)
                                        if (n < 0) break
                                        out.emitCompleteSegments()
                                        got += n
                                        onProgress(got)
                                    }
                                }
                            }
                            r.code
                        }
                    } catch (_: IOException) {
                        null
                    }
                    if (ok == null) callback.onFailure() else callback.onDone(ok)
                }
            },
        )
        return HttpCall { call.cancel() }
    }
}

/** 对象存储那一步失败（拒绝或网络错误）。不带地址。status 为 0 表示没有拿到响应。 */
public class ObjectTransferError(public val status: Int) : Exception(if (status != 0) "object transfer status $status" else "object transfer failed")

/** 单次最多 50 项（F-FILE-04 规则 1，超出 4131）。 */
public const val URLS_BATCH: Int = 50

/** complete 返回 5032：退避重试 3 次再转失败（F-FILE-07 异常）。 */
public const val COMPLETE_RETRIES: Int = 3
private const val COMPLETE_BACKOFF_MS = 1_000L
private const val JITTER = 0.2

/**
 * /v1/files 下的接口和对象存储传输（第 13.1 节）。没有自己的状态：批量合并、地址缓存、下载缓存在 IMClient（SDK 的单线程上）。
 * clock、random、logger 由 IMClient 注入同一份（测试用假时钟推进 complete 的退避）。
 */
public class FileClient(
    public val auth: FileAuth,
    public val objects: ObjectTransport = OkHttpObjectTransport(),
) {
    internal var clock: Clock? = null
    internal var random: () -> Double = { kotlin.random.Random.nextDouble() }
    internal var log: Logger = SilentLogger

    /** POST /v1/files/uploads。4292、4290（uploadPendingLimit）不重试，原样抛出（F-FILE-01 规则 6、6a）。 */
    public suspend fun createUpload(req: UploadRequest): UploadResponse {
        val body = buildJsonObject {
            put("kind", req.kind)
            put("name", req.name)
            put("size", req.size)
            put("mime", req.mime)
            if (req.thumbSize != null && req.thumbMime != null) {
                put("thumb", buildJsonObject { put("size", req.thumbSize); put("mime", req.thumbMime) })
            }
        }
        val r = auth.authorized("POST", "/v1/files/uploads", body) as? JsonObject ?: throw AccountNetworkError("invalid response")
        fun presigned(o: JsonElement?): PresignedPut? {
            val p = o as? JsonObject ?: return null
            val url = p["url"]?.jsonPrimitive?.contentOrNull ?: return null
            val headers = (p["headers"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            return PresignedPut(url, headers, p["expiresAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0)
        }
        val fileId = r["fileId"]?.jsonPrimitive?.contentOrNull ?: throw AccountNetworkError("invalid response")
        return UploadResponse(fileId, presigned(r["put"]) ?: throw AccountNetworkError("invalid response"), presigned(r["thumbPut"]))
    }

    /** PUT 一个变体：签进去的头原样带上（RC2-08）。非 2xx 抛 ObjectTransferError；协程取消时中止传输。 */
    public suspend fun put(target: PresignedPut, file: File, onProgress: (Long) -> Unit = {}) {
        val status = transfer { cb -> objects.put(target.url, target.headers, file, onProgress, cb) }
        if (status !in 200..299) {
            log.warn("files: put status $status")
            throw ObjectTransferError(status)
        }
    }

    /** GET 到 dest。非 2xx 抛 ObjectTransferError（403 一般是地址过期）。 */
    public suspend fun get(url: String, dest: File, onProgress: (Long) -> Unit = {}) {
        val status = transfer { cb -> objects.get(url, dest, onProgress, cb) }
        if (status !in 200..299) {
            log.warn("files: get status $status")
            throw ObjectTransferError(status)
        }
    }

    private suspend fun transfer(start: (ObjectCallback) -> HttpCall): Int {
        val status = suspendCancellableCoroutine<Int?> { cont ->
            val call = start(
                object : ObjectCallback {
                    override fun onDone(status: Int) { if (cont.isActive) cont.resume(status) }
                    override fun onFailure() { if (cont.isActive) cont.resume(null) }
                },
            )
            cont.invokeOnCancellation { call.cancel() }
        }
        return status ?: throw ObjectTransferError(0)
    }

    /** POST /v1/files/{id}/complete。5032 退避重试 3 次（1、2、4 秒，±20%），再失败抛出。 */
    public suspend fun complete(fileId: String): CompleteResponse {
        var i = 0
        while (true) {
            try {
                val r = auth.authorized("POST", "/v1/files/$fileId/complete", null)
                return try {
                    ImJson.decodeFromJsonElement(CompleteResponse.serializer(), r)
                } catch (_: IllegalArgumentException) {
                    throw AccountNetworkError("invalid response")
                }
            } catch (e: AccountError) {
                if (e.code != 5032 || i >= COMPLETE_RETRIES) throw e
                log.debug("files: complete 5032, retry ${i + 1}")
                val base = COMPLETE_BACKOFF_MS * 2.0.pow(i)
                sleep((base * (1 + (random() * 2 - 1) * JITTER)).roundToLong())
                i++
            }
        }
    }

    /** DELETE /v1/files/uploads/{id}（cancelUpload）：幂等，失败只记日志（尽力而为）。 */
    public suspend fun cancelUpload(fileId: String) {
        try {
            auth.authorized("DELETE", "/v1/files/uploads/$fileId", null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("files: cancel upload failed: ${if (e is IMError) e.code else "network"}")
        }
    }

    /** POST /v1/files/urls，一批 ≤ 50 项。不可访问的项 code=4048。 */
    public suspend fun urls(items: List<FileUrlItem>): List<FileUrlResult> {
        require(items.size <= URLS_BATCH)
        val body = buildJsonObject {
            put(
                "items",
                buildJsonArray {
                    for (i in items) add(buildJsonObject { put("fileId", i.fileId); put("variant", i.variant.wire) })
                },
            )
        }
        val r = auth.authorized("POST", "/v1/files/urls", body) as? JsonObject
        val got = (r?.get("items") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        return items.mapIndexed { idx, item ->
            val o = got.getOrNull(idx)?.takeIf { it.str("fileId") == item.fileId && it.str("variant") == item.variant.wire }
                ?: got.firstOrNull { it.str("fileId") == item.fileId && it.str("variant") == item.variant.wire }
            val url = o?.str("url")
            val exp = o?.get("expiresAt")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            if (url != null && exp != null) {
                FileUrlResult(item.fileId, item.variant, url, exp)
            } else {
                FileUrlResult(item.fileId, item.variant, code = o?.get("code")?.jsonPrimitive?.intOrNull ?: 4048)
            }
        }
    }

    private suspend fun sleep(ms: Long) {
        val c = clock ?: run {
            kotlinx.coroutines.delay(ms)
            return
        }
        suspendCancellableCoroutine { cont ->
            val t = c.schedule(ms) { if (cont.isActive) cont.resume(Unit) }
            cont.invokeOnCancellation { t.cancel() }
        }
    }
}

private fun JsonObject.str(k: String): String? = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
