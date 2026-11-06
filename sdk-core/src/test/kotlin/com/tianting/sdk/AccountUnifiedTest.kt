// 三套 SDK 统一的语义（frontend-chat 问题表 S1、K7、S3、K5，docs/sdk/README.md 第 6 节）。
// sdk-ts 的 test/account-unified.test.ts、sdk-swift 的 AccountUnifiedTests 是同一组用例。本地 JVM 单测结果，CI 不运行。
package com.tianting.sdk

import com.tianting.sdk.testing.FakeConnection
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ACK = "/v1/account/recovery-code/ack"
private const val REGEN = "/v1/account/recovery-code"
private const val RECOVER = "/v1/account/recover"

/** 超过这么多次就不再应答：修复前循环一直在调度器里跑，这样用例是断言失败，不是卡死。 */
private const val ANSWER_CAP = 50

class AccountUnifiedTest : AccountTest() {
    /** 假服务端：每次刷新都立刻回一个新的 token，到期时间由 left 决定。 */
    private fun AccountHarness.autoRefresh(left: () -> Long) {
        var gen = 100L
        http.onRequest = { call ->
            check(call)
            if (call.path == REFRESH && gen - 100 < ANSWER_CAP) {
                gen += 1
                call.respond(200, authBody(gen, clock.now() + left()))
            }
        }
    }

    /** 跑 rounds 轮：新连接自动完成 auth，时钟往前走。 */
    private fun AccountHarness.run(rounds: Int) {
        repeat(rounds) {
            h.flush()
            val c = transport.connections.lastOrNull()
            if (c != null && c.closedWith == null && c.frames("auth").isEmpty()) h.login(USER)
            clock.advance(1_000)
        }
        h.flush()
    }

    private fun AccountHarness.oldSidKick(c: FakeConnection) = kicked(c, "passwordChanged", 4012)

    // ---- S1 ----

    @Test
    fun `S1 TTL 配得短：只刷新一次、重连一次，不循环`() {
        val a = setupAccount()
        a.session(1, 5 * HOUR)
        a.autoRefresh { 4 * HOUR }
        a.connect()
        a.run(30)
        assertEquals(1, a.http.to(REFRESH).size)
        // 假 HTTP 同步应答，token 在拨号之前就换好了，所以只有一条连接；真实网络上是一条被 1000 断开、一条新的。
        assertEquals(1, a.transport.connections.size)
        assertEquals(jwt("sid1", 101), authToken(a.transport.current))
        assertNull(a.transport.current.closedWith)
        assertEquals(emptyList(), a.ended)
    }

    @Test
    fun `S1 本地时钟偏快，启动已过期：先刷新再 auth，新 token 看起来也过期，不循环，也不当成过期`() {
        val a = setupAccount()
        a.session(1, -HOUR)
        a.autoRefresh { -MIN }
        a.connect()
        assertEquals(1, a.http.to(REFRESH).size)
        assertEquals(1, a.transport.connections.size)
        assertEquals(jwt("sid1", 101), authToken(a.authNew()))
        a.run(30)
        assertEquals(1, a.http.to(REFRESH).size)
        assertEquals(1, a.transport.connections.size)
        // 断线重连：还是用这个 token，不刷新。
        a.transport.current.drop(1006)
        a.run(3)
        assertEquals(1, a.http.to(REFRESH).size)
        assertEquals(2, a.transport.connections.size)
        assertEquals(jwt("sid1", 101), authToken(a.transport.current))
        assertEquals(emptyList(), a.ended)
    }

    @Test
    fun `S1 前台和定时器都不再刷新；HTTP 请求带这个 token 直接发`() {
        val a = setupAccount()
        a.session(1, 5 * HOUR)
        a.autoRefresh { -MIN }
        a.foreground()
        assertEquals(1, a.http.to(REFRESH).size)
        a.foreground()
        a.call { trigger(RefreshSource.TIMER) }
        assertEquals(1, a.http.to(REFRESH).size)

        val events = a.start { listEvents() }
        assertEquals(1, a.http.to(REFRESH).size)
        a.http.last("/v1/account/events").respond(200, jsonOf("""{"events":[],"otherSessions":{"count":0}}"""))
        a.await(events)

        val change = a.start { changePassword("Password-1", "Password-2") }
        assertEquals(1, a.http.to(REFRESH).size)
        a.http.last(PASSWORD).respond(200, authBody(1, a.clock.now() + DAY, sid = "sid2"))
        a.await(change)
    }

    @Test
    fun `S1 服务端的 4011 还是会刷新，一直短也有上限：每个 4011 最多一次`() {
        val a = setupAccount()
        a.session(1, 5 * HOUR)
        a.autoRefresh { 4 * HOUR }
        a.connect()
        a.run(3)
        assertEquals(1, a.http.to(REFRESH).size)
        for (i in 1..5) {
            a.transport.current.drop(4011)
            a.run(40) // 第二次起按退避重连
            assertEquals(1 + i, a.http.to(REFRESH).size)
        }
        assertEquals(emptyList(), a.ended)
    }

    @Test
    fun `S1 下一个时间正常的 token 恢复定时刷新`() {
        val a = setupAccount()
        a.session(1, 5 * HOUR)
        var left = 4 * HOUR
        a.autoRefresh { left }
        a.connect()
        a.run(3)
        assertEquals(1, a.http.to(REFRESH).size)
        left = DAY
        a.transport.current.drop(4011)
        a.run(3)
        assertEquals(2, a.http.to(REFRESH).size)
        a.clock.advance(18 * HOUR)
        assertEquals(3, a.http.to(REFRESH).size)
    }

    @Test
    fun `S1 改密换来的 token 不足 6 小时不算刷新回来的：可以再刷新一次，然后才停`() {
        val a = setupAccount()
        a.session(1, 5 * HOUR)
        a.autoRefresh { 4 * HOUR }
        a.foreground()
        assertEquals(1, a.http.to(REFRESH).size)
        val change = a.start { changePassword("Password-1", "Password-2") }
        a.http.last(PASSWORD).respond(200, authBody(1, a.clock.now() + 4 * HOUR, sid = "sid2"))
        a.await(change)
        a.foreground()
        assertEquals(2, a.http.to(REFRESH).size)
        a.foreground()
        assertEquals(2, a.http.to(REFRESH).size)
    }

    @Test
    fun `S1 重启后判断从头开始：每次启动最多多一次刷新`() {
        val a = setupAccount()
        a.session(1, 5 * HOUR)
        a.autoRefresh { 4 * HOUR }
        a.foreground()
        assertEquals(1, a.http.to(REFRESH).size)
        val s = a.current!!
        a.call { restore(s) }
        a.foreground()
        assertEquals(2, a.http.to(REFRESH).size)
        a.foreground()
        assertEquals(2, a.http.to(REFRESH).size)
    }

    @Test
    fun `S1 绝对上限不受影响：到期回登录页`() {
        val a = setupAccount()
        a.session(1, 5 * HOUR)
        val at = a.clock.now() + 2 * HOUR
        a.foreground()
        a.refresh().respond(200, authBody(2, at, absoluteExpiresAt = at))
        a.online()
        a.clock.advance(2 * HOUR)
        assertEquals(listOf("expired"), a.ended.map { it.reason })
        assertEquals(1, a.http.to(REFRESH).size)
    }

    // ---- K7 ----

    @Test
    fun `K7 没有登录过：发一次 session 类 fatal，不停在连接中，不开连接`() {
        val a = setupAccount()
        a.connect()
        assertEquals(listOf<FatalReason>(FatalReason.Session(SessionEnd(SessionEndAction.LOGIN))), a.fatals())
        assertEquals(ConnState.DISCONNECTED, a.im.state.value)
        assertEquals(0, a.transport.connections.size)
        a.clock.advance(5 * MIN)
        assertEquals(1, a.fatals().size)
        assertEquals(0, a.transport.connections.size)
    }

    @Test
    fun `K7 start 的刷新收到 4019 之后才连接：fatal 带这次结束的原因`() {
        val a = setupAccount()
        a.session(1, 5 * HOUR)
        a.foreground()
        a.refresh().respondError(401, 4019, "replayDetected")
        assertEquals(1, a.ended.size)
        assertEquals(emptyList(), a.fatals())
        a.connect()
        assertEquals(
            listOf<FatalReason>(FatalReason.Session(SessionEnd(SessionEndAction.LOGIN, SessionDialog.CREDENTIAL_ANOMALY, reason = "replayDetected", code = 4019))),
            a.fatals(),
        )
        assertEquals(ConnState.DISCONNECTED, a.im.state.value)
    }

    @Test
    fun `K7 踢线结束后才连接：fatal 不带 fromKick`() {
        val a = setupAccount()
        a.session(1, DAY)
        val c = a.online()
        a.kicked(c, "passwordChanged")
        assertEquals(1, a.fatals().size)
        a.connect()
        assertEquals(2, a.fatals().size)
        assertEquals(
            FatalReason.Session(SessionEnd(SessionEndAction.LOGIN, SessionDialog.ACCOUNT_OFFLINE, reason = "passwordChanged", code = 4012)),
            a.fatals()[1],
        )
    }

    @Test
    fun `K7 退出之后才连接：action 是 LOGOUT`() {
        val a = setupAccount()
        a.session(1, DAY)
        val out = a.start { logout() }
        a.http.last(LOGOUT).respond(200)
        a.await(out)
        a.connect()
        assertEquals(listOf<FatalReason>(FatalReason.Session(SessionEnd(SessionEndAction.LOGOUT))), a.fatals())
    }

    @Test
    fun `K7 等 token 时会话才结束：只有一次 fatal`() {
        val a = setupAccount()
        a.session(1, -HOUR)
        a.connect()
        a.refresh().respondError(401, 4019, "revoked")
        assertEquals(1, a.fatals().size)
        assertEquals(ConnState.DISCONNECTED, a.im.state.value)
    }

    @Test
    fun `K7 fatal 之后重新登录再连接：正常连上`() {
        val a = setupAccount()
        a.connect()
        assertEquals(1, a.fatals().size)
        val login = a.start { login(USER, "Password-1") }
        a.http.last("/v1/account/login").respond(200, authBody(1, a.clock.now() + DAY))
        a.await(login)
        a.connect()
        assertEquals(jwt("sid1", 1), authToken(a.authNew()))
        assertEquals(ConnState.CONNECTED, a.im.state.value)
        assertEquals(1, a.fatals().size)
    }

    // ---- S3（Kotlin 的存储有锁，窗口一和顺序之前就是对的，这里钉住；窗口二是这次修的） ----

    @Test
    fun `S3 窗口一：改密响应落盘时退出，响应不把已结束的会话救回来，存储里也不留`() {
        val a = setupAccount()
        a.session(1, DAY)
        val gate = CompletableDeferred<Unit>()
        val change = a.start { changePassword("Password-1", "Password-2") }
        a.store.pauseBeforeSave = gate
        a.http.last(PASSWORD).respond(200, authBody(1, a.clock.now() + DAY, sid = "sid2"))
        val out = a.start { logout() } // 先写"待退出"和清存储，再发请求：慢存储放开之后才有 logout 请求
        a.store.pauseBeforeSave = null
        gate.complete(Unit)
        a.h.flush()
        a.failure<SessionEndedError>(change)
        a.http.last(LOGOUT).respond(200)
        a.await(out)
        assertNull(a.current)
        assertNull(a.store.saved)
        assertEquals(listOf(SessionEndAction.LOGOUT), a.ended.map { it.action })
    }

    private fun AccountHarness.unacked() {
        session(1, DAY)
        val change = start { changePassword("Password-1", "Password-2") }
        http.last(PASSWORD).respond(200, authBody(1, clock.now() + DAY, sid = "sid2", recoveryCode = "CODE", recoveryCodeGen = 2))
        await(change)
        assertNull(store.saved)
    }

    @Test
    fun `S3 窗口二：写存储失败，错误给调用方，再确认一次能重写，标记没有提前清`() {
        val a = setupAccount()
        a.unacked()
        a.store.failSave = true
        val first = a.start { ackRecoveryCode(2) }
        a.http.last(ACK).respond(200)
        a.failure<java.io.IOException>(first)
        assertNull(a.store.saved)
        a.store.failSave = false
        val second = a.start { ackRecoveryCode(2) }
        a.http.last(ACK).respond(200)
        a.await(second)
        assertEquals(jwt("sid2", 1), a.store.saved?.accessToken)
    }

    @Test
    fun `S3 窗口二：写存储失败之后，刷新还是不落盘`() {
        val a = setupAccount()
        a.unacked()
        a.store.failSave = true
        val first = a.start { ackRecoveryCode(2) }
        a.http.last(ACK).respond(200)
        a.failure<java.io.IOException>(first)
        a.store.failSave = false
        a.clock.advance(18 * HOUR + 1)
        a.foreground()
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY, sid = "sid2"))
        assertEquals(jwt("sid2", 2), a.current?.accessToken)
        assertNull(a.store.saved)
    }

    @Test
    fun `S3 窗口二：写存储期间刷新换了 token，最后存的是在用的，之后的刷新正常落盘`() {
        val a = setupAccount()
        a.unacked()
        val gate = CompletableDeferred<Unit>()
        a.store.pauseBeforeSave = gate
        val ack = a.start { ackRecoveryCode(2) }
        a.http.last(ACK).respond(200)
        a.clock.advance(18 * HOUR + 1)
        a.foreground()
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY, sid = "sid2"))
        a.store.pauseBeforeSave = null
        gate.complete(Unit)
        a.h.flush()
        a.await(ack)
        assertEquals(jwt("sid2", 2), a.current?.accessToken)
        assertEquals(jwt("sid2", 2), a.store.saved?.accessToken)
        a.clock.advance(18 * HOUR + 1)
        a.foreground()
        a.refresh().respond(200, authBody(3, a.clock.now() + DAY, sid = "sid2"))
        assertEquals(jwt("sid2", 3), a.store.saved?.accessToken)
    }

    @Test
    fun `S3 窗口二：写存储期间会话结束，存储里不留`() {
        val a = setupAccount()
        a.unacked()
        val gate = CompletableDeferred<Unit>()
        a.store.pauseBeforeSave = gate
        val ack = a.start { ackRecoveryCode(2) }
        a.http.last(ACK).respond(200)
        val out = a.start { logout() }
        a.store.pauseBeforeSave = null
        gate.complete(Unit)
        a.h.flush()
        a.await(ack)
        a.http.last(LOGOUT).respond(200)
        a.await(out)
        assertNull(a.current)
        assertNull(a.store.saved)
    }

    // ---- 恢复码确认和换代交错：ack 只对当前未确认的那一代生效（F-ACC-05 规则 1，RS5-13） ----

    private fun AccountHarness.withCode(sid: String, code: Long) =
        authBody(1, clock.now() + DAY, sid = sid, recoveryCode = "CODE-$code", recoveryCodeGen = code)

    /** 改密拿到第 2 代恢复码，没有确认。sid 是 sid2。 */
    private fun AccountHarness.unackedGen2() {
        session(1, DAY)
        val change = start { changePassword("Password-1", "Password-2") }
        http.last(PASSWORD).respond(200, withCode("sid2", 2))
        await(change)
        assertNull(store.saved)
    }

    /** 还只放内存：刷新不落盘。 */
    private fun AccountHarness.stillMemoryOnly(sid: String) {
        val next = account.localGen + 1
        clock.advance(18 * HOUR + 1)
        foreground()
        refresh().respond(200, authBody(next, clock.now() + DAY, sid = sid))
        assertEquals(jwt(sid, next), current?.accessToken)
        assertNull(store.saved)
    }

    private fun AccountHarness.ackNow(gen: Long) {
        val p = start { ackRecoveryCode(gen) }
        http.last(ACK).respond(200)
        await(p)
    }

    @Test
    fun `恢复码 ack(2) 进行中改密拿到第 3 代：第 2 代的响应不让新 token 落盘，确认第 3 代才写`() {
        val a = setupAccount()
        a.unackedGen2()
        val stale = a.start { ackRecoveryCode(2) }
        val staleCall = a.http.last(ACK)
        val change = a.start { changePassword("Password-2", "Password-3") }
        a.http.last(PASSWORD).respond(200, a.withCode("sid3", 3))
        a.await(change)
        staleCall.respond(200)
        a.await(stale)
        assertNull(a.store.saved)
        a.stillMemoryOnly("sid3")
        a.ackNow(3)
        assertEquals(jwt("sid3", 2), a.store.saved?.accessToken)
    }

    @Test
    fun `恢复码 ack(2) 进行中重新生成拿到第 3 代：第 2 代的响应不落盘，确认第 3 代才写`() {
        val a = setupAccount()
        a.unackedGen2()
        val stale = a.start { ackRecoveryCode(2) }
        val staleCall = a.http.last(ACK)
        val regen = a.start { regenerateRecoveryCode("Password-2") }
        a.http.last(REGEN).respond(200, jsonOf("""{"recoveryCode":"CODE-3","recoveryCodeGen":3}"""))
        a.await(regen)
        staleCall.respond(200)
        a.await(stale)
        assertNull(a.store.saved)
        a.stillMemoryOnly("sid2")
        a.ackNow(3)
        assertEquals(jwt("sid2", 2), a.store.saved?.accessToken)
    }

    @Test
    fun `恢复码 ack(2) 进行中找回拿到第 3 代：第 2 代的响应不落盘，确认第 3 代才写`() {
        val a = setupAccount()
        a.unackedGen2()
        val stale = a.start { ackRecoveryCode(2) }
        val staleCall = a.http.last(ACK)
        val recover = a.start { recover(USER, "CODE-2", "Password-3") }
        a.http.last(RECOVER).respond(200, a.withCode("sid3", 3))
        a.await(recover)
        staleCall.respond(200)
        a.await(stale)
        assertNull(a.store.saved)
        a.stillMemoryOnly("sid3")
        a.ackNow(3)
        assertEquals(jwt("sid3", 2), a.store.saved?.accessToken)
    }

    @Test
    fun `恢复码 ack(2) 正在写存储时改密拿到第 3 代：写的旧值被清掉，标记保持`() {
        val a = setupAccount()
        a.unackedGen2()
        val gate = CompletableDeferred<Unit>()
        a.store.pauseBeforeSave = gate
        val stale = a.start { ackRecoveryCode(2) }
        a.http.last(ACK).respond(200)
        val change = a.start { changePassword("Password-2", "Password-3") }
        a.http.last(PASSWORD).respond(200, a.withCode("sid3", 3))
        a.store.pauseBeforeSave = null
        gate.complete(Unit)
        a.h.flush()
        a.await(stale)
        a.await(change)
        assertEquals(jwt("sid3", 1), a.current?.accessToken)
        assertNull(a.store.saved)
        a.stillMemoryOnly("sid3")
    }

    @Test
    fun `恢复码 重新生成的响应比改密晚到：代号不倒退，确认最新一代才写`() {
        val a = setupAccount()
        a.unackedGen2()
        val regen = a.start { regenerateRecoveryCode("Password-2") }
        val regenCall = a.http.last(REGEN)
        val change = a.start { changePassword("Password-2", "Password-3") }
        a.http.last(PASSWORD).respond(200, a.withCode("sid3", 4))
        a.await(change)
        regenCall.respond(200, jsonOf("""{"recoveryCode":"CODE-3","recoveryCodeGen":3}"""))
        a.await(regen)
        a.ackNow(4)
        assertEquals(jwt("sid3", 1), a.store.saved?.accessToken)
    }

    @Test
    fun `恢复码 退出其他设备不换恢复码：代号留着，确认这一代还能写`() {
        val a = setupAccount()
        a.unackedGen2()
        val others = a.start { logoutOthers("Password-2") }
        a.http.last("/v1/account/logout-others").respond(200, authBody(1, a.clock.now() + DAY, sid = "sid3"))
        a.await(others)
        assertNull(a.store.saved)
        a.ackNow(2)
        assertEquals(jwt("sid3", 1), a.store.saved?.accessToken)
    }

    @Test
    fun `恢复码 确认过之后：刷新正常落盘，重新生成不让 token 回到只放内存`() {
        val a = setupAccount()
        a.unackedGen2()
        a.ackNow(2)
        val regen = a.start { regenerateRecoveryCode("Password-2") }
        a.http.last(REGEN).respond(200, jsonOf("""{"recoveryCode":"CODE-3","recoveryCodeGen":3}"""))
        a.await(regen)
        a.ackNow(3)
        a.clock.advance(18 * HOUR + 1)
        a.foreground()
        a.refresh().respond(200, authBody(2, a.clock.now() + DAY, sid = "sid2"))
        assertEquals(jwt("sid2", 2), a.store.saved?.accessToken)
    }

    // ---- K5 ----

    @Test
    fun `K5 连接带着旧 sid 被踢：不结束会话，用新 token 重连`() {
        val a = setupAccount()
        a.session(1, DAY, "sid-new")
        // 拨号时拿到旧 token，通知比这条连接先到（两条线程的竞争）：这条连接没有被"token 换了"断开。
        var first = true
        val real = a.account.tokenProvider
        val racing = object : TokenProvider by real {
            override suspend fun accessToken(): String {
                if (first) {
                    first = false
                    return jwt("sid-old", 1)
                }
                return real.accessToken()
            }
        }
        a.im.connect(racing)
        a.h.flush()
        val c = a.transport.current
        c.open()
        a.oldSidKick(c)
        assertEquals(2, a.transport.connections.size)
        assertEquals(jwt("sid-new", 1), authToken(a.authNew()))
        assertEquals(emptyList(), a.fatals())
        assertEquals(emptyList(), a.ended)
        assertNotNull(a.current)
    }

    @Test
    fun `K5 当前 sid 的踢线照常生效`() {
        val a = setupAccount()
        a.session(1, DAY, "sid-now")
        val c = a.online()
        a.kicked(c, "passwordChanged")
        assertEquals(1, a.ended.size)
        assertNull(a.current)
        assertTrue(a.fatals().isNotEmpty())
        assertFalse(a.account.refreshInflight)
    }

    // ---- A1：logout 进行中登录（frontend-chat 问题表 A1、I6），只收尾开始时的那个会话 ----

    private val loginPath = "/v1/account/login"
    private fun AccountHarness.loggingOut(): Pair<FakeConnection, kotlinx.coroutines.Deferred<Boolean>> {
        session(1, DAY, "old")
        val c = online()
        return c to start { logout() }
    }

    private fun AccountHarness.loginAgain(sid: String = "new", code: String? = null) {
        val p = start { login(USER, "Password-1") }
        http.last(loginPath).respond(200, authBody(1, clock.now() + DAY, sid, recoveryCode = code, recoveryCodeGen = 3))
        await(p)
    }

    @Test
    fun `A1 旧 logout 的响应晚到：新会话和存储都还在，没有 ended，新 token 连接`() {
        val a = setupAccount()
        val (_, out) = a.loggingOut()
        val call = a.http.last(LOGOUT)
        a.loginAgain()
        assertEquals(jwt("new", 1), a.current?.accessToken)
        assertEquals(jwt("new", 1), a.store.saved?.accessToken)
        call.respond(200)
        assertTrue(a.await(out))
        assertEquals(jwt("new", 1), a.current?.accessToken)
        assertEquals(jwt("new", 1), a.store.saved?.accessToken)
        assertEquals(emptyList(), a.ended)
        assertEquals(emptyList(), a.fatals())
        assertEquals(jwt("new", 1), authToken(a.authNew()))
        assertEquals(emptyList(), a.pendingStore.items, "旧 logout 确认了，待退出清掉")
    }

    @Test
    fun `A1 旧 logout 失败：不碰新会话，待退出还是旧 sid，补发不带新 token`() {
        val a = setupAccount()
        val (_, out) = a.loggingOut()
        val call = a.http.last(LOGOUT)
        a.loginAgain()
        call.fail()
        assertFalse(a.await(out))
        assertEquals(jwt("new", 1), a.current?.accessToken)
        assertEquals(jwt("new", 1), a.store.saved?.accessToken)
        assertEquals(emptyList(), a.ended)
        assertEquals(listOf(PendingLogout("old", refreshToken("old", 1))), a.pendingStore.items)
        assertEquals(emptyList(), a.revokes, "系统令牌这时已经是新会话在用")
        val retry = a.http.last(LOGOUT)
        assertFalse(retry === call, "有新会话时立刻补发旧 sid：系统令牌没撤销，要尽快解绑")
        assertNull(retry.bearer)
        assertEquals(refreshToken("old", 1), retry.refreshField)
        retry.respond(200)
        a.h.flush()
        assertEquals(emptyList(), a.pendingStore.items)
        assertEquals(jwt("new", 1), a.current?.accessToken)
    }

    @Test
    fun `A1 服务端拒绝旧 logout：记录删除，新会话不动`() {
        val a = setupAccount()
        val (_, out) = a.loggingOut()
        val call = a.http.last(LOGOUT)
        a.loginAgain()
        call.respondError(401, 4019)
        assertFalse(a.await(out))
        assertEquals(emptyList(), a.pendingStore.items)
        assertEquals(jwt("new", 1), a.current?.accessToken)
    }

    @Test
    fun `A1 待退出还在写时登录成功：logout 接下来不清新 token`() {
        val a = setupAccount()
        a.session(1, DAY, "old")
        a.online()
        val gate = CompletableDeferred<Unit>()
        a.pendingStore.pauseSave = gate
        val out = a.start { logout() }
        assertEquals(0, a.http.to(LOGOUT).size, "待退出写完才发请求")
        a.pendingStore.pauseSave = null
        val login = a.start { login(USER, "Password-1") }
        a.http.last(loginPath).respond(200, authBody(1, a.clock.now() + DAY, "new")) // 登录的保存排在存储锁上，在 logout 的清除前面
        val clears = a.store.clears
        gate.complete(Unit)
        a.await(login)
        assertEquals(clears, a.store.clears, "logout 不再清存储")
        assertEquals(jwt("new", 1), a.store.saved?.accessToken)
        a.http.last(LOGOUT).respond(200)
        a.await(out)
        assertEquals(jwt("new", 1), a.current?.accessToken)
        assertEquals(jwt("new", 1), a.store.saved?.accessToken)
        assertEquals(emptyList(), a.ended)
    }

    @Test
    fun `A1 新会话只放内存：旧 logout 不写盘，确认才落盘；恢复码没确认的旧 token 失败时才进待退出`() {
        val a = setupAccount()
        val (_, out) = a.loggingOut()
        val call = a.http.last(LOGOUT)
        a.loginAgain(code = "RC")
        call.respond(200)
        a.await(out)
        assertEquals(jwt("new", 1), a.current?.accessToken)
        assertNull(a.store.saved)
        val ack = a.start { ackRecoveryCode(3) }
        a.http.last(ACK).respond(200)
        a.await(ack)
        assertEquals(jwt("new", 1), a.store.saved?.accessToken)
    }

    @Test
    fun `A1 logout 进行中登录失败：旧会话照常结束`() {
        val a = setupAccount()
        val (_, out) = a.loggingOut()
        val p = a.start { login(USER, "wrong-password") }
        a.http.last(loginPath).respondError(401, 4018)
        assertEquals(4018, a.failure<AccountError>(p).code)
        a.http.last(LOGOUT).respond(200)
        a.await(out)
        assertNull(a.current)
        assertNull(a.store.saved)
        assertEquals(listOf(SessionEnd(SessionEndAction.LOGOUT)), a.ended)
    }

    @Test
    fun `A1 登录先发、logout 后发：响应不采用，退出优先`() {
        val a = setupAccount()
        a.session(1, DAY, "old")
        a.online()
        val p = a.start { login(USER, "Password-1") }
        val out = a.start { logout() }
        a.http.last(loginPath).respond(200, authBody(1, a.clock.now() + DAY, "new"))
        a.failure<SessionEndedError>(p)
        a.http.last(LOGOUT).respond(200)
        a.await(out)
        assertNull(a.current)
        assertNull(a.store.saved)
        assertEquals(listOf(SessionEnd(SessionEndAction.LOGOUT)), a.ended)
    }

    @Test
    fun `A1 两次 logout 各管各的：新会话的 logout 结束新会话，旧的晚到不再有 ended`() {
        val a = setupAccount()
        val (_, out1) = a.loggingOut()
        val first = a.http.last(LOGOUT)
        a.loginAgain()
        val out2 = a.start { logout() }
        val second = a.http.last(LOGOUT)
        assertFalse(second === first)
        assertEquals(refreshToken("new", 1), second.refreshField)
        second.respond(200)
        a.await(out2)
        assertNull(a.current)
        assertEquals(listOf(SessionEnd(SessionEndAction.LOGOUT)), a.ended)
        first.respond(200)
        a.await(out1)
        assertEquals(listOf(SessionEnd(SessionEndAction.LOGOUT)), a.ended)
        assertEquals(emptyList(), a.pendingStore.items)
    }

    @Test
    fun `A1 两个 logout 都没回时第三次登录：第三个会话不受影响`() {
        val a = setupAccount()
        val (_, out1) = a.loggingOut()
        val first = a.http.last(LOGOUT)
        a.loginAgain()
        val out2 = a.start { logout() }
        val second = a.http.last(LOGOUT)
        a.loginAgain("third")
        first.respond(200)
        second.respond(200)
        a.await(out1)
        a.await(out2)
        assertEquals(jwt("third", 1), a.current?.accessToken)
        assertEquals(jwt("third", 1), a.store.saved?.accessToken)
        assertEquals(emptyList(), a.ended)
    }

    @Test
    fun `A1 旧连接的踢线在等 logout 时缓存，新会话建立后作废；新会话自己的踢线立即生效`() {
        val a = setupAccount()
        val (c, out) = a.loggingOut()
        val call = a.http.last(LOGOUT)
        a.kicked(c, "sessionRevoked")
        a.loginAgain()
        assertEquals(jwt("new", 1), a.current?.accessToken)
        assertEquals(emptyList(), a.fatals())
        val c2 = a.authNew()
        a.kicked(c2, "passwordChanged")
        assertNull(a.current)
        assertEquals(listOf("passwordChanged"), a.ended.map { it.reason })
        call.respond(200)
        a.await(out)
        assertEquals(1, a.ended.size)
    }

    @Test
    fun `A1 演示会话 restore 也算新会话：旧 logout 不结束它`() {
        val a = setupAccount()
        val (_, out) = a.loggingOut()
        val call = a.http.last(LOGOUT)
        a.call { restore(AccountSession(mode = SessionMode.DEMO, userId = USER, accessToken = "demo-token")) }
        call.respond(200)
        a.await(out)
        assertEquals("demo-token", a.current?.accessToken)
        assertEquals(emptyList(), a.ended)
    }
}
