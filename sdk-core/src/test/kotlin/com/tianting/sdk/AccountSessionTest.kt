// FE-611：会话语义（PRD-chat F-ACC-03 规则 5～7、F-ACC-04 规则 3、F-ACC-05 规则 1）。对照 sdk-ts 的 test/account-session.test.ts 翻译。
// 假 HTTP + FakeTransport + 假时钟。测试名带 TC-ACC 编号。这些是本地 JVM 单测结果；界面部分（弹窗、页面）属于 FE-615、FE-616。
package com.tianting.sdk

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BEFORE = 1_800_518_400_000L

class AccountSessionTest : AccountTest() {
    private fun AccountHarness.noBannerShown() = assertEquals(emptyList(), banner.filter { it })

    // ---- TC-ACC-03-10 ----

    @Test
    fun `TC-ACC-03-10 连接中剩余不足 6 小时：刷新一次，1000 断开，用新 token 重连，不提示，消息照常收发`() {
        val a = setupAccount()
        a.session(1, 6 * HOUR + MIN)
        val c1 = a.online()
        assertEquals(0, a.http.to(REFRESH).size)
        a.clock.advance(MIN + 1_000)
        assertEquals(1, a.http.to(REFRESH).size)
        assertEquals(refreshToken("sid1", 1), a.refresh().refreshField)
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY))
        assertEquals(1000 to "token rotated", c1.closedWith)
        assertEquals(2, a.transport.connections.size)
        val c2 = a.authNew()
        assertEquals(jwt("sid1", 2), authToken(c2))
        assertEquals(ConnState.CONNECTED, a.im.state.value)
        a.clock.advance(MIN - 1_000)
        assertEquals(1, a.http.to(REFRESH).size)
        a.noBannerShown()
        assertEquals(emptyList(), a.fatals())
        assertEquals(emptyList(), a.ended)
        a.h.call { send("c2c:alice_1:bobby", "hi") }
        assertEquals("bobby", c2.last("send").dataObj["to"]!!.jsonPrimitive.content)
    }

    @Test
    fun `TC-ACC-03-10 剩余恰好 6 小时不刷新，过这个点才刷新`() {
        val a = setupAccount()
        a.session(1, 6 * HOUR + MIN)
        a.online()
        a.clock.advance(MIN)
        assertEquals(0, a.http.to(REFRESH).size)
        a.clock.advance(1)
        assertEquals(1, a.http.to(REFRESH).size)
    }

    @Test
    fun `TC-ACC-03-10 恰好 6 小时：回前台也不刷新，少 1 毫秒才刷新`() {
        val a = setupAccount()
        a.session(1, 6 * HOUR + MIN)
        a.clock.advance(MIN)
        a.foreground()
        assertEquals(0, a.http.to(REFRESH).size)
        a.clock.advance(1)
        a.foreground()
        assertEquals(1, a.http.to(REFRESH).size)
    }

    @Test
    fun `TC-ACC-03-10 没有连接时不用定时器，回前台才检查；新 token 先落盘再使用（R-CF-09）`() {
        val a = setupAccount()
        a.session(1, HOUR)
        a.clock.advance(DAY / 2)
        assertEquals(0, a.http.to(REFRESH).size)
        a.foreground()
        assertEquals(1, a.http.to(REFRESH).size)
        var savedBeforeAdopt = false
        val tap = a.account.eventTap
        a.account.eventTap = { e ->
            tap?.invoke(e)
            if (e == AccountEvent.TokensChanged) savedBeforeAdopt = a.store.saved?.accessToken == a.current?.accessToken
        }
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY))
        assertTrue(savedBeforeAdopt)
        assertEquals(jwt("sid1", 2), a.store.saved?.accessToken)
    }

    @Test
    fun `TC-ACC-03-10 落盘还没完成时不使用新 token（R-CF-09）`() {
        val a = setupAccount()
        a.session(1, HOUR)
        a.foreground()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        a.store.pauseBeforeSave = gate
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY))
        assertEquals(1L, a.account.localGen)
        assertEquals(jwt("sid1", 1), a.current?.accessToken)
        gate.complete(Unit)
        a.h.flush()
        assertEquals(2L, a.account.localGen)
    }

    @Test
    fun `TC-ACC-03-10 落盘失败时不使用新 token，按退避重试，旧值仍然有效；日志没有凭证`() {
        val a = setupAccount()
        a.session(1, HOUR)
        a.foreground()
        a.store.failSave = true
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY))
        assertEquals(1L, a.account.localGen)
        a.store.failSave = false
        a.clock.advance(1_000)
        assertEquals(2, a.http.to(REFRESH).size)
        assertEquals(refreshToken("sid1", 1), a.refresh().refreshField)
        assertFalse(a.logs.any { it.contains(jwt("sid1", 2)) })
    }

    @Test
    fun `落盘期间超时放弃：响应写了存储也不换 token，序号作废（落盘后再比一次）`() {
        val a = setupAccount()
        a.session(1, HOUR)
        a.foreground()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        a.store.pauseBeforeSave = gate
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY))
        a.clock.advance(30_000) // 还在落盘，刷新超时了
        assertFalse(a.account.refreshInflight)
        gate.complete(Unit)
        a.h.flush()
        assertEquals(1L, a.account.localGen)
        assertEquals(jwt("sid1", 1), a.current?.accessToken)
    }

    // ---- 启动已过期 ----

    @Test
    fun `TC-ACC-03-07 冷启动已过期：没发 auth 之前先刷新；不足 1 秒不提示，不回登录页`() {
        val a = setupAccount()
        a.session(1, -HOUR)
        a.connect()
        assertEquals(0, a.transport.connections.size)
        assertEquals(ConnState.CONNECTING, a.im.state.value)
        assertEquals(1, a.http.to(REFRESH).size)
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY))
        val c = a.authNew()
        assertEquals(jwt("sid1", 2), authToken(c))
        assertEquals(emptyList(), a.fatals())
        assertEquals(emptyList(), a.ended)
    }

    @Test
    fun `TC-ACC-03-07 start 从存储读过期的会话，刷新回来后 connect 直接用新 token`() {
        val a = setupAccount()
        a.store.saved = AccountSession(SessionMode.ACCOUNT, USER, jwt("sid1", 1), a.clock.now() - HOUR, refreshToken("sid1", 1), a.clock.now() + DAY, a.clock.now() + 300 * DAY)
        a.call { start() }
        assertEquals(1, a.http.to(REFRESH).size)
        a.connect()
        assertEquals(0, a.transport.connections.size)
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY))
        assertEquals(jwt("sid1", 2), authToken(a.authNew()))
        assertEquals(1, a.http.to(REFRESH).size)
    }

    @Test
    fun `TC-ACC-03-11 飞行模式 + access 过期冷启动：一直连接中，提示网络不可用，不回登录页；恢复网络后刷新并连接（SDK 部分）`() {
        val a = setupAccount()
        a.session(1, -HOUR)
        a.connect()
        a.refresh().fail()
        a.clock.advance(1_000)
        a.refresh().fail()
        a.clock.advance(2_000)
        assertEquals(3, a.http.to(REFRESH).size)
        assertEquals(ConnState.CONNECTING, a.im.state.value)
        assertTrue(a.im.banner.value)
        assertEquals(emptyList(), a.ended)
        assertEquals(emptyList(), a.fatals())
        assertNotNull(a.current)
        assertEquals(0, a.transport.connections.size)
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY))
        val c = a.authNew()
        assertEquals(jwt("sid1", 2), authToken(c))
        assertFalse(a.im.banner.value)
    }

    @Test
    fun `TC-ACC-03-11 飞行模式冷启动时本地库的缓存还在，刷新失败不清库（SDK 部分）`(@TempDir dir: File) {
        val conv = "c2c:alice_1:bobby"
        val first = setupAccount(tempDb(dir))
        first.session(1, DAY)
        first.connect()
        first.h.login(USER, listOf(conv(conv, name = "Bob", maxSeq = 1)))
        assertEquals(listOf(conv), first.im.conversations.value.map { it.convId })
        first.h.call { close() }

        val a = setupAccount(tempDb(dir))
        a.session(1, -HOUR)
        a.connect()
        a.refresh().fail()
        a.clock.advance(2_000)
        assertEquals(listOf(conv), a.im.conversations.value.map { it.convId })
        assertTrue(a.im.banner.value)
        assertEquals(emptyList(), a.ended)
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY))
        assertEquals(jwt("sid1", 2), authToken(a.h.login(USER, listOf(conv(conv, name = "Bob", maxSeq = 1)))))
    }

    @Test
    fun `退出和会话结束都清本地库（FE-131）：刷新 4019 按被踢处理，退出按 disconnect 处理`(@TempDir dir: File) {
        val conv = "c2c:alice_1:bobby"
        val a = setupAccount(tempDb(File(dir, "a").apply { mkdirs() }))
        a.session(1, DAY)
        a.connect()
        a.h.login(USER, listOf(conv(conv, maxSeq = 1)))
        val p = a.start { logout() }
        a.http.last(LOGOUT).respond(200)
        a.await(p)
        assertEquals(emptyList(), a.im.conversations.value)
        assertEquals("", a.im.userId.value)
        assertEquals(null, a.im.fatal.value)

        val b = setupAccount(tempDb(File(dir, "b").apply { mkdirs() }))
        b.session(1, HOUR)
        b.connect()
        b.h.login(USER, listOf(conv(conv, maxSeq = 1)))
        b.refresh().respondError(401, 4019, "revoked")
        assertIs<FatalReason.Session>(b.im.fatal.value)
        b.h.call { close() }
        val c = setupAccount(tempDb(File(dir, "b")))
        assertEquals(emptyList(), c.im.conversations.value)
    }

    @Test
    fun `FatalReason Session 也是 Closed：现有 App 按 Kicked、Closed 的 when 不用改就能编译；相等性和 data class 一样`() {
        val s = FatalReason.Session(SessionEnd(SessionEndAction.LOGIN, SessionDialog.SESSION_EXPIRED, code = 4019, reason = "revoked"))
        val code = when (val r: FatalReason = s) {
            is FatalReason.Kicked -> r.code
            is FatalReason.Closed -> r.code
        }
        assertEquals(4019, code)
        assertEquals(FatalReason.Closed(4012, "x"), FatalReason.Closed(4012, "x"))
        assertNotEquals<FatalReason>(FatalReason.Closed(4019, "revoked"), s)
        assertEquals(FatalReason.Closed(4012, "x").hashCode(), FatalReason.Closed(4012, "x").hashCode())
    }

    @Test
    fun `TC-ACC-03-13 新 token 落盘后、使用前杀 App：重启用新 token，假服务端转正，没有吊销`() {
        val a = setupAccount()
        val srv = FakeAccountSession()
        a.session(1, HOUR)
        a.foreground()
        val r = srv.refresh(1)
        assertEquals("issued" to 2L, r)
        a.store.pauseAfterSave = kotlinx.coroutines.CompletableDeferred() // 停在"落盘后"
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY))
        assertEquals(jwt("sid1", 2), a.store.saved?.accessToken)
        assertEquals(1L, a.account.localGen) // 旧实例还没换上
        a.account.close() // 杀 App

        val b = a.newClient()
        val saved = a.h.await(a.h.callScope.async { b.start() })
        assertEquals(jwt("sid1", 2), saved?.accessToken)
        a.im.connect(b.tokenProvider)
        a.h.flush()
        val token = authToken(a.transport.current.also { it.open() })
        assertEquals(jwt("sid1", 2), token)
        assertEquals("promoted", srv.access(lineageOf(token).gen))
        assertFalse(srv.revoked)
        assertEquals(1, a.http.to(REFRESH).size)
    }

    @Test
    fun `TC-ACC-03-20 刷新请求发出后杀 App：5 分钟后用旧 refresh 重试成功，不回登录页`() {
        val a = setupAccount()
        val srv = FakeAccountSession()
        a.session(1, HOUR)
        a.foreground()
        assertEquals("issued" to 2L, srv.refresh(1)) // 服务端处理了，响应没到
        a.account.close()
        a.clock.advance(5 * MIN)
        srv.now = a.clock.now()

        val b = a.newClient()
        a.h.await(a.h.callScope.async { b.start() })
        assertEquals(2, a.http.to(REFRESH).size)
        assertEquals(refreshToken("sid1", 1), a.refresh().refreshField)
        val (result, gen) = srv.refresh(1)
        assertEquals("issued", result)
        a.refresh().respond(200, authBody(gen!!, a.clock.now() + DAY))
        assertEquals(gen, b.localGen)
        assertEquals(emptyList(), a.ended)
        assertFalse(srv.revoked)
    }

    @Test
    fun `TC-ACC-03-18 老版本留下的 {userId, token}：按演示模式，不刷新，能连接`() {
        val a = setupAccount()
        val old = AccountSession.fromJson("""{"userId":"$USER","token":"old-demo-token"}""")
        assertNotNull(old)
        assertNull(old.mode)
        a.store.saved = old
        a.call { start() }
        assertEquals(SessionMode.DEMO, a.current?.mode)
        assertEquals(0, a.http.calls.size)
        a.foreground()
        a.clock.advance(DAY)
        assertEquals(0, a.http.calls.size)
        a.connect()
        val c = a.h.login(USER)
        assertEquals("old-demo-token", authToken(c))
        assertEquals(ConnState.CONNECTED, a.im.state.value)
        assertEquals(0, a.http.calls.size)
    }

    @Test
    fun `TC-ACC-03-18 演示会话 token 过期：收到 4011 回登录页，不发 HTTP`() {
        val a = setupAccount()
        a.call { restore(AccountSession(userId = USER, accessToken = "old-demo-token")) }
        val c = a.online()
        c.drop(4011, "tokenExpired")
        assertEquals(SessionDialog.SESSION_EXPIRED, a.ended.single().dialog)
        assertEquals(0, a.http.calls.size)
        assertNull(a.current)
    }

    @Test
    fun `TC-ACC-03-18 老数据的到期时间取 JWT 的 exp；新数据往返不丢字段；坏内容当没有登录`() {
        val exp = 1_900_000_000L
        val token = "e30." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":$exp}""".toByteArray()) + ".x"
        assertEquals(exp * 1000, AccountSession.fromJson("""{"userId":"u","token":"$token"}""")?.accessExpiresAt)
        val s = AccountSession(SessionMode.ACCOUNT, USER, jwt("s", 1), 1, refreshToken("s", 1), 2, 3)
        assertEquals(s, AccountSession.fromJson(s.toJson()))
        assertNull(AccountSession.fromJson("not json"))
        assertNull(AccountSession.fromJson("""{"token":"x"}"""))
    }

    // ---- TC-ACC-03-12 ----

    @Test
    fun `TC-ACC-03-12 503：退避重试，不回登录页，成功后恢复`() {
        val a = setupAccount()
        a.session(1, HOUR)
        a.online()
        a.refresh().respondError(503, 5030)
        assertEquals(emptyList(), a.ended)
        assertEquals(ConnState.CONNECTED, a.im.state.value)
        a.clock.advance(999)
        assertEquals(1, a.http.to(REFRESH).size)
        a.clock.advance(1)
        assertEquals(2, a.http.to(REFRESH).size)
        a.refresh().respondError(503, 5030)
        a.clock.advance(1_999)
        assertEquals(2, a.http.to(REFRESH).size)
        a.clock.advance(1)
        assertEquals(3, a.http.to(REFRESH).size)
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY))
        a.authNew()
        assertEquals(emptyList(), a.fatals())
        assertEquals(emptyList(), a.ended)
    }

    @Test
    fun `TC-ACC-03-12 退避有上限：1、2、4、8、16、30、30 秒，±20% 抖动`() {
        val a = setupAccount()
        a.session(1, HOUR)
        a.foreground()
        for ((i, wait) in listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000).withIndex()) {
            a.refresh().fail()
            a.clock.advance(wait - 1)
            assertEquals(i + 1, a.http.to(REFRESH).size, "第 ${i + 1} 次")
            a.clock.advance(1)
            assertEquals(i + 2, a.http.to(REFRESH).size, "第 ${i + 1} 次")
        }
    }

    @Test
    fun `TC-ACC-03-12 代理返回的 HTML（没有错误码）不算服务端判断：退避，不回登录页`() {
        val a = setupAccount()
        a.session(1, HOUR)
        a.foreground()
        a.refresh().respond(403, "<html>captive portal</html>")
        assertEquals(emptyList(), a.ended)
        a.clock.advance(1_000)
        assertEquals(2, a.http.to(REFRESH).size)
    }

    @Test
    fun `TC-ACC-03-12 200 但字段不全：按网络错误，不回登录页`() {
        val a = setupAccount()
        a.session(1, HOUR)
        a.foreground()
        a.refresh().respond(200, """{"userId":"$USER"}""")
        assertEquals(emptyList(), a.ended)
        assertEquals(1L, a.account.localGen)
        a.clock.advance(1_000)
        assertEquals(2, a.http.to(REFRESH).size)
    }

    @Test
    fun `TC-ACC-03-12 200 但 refreshToken 是空串：按网络错误，不换 token`() {
        val a = setupAccount()
        a.session(1, HOUR)
        a.foreground()
        val body = jsonOf(authBody(2, a.clock.now() + DAY).toString().replace(refreshToken("sid1", 2), ""))
        a.refresh().respond(200, body)
        assertEquals(1L, a.account.localGen)
        assertEquals(refreshToken("sid1", 1), a.current?.refreshToken)
        a.clock.advance(1_000)
        assertEquals(2, a.http.to(REFRESH).size)
    }

    @Test
    fun `TC-ACC-03-12 不认识的错误码回登录页（有 code 按 model go）`() {
        val a = setupAccount()
        a.session(1, HOUR)
        a.foreground()
        a.refresh().respondError(400, 4999)
        assertEquals(SessionDialog.SESSION_EXPIRED, a.ended.single().dialog)
    }

    @Test
    fun `TC-ACC-03-12 kicked tokenExpired 且刷新成功：不弹窗，自动重连`() {
        val a = setupAccount()
        a.session(1, DAY)
        val c1 = a.online()
        a.kicked(c1, "tokenExpired", 4011)
        assertEquals(1, a.http.to(REFRESH).size) // 不看剩余时间
        assertEquals(1, a.transport.connections.size) // 刷新回来之前不用旧 token 重连
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY))
        assertEquals(2, a.transport.connections.size)
        assertEquals(jwt("sid1", 2), authToken(a.authNew()))
        assertEquals(emptyList(), a.fatals())
        assertEquals(emptyList(), a.ended)
    }

    @Test
    fun `TC-ACC-03-12 kicked tokenExpired 后刷新一直失败：mustRefresh 带进退避重试，剩余时间充足也继续`() {
        val a = setupAccount()
        a.session(1, DAY)
        val c1 = a.online()
        a.kicked(c1, "tokenExpired", 4011)
        a.refresh().respondError(503, 5030)
        a.clock.advance(1_000)
        assertEquals(2, a.http.to(REFRESH).size)
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY))
        assertEquals(2, a.transport.connections.size)
    }

    @Test
    fun `TC-ACC-03-12 只有关闭码 4011 没有 kicked 帧：同样走规则 7`() {
        val a = setupAccount()
        a.session(1, DAY)
        a.online().drop(4011)
        assertEquals(1, a.http.to(REFRESH).size)
        assertEquals(emptyList(), a.fatals())
    }

    @Test
    fun `规则 7 4011 比较代号：本地比被拒绝的新就直接用本地值重连，不刷新`() {
        val a = setupAccount()
        val s = a.session(5, DAY)
        a.connect()
        val c1 = a.transport.current
        c1.open()
        // 拨号之后本地换上了更新的代（比如另一个请求刷新过），连接还没换的时候被拒绝。
        a.call { restore(s.copy(accessToken = jwt("sid1", 6), refreshToken = refreshToken("sid1", 6))) }
        a.kicked(c1, "tokenExpired", 4011)
        assertEquals(0, a.http.to(REFRESH).size)
        assertEquals(2, a.transport.connections.size)
        a.transport.current.open()
        assertEquals(jwt("sid1", 6), authToken(a.transport.current))
    }

    @Test
    fun `规则 7 本地不比被拒绝的新（gen 相同）：要刷新`() {
        val a = setupAccount()
        a.session(6, DAY)
        a.online().drop(4011)
        assertEquals(1, a.http.to(REFRESH).size)
    }

    @Test
    fun `规则 7 本地换过 sid：新 sid 的代号比旧的小也算更新（改密后 gen 从头开始）`() {
        val a = setupAccount()
        val s = a.session(9, DAY, "sid-old")
        a.connect()
        val c1 = a.transport.current
        c1.open()
        a.call { restore(s.copy(accessToken = jwt("sid-new", 1), refreshToken = refreshToken("sid-new", 1))) }
        c1.drop(4011)
        assertEquals(0, a.http.to(REFRESH).size)
        assertEquals(2, a.transport.connections.size)
    }

    @Test
    fun `规则 7 同 sid 比 gen 的纯函数：sid 不同一定更新，sid 相同才比 gen，缺字段按最不新`() {
        assertTrue(isLocalNewer(TokenLineage("new", 1), RejectedToken("old", 9)))
        assertFalse(isLocalNewer(TokenLineage("s", 3), RejectedToken("s", 3)))
        assertTrue(isLocalNewer(TokenLineage("s", 4), RejectedToken("s", 3)))
        assertFalse(isLocalNewer(TokenLineage("s", 2), RejectedToken("s", 3)))
        assertTrue(isLocalNewer(TokenLineage("s", 4), RejectedToken(gen = 3)))
        assertEquals(TokenLineage("", 0), lineageOf("garbage"))
        assertEquals(TokenLineage("", 0), lineageOf("a.%%%.c"))
        assertEquals(TokenLineage("sid9", 7), lineageOf(jwt("sid9", 7)))
    }

    @Test
    fun `规则 7 连续被 4011 拒绝：第一次立即重连，之后按退避，不和服务端打转`() {
        val a = setupAccount()
        a.session(1, DAY)
        a.http.onRequest = { call ->
            a.check(call)
            val gen = call.refreshField.split('.')[2].toLong()
            call.respond(200, authBody(gen + 1, a.clock.now() + DAY))
        }
        a.connect()
        a.transport.current.open()
        a.transport.current.drop(4011)
        assertEquals(2, a.transport.connections.size) // 第一次立即
        a.transport.current.open()
        a.transport.current.drop(4011)
        assertEquals(2, a.transport.connections.size) // 第二次等退避
        a.clock.advance(1_000)
        assertEquals(3, a.transport.connections.size)
    }

    @Test
    fun `演示模式：connect(token) 收到 4011 不重连，不发 HTTP（行为不变）`() {
        val a = setupAccount()
        a.im.connect("demo-token")
        a.h.flush()
        val c = a.h.login("demo")
        a.kicked(c, "tokenExpired", 4011)
        assertEquals(listOf<FatalReason>(FatalReason.Kicked("tokenExpired", 4011)), a.fatals())
        assertEquals(0, a.http.calls.size)
        a.clock.advance(60_000)
        assertEquals(1, a.transport.connections.size)
    }

    @Test
    fun `演示模式：账号会话结束后 connect(token)，不再走 token 来源`() {
        val a = setupAccount()
        a.session(1, DAY)
        a.kicked(a.online(), "tooManyConnections", 4429) // 连接停了，账号会话还在
        a.im.connect("demo-token")
        a.h.flush()
        assertEquals("demo-token", authToken(a.h.login("demo")))
    }

    @Test
    fun `演示模式：不认识的踢线原因 + 可以重连的关闭码，忽略它继续重连（FE-601 第 2 条）`() {
        val h = setup()
        h.connect()
        val c = h.login()
        c.receive(event("kicked", "reason" to "futureReason"))
        c.drop(1006)
        assertEquals(emptyList(), h.fatals())
        h.clock.advance(1_000)
        assertEquals(2, h.transport.connections.size)
    }

    @Test
    fun `不认识的踢线原因：关闭码可以重连就忽略它；账号模式 4012 按关闭码结束会话`() {
        val a = setupAccount()
        a.session(1, DAY)
        val c1 = a.online()
        c1.receive(event("kicked", "reason" to "futureReason"))
        c1.drop(1006)
        a.clock.advance(1_000)
        assertEquals(2, a.transport.connections.size)
        assertEquals(emptyList(), a.fatals())
        val c2 = a.authNew()
        c2.receive(event("kicked", "reason" to "futureReason"))
        c2.drop(4012)
        assertEquals(listOf<FatalReason>(FatalReason.Kicked("futureReason", 4012)), a.fatals())
        assertEquals(SessionDialog.ACCOUNT_OFFLINE, a.ended.single().dialog)
        assertTrue(a.ended.single().fromKick)
        assertNull(a.current)
    }

    // ---- TC-ACC-03-23 ----

    @Test
    fun `TC-ACC-03-23 启动、回前台、定时器、kicked 同时触发：同一时间 1 个请求；超时后重试；迟到的 R2 被丢弃，保存的是 R3`() {
        val a = setupAccount()
        a.session(1, 5 * HOUR)
        a.call { start() }
        a.foreground()
        a.call { trigger(RefreshSource.TIMER) }
        val c1 = a.online()
        a.kicked(c1, "tokenExpired", 4011)
        assertEquals(1, a.http.to(REFRESH).size)
        assertTrue(a.account.refreshInflight)

        a.clock.advance(30_000)
        assertFalse(a.account.refreshInflight)
        assertTrue(a.refresh(0).cancelled)
        assertEquals(1, a.http.to(REFRESH).size)
        a.clock.advance(1_000) // 退避 1 秒
        assertEquals(2, a.http.to(REFRESH).size)
        val (first, second) = a.http.to(REFRESH)

        second.respond(200, authBody(3, a.clock.now() + DAY))
        assertEquals(refreshToken("sid1", 3), a.store.saved?.refreshToken)
        val saves = a.store.saves.size

        first.respond(200, authBody(2, a.clock.now() + DAY))
        assertEquals(3L, a.account.localGen)
        assertEquals(refreshToken("sid1", 3), a.current?.refreshToken)
        assertEquals(refreshToken("sid1", 3), a.store.saved?.refreshToken)
        assertEquals(saves, a.store.saves.size)
        assertTrue(a.maxPending <= 2) // 第一个被放弃后才发第二个；它的响应还可能迟到
    }

    @Test
    fun `TC-ACC-03-23 迟到的 4019 不让会话结束`() {
        val a = setupAccount()
        a.session(1, HOUR)
        a.foreground()
        a.clock.advance(30_000)
        a.clock.advance(1_000)
        val (first, second) = a.http.to(REFRESH)
        first.respondError(401, 4019, "revoked")
        assertEquals(emptyList(), a.ended)
        assertTrue(a.account.refreshInflight)
        second.respond(200, authBody(2, a.clock.now() + DAY))
        assertEquals(2L, a.account.localGen)
    }

    @Test
    fun `TC-ACC-03-23 迟到的网络错误不打断当前请求，也不多排一次退避`() {
        val a = setupAccount()
        a.session(1, HOUR)
        a.foreground()
        a.clock.advance(31_000)
        val (first, second) = a.http.to(REFRESH)
        first.fail()
        assertTrue(a.account.refreshInflight)
        a.clock.advance(5_000)
        assertEquals(2, a.http.to(REFRESH).size)
        second.respond(200, authBody(2, a.clock.now() + DAY))
        assertEquals(2L, a.account.localGen)
    }

    @Test
    fun `TC-ACC-03-23 等待退避期间回前台不提前发请求`() {
        val a = setupAccount()
        a.session(1, HOUR)
        a.foreground()
        a.refresh().fail()
        a.foreground()
        assertEquals(1, a.http.to(REFRESH).size)
        a.clock.advance(1_000)
        assertEquals(2, a.http.to(REFRESH).size)
    }

    // ---- TC-ACC-03-26 ----

    @Test
    fun `TC-ACC-03-26 replayDetected：回登录页前带弹窗文字，本地 token 和存储都清除，连接停止`() {
        val a = setupAccount()
        a.session(1, HOUR)
        val c = a.online()
        a.refresh().respondError(401, 4019, "replayDetected")
        val e = SessionEnd(SessionEndAction.LOGIN, SessionDialog.CREDENTIAL_ANOMALY, null, "replayDetected", 4019)
        assertEquals(listOf(e), a.ended)
        assertEquals("检测到登录凭证异常，这台设备已下线。如果不是你本人操作，请尽快改密码", sessionDialogText(e.dialog))
        assertEquals(listOf<FatalReason>(FatalReason.Session(e)), a.fatals())
        assertNull(a.current)
        assertNull(a.store.saved)
        assertEquals(1000, c.closedWith?.first)
        a.clock.advance(60_000)
        assertEquals(1, a.transport.connections.size)
    }

    // ---- TC-ACC-03-34、04-24 ----

    @Test
    fun `TC-ACC-03-34 在线或 access 未过期：kicked 带日期；不重连`() {
        for (reason in listOf("passwordChanged", "sessionRevoked", "accountDeleted")) {
            val a = setupAccount()
            a.session(1, DAY)
            val c = a.online()
            a.kicked(c, reason, recoverBefore = BEFORE)
            assertEquals(listOf<FatalReason>(FatalReason.Kicked(reason, 4012, null, BEFORE)), a.fatals())
            val e = a.ended.single()
            assertEquals(SessionDialog.RECOVER_BEFORE, e.dialog)
            assertEquals(BEFORE, e.recoverBefore)
            assertEquals("你的账号凭证有变化。如果不是你本人操作，请在 11月13日 前用恢复码找回", sessionDialogText(e.dialog, e.recoverBefore) { "11月13日" })
            a.clock.advance(60_000)
            assertEquals(1, a.transport.connections.size)
            assertNull(a.current)
        }
    }

    @Test
    fun `TC-ACC-04-15 access 已过期：刷新得到 4019 credentialsChanged，日期在 details 里，同样提示日期，不弹凭证异常（AC-ACC-04-16）`() {
        val a = setupAccount()
        a.session(1, -HOUR)
        a.connect()
        a.refresh().respondError(401, 4019, "credentialsChanged", obj("recoverBefore" to BEFORE))
        val e = a.ended.single()
        assertEquals(SessionDialog.RECOVER_BEFORE, e.dialog)
        assertEquals(BEFORE, e.recoverBefore)
        assertEquals(listOf<FatalReason>(FatalReason.Session(e)), a.fatals())
        assertEquals(0, a.transport.connections.size)
    }

    @Test
    fun `TC-ACC-04-24 没有日期：按原因显示现有文案（AC-ACC-04-25 的 SDK 部分）`() {
        val a = setupAccount()
        a.session(1, DAY)
        a.kicked(a.online(), "passwordChanged")
        assertEquals("账号已被下线", sessionDialogText(a.ended.single().dialog))
    }

    @Test
    fun `TC-ACC-04-05 tooManyConnections 不结束账号会话，其他踢线结束`() {
        val a = setupAccount()
        a.session(1, DAY)
        a.kicked(a.online(), "tooManyConnections", 4429)
        assertEquals("tooManyConnections", (a.fatals().single() as FatalReason.Kicked).reason)
        assertNotNull(a.current)
    }

    @Test
    fun `serverShutdown 按退避重连，账号会话不受影响`() {
        val a = setupAccount()
        a.session(1, DAY)
        a.kicked(a.online(), "serverShutdown", 1001)
        assertEquals(emptyList(), a.fatals())
        a.clock.advance(1_000)
        assertEquals(2, a.transport.connections.size)
    }

    // ---- TC-ACC-03-39、03-41 ----

    @Test
    fun `TC-ACC-03-39 4290：退避，不回登录页（AC-ACC-03-37 的 SDK 部分）`() {
        val a = setupAccount()
        a.session(1, HOUR)
        a.foreground()
        a.refresh().respondError(429, 4290)
        assertEquals(emptyList(), a.ended)
        a.clock.advance(1_000)
        assertEquals(2, a.http.to(REFRESH).size)
    }

    private fun com.tianting.sdk.testing.FakeHttpCall.superseded(seconds: Int = 120) =
        respondError(401, 4019, "superseded", obj("retryAfterSeconds" to seconds))

    @Test
    fun `TC-ACC-03-41 按 retryAfterSeconds 重试，间隔都是 120 秒，第 3 次仍然 superseded 回登录页（AC-ACC-03-39）`() {
        val a = setupAccount()
        a.session(1, HOUR)
        a.foreground()
        a.refresh().superseded()
        assertEquals(emptyList(), a.ended)
        a.clock.advance(119_999)
        assertEquals(1, a.http.to(REFRESH).size)
        a.clock.advance(1)
        assertEquals(2, a.http.to(REFRESH).size)
        a.refresh().superseded()
        assertEquals(emptyList(), a.ended)
        a.clock.advance(120_000)
        assertEquals(3, a.http.to(REFRESH).size)
        a.refresh().superseded()
        assertEquals(SessionDialog.SUPERSEDED_EXCEEDED, a.ended.single().dialog)
        a.clock.advance(600_000)
        assertEquals(3, a.http.to(REFRESH).size)
    }

    @Test
    fun `TC-ACC-03-41 retryAfterSeconds 异常值有上限 600 秒，缺字段按 1 秒`() {
        val a = setupAccount()
        a.session(1, HOUR)
        a.foreground()
        a.refresh().superseded(100_000)
        a.clock.advance(600_000)
        assertEquals(2, a.http.to(REFRESH).size)
        a.refresh().respondError(401, 4019, "superseded")
        a.clock.advance(1_000)
        assertEquals(3, a.http.to(REFRESH).size)
    }

    @Test
    fun `TC-ACC-03-41 重试前重读本地最新 token`() {
        val a = setupAccount()
        a.session(1, HOUR)
        a.foreground()
        a.refresh().superseded()
        // 另一个进程或者崩溃前写进存储的新值。
        a.store.saved = a.store.saved!!.copy(accessToken = jwt("sid1", 7), refreshToken = refreshToken("sid1", 7))
        a.clock.advance(120_000)
        assertEquals(refreshToken("sid1", 7), a.refresh().refreshField)
    }

    @Test
    fun `TC-ACC-03-41 存储里的值不比本地新：不换`() {
        val a = setupAccount()
        a.session(3, HOUR)
        a.foreground()
        a.refresh().superseded()
        a.store.saved = a.store.saved!!.copy(accessToken = jwt("sid1", 2), refreshToken = refreshToken("sid1", 2))
        a.clock.advance(120_000)
        assertEquals(refreshToken("sid1", 3), a.refresh().refreshField)
    }

    @Test
    fun `TC-ACC-03-41 等 superseded 重试时回前台、kicked 都不提前`() {
        val a = setupAccount()
        a.session(1, HOUR)
        val c = a.online()
        a.refresh().superseded()
        a.foreground()
        c.drop(4011)
        assertEquals(1, a.http.to(REFRESH).size)
    }

    @Test
    fun `TC-ACC-03-41 成功后清零：之后 3 次 superseded 才回登录页`() {
        val a = setupAccount()
        a.session(1, HOUR)
        a.foreground()
        a.refresh().superseded(1)
        a.clock.advance(1_000)
        a.refresh().superseded(1)
        a.clock.advance(1_000)
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY))
        a.clock.advance(18 * HOUR + 1) // 刷新回来就不足 6 小时的 token 不再主动刷新（问题 S1）
        a.foreground()
        repeat(2) {
            a.refresh().superseded(1)
            a.clock.advance(1_000)
        }
        assertEquals(emptyList(), a.ended)
        a.refresh().superseded(1)
        assertEquals(1, a.ended.size)
    }

    // ---- TC-ACC-03-44 ----

    private val eventsOk = obj("events" to emptyList<Any>(), "otherSessions" to obj("count" to 0))

    @Test
    fun `TC-ACC-03-44 3 个请求都用本地新 token 重试成功，没有多发刷新（AC-ACC-03-42，C1 用 events 代替 files-urls）`() {
        val a = setupAccount()
        val s = a.session(1, DAY)
        val results = List(3) { a.start { listEvents() } }
        val inflight = a.http.pending
        assertEquals(3, inflight.size)
        a.call { restore(s.copy(accessToken = jwt("sid1", 2), refreshToken = refreshToken("sid1", 2))) }
        for (c in inflight) c.respondError(401, 4011, "tokenSuperseded")
        val retries = a.http.pending
        assertEquals(3, retries.size)
        for (c in retries) {
            assertEquals(jwt("sid1", 2), c.bearer)
            c.respond(200, eventsOk)
        }
        results.forEach { a.await(it) }
        assertEquals(0, a.http.to(REFRESH).size)
    }

    @Test
    fun `TC-ACC-03-44 本地不比它新：3 个 4011 合并成一次刷新，刷新后都重试`() {
        val a = setupAccount()
        a.session(1, DAY)
        val results = List(3) { a.start { listEvents() } }
        for (c in a.http.pending) c.respondError(401, 4011)
        assertEquals(1, a.http.to(REFRESH).size)
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY))
        for (c in a.http.pending) {
            assertEquals(jwt("sid1", 2), c.bearer)
            c.respond(200, eventsOk)
        }
        results.forEach { a.await(it) }
        assertEquals(1, a.http.to(REFRESH).size)
    }

    @Test
    fun `TC-ACC-03-44 一直 4011：有上限，把错误交给调用方`() {
        val a = setupAccount()
        a.session(5, DAY)
        a.http.onRequest = { call ->
            a.check(call)
            if (call.path == REFRESH) {
                val gen = call.refreshField.split('.')[2].toLong()
                call.respond(200, authBody(gen + 1, a.clock.now() + DAY))
            } else {
                call.respondError(401, 4011)
            }
        }
        val p = a.start { listEvents() }
        assertEquals(4011, a.failure<AccountError>(p).code)
        assertEquals(3, a.http.to("/v1/account/events").size)
    }

    @Test
    fun `TC-ACC-03-44 等刷新最多 30 秒，超时把错误交给调用方，后台继续重试`() {
        val a = setupAccount()
        a.session(1, DAY)
        val p = a.start { listEvents() }
        a.http.last("/v1/account/events").respondError(401, 4011)
        a.refresh().fail()
        a.clock.advance(1_000)
        a.refresh().fail()
        a.clock.advance(29_000)
        a.failure<AccountNetworkError>(p)
        a.clock.advance(4_000)
        assertTrue(a.http.to(REFRESH).size >= 3)
    }

    @Test
    fun `TC-ACC-03-44 HTTP 和 WS 共用 token 来源：HTTP 触发的刷新让连接也换 token`() {
        val a = setupAccount()
        a.session(1, DAY)
        val c1 = a.online()
        val p = a.start { listEvents() }
        a.http.last("/v1/account/events").respondError(401, 4011)
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY))
        assertEquals(1000, c1.closedWith?.first)
        assertEquals(jwt("sid1", 2), authToken(a.authNew()))
        a.http.last("/v1/account/events").respond(200, obj("events" to emptyList<Any>(), "otherSessions" to obj("count" to 1)))
        assertEquals(1, a.await(p).otherSessions.count)
    }

    @Test
    fun `TC-ACC-03-44 HTTP 4012：会话结束`() {
        val a = setupAccount()
        a.session(1, DAY)
        val p = a.start { listEvents() }
        a.http.last("/v1/account/events").respondError(401, 4012)
        a.failure<AccountError>(p)
        assertEquals(SessionEnd(SessionEndAction.LOGIN, SessionDialog.SESSION_EXPIRED, code = 4012), a.ended.single())
    }

    // ---- TC-ACC-03-45 ----

    private fun capped(a: AccountHarness): Long {
        a.session(1, 7 * HOUR)
        a.foreground()
        a.clock.advance(2 * HOUR) // 还有 5 小时
        a.foreground()
        assertEquals(1, a.http.to(REFRESH).size)
        val at = a.clock.now() + 5 * HOUR
        a.refresh().respond(200, authBody(2, at, absoluteExpiresAt = at))
        return at
    }

    @Test
    fun `TC-ACC-03-45 access 到期等于绝对上限：不再刷新，不重连；到期后回登录页（AC-ACC-03-43）`() {
        val a = setupAccount()
        capped(a)
        val c = a.online()
        a.clock.advance(HOUR)
        a.foreground()
        assertEquals(1, a.http.to(REFRESH).size)
        assertEquals(1, a.transport.connections.size)
        assertNull(c.closedWith)
        assertEquals(emptyList(), a.ended)
        a.clock.advance(4 * HOUR)
        assertEquals(SessionDialog.SESSION_EXPIRED, a.ended.single().dialog)
        assertEquals(SessionDialog.SESSION_EXPIRED, (a.fatals().single() as FatalReason.Session).end.dialog)
        assertNull(a.current)
        assertEquals(1, a.http.to(REFRESH).size)
    }

    @Test
    fun `P-35 L1 上限前最后一秒服务端踢 kicked tokenExpired：直接登录失效，不发刷新、不重连`() {
        val a = setupAccount()
        val at = capped(a)
        val c = a.online()
        a.clock.advance(at - a.clock.now() - 400) // JWT 的 exp 只到秒：服务端比本地的上限早不到 1 秒踢
        a.kicked(c, "tokenExpired", code = 4011)
        assertEquals(SessionDialog.SESSION_EXPIRED, a.ended.single().dialog)
        assertEquals("expired", a.ended.single().reason)
        assertEquals(SessionDialog.SESSION_EXPIRED, (a.fatals().single() as FatalReason.Session).end.dialog)
        assertNull(a.current)
        a.clock.advance(120_000)
        assertEquals(1, a.http.to(REFRESH).size) // 只有 capped 里那一次
        assertEquals(1, a.transport.connections.size)
    }

    @Test
    fun `P-35 L1 只有关闭码 4011（没有 kicked 帧）同样不刷新`() {
        val a = setupAccount()
        val at = capped(a)
        val c = a.online()
        a.clock.advance(at - a.clock.now() - 400)
        c.drop(4011)
        assertEquals(SessionDialog.SESSION_EXPIRED, a.ended.single().dialog)
        a.clock.advance(120_000)
        assertEquals(1, a.http.to(REFRESH).size)
        assertEquals(1, a.transport.connections.size)
    }

    @Test
    fun `P-35 L1 只看到期等于上限：普通 token 的 kicked tokenExpired 仍然刷新一次`() {
        val a = setupAccount()
        a.session(1, DAY)
        a.kicked(a.online(), "tokenExpired", code = 4011)
        assertEquals(1, a.http.to(REFRESH).size)
        assertEquals(emptyList(), a.ended)
    }

    @Test
    fun `TC-ACC-03-45 没有连接：到期后回前台或拨号都回登录页，不发刷新`() {
        val a = setupAccount()
        capped(a)
        a.clock.advance(6 * HOUR)
        a.foreground()
        assertEquals(SessionDialog.SESSION_EXPIRED, a.ended.single().dialog)
        assertEquals(1, a.http.to(REFRESH).size)
    }

    // ---- TC-ACC-04-05 ----

    @Test
    fun `TC-ACC-04-05 passwordChanged：不重连，带日期，本地清空（AC-ACC-04-5）`() {
        val a = setupAccount()
        a.session(1, DAY)
        a.kicked(a.online(), "passwordChanged", recoverBefore = BEFORE)
        assertEquals(1, a.fatals().size)
        assertNull(a.store.saved)
        a.clock.advance(120_000)
        assertEquals(1, a.transport.connections.size)
    }

    // ---- TC-ACC-04-09 ----

    @Test
    fun `TC-ACC-04-09 退出其他设备：密码错误 401，没有变化，连接不受影响（AC-ACC-04-10）`() {
        val a = setupAccount()
        a.session(1, DAY)
        val c = a.online()
        val p = a.start { logoutOthers("wrong") }
        a.http.last("/v1/account/logout-others").respondError(401, 4018)
        assertEquals(4018, a.failure<AccountError>(p).code)
        assertNull(c.closedWith)
        assertEquals(emptyList(), a.ended)
        assertEquals(1L, a.account.localGen)
    }

    @Test
    fun `TC-ACC-04-09 密码正确：旧 sid 的踢线被缓存后忽略，换新 sid 1 秒内重连，不提示，新 token 落盘`() {
        val a = setupAccount()
        a.session(4, DAY, "sid-old")
        val c1 = a.online()
        val p = a.start { logoutOthers("Password-1") }
        a.kicked(c1, "sessionRevoked")
        assertEquals(emptyList(), a.fatals())
        assertEquals(1, a.transport.connections.size)
        a.clock.advance(500)
        a.http.last("/v1/account/logout-others").respond(200, authBody(1, a.clock.now() + DAY, sid = "sid-new"))
        a.await(p)
        assertEquals(2, a.transport.connections.size)
        assertEquals(jwt("sid-new", 1), authToken(a.authNew()))
        assertEquals(refreshToken("sid-new", 1), a.store.saved?.refreshToken)
        a.clock.advance(5_000)
        assertEquals(emptyList(), a.fatals())
        a.noBannerShown()
    }

    // ---- TC-ACC-04-16 ----

    @Test
    fun `TC-ACC-04-16 改密进行中定时器到期：不发刷新；改密成功换 token 后也不用再刷新（AC-ACC-04-17）`() {
        val a = setupAccount()
        a.session(1, 6 * HOUR + 10_000)
        a.online()
        val p = a.start { changePassword("Password-1", "Password-2") }
        a.clock.advance(11_000)
        assertEquals(0, a.http.to(REFRESH).size)
        a.http.last(PASSWORD).respond(200, authBody(1, a.clock.now() + DAY, sid = "s2", recoveryCode = "CODE-2"))
        a.await(p)
        assertEquals(0, a.http.to(REFRESH).size)
        assertEquals(1, a.maxPending)
    }

    @Test
    fun `TC-ACC-04-16 改密失败：排着的刷新接着发`() {
        val a = setupAccount()
        a.session(1, 6 * HOUR + 10_000)
        a.online()
        val p = a.start { changePassword("wrong", "Password-2") }
        a.clock.advance(11_000)
        a.http.last(PASSWORD).respondError(401, 4018)
        assertEquals(4018, a.failure<AccountError>(p).code)
        assertEquals(1, a.http.to(REFRESH).size)
        assertEquals(1, a.maxPending)
    }

    @Test
    fun `TC-ACC-04-16 刷新进行中调用改密：等刷新结束，用新 token 发，顺序固定`() {
        val a = setupAccount()
        a.session(1, HOUR)
        a.foreground()
        val p = a.start { changePassword("Password-1", "Password-2") }
        assertEquals(0, a.http.to(PASSWORD).size)
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY))
        assertEquals(1, a.http.to(PASSWORD).size)
        assertEquals(jwt("sid1", 2), a.http.last(PASSWORD).bearer)
        a.http.last(PASSWORD).respond(200, authBody(1, a.clock.now() + DAY, sid = "s2", recoveryCode = "C"))
        a.await(p)
        assertEquals(1, a.maxPending)
    }

    @Test
    fun `TC-ACC-04-16 队列内的改密要先刷新：access 已过期时直接在自己的位置刷新，不重新排队，也不等自己`() {
        val a = setupAccount()
        a.session(1, -MIN)
        val p = a.start { changePassword("Password-1", "Password-2") }
        assertEquals(listOf(REFRESH), a.http.calls.map { it.path })
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY))
        assertEquals(listOf(REFRESH, PASSWORD), a.http.calls.map { it.path })
        assertEquals(jwt("sid1", 2), a.http.last(PASSWORD).bearer)
        a.http.last(PASSWORD).respond(200, authBody(1, a.clock.now() + DAY, sid = "s2", recoveryCode = "C"))
        assertEquals("C", a.await(p).recoveryCode)
    }

    @Test
    fun `TC-ACC-04-16 改密收到 4011：在自己的位置刷新后重发一次`() {
        val a = setupAccount()
        a.session(1, DAY)
        val p = a.start { changePassword("Password-1", "Password-2") }
        a.http.last(PASSWORD).respondError(401, 4011)
        assertEquals(1, a.http.to(REFRESH).size)
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY))
        assertEquals(2, a.http.to(PASSWORD).size)
        a.http.last(PASSWORD).respond(200, authBody(1, a.clock.now() + DAY, sid = "s2", recoveryCode = "C"))
        a.await(p)
    }

    @Test
    fun `TC-ACC-04-16 队列内的刷新失败（网络）：改密失败，不堵队列，后台按退避继续刷新`() {
        val a = setupAccount()
        a.session(1, -MIN)
        val p = a.start { changePassword("Password-1", "Password-2") }
        a.refresh().fail()
        a.failure<AccountNetworkError>(p)
        assertEquals(0, a.http.to(PASSWORD).size)
        a.clock.advance(1_000)
        assertEquals(2, a.http.to(REFRESH).size)
    }

    @Test
    fun `TC-ACC-04-16 找回和退出其他设备也进同一个队列`() {
        val a = setupAccount()
        a.session(1, HOUR)
        a.foreground()
        val p1 = a.start { logoutOthers("Password-1") }
        val p2 = a.start { recover(USER, "CODE", "Password-2") }
        assertEquals(listOf(REFRESH), a.http.pending.map { it.path })
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY))
        assertEquals(listOf("/v1/account/logout-others"), a.http.pending.map { it.path })
        a.http.pending[0].respond(200, authBody(1, a.clock.now() + DAY, sid = "s2"))
        a.await(p1)
        assertEquals(listOf("/v1/account/recover"), a.http.pending.map { it.path })
        a.http.pending[0].respond(200, authBody(1, a.clock.now() + DAY, sid = "s3", recoveryCode = "C3"))
        a.await(p2)
        assertEquals(1, a.maxPending)
    }

    @Test
    fun `TC-ACC-04-16 登录、注册、注销申请也进同一个队列（问题 7）`() {
        val a = setupAccount()
        a.session(1, HOUR)
        a.foreground()
        val login = a.start { login(USER, "Password-1") }
        val register = a.start { register(USER, "Password-1", inviteCode = "I") }
        val delete = a.start { requestDelete("Password-1") }
        assertEquals(listOf(REFRESH), a.http.pending.map { it.path })
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY))
        assertEquals(listOf("/v1/account/login"), a.http.pending.map { it.path })
        a.http.pending[0].respondError(401, 4018)
        a.failure<AccountError>(login)
        assertEquals(listOf("/v1/account/register"), a.http.pending.map { it.path })
        a.http.pending[0].respondError(409, 4095)
        a.failure<AccountError>(register)
        assertEquals(listOf("/v1/account/delete"), a.http.pending.map { it.path })
        a.http.pending[0].respondError(401, 4018)
        a.failure<AccountError>(delete)
        assertEquals(1, a.maxPending)
    }

    @Test
    fun `改密进行中调用方的协程被取消：响应回来还是换 token，队列释放（Kotlin 特有）`() {
        val a = setupAccount()
        a.session(1, DAY)
        val job = a.h.callScope.launch(start = CoroutineStart.UNDISPATCHED) { a.account.changePassword("Password-1", "Password-2") }
        a.h.flush()
        val call = a.http.last(PASSWORD)
        a.h.await(a.h.callScope.async { job.cancelAndJoin() })
        call.respond(200, authBody(1, a.clock.now() + DAY, sid = "s2", recoveryCode = "C"))
        assertEquals("s2", lineageOf(a.current!!.accessToken).sid)
        val next = a.start { login(USER, "Password-2") }
        assertEquals(1, a.http.to("/v1/account/login").size)
        a.http.last("/v1/account/login").respondError(401, 4018)
        a.failure<AccountError>(next)
    }

    // ---- TC-ACC-04-25 ----

    private fun AccountHarness.kick(c: com.tianting.sdk.testing.FakeConnection) = kicked(c, "passwordChanged", recoverBefore = BEFORE)

    private val fatalKick: FatalReason = FatalReason.Kicked("passwordChanged", 4012, null, BEFORE)

    private fun AccountHarness.newTokens() = authBody(1, clock.now() + DAY, sid = "sid-new", recoveryCode = "CODE-2", recoveryCodeGen = 2)

    /** 确认队列不堵：下一个操作能发出去。 */
    private fun queueFree(a: AccountHarness) {
        val before = a.http.calls.size
        a.start { login(USER, "Password-2") }
        assertEquals(before + 1, a.http.calls.size)
    }

    @Test
    fun `TC-ACC-04-25 场景一 响应一直不来：30 秒后提示失败，缓存的踢线生效（AC-ACC-04-27）`() {
        val a = setupAccount()
        a.session(1, DAY)
        val c = a.online()
        val p = a.start { changePassword("Password-1", "Password-2") }
        a.kick(c)
        a.clock.advance(29_999)
        assertEquals(emptyList(), a.fatals())
        a.clock.advance(1)
        a.failure<AccountTimeoutError>(p)
        assertEquals(listOf(fatalKick), a.fatals())
        assertEquals(SessionDialog.RECOVER_BEFORE, a.ended.single().dialog)
        assertTrue(a.ended.single().fromKick)
        assertNull(a.current)
        queueFree(a)
    }

    @Test
    fun `TC-ACC-04-25 场景二 响应在 31 秒到达：丢弃，不换 token，踢线已经生效`() {
        val a = setupAccount()
        a.session(1, DAY)
        val c = a.online()
        val p = a.start { changePassword("Password-1", "Password-2") }
        val call = a.http.last(PASSWORD)
        a.kick(c)
        a.clock.advance(31_000)
        call.respond(200, a.newTokens())
        a.failure<AccountTimeoutError>(p)
        assertNull(a.current)
        assertEquals(1L, a.account.localGen)
        assertNull(a.store.saved)
        assertEquals(1, a.transport.connections.size)
        assertEquals(listOf(fatalKick), a.fatals())
        queueFree(a)
    }

    @Test
    fun `TC-ACC-04-25 场景三 响应 500，同时收到踢线：提示失败，踢线生效`() {
        val a = setupAccount()
        a.session(1, DAY)
        val c = a.online()
        val p = a.start { changePassword("Password-1", "Password-2") }
        a.kick(c)
        a.http.last(PASSWORD).respondError(500, 5000)
        assertEquals(5000, a.failure<AccountError>(p).code)
        assertEquals(listOf(fatalKick), a.fatals())
        queueFree(a)
    }

    @Test
    fun `TC-ACC-04-25 场景四 响应先到踢线后到：不弹窗，用新 token 重连，旧 sid 的踢线被忽略`() {
        val a = setupAccount()
        a.session(3, DAY, "sid-old")
        val c1 = a.online()
        val p = a.start { changePassword("Password-1", "Password-2") }
        a.http.last(PASSWORD).respond(200, a.newTokens())
        val r = a.await(p)
        assertEquals("CODE-2", r.recoveryCode)
        assertEquals(2L, r.recoveryCodeGen)
        assertEquals(1000, c1.closedWith?.first)
        a.kick(c1)
        assertEquals(jwt("sid-new", 1), authToken(a.authNew()))
        a.clock.advance(5_000)
        assertEquals(emptyList(), a.fatals())
        assertEquals(emptyList(), a.ended)
        a.noBannerShown()
        assertEquals(2, a.transport.connections.size)
        queueFree(a)
    }

    @Test
    fun `TC-ACC-04-25 踢线先到响应后到（真实服务端的顺序）：踢线被缓存，成功后立即重连，不弹窗`() {
        val a = setupAccount()
        a.session(3, DAY, "sid-old")
        val c1 = a.online()
        val p = a.start { changePassword("Password-1", "Password-2") }
        a.kick(c1)
        assertEquals(emptyList(), a.fatals())
        assertEquals(1, a.transport.connections.size)
        a.http.last(PASSWORD).respond(200, a.newTokens())
        a.await(p)
        assertEquals(2, a.transport.connections.size)
        assertEquals(jwt("sid-new", 1), authToken(a.authNew()))
        a.clock.advance(5_000)
        assertEquals(emptyList(), a.fatals())
        a.noBannerShown()
    }

    @Test
    fun `TC-ACC-04-25 踢线比换 sid 的响应晚一步到账号这边（两条线程的竞争，Kotlin 特有）：旧 sid 的踢线作废，当前 sid 的照常生效`() {
        val a = setupAccount()
        a.session(3, DAY, "sid-old")
        val p = a.start { changePassword("Password-1", "Password-2") }
        a.http.last(PASSWORD).respond(200, a.newTokens())
        a.await(p)
        val late = a.h.callScope.async { a.account.tokenProvider.kickReceived(KickInfo("passwordChanged", 4012, BEFORE, "sid-old")) }
        assertEquals(KickDecision.IGNORE, a.await(late))
        assertNotNull(a.current)
        assertEquals(emptyList(), a.ended)
        val real = a.h.callScope.async { a.account.tokenProvider.kickReceived(KickInfo("passwordChanged", 4012, BEFORE, "sid-new")) }
        assertEquals(KickDecision.PASS, a.await(real))
        assertNull(a.current)
    }

    @Test
    fun `TC-ACC-04-25 封禁不缓存：改密进行中收到 banned 立即生效`() {
        val a = setupAccount()
        a.session(1, DAY)
        val c = a.online()
        a.start { changePassword("Password-1", "Password-2") }
        a.kicked(c, "banned")
        assertEquals("banned", (a.fatals().single() as FatalReason.Kicked).reason)
    }

    @Test
    fun `TC-ACC-04-25 不认识的原因 + 4012：改密进行中也缓存，成功后忽略`() {
        val a = setupAccount()
        a.session(1, DAY, "sid-old")
        val c = a.online()
        val p = a.start { changePassword("Password-1", "Password-2") }
        c.receive(event("kicked", "reason" to "futureReason"))
        c.drop(4012)
        assertEquals(emptyList(), a.fatals())
        a.http.last(PASSWORD).respond(200, a.newTokens())
        a.await(p)
        assertEquals(emptyList(), a.fatals())
        assertEquals(2, a.transport.connections.size)
    }

    @Test
    fun `TC-ACC-04-25 没有改密进行时：同样的踢线立即生效`() {
        val a = setupAccount()
        a.session(1, DAY)
        a.kick(a.online())
        assertEquals(listOf(fatalKick), a.fatals())
    }

    @Test
    fun `TC-ACC-04-25 改密还在队列里没发出去：踢线不缓存`() {
        val a = setupAccount()
        a.session(1, HOUR)
        val c = a.online()
        a.start { changePassword("Password-1", "Password-2") }
        assertEquals(0, a.http.to(PASSWORD).size) // 前面有刷新
        a.kick(c)
        assertEquals(listOf(fatalKick), a.fatals())
    }

    // ---- TC-ACC-05-06、05-18 ----

    @Test
    fun `TC-ACC-05-06 注册、登录：确认前不落盘，确认后才落盘（AC-ACC-05-7）`() {
        val a = setupAccount()
        val p = a.start { register(USER, "Password-1", inviteCode = "INV") }
        a.http.last("/v1/account/register").respond(200, authBody(1, a.clock.now() + DAY, recoveryCode = "CODE-1", recoveryCodeGen = 1))
        val r = a.await(p)
        assertEquals(USER, r.userId)
        assertEquals("CODE-1", r.recoveryCode)
        assertEquals(1L, r.recoveryCodeGen)
        assertNotNull(a.current)
        assertNull(a.store.saved)
        // 杀掉 App：新实例读不到 token，回登录页。
        assertNull(a.h.await(a.h.callScope.async { a.newClient().start() }))
        val ack = a.start { ackRecoveryCode(1) }
        assertEquals("1", a.http.last("/v1/account/recovery-code/ack").json["gen"].toString())
        a.http.last("/v1/account/recovery-code/ack").respond(200)
        a.await(ack)
        assertEquals(refreshToken("sid1", 1), a.store.saved?.refreshToken)
    }

    @Test
    fun `TC-ACC-05-06 确认前刷新也不落盘`() {
        val a = setupAccount()
        val login = a.start { login(USER, "Password-1") }
        a.http.last("/v1/account/login").respond(200, authBody(1, a.clock.now() + HOUR, recoveryCode = "C", recoveryCodeGen = 3))
        a.await(login)
        a.foreground()
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY))
        assertEquals(2L, a.account.localGen)
        assertNull(a.store.saved)
        assertEquals(emptyList(), a.store.saves)
    }

    @Test
    fun `TC-ACC-05-18 改密响应到达：旧 token 从存储清除，新 token 确认前不落盘（杀 App 后回登录页）（AC-ACC-05-19）`() {
        val a = setupAccount()
        a.session(1, DAY)
        assertNotNull(a.store.saved)
        val p = a.start { changePassword("Password-1", "Password-2") }
        a.http.last(PASSWORD).respond(200, authBody(1, a.clock.now() + DAY, sid = "s2", recoveryCode = "C", recoveryCodeGen = 2))
        a.await(p)
        assertNull(a.store.saved)
        assertEquals(refreshToken("s2", 1), a.current?.refreshToken)
        assertNull(a.h.await(a.h.callScope.async { a.newClient().start() }))
    }

    @Test
    fun `TC-ACC-05-18 恢复码还没确认时退出其他设备：新 token 同样只放内存`() {
        val a = setupAccount()
        val login = a.start { login(USER, "Password-1") }
        a.http.last("/v1/account/login").respond(200, authBody(1, a.clock.now() + DAY, recoveryCode = "C", recoveryCodeGen = 3))
        a.await(login)
        val p = a.start { logoutOthers("Password-1") }
        a.http.last("/v1/account/logout-others").respond(200, authBody(1, a.clock.now() + DAY, sid = "s2"))
        a.await(p)
        assertEquals(refreshToken("s2", 1), a.current?.refreshToken)
        assertNull(a.store.saved)
        assertEquals(emptyList(), a.store.saves)
    }

    @Test
    fun `TC-ACC-05-18 auth ack 带 recoveryCodeUnacked：App 收到事件`() {
        val a = setupAccount()
        a.session(1, DAY)
        a.connect()
        val c = a.transport.current
        c.open()
        c.ack("auth", obj("userId" to USER, "serverTime" to a.clock.now(), "recoveryCodeUnacked" to true))
        a.h.flush()
        assertEquals(1, a.h.events.count { it == IMEvent.RecoveryCodeUnacked })
        assertEquals(1, a.accountEvents.count { it == AccountEvent.RecoveryCodeUnacked })
    }

    @Test
    fun `TC-ACC-05-18 账号接口的响应带 recoveryCodeUnacked：App 收到事件`() {
        val a = setupAccount()
        a.session(1, DAY)
        val p = a.start { listEvents() }
        a.http.last("/v1/account/events").respond(200, obj("events" to emptyList<Any>(), "otherSessions" to obj("count" to 0), "recoveryCodeUnacked" to true))
        assertTrue(a.await(p).recoveryCodeUnacked)
        assertEquals(1, a.accountEvents.count { it == AccountEvent.RecoveryCodeUnacked })
    }

    @Test
    fun `TC-ACC-05-18 重新生成恢复码：响应只有恢复码（RecoveryCodeResponse），token 不变`() {
        val a = setupAccount()
        a.session(1, DAY)
        val p = a.start { regenerateRecoveryCode("Password-1") }
        a.http.last("/v1/account/recovery-code").respond(200, obj("recoveryCode" to "NEW", "recoveryCodeGen" to 5))
        val r = a.await(p)
        assertEquals("NEW", r.recoveryCode)
        assertEquals(5L, r.recoveryCodeGen)
        assertEquals(1L, a.account.localGen)
    }

    @Test
    fun `TC-ACC-05-14 recoveryCodeStale：提示后回登录页，不算确认，token 不落盘（AC-ACC-05-15）`() {
        val a = setupAccount()
        val login = a.start { login(USER, "Password-1") }
        a.http.last("/v1/account/login").respond(200, authBody(1, a.clock.now() + DAY, recoveryCode = "C", recoveryCodeGen = 1))
        a.await(login)
        val ack = a.start { ackRecoveryCode(1) }
        a.http.last("/v1/account/recovery-code/ack").respondError(400, 4000, "recoveryCodeStale")
        assertEquals("recoveryCodeStale", a.failure<AccountError>(ack).reason)
        assertEquals(SessionDialog.RECOVERY_CODE_STALE, a.ended.single().dialog)
        assertEquals("恢复码已更新，请重新登录查看", sessionDialogText(a.ended.single().dialog))
        assertNull(a.store.saved)
    }

    // ---- TC-ACC-06-05 ----

    @Test
    fun `TC-ACC-06-05 改昵称：本端立即显示；其他端 SDK 钟推进 10 分钟后重新查询（AC-ACC-06-5）`() {
        val a = setupAccount()
        a.session(1, DAY)
        val c = a.online()
        val p = a.start { updateProfile(nickname = "小明") }
        assertEquals(jsonOf("""{"nickname":"小明"}"""), a.http.last("/v1/account/profile").json)
        a.http.last("/v1/account/profile").respond(200)
        a.await(p)
        assertEquals(listOf(USER), a.h.events.filterIsInstance<IMEvent.ProfileChanged>().map { it.userId })
        assertEquals("小明", a.h.call { displayName(USER) })

        // 其他端：缓存 10 分钟（F-SOC-10）。
        val q = a.h.start { getUsers(listOf("bobby")) }
        a.clock.advance(16)
        c.ack("users", obj("users" to listOf(obj("userId" to "bobby", "nickname" to "旧名"))))
        a.h.await(q)
        a.h.start { getUsers(listOf("bobby")) }
        a.clock.advance(16)
        assertEquals(1, c.frames("users").size)
        a.clock.advance(10 * MIN)
        a.h.start { getUsers(listOf("bobby")) }
        a.clock.advance(16)
        assertEquals(2, c.frames("users").size)
    }

    @Test
    fun `H13 改头像：PATCH 成功后马上重新拉自己的资料，缓存里是服务端的新地址，通知 App；只改昵称不重新拉`() {
        val a = setupAccount()
        a.session(1, DAY)
        val c = a.online()
        val p = a.start { updateProfile(avatarFileId = "F9") }
        assertEquals(jsonOf("""{"avatarFileId":"F9"}"""), a.http.last("/v1/account/profile").json)
        a.http.last("/v1/account/profile").respond(200)
        a.await(p)
        val users = c.frames("users")
        assertEquals(1, users.size, "改头像后重新拉自己的资料")
        assertEquals(listOf(USER), users[0].dataObj["userIds"]!!.jsonArray.map { it.str })
        c.ack("users", obj("users" to listOf(obj("userId" to USER, "nickname" to "我", "avatar" to "https://pub.test/a/new.jpg"))))
        assertEquals("https://pub.test/a/new.jpg", a.h.call { profileOf(USER) }?.avatar)
        assertEquals(listOf(USER), a.h.events.filterIsInstance<IMEvent.ProfileChanged>().map { it.userId })

        val q = a.start { updateProfile(nickname = "小明") }
        a.http.last("/v1/account/profile").respond(200)
        a.await(q)
        assertEquals(1, c.frames("users").size, "只改昵称：昵称在本地就有，不重新拉")
    }

    @Test
    fun `H13 改头像时没有连接：连上后重新拉自己的资料`() {
        val a = setupAccount()
        a.session(1, DAY)
        val c = a.online()
        c.drop(1006)
        val p = a.start { updateProfile(avatarFileId = "F9") }
        a.http.last("/v1/account/profile").respond(200)
        a.await(p)
        assertEquals(0, c.frames("users").size)
        a.clock.advance(1_000)
        val c2 = a.authNew()
        val mine = c2.frames("users").filter { f -> f.dataObj["userIds"]!!.jsonArray.map { it.str } == listOf(USER) }
        assertEquals(1, mine.size, "连上后重新拉")
    }

    // ---- TC-ACC-02-13 ----

    @Test
    fun `TC-ACC-02-13 新设备登录：事件发给 App；不认识的 kind 忽略（AC-ACC-02-13 的 SDK 部分）`() {
        val a = setupAccount()
        a.session(1, DAY)
        val c = a.online()
        c.receive(event("securityNotice", "kind" to "newDeviceLogin", "at" to 123, "platform" to "ios"))
        c.receive(event("securityNotice", "kind" to "somethingNew", "at" to 124, "platform" to "ios"))
        assertEquals(listOf(IMEvent.SecurityNotice("newDeviceLogin", 123, "ios")), a.h.events.filterIsInstance<IMEvent.SecurityNotice>())
    }

    @Test
    fun `TC-ACC-02-13 服务端发的七种 kind 都放行，不认识的不放行（AC-ACC-03-25、AC-ACC-03-45，F-ACC-05 规则 5）`() {
        val a = setupAccount()
        a.session(1, DAY)
        val c = a.online()
        val kinds = listOf(
            "newDeviceLogin", "passwordChanged", "passwordRecovered", "recoveryCodeRegenerated", "refreshReplay", "refreshRateLimited", "newIpPrefix",
        )
        for (k in kinds + listOf("futureKind", "")) c.receive(event("securityNotice", "kind" to k, "at" to 1, "platform" to "ios"))
        assertEquals(kinds, a.h.events.filterIsInstance<IMEvent.SecurityNotice>().map { it.kind })
    }

    // ---- TC-ACC-09-01 ----

    @Test
    fun `TC-ACC-09-01 第三个注册 403：错误码和 reason 交给 App，不产生会话（AC-ACC-09-1 的 SDK 部分）`() {
        val a = setupAccount()
        val p = a.start { register("carol_1", "Password-1", inviteCode = "INV") }
        a.http.last("/v1/account/register").respondError(403, 4030, "inviteInvalid")
        val e = a.failure<AccountError>(p)
        assertEquals(403, e.status)
        assertEquals(4030, e.code)
        assertEquals("inviteInvalid", e.reason)
        assertNull(a.current)
    }

    // ---- 退出（F-ACC-04 规则 1） ----

    @Test
    fun `F-ACC-04 规则 1 先通知服务端，再 1000 断开；期间的踢线不算`() {
        val a = setupAccount()
        a.session(1, DAY)
        val c = a.online()
        val p = a.start { logout() }
        val call = a.http.last(LOGOUT)
        assertEquals(jsonOf("""{"refreshToken":"${refreshToken("sid1", 1)}"}"""), call.json)
        assertEquals(jwt("sid1", 1), call.bearer)
        assertNull(c.closedWith)
        a.kicked(c, "sessionRevoked")
        assertEquals(emptyList(), a.fatals())
        call.respond(200)
        assertTrue(a.await(p))
        assertEquals(emptyList(), a.fatals())
        assertEquals(listOf(SessionEnd(SessionEndAction.LOGOUT)), a.ended)
        assertNull(a.store.saved)
        a.clock.advance(60_000)
        assertEquals(1, a.transport.connections.size)
    }

    @Test
    fun `F-ACC-04 规则 1 连接正常时 logout 成功后以 1000 断开`() {
        val a = setupAccount()
        a.session(1, DAY)
        val c = a.online()
        val p = a.start { logout() }
        a.http.last(LOGOUT).respond(200)
        a.await(p)
        assertEquals(1000, c.closedWith?.first)
    }

    @Test
    fun `F-ACC-04 规则 1 退出后迟到的刷新响应、改密响应都救不回会话`() {
        val a = setupAccount()
        a.session(1, HOUR)
        a.foreground()
        val refresh = a.refresh()
        val p = a.start { logout() }
        a.http.last(LOGOUT).respond(200)
        a.await(p)
        refresh.respond(200, authBody(2, a.clock.now() + DAY))
        assertNull(a.current)
        assertNull(a.store.saved)

        val b = setupAccount()
        b.session(1, DAY)
        val change = b.start { changePassword("Password-1", "Password-2") }
        val pw = b.http.last(PASSWORD)
        val out = b.start { logout() }
        b.http.last(LOGOUT).respond(200)
        b.await(out)
        pw.respond(200, authBody(1, b.clock.now() + DAY, sid = "s2", recoveryCode = "C"))
        b.failure<SessionEndedError>(change)
        assertNull(b.current)
        assertNull(b.store.saved)
    }

    @Test
    fun `F-ACC-04 规则 1 慢存储：退出和踢线之后，更早发起的保存不会把会话写回来（Kotlin 特有）`() {
        val a = setupAccount()
        a.session(1, HOUR)
        a.foreground()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        a.store.pauseBeforeSave = gate
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY))
        val p = a.start { logout() } // 刷新的保存还卡着：clear 排在它后面
        assertEquals(0, a.http.to(LOGOUT).size)
        a.store.pauseBeforeSave = null
        gate.complete(Unit)
        a.h.flush()
        a.http.last(LOGOUT).respond(200)
        assertTrue(a.await(p))
        assertEquals(1L, a.account.localGen) // 被放弃的刷新没有换上
        assertNull(a.store.saved)
        assertNull(a.current)
    }

    @Test
    fun `F-ACC-04 规则 1 登录响应落盘期间退出：不复活（Kotlin 特有）`() {
        val a = setupAccount()
        a.session(1, DAY)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        a.store.pauseBeforeSave = gate
        val p = a.start { logoutOthers("Password-1") }
        a.http.last("/v1/account/logout-others").respond(200, authBody(1, a.clock.now() + DAY, sid = "s2"))
        val out = a.start { logout() }
        a.store.pauseBeforeSave = null
        gate.complete(Unit)
        a.h.flush()
        a.http.last(LOGOUT).respond(200)
        a.await(out)
        a.failure<SessionEndedError>(p)
        assertNull(a.current)
        assertNull(a.store.saved)
    }

    @Test
    fun `会话结束时，等 token 的调用方都收到 SessionEndedError，不会一直挂着`() {
        val a = setupAccount()
        a.session(1, DAY)
        val waiting = a.start { refresh() }
        val events = a.start { listEvents() }
        a.http.last("/v1/account/events").respondError(401, 4011) // 也在等这次刷新
        a.refresh().respondError(401, 4019, "revoked")
        a.failure<SessionEndedError>(waiting)
        a.failure<SessionEndedError>(events)
    }

    @Test
    fun `没有会话时调账号接口：SessionEndedError，不发请求`() {
        val a = setupAccount()
        a.failure<SessionEndedError>(a.start { listEvents() })
        a.failure<SessionEndedError>(a.start { changePassword("a", "b") })
        assertEquals(0, a.http.calls.size)
    }

    // ---- 注销（F-ACC-08） ----

    @Test
    fun `F-ACC-08 申请成功：会话结束，踢线不重复通知；密码错误：无变化`() {
        val a = setupAccount()
        a.session(1, DAY)
        val c = a.online()
        val wrong = a.start { requestDelete("wrong") }
        a.http.last("/v1/account/delete").respondError(401, 4018)
        assertEquals(4018, a.failure<AccountError>(wrong).code)
        assertNull(c.closedWith)

        val ok = a.start { requestDelete("Password-1") }
        a.kicked(c, "accountDeleted")
        assertEquals(emptyList(), a.fatals())
        a.http.last("/v1/account/delete").respond(200)
        a.await(ok)
        assertEquals(1, a.fatals().size)
        assertNull(a.current)
        assertEquals(1, a.transport.connections.size)
    }

    @Test
    fun `F-ACC-08 冷静期登录 403 带 deleteAt；撤销是匿名接口`() {
        val a = setupAccount()
        val p = a.start { login(USER, "Password-1") }
        a.http.last("/v1/account/login").respondError(403, 4030, "accountDeletionPending", obj("deleteAt" to 123))
        val e = a.failure<AccountError>(p)
        assertEquals("accountDeletionPending", e.reason)
        assertEquals("123", e.details?.get("deleteAt").toString())
        val c = a.start { cancelDelete(USER, "Password-1") }
        assertNull(a.http.last("/v1/account/delete/cancel").bearer)
        a.http.last("/v1/account/delete/cancel").respond(200)
        a.await(c)
    }

    // ---- HTTP 的基础约定 ----

    @Test
    fun `第 4-2 节 超时走注入的 Clock，30 秒`() {
        val a = setupAccount()
        val p = a.start { getConfig() }
        a.clock.advance(29_999)
        assertEquals(1, a.http.pending.size)
        a.clock.advance(1)
        a.failure<AccountTimeoutError>(p)
        assertTrue(a.http.last("/v1/config").cancelled)
    }

    @Test
    fun `第 13-1 节 getConfig 不带 Bearer；响应解析出来`() {
        val a = setupAccount()
        a.session(1, DAY)
        val p = a.start { getConfig() }
        val limits = obj(
            "usernameMinLen" to 4, "usernameMaxLen" to 32, "passwordMinLen" to 8,
            "passwordMaxLen" to 128, "nicknameMaxLen" to 32, "deviceNameMaxLen" to 64,
        )
        assertNull(a.http.last("/v1/config").bearer)
        a.http.last("/v1/config").respond(200, obj("registerMode" to "invite", "demoLogin" to false, "features" to obj("files" to false), "limits" to limits))
        val cfg = a.await(p)
        assertEquals("invite", cfg.registerMode)
        assertEquals(8, cfg.limits.passwordMinLen)
    }

    @Test
    fun `问题 36（F-UI-09 规则 4）：features 解析出 msgActions；老服务端没有这个字段按关闭`() {
        for ((features, want) in listOf(
            obj("files" to true, "msgActions" to true) to true,
            obj("files" to true, "msgActions" to false) to false,
            obj("files" to true) to false,
        )) {
            val a = setupAccount()
            val p = a.start { getConfig() }
            val limits = obj(
                "usernameMinLen" to 4, "usernameMaxLen" to 32, "passwordMinLen" to 8,
                "passwordMaxLen" to 128, "nicknameMaxLen" to 32, "deviceNameMaxLen" to 64,
            )
            a.http.last("/v1/config").respond(200, obj("registerMode" to "open", "demoLogin" to false, "features" to features, "limits" to limits))
            val cfg = a.await(p)
            assertEquals(want, cfg.features.msgActions, features.toString())
            assertEquals(true, cfg.features.files)
        }
    }

    @Test
    fun `第 13-1 节 响应形状不对按网络错误，不把内容带进异常`() {
        val a = setupAccount()
        val p = a.start { getConfig() }
        a.http.last("/v1/config").respond(200, """{"registerMode":"SECRET-BODY"}""")
        val e = a.failure<AccountNetworkError>(p)
        assertFalse(e.message!!.contains("SECRET-BODY"))
    }

    @Test
    fun `AccountError 是 IMError，describeError 能用；visibilityChanged 触发 foreground（只有可见时）`() {
        val a = setupAccount()
        val s = a.session(1, DAY)
        a.online()
        a.call { restore(s.copy(accessExpiresAt = a.clock.now() + HOUR)) } // 下一次检查就该刷新
        a.h.visible = false
        a.h.im.visibilityChanged()
        a.h.flush()
        assertEquals(0, a.http.to(REFRESH).size)
        a.h.visible = true
        a.h.im.visibilityChanged()
        a.h.flush()
        assertEquals(1, a.http.to(REFRESH).size)
        assertIs<IMError>(AccountError(401, 4018, "x"))
    }

    @Test
    fun `AC-ACC-05-15 回登录页只清本地，不调 logout（问题 10）`() {
        val a = setupAccount()
        a.session(1, DAY)
        val p = a.start { ackRecoveryCode(1) }
        a.http.last("/v1/account/recovery-code/ack").respondError(400, 4000, "recoveryCodeStale")
        a.failure<AccountError>(p)
        assertEquals(0, a.http.to(LOGOUT).size)
        assertEquals(emptyList(), a.pendingStore.items)
    }
}
