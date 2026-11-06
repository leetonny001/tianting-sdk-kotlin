// FE-611 第 3 条："待退出"持久化 sid 和 refresh，联网补发（F-ACC-04 规则 1，TC-ACC-04-06 的 SDK 部分）；日志和凭证。
// 系统令牌注销只验证调用点存在于替身，FCM 接入随 C3。本地 JVM 结果。
package com.tianting.sdk

import kotlinx.coroutines.async
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountPendingLogoutTest : AccountTest() {
    private fun pending(gen: Long = 1) = listOf(PendingLogout("sid1", refreshToken("sid1", gen)))

    @Test
    fun `TC-ACC-04-06 网络不通：本地立即退出，token 清除，待退出只留 sid 和 refresh，调了系统令牌注销（AC-ACC-04-6）`() {
        val a = setupAccount()
        a.session(1, DAY)
        val c = a.online()
        val p = a.start { logout() }
        a.http.last(LOGOUT).fail()
        assertFalse(a.await(p))
        assertNull(a.current)
        assertNull(a.store.saved)
        assertEquals(pending(), a.pendingStore.items)
        assertEquals(listOf("revoked"), a.revokes)
        assertEquals(1000, c.closedWith?.first)
        assertEquals(listOf(SessionEnd(SessionEndAction.LOGOUT)), a.ended)
    }

    @Test
    fun `TC-ACC-04-06 联网后补发：只带 refresh，没有 Bearer；成功后清除记录`() {
        val a = setupAccount()
        a.session(1, DAY)
        val p = a.start { logout() }
        a.http.last(LOGOUT).fail()
        a.await(p)
        a.account.networkOnline()
        a.h.flush()
        assertEquals(2, a.http.to(LOGOUT).size)
        val retry = a.http.last(LOGOUT)
        assertNull(retry.bearer)
        assertEquals(jsonOf("""{"refreshToken":"${refreshToken("sid1", 1)}"}"""), retry.json)
        retry.respond(200)
        assertEquals(emptyList(), a.pendingStore.items)
        a.account.networkOnline()
        a.h.flush()
        assertEquals(2, a.http.to(LOGOUT).size) // 不再发
    }

    @Test
    fun `TC-ACC-04-06 杀 App 后：下次启动（登录页）补发，记录被清除`() {
        val a = setupAccount()
        a.session(1, DAY)
        val p = a.start { logout() }
        a.http.last(LOGOUT).fail()
        a.await(p)
        a.account.close()
        val b = a.newClient()
        assertNull(a.h.await(a.h.callScope.async { b.start() }))
        a.http.last(LOGOUT).respond(200)
        assertEquals(emptyList(), a.pendingStore.items)
    }

    @Test
    fun `TC-ACC-04-06 请求没回来就被杀：记录已经写了，登录态已经清了，重启不回到登录状态，还能补发`() {
        val a = setupAccount()
        a.session(1, DAY)
        a.start { logout() }
        assertEquals(pending(), a.pendingStore.items)
        assertNull(a.store.saved)
        a.account.close()
        val b = a.newClient()
        assertNull(a.h.await(a.h.callScope.async { b.start() }))
        assertNull(b.session.value)
        assertEquals(2, a.http.to(LOGOUT).size)
    }

    @Test
    fun `TC-ACC-04-06 服务端确认：记录清除，不调系统令牌注销（服务端已经吊销推送令牌）`() {
        val a = setupAccount()
        a.session(1, DAY)
        val p = a.start { logout() }
        a.http.last(LOGOUT).respond(200)
        assertTrue(a.await(p))
        assertEquals(emptyList(), a.pendingStore.items)
        assertEquals(emptyList(), a.revokes)
    }

    @Test
    fun `TC-ACC-04-06 503、429 算没送到，留着重试；4019 invalid 算服务端判断，重试没用，清除`() {
        for ((status, code, keep) in listOf(Triple(503, 5030, true), Triple(429, 4290, true), Triple(401, 4019, false))) {
            val a = setupAccount()
            a.session(1, DAY)
            val p = a.start { logout() }
            a.http.last(LOGOUT).respondError(status, code)
            assertFalse(a.await(p))
            assertEquals(if (keep) pending() else emptyList(), a.pendingStore.items, "$code")
            assertEquals(if (keep) listOf("revoked") else emptyList(), a.revokes, "$code")
        }
    }

    @Test
    fun `TC-ACC-04-06 代理的页面（没有错误码）算没送到`() {
        val a = setupAccount()
        a.session(1, DAY)
        val p = a.start { logout() }
        a.http.last(LOGOUT).respond(403, "<html>portal</html>")
        assertFalse(a.await(p))
        assertEquals(pending(), a.pendingStore.items)
    }

    @Test
    fun `TC-ACC-04-06 补发失败：按退避重试几次，用完就等下一次回前台或联网；同一时间一个请求`() {
        val a = setupAccount()
        a.session(1, DAY)
        val p = a.start { logout() }
        a.http.last(LOGOUT).fail()
        a.await(p)
        a.account.networkOnline()
        a.account.networkOnline()
        a.h.flush()
        assertEquals(2, a.http.to(LOGOUT).size) // 单飞
        for (wait in listOf(1_000L, 2_000, 4_000, 8_000, 16_000)) {
            a.http.last(LOGOUT).fail()
            a.clock.advance(wait)
        }
        val n = a.http.to(LOGOUT).size
        assertEquals(7, n)
        a.http.last(LOGOUT).fail()
        a.clock.advance(HOUR)
        assertEquals(n, a.http.to(LOGOUT).size)
        a.foreground()
        assertEquals(n + 1, a.http.to(LOGOUT).size)
    }

    @Test
    fun `TC-ACC-04-06 多条记录按顺序补发；中途断网就停，留着后面的；重复的合并；有上限`() {
        val a = setupAccount()
        a.pendingStore.items = listOf(PendingLogout("s1", refreshToken("s1", 1)), PendingLogout("s2", refreshToken("s2", 1)))
        a.account.networkOnline()
        a.h.flush()
        assertEquals(refreshToken("s1", 1), a.http.last(LOGOUT).refreshField)
        a.http.last(LOGOUT).respond(200)
        assertEquals(refreshToken("s2", 1), a.http.last(LOGOUT).refreshField)
        a.http.last(LOGOUT).fail()
        assertEquals(listOf("s2"), a.pendingStore.items.map { it.sid })

        val b = setupAccount()
        b.pendingStore.items = List(8) { PendingLogout("s$it", refreshToken("s$it", 1)) }
        b.session(1, DAY)
        b.start { logout() }
        assertEquals(8, b.pendingStore.items.size)
        assertEquals("sid1", b.pendingStore.items.last().sid)
    }

    @Test
    fun `TC-ACC-04-06 退出时刷新进行中：记下的是当前本地值，刷新作废`() {
        val a = setupAccount()
        a.session(3, HOUR)
        a.foreground()
        val refresh = a.refresh()
        val p = a.start { logout() }
        assertEquals(pending(3), a.pendingStore.items)
        a.http.last(LOGOUT).fail()
        a.await(p)
        refresh.respond(200, authBody(4, a.clock.now() + DAY))
        assertNull(a.current)
        assertNull(a.store.saved)
        assertEquals(pending(3), a.pendingStore.items)
    }

    @Test
    fun `TC-ACC-04-06 系统令牌注销失败、待退出写失败都不影响本地退出`() {
        val a = setupAccount()
        a.pendingStore.failSave = true
        a.session(1, DAY)
        val p = a.start { logout() }
        a.http.last(LOGOUT).fail()
        assertFalse(a.await(p))
        assertNull(a.current)
        assertNull(a.store.saved)
    }

    @Test
    fun `TC-ACC-04-06 恢复码没确认时退出：确认前 token 只放内存，请求成功就不落盘，断网才写待退出（问题 14）`() {
        val a = setupAccount()
        val login = a.start { login(USER, "Password-1") }
        a.http.last("/v1/account/login").respond(200, authBody(1, a.clock.now() + DAY, recoveryCode = "C", recoveryCodeGen = 1))
        a.await(login)
        val p = a.start { logout() }
        assertEquals(emptyList(), a.pendingStore.items)
        a.http.last(LOGOUT).respond(200)
        a.await(p)
        assertEquals(emptyList(), a.pendingStore.items)

        val b = setupAccount()
        val login2 = b.start { login(USER, "Password-1") }
        b.http.last("/v1/account/login").respond(200, authBody(1, b.clock.now() + DAY, recoveryCode = "C", recoveryCodeGen = 1))
        b.await(login2)
        val q = b.start { logout() }
        assertEquals(emptyList(), b.pendingStore.items)
        b.http.last(LOGOUT).fail()
        b.await(q)
        assertEquals(pending(), b.pendingStore.items)
        assertNull(b.store.saved)
    }

    @Test
    fun `演示会话退出：不发 HTTP，没有待退出`() {
        val a = setupAccount()
        a.call { restore(AccountSession(userId = USER, accessToken = "demo")) }
        assertTrue(a.call { logout() })
        assertEquals(0, a.http.calls.size)
        assertEquals(emptyList(), a.pendingStore.items)
    }

    @Test
    fun `没有配 pendingLogoutStore：记录只在内存，进程内还能补发`() {
        val h = setup(clock = com.tianting.sdk.testing.FakeClock(START_MS))
        val http = com.tianting.sdk.testing.FakeHttp { h.flush() }
        val c = AccountClient(AccountClientOptions("https://im.test", http = http, clock = h.clock, dispatcher = h.dispatcher, random = { 0.5 }))
        h.await(h.callScope.async { c.restore(AccountSession(SessionMode.ACCOUNT, USER, jwt("s", 1), START_MS + DAY, refreshToken("s", 1), null, START_MS + 300 * DAY)) })
        val p = h.callScope.async { c.logout() }
        h.flush()
        http.last(LOGOUT).fail()
        assertFalse(h.await(p))
        c.networkOnline()
        h.flush()
        assertEquals(2, http.to(LOGOUT).size)
    }

    // ---- 日志和凭证 ----

    @Test
    fun `第 13-1 节 token、refresh、恢复码、密码不进 URL、日志、事件和错误信息`() {
        val a = setupAccount()
        val secrets = linkedSetOf("Password-1", "Password-2", "CODE-SECRET", "CODE-SECRET-2", "INV-SECRET")
        val errors = ArrayList<String>()
        fun remember(e: Throwable) {
            errors.add(e.toString())
            errors.add(e.stackTraceToString())
        }

        val reg = a.start { register(USER, "Password-1", inviteCode = "INV-SECRET") }
        var body = authBody(1, a.clock.now() + HOUR, recoveryCode = "CODE-SECRET", recoveryCodeGen = 1)
        secrets.add(body["accessToken"]!!.str)
        secrets.add(body["refreshToken"]!!.str)
        a.http.last("/v1/account/register").respond(200, body)
        a.await(reg)
        val c1 = a.online()
        body = authBody(2, a.clock.now() + DAY)
        secrets.add(body["accessToken"]!!.str)
        secrets.add(body["refreshToken"]!!.str)
        a.refresh().respond(200, body)
        a.authNew()
        assertEquals(1000, c1.closedWith?.first)

        val wrong = a.start { changePassword("Password-1", "Password-2") }
        a.http.last(PASSWORD).respondError(401, 4018)
        remember(a.failure<AccountError>(wrong))
        val pw = a.start { changePassword("Password-1", "Password-2") }
        body = authBody(1, a.clock.now() + DAY, sid = "s2", recoveryCode = "CODE-SECRET-2")
        secrets.add(body["accessToken"]!!.str)
        secrets.add(body["refreshToken"]!!.str)
        a.http.last(PASSWORD).respond(200, body)
        val auth = a.await(pw)
        a.authNew()
        val ack = a.start { ackRecoveryCode(2) }
        a.http.last("/v1/account/recovery-code/ack").respond(200)
        a.await(ack)

        val failing = a.start { logoutOthers("Password-1") }
        a.http.pending[0].fail()
        remember(a.failure<AccountNetworkError>(failing))

        a.store.failSave = true // 存储的异常信息里有 token，不能进日志
        val again = a.start { refresh() }
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY, sid = "s2"))
        a.store.failSave = false
        a.clock.advance(30_000)
        remember(a.failure<Exception>(again))
        a.foreground()

        val lg = a.start { logout() }
        a.http.last(LOGOUT).fail()
        a.await(lg)
        a.account.networkOnline()
        a.h.flush()
        a.http.last(LOGOUT).fail()

        assertTrue(a.logs.isNotEmpty())
        val strings = a.accountEvents.map { it.toString() } + a.h.events.map { it.toString() } + listOf(
            auth.toString(),
            a.pendingStore.items.toString(),
            AccountSession(SessionMode.ACCOUNT, USER, jwt("sid1", 1), 0, refreshToken("sid1", 1)).toString(),
            RecoveryCode("CODE-SECRET", 1).toString(),
        )
        val urls = a.http.calls.map { it.req.url } + a.transport.connections.map { it.url }
        val requests = a.http.calls.map { it.req.toString() }
        for ((name, lines) in listOf("logs" to a.logs, "urls" to urls, "events" to strings, "errors" to errors, "requests" to requests)) {
            for (line in lines) {
                for (s in secrets) assertFalse(line.contains(s), "$name 里有凭证: ${line.take(60)}")
                assertFalse(line.contains("Bearer"), "$name 里有 Bearer")
                assertFalse(line.contains("eyJ"), "$name 里有 JWT")
            }
        }
        assertTrue(a.http.calls.all { it.query == null })
        assertTrue(a.logs.none { it.contains('?') }, "调试日志不能有 URL 查询串")
    }

    @Test
    fun `AccountSession 等的 toString 不带凭证（data class 默认会带）`() {
        val s = AccountSession(SessionMode.ACCOUNT, USER, "ACCESS-SECRET", 0, "REFRESH-SECRET")
        val p = PendingLogout("sid", "REFRESH-SECRET")
        val r = AuthResult(USER, "CODE-SECRET", 1)
        for (text in listOf(s.toString(), p.toString(), r.toString(), RecoveryCode("CODE-SECRET", 1).toString())) {
            for (secret in listOf("ACCESS-SECRET", "REFRESH-SECRET", "CODE-SECRET")) assertFalse(text.contains(secret), text)
        }
    }

}
