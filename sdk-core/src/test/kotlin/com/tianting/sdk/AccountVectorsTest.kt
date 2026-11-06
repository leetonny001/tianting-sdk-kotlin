// TC-CHAT-90-13（AC-CHAT-90-15）：共享用例 protocol/vectors/account-session.json。
// cases 用真实的 AccountClient + 假 HTTP + 假时钟跑：refresh 真的发了请求，refreshOk 真的是 HTTP 响应，requestTimeout 真的是时钟走了 30 秒。
// 被踢入口（source=kicked）直接调 AccountClient.trigger，也就是连接调用的同一个入口；经过 FakeTransport 的路径在 AccountSessionTest。
// serverCases 约束 FakeAccountSession，这里再用它当假服务端跑一段 SDK。
package com.tianting.sdk

import com.tianting.sdk.testing.FakeClock
import com.tianting.sdk.testing.FakeHttp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AccountVectorsTest : AccountTest() {
    private val spec get() = vector("account-session.json").jsonObject
    private val httpStatus = mapOf(4019 to 401, 4290 to 429, 5030 to 503, 4034 to 403)

    private fun JsonObject.long(k: String): Long? = this[k]?.jsonPrimitive?.longOrNull

    private fun expected(e: JsonObject): SessionOutcome = when (val action = e["action"]!!.str) {
        "none" -> SessionOutcome.None
        "useLocal" -> SessionOutcome.UseLocal
        "backoff" -> SessionOutcome.Backoff
        "keepToken" -> SessionOutcome.KeepToken
        "banNotice" -> SessionOutcome.BanNotice
        "refresh" -> SessionOutcome.Refresh(e["attempt"]!!.jsonPrimitive.long)
        "retryAfter" -> SessionOutcome.RetryAfter(e["retryAfterSeconds"]?.jsonPrimitive?.int)
        "login" -> SessionOutcome.Login(e["dialog"]?.str?.let { d -> SessionDialog.entries.first { it.wire == d } }, e.long("recoverBefore"))
        else -> error("unknown action $action")
    }

    @Test
    fun `TC-CHAT-90-13 用例集不是空的，四个入口、各种失败都覆盖到了`() {
        val cases = spec["cases"]!!.jsonArray
        assertTrue(cases.size >= 20)
        val sources = cases.flatMap { c -> c.jsonObject["steps"]!!.jsonArray.mapNotNull { it.jsonObject["source"]?.str } }.toSet()
        assertEquals(setOf("start", "foreground", "timer", "kicked"), sources)
    }

    @TestFactory
    fun `TC-CHAT-90-13 account-session json cases（规则 5、6、6a、7）`(): List<DynamicTest> = spec["cases"]!!.jsonArray.map { raw ->
        val c = raw.jsonObject
        dynamicTest("${c["name"]!!.str}（${c["rule"]!!.str}）") { runCase(c) }
    }

    private fun runCase(c: JsonObject) {
        val steps = c["steps"]!!.jsonArray.map { it.jsonObject }
        val start = c["start"]!!.jsonObject
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val flush = { dispatcher.scheduler.runCurrent() }
        val clock = FakeClock(steps.firstNotNullOfOrNull { it.long("nowMs") } ?: START_MS).apply { afterEach = flush }
        val http = FakeHttp(flush)
        val account = AccountClient(AccountClientOptions("https://im.test", http = http, clock = clock, random = { 0.5 }, dispatcher = dispatcher))
        val outcomes = ArrayList<SessionOutcome>()
        account.eventTap = { if (it is AccountEvent.Outcome) outcomes.add(it.outcome) }
        val gen = start["accessGen"]!!.jsonPrimitive.long
        val mode = if (start["mode"]!!.str == "account") SessionMode.ACCOUNT else SessionMode.DEMO
        scope.async {
            account.restore(
                AccountSession(
                    mode, USER, jwt("sid1", gen), start.long("accessExpiresAt")!!,
                    if (mode == SessionMode.ACCOUNT) refreshToken("sid1", gen) else null,
                    null, start.long("absoluteExpiresAt"),
                ),
            )
        }
        flush()
        assertEquals(gen, lineageOf(account.session.value!!.accessToken).gen)
        var attempts = 0L
        for ((i, s) in steps.withIndex()) {
            val op = s["op"]!!.str
            val at = "${c["name"]!!.str} 第 ${i + 1} 步 $op"
            outcomes.clear()
            when (op) {
                "trigger" -> {
                    s.long("nowMs")?.let { if (it > clock.now()) clock.advance(it - clock.now()) }
                    outcomes.clear()
                    val source = RefreshSource.valueOf(s["source"]!!.str.uppercase())
                    scope.async { account.trigger(source, s.long("rejectedGen")?.let { RejectedToken(gen = it) }) }
                }
                "http4011" -> scope.async { account.http4011(RejectedToken(gen = s.long("rejectedGen") ?: 0)) }
                "requestTimeout" -> clock.advance(30_000)
                "refreshOk" -> {
                    val t = s["tokens"]!!.jsonObject
                    http.to(REFRESH)[(s.long("attempt")!! - 1).toInt()].respond(
                        200,
                        authBody(t.long("accessGen")!!, t.long("accessExpiresAt")!!, absoluteExpiresAt = t.long("absoluteExpiresAt")!!),
                    )
                }
                "refreshFailed" -> {
                    val f = s["failure"]!!.jsonObject
                    val call = http.to(REFRESH)[(s.long("attempt")!! - 1).toInt()]
                    val code = f["code"]!!.jsonPrimitive.int
                    if (code == 0) {
                        call.fail()
                    } else {
                        // 向量把 retryAfterSeconds、recoverBefore 写平了，真实响应在 details 里。
                        val details = objNoNull("retryAfterSeconds" to f.long("retryAfterSeconds"), "recoverBefore" to f.long("recoverBefore"))
                        call.respondError(httpStatus[code] ?: 400, code, f["reason"]?.str, details.takeIf { it.isNotEmpty() })
                    }
                }
                else -> error("unknown op $op")
            }
            flush()
            val want = expected(s["expect"]!!.jsonObject)
            assertEquals(want, outcomes.lastOrNull() ?: SessionOutcome.None, at)
            assertEquals(s["localGen"]!!.jsonPrimitive.long, account.localGen, "$at localGen")
            assertEquals(s["inflight"]!!.jsonPrimitive.boolean, account.refreshInflight, "$at inflight")
            if (want is SessionOutcome.Refresh) attempts = want.attempt
            assertEquals(attempts, http.to(REFRESH).size.toLong(), "$at 发出的刷新请求数")
        }
    }

    @TestFactory
    fun `TC-CHAT-90-13 account-session json serverCases（规则 4，约束假服务端）`(): List<DynamicTest> = spec["serverCases"]!!.jsonArray.map { raw ->
        val c = raw.jsonObject
        dynamicTest("${c["name"]!!.str}（${c["rule"]!!.str}）") {
            val srv = FakeAccountSession()
            for ((i, st) in c["steps"]!!.jsonArray.map { it.jsonObject }.withIndex()) {
                val op = st["op"]!!.str
                val at = "${c["name"]!!.str} 第 ${i + 1} 步 $op"
                when (op) {
                    "advance" -> srv.now += st.long("ms") ?: 0
                    "access" -> assertEquals(st["result"]!!.str, srv.access(st.long("gen")!!), at)
                    "refresh" -> {
                        val repeat = st["repeat"]?.jsonPrimitive?.int
                        if (repeat != null) {
                            repeat(repeat) { srv.refresh(st.long("gen")!!) }
                        } else {
                            val (result, newGen) = srv.refresh(st.long("gen")!!)
                            assertEquals(st["result"]!!.str, result, at)
                            st.long("newGen")?.let { assertEquals(it, newGen, at) }
                        }
                    }
                    else -> error("unknown op $op")
                }
                assertEquals(st.long("current"), srv.current, "$at current")
                assertEquals(st["revoked"]!!.jsonPrimitive.boolean, srv.revoked, "$at revoked")
                assertEquals(st.long("overlapMinGen"), srv.overlapMinGen, "$at overlapMinGen")
                assertEquals(st["inWindow"]!!.jsonPrimitive.boolean, srv.now < srv.overlapUntil, "$at inWindow")
                assertEquals(st["pendings"]!!.jsonArray.map { it.jsonPrimitive.long }, srv.pendings.toList(), "$at pendings")
            }
        }
    }

    @Test
    fun `TC-CHAT-90-13 SDK 对着假服务端：连续 3 轮刷新并重连，每轮都转正，没有 superseded，没有吊销`() {
        val a = setupAccount()
        val srv = FakeAccountSession()
        a.session(1, 5 * HOUR)
        a.http.onRequest = { call ->
            a.check(call)
            srv.now = a.clock.now()
            val (result, gen) = srv.refresh(call.refreshField.split('.')[2].toLong())
            assertTrue(result.startsWith("issued"), result)
            call.respond(200, authBody(gen!!, a.clock.now() + 5 * HOUR))
        }
        fun authed() {
            val conn = a.transport.current
            conn.open()
            assertNotEquals("tokenExpired", srv.access(lineageOf(authToken(conn)).gen))
            conn.ack("auth", obj("userId" to USER, "serverTime" to a.clock.now()))
            conn.ack("convs", obj("convs" to emptyList<Any>(), "truncated" to false))
        }
        a.connect()
        a.h.flush()
        authed() // 不足 6 小时：拨号时就开始刷新，换上之后 1000 重连
        repeat(3) {
            a.clock.advance(60_000)
            a.foreground()
            authed()
        }
        assertEquals(false, srv.revoked)
        assertEquals(srv.current, a.account.localGen)
        assertTrue(a.account.localGen >= 4)
    }
}
