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
}
