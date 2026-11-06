package com.tianting.sdk

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OkHttp 的 HTTP 传输层（PRD-chat 第 21 节 16：隐式重试）。OkHttp 默认会自己重发请求，同一个 refresh 发两次就可能触发重放判定：
 * - retryOnConnectionFailure=false：复用的连接断在半路时不重发；
 * - followRedirects、followSslRedirects=false：不跟随重定向（Bearer 和请求体不能带到别的地址，sdk-ts 同样 redirect:'error'）；
 * - 请求体标记 one-shot：`503 + Retry-After: 0` 不受 retryOnConnectionFailure 控制，只有 one-shot 能拦住；408、421 同理。
 * 传进来的 client 也会套上这些设置。不装 HttpLoggingInterceptor：URL 查询串和请求头不能进日志。
 * 超时由 AccountClient 用 Clock 控制（30 秒），这里的超时只比它长，兜底 Clock 没走到的情况。
 */
public class OkHttpHttpTransport(client: OkHttpClient = OkHttpClient()) : HttpTransport {
    private val client: OkHttpClient = client.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(SAFETY_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(SAFETY_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(SAFETY_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(SAFETY_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    override fun request(req: HttpRequest, callback: HttpCallback): HttpCall {
        val call = try {
            val b = Request.Builder().url(req.url)
            req.headers.forEach { (k, v) -> b.header(k, v) }
            b.method(req.method, req.body?.let { OneShotJsonBody(it) } ?: if (req.method == "GET") null else OneShotJsonBody(""))
            client.newCall(b.build())
        } catch (_: IllegalArgumentException) {
            // URL 不合法。异常信息里有 URL，不往上传。
            callback.onFailure()
            return HttpCall { }
        }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) = callback.onFailure()

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { HttpResponse(it.code, it.body?.string() ?: "") }
                    } catch (_: IOException) {
                        null
                    }
                    if (result != null) callback.onResponse(result) else callback.onFailure()
                }
            },
        )
        return HttpCall { call.cancel() }
    }

    private class OneShotJsonBody(private val text: String) : RequestBody() {
        override fun contentType(): MediaType = JSON
        override fun contentLength(): Long = text.toByteArray(Charsets.UTF_8).size.toLong()
        override fun writeTo(sink: BufferedSink) {
            sink.writeUtf8(text)
        }

        override fun isOneShot(): Boolean = true
    }

    private companion object {
        const val SAFETY_TIMEOUT_S = 35L
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
