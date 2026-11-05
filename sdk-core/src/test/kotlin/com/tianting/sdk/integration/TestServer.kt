// 集成测试用的真实 im-server：编译 server/cmd/im-server，以测试模式启动在随机端口（同 sdk-ts 的 test/integration/server.ts）。
package com.tianting.sdk.integration

import com.tianting.sdk.AdminSigner
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64

private const val ADMIN_KEY = "sdk-it-admin-key-0123456789abcdef"
private const val CALLER_ID = "sdk-it"
private const val CALLER_SECRET = "sdk-it-caller-secret-0123456789abcdef"

/** 打开第二批功能（IM_V13_ADMIN_FEATURES）需要的环境变量。 */
val MODERATION_ENV = mapOf("IM_V13_ADMIN_FEATURES" to "1", "IM_QUARANTINE_KEY" to "sdk-it-quarantine-key-0123456789abcdef")

class TestServer private constructor(private val proc: Process, private val adminPort: Int, val wsURL: String) {
    private val http = HttpClient.newHttpClient()

    fun admin(method: String, path: String, body: JsonObject? = null): JsonObject {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$adminPort$path"))
            .header("X-Admin-Key", ADMIN_KEY).header("Content-Type", "application/json")
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody())
        return send(method, path, b)
    }

    /** 用调用方签名调用管理接口（protocol/ADMIN.md「鉴权」）。 */
    fun signed(method: String, path: String, body: JsonObject? = null): JsonObject {
        val raw = body?.toString() ?: ""
        val ts = System.currentTimeMillis().toString()
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val toSign = AdminSigner.stringToSign(method, path, ts, nonce, raw, "it", "", "")
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$adminPort$path"))
            .header("Content-Type", "application/json")
            .header("X-IM-Caller", CALLER_ID)
            .header("X-IM-Timestamp", ts)
            .header("X-IM-Nonce", nonce)
            .header("X-IM-Operator", "it")
            .header("X-IM-Signature", AdminSigner.sign(CALLER_SECRET.toByteArray(), toSign))
            .method(method, if (raw.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(raw))
        return send(method, path, b)
    }

    private fun send(method: String, path: String, b: HttpRequest.Builder): JsonObject {
        val res = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        check(res.statusCode() in 200..299) { "$method $path: ${res.statusCode()} ${res.body()}" }
        return if (res.body().isEmpty()) JsonObject(emptyMap()) else Json.parseToJsonElement(res.body()).jsonObject
    }

    fun token(userId: String): String =
        admin("POST", "/admin/users/token", com.tianting.sdk.obj("userId" to userId))["token"]!!.jsonPrimitive.content

    fun importUsers(vararg users: Pair<String, String?>) {
        admin(
            "POST",
            "/admin/users/import",
            com.tianting.sdk.obj("users" to users.map { (u, n) -> com.tianting.sdk.objNoNull("userId" to u, "nickname" to n) }),
        )
    }

    fun stop() {
        proc.destroy()
        proc.waitFor()
    }

    companion object {
        private val bin: File by lazy {
            val dir = Files.createTempDirectory("im-sdk-it-").toFile()
            val out = File(dir, "im-server")
            val p = ProcessBuilder("go", "build", "-o", out.absolutePath, "./cmd/im-server")
                .directory(serverDir()).inheritIO().start()
            check(p.waitFor() == 0) { "go build failed" }
            out
        }

        /** 服务端源码目录：环境变量 TIANTING_SERVER_DIR，否则找同级目录的 tianting-server（或旧名 im-mvp）检出。 */
        private fun serverDir(): File {
            val root = File(System.getenv("TIANTING_ROOT") ?: "..")
            val env = System.getenv("TIANTING_SERVER_DIR")
            val candidates = if (env != null) listOf(File(env)) else listOf(File(root, "../tianting-server/server"), File(root, "../im-mvp/server"))
            return candidates.firstOrNull { File(it, "cmd/im-server").isDirectory }
                ?: error("找不到 tianting-server 的 server 目录（试过 $candidates），请设置 TIANTING_SERVER_DIR")
        }

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }

        suspend fun start(env: Map<String, String> = emptyMap()): TestServer {
            val port = freePort()
            val adminPort = freePort() // 管理接口在单独的端口上（PRD-admin F-KEY-01）
            val dir = Files.createTempDirectory("im-sdk-db-").toFile()
            val callers = File(dir, "callers.json")
            val secret = Base64.getEncoder().encodeToString(CALLER_SECRET.toByteArray())
            callers.writeText("""[{"id":"$CALLER_ID","alg":"hmac-sha256","secret":"$secret","scopes":["user.ban","msg.delete","mute.write","social.settings","social.friends"]}]""")
            Files.setPosixFilePermissions(callers.toPath(), PosixFilePermissions.fromString("rw-------"))
            val pb = ProcessBuilder(bin.absolutePath)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
            pb.environment().putAll(
                mapOf(
                    "IM_ADDR" to "127.0.0.1:$port",
                    "IM_ADMIN_ADDR" to "127.0.0.1:$adminPort",
                    "IM_DB" to File(dir, "im.db").absolutePath,
                    "IM_ADMIN_KEY" to ADMIN_KEY,
                    "IM_JWT_SECRET" to "sdk-it-jwt-secret-0123456789",
                    "IM_TEST_MODE" to "1",
                    "IM_METRICS_ADDR" to "off",
                    "IM_ADMIN_CALLERS" to callers.absolutePath,
                ) + env,
            )
            val proc = pb.start()
            val http = HttpClient.newHttpClient()
            var i = 0
            while (true) {
                try {
                    val r = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/readyz")).build(), HttpResponse.BodyHandlers.discarding())
                    if (r.statusCode() == 200) break
                } catch (_: Exception) {
                    // 还没起来
                }
                check(++i < 200 && proc.isAlive) { "im-server did not start" }
                delay(50)
            }
            return TestServer(proc, adminPort, "ws://127.0.0.1:$port/ws")
        }
    }
}
