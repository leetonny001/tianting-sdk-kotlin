// FE-601：假 HTTP、SDK 钟注入点、client scope 错误码、C1 的帧（AC-CHAT-90-10、AC-CHAT-90-14）。本地 JVM 结果，CI 不运行。
package com.tianting.sdk

import com.tianting.sdk.testing.FakeClock
import com.tianting.sdk.testing.FakeHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpFakeTest {
    private class Recorder : HttpCallback {
        val got = ArrayList<String>()
        override fun onResponse(response: HttpResponse) {
            got.add("${response.status} ${response.text}")
        }

        override fun onFailure() {
            got.add("failed")
        }
    }

    private fun post(path: String, body: String? = null, bearer: String? = null) = HttpRequest(
        "POST",
        "https://x.test$path",
        bearer?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap(),
        body,
    )

    @Test
    fun `FakeHttp 记录请求，由测试代码决定响应；可以迟到，可以失败`() {
        var flushes = 0
        val http = FakeHttp { flushes++ }
        val a = Recorder()
        val b = Recorder()
        http.request(post("/v1/a", """{"k":1}""", "t"), a)
        http.request(post("/v1/b"), b)
        assertEquals(listOf("/v1/a", "/v1/b"), http.pending.map { it.path })
        assertEquals("1", http.last("/v1/a").json["k"].toString())
        assertEquals("t", http.last("/v1/a").bearer)
        http.last("/v1/a").respond(200, """{"ok":true}""")
        assertEquals(listOf("""200 {"ok":true}"""), a.got)
        http.last("/v1/b").fail()
        assertEquals(listOf("failed"), b.got)
        assertEquals(emptyList(), http.pending)
        assertEquals(2, flushes)
        assertFailsWith<AssertionError> { http.last("/v1/none") }
    }

    @Test
    fun `cancel 只做记录，放弃之后响应还可以迟到（SDK 要自己丢弃）`() {
        val http = FakeHttp()
        val r = Recorder()
        val call = http.request(post("/v1/a"), r)
        call.cancel()
        assertTrue(http.last("/v1/a").cancelled)
        http.last("/v1/a").respondError(401, 4019, reason = "revoked")
        assertTrue(r.got.single().startsWith("401 "))
    }

    @Test
    fun `onRequest 自动应答，可以搭假服务端`() {
        val http = FakeHttp()
        http.onRequest = { it.respond(200) }
        val r = Recorder()
        http.request(post("/v1/a"), r)
        assertEquals(listOf("200 {}"), r.got)
    }

    @Test
    fun `HttpRequest 的 toString 不带请求头、请求体和查询串`() {
        val s = HttpRequest("POST", "https://x.test/a?sig=SECRET", mapOf("Authorization" to "Bearer SECRET"), "SECRET").toString()
        assertFalse(s.contains("SECRET"), s)
    }

    @Test
    fun `SDK 钟注入点：FakeClock 实现公开的 Clock，只有 advance 才走时间`() {
        val clock: Clock = FakeClock(1_000)
        var fired = 0
        clock.schedule(10) { fired++ }
        assertEquals(1_000, clock.now())
        (clock as FakeClock).advance(9)
        assertEquals(0, fired)
        clock.advance(1)
        assertEquals(1, fired)
        assertEquals(1_010, clock.now())
    }

    @Test
    fun `sdk-core 的主代码除了默认时钟不直接读系统时间`() {
        val root = File(System.getenv("TIANTING_ROOT") ?: "..", "sdk-core/src/main/kotlin")
        val users = root.walkTopDown().filter { it.extension == "kt" }
            .filter { f -> f.readText().contains("System.currentTimeMillis") }.map { it.name }.toList()
        assertEquals(listOf("Support.kt"), users)
    }

    @Test
    fun `TC-CHAT-90-12 client scope 的码都在枚举里，和 errors json 一致（AC-CHAT-90-14）`() {
        val spec = Json.parseToJsonElement(File(protocolDir, "errors.json").readText()).jsonObject
        val client = spec["errors"]!!.jsonArray.map { it.jsonObject }.filter { e -> e["scope"]!!.jsonArray.any { it.str == "client" } }
        assertTrue(client.isNotEmpty())
        assertEquals(client.map { it["code"]!!.jsonPrimitive.int }.toSet(), CLIENT_ERROR_CODES)
        for (e in client) {
            assertEquals(e["code"]!!.jsonPrimitive.int, ErrorCode::class.java.getField(e["name"]!!.str).getInt(null))
        }
        for (name in listOf("ACCOUNT_CREDENTIALS_INVALID", "REFRESH_TOKEN_INVALID", "USERNAME_TAKEN", "ACCOUNT_LOGIN_LOCKED")) {
            ErrorCode::class.java.getField(name) // 没有就抛异常
        }
        val reasons = spec["reasons"]!!.jsonObject.filterKeys { it != "\$comment" }
            .map { (k, v) -> k.toInt() to v.jsonArray.map { it.str } }.toMap()
        assertEquals(reasons, ERROR_REASONS)
    }

    @Test
    fun `TC-CHAT-90-09 C1 的帧：kicked 带 recoverBefore、新的踢线原因、securityNotice、auth ack 的 recoveryCodeUnacked`() {
        fun ev(vararg p: Pair<String, Any?>) = obj("cmd" to "event", "data" to obj(*p)).toString()
        for (reason in listOf("passwordChanged", "sessionRevoked", "accountDeleted")) {
            assertEquals(emptyList(), FrameSchema.errors(ev("type" to "kicked", "reason" to reason, "recoverBefore" to 1_800_000_000_000)))
        }
        assertEquals(emptyList(), FrameSchema.errors(ev("type" to "securityNotice", "kind" to "newDeviceLogin", "at" to 1_800_000_000_000, "platform" to "ios")))
        assertTrue(FrameSchema.errors(ev("type" to "securityNotice", "kind" to "x", "at" to 1_800_000_000_000)).isNotEmpty())
        val ack = obj("id" to "a1", "cmd" to "ack", "data" to obj("userId" to "alice", "serverTime" to 1_800_000_000_000, "recoveryCodeUnacked" to true))
        assertEquals(emptyList(), FrameSchema.errors(ack.toString()))
    }
}
