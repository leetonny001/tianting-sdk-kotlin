// FE-611：SDK 对真实 im-server（测试模式，SQLite，随机端口）。真实的 OkHttp、WebSocket、协程线程和时钟。对照 sdk-ts 的 test/integration/account.test.ts。
// 重叠窗口配成 61 秒（配置下限是 60 秒）：superseded 到重放这一段真的要等一个窗口。
package com.tianting.sdk.integration

import com.tianting.sdk.AccountClient
import com.tianting.sdk.AccountClientOptions
import com.tianting.sdk.AccountError
import com.tianting.sdk.AccountEvent
import com.tianting.sdk.AccountSession
import com.tianting.sdk.ConnState
import com.tianting.sdk.FatalReason
import com.tianting.sdk.HttpCall
import com.tianting.sdk.HttpCallback
import com.tianting.sdk.HttpRequest
import com.tianting.sdk.HttpTransport
import com.tianting.sdk.IMClient
import com.tianting.sdk.IMClientOptions
import com.tianting.sdk.IMEvent
import com.tianting.sdk.Logger
import com.tianting.sdk.OkHttpHttpTransport
import com.tianting.sdk.PendingLogout
import com.tianting.sdk.PendingLogoutStore
import com.tianting.sdk.Platform
import com.tianting.sdk.SessionDialog
import com.tianting.sdk.SessionEnd
import com.tianting.sdk.SessionEndAction
import com.tianting.sdk.SessionStore
import com.tianting.sdk.lineageOf
import com.tianting.sdk.obj
import com.tianting.sdk.sessionDialogText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.net.http.HttpRequest as JdkRequest
import java.net.http.HttpResponse as JdkResponse

private const val PASSWORD = "Tt-good-pass-2026!"
private const val NEW_PASSWORD = "Tt-new-pass-2026!"

private class Store : SessionStore {
    @Volatile
    var saved: AccountSession? = null
    override suspend fun load(): AccountSession? = saved
    override suspend fun save(session: AccountSession) {
        saved = session
    }

    override suspend fun clear() {
        saved = null
    }
}

private class Pending : PendingLogoutStore {
    @Volatile
    var items: List<PendingLogout> = emptyList()
    override suspend fun load(): List<PendingLogout> = items
    override suspend fun save(items: List<PendingLogout>) {
        this.items = items
    }
}

/** 可以"拔网线"的 HTTP：断开时所有请求立即失败。 */
private class GatedHttp(private val inner: HttpTransport = OkHttpHttpTransport()) : HttpTransport {
    val online = AtomicBoolean(true)
    override fun request(req: HttpRequest, callback: HttpCallback): HttpCall {
        if (!online.get()) {
            callback.onFailure()
            return HttpCall { }
        }
        return inner.request(req, callback)
    }
}

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountIntegrationTest {
    private lateinit var srv: TestServer
    private val base get() = srv.wsURL.removePrefix("ws://").removeSuffix("/ws").let { "http://$it" }
    private val made = ArrayList<IMClient>()
    private val accounts = ArrayList<AccountClient>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val raw = HttpClient.newHttpClient()
    private var serial = 0

    @BeforeAll
    fun up() = runBlocking {
        srv = TestServer.start(mapOf("IM_REGISTER_MODE" to "invite", "IM_REFRESH_OVERLAP" to "61s", "IM_TEST_DISABLE_AUTH_LOCK" to "1"))
    }

    @AfterAll
    fun down() = runBlocking {
        for (im in made) runCatching { im.close() }
        for (a in accounts) a.close()
        srv.stop()
    }

    private inner class Device(platform: Platform = Platform.ANDROID, val store: Store = Store(), val pending: Pending = Pending(), val http: GatedHttp = GatedHttp()) {
        val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
        val ended: MutableList<SessionEnd> = Collections.synchronizedList(ArrayList())
        val fatal: MutableList<FatalReason> = Collections.synchronizedList(ArrayList())
        val banner: MutableList<Boolean> = Collections.synchronizedList(ArrayList())
        val events: MutableList<IMEvent> = Collections.synchronizedList(ArrayList())
        private val logger = object : Logger {
            override fun debug(message: String) { logs.add(message) }
            override fun warn(message: String) { logs.add(message) }
            override fun error(message: String) { logs.add(message) }
        }
        val account = AccountClient(
            AccountClientOptions(baseUrl = base, platform = platform, store = store, pendingLogoutStore = pending, http = http, logger = logger),
        ).also { accounts.add(it) }
        val im = IMClient(IMClientOptions(url = srv.wsURL, platform = platform, logger = logger)).also { made.add(it) }

        init {
            account.eventTap = { if (it is AccountEvent.Ended) ended.add(it.end) }
            im.eventTap = { e ->
                events.add(e)
                if (e is IMEvent.Fatal) fatal.add(e.reason)
            }
            scope.launch { im.banner.collect { banner.add(it) } }
        }

        val session get() = account.session.value

        suspend fun connect() {
            im.connect(account.tokenProvider)
            until("connected") { im.state.value == ConnState.CONNECTED }
        }
    }

    private suspend fun until(what: String, timeoutMs: Long = 8_000, check: () -> Boolean) {
        try {
            withTimeout(timeoutMs) {
                while (!check()) delay(20)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private fun invite(maxUses: Int): Pair<String, String> {
        val r = srv.signedInvite(maxUses)
        val inv = r["invites"]!!.jsonArray[0].jsonObject
        return inv["code"]!!.jsonPrimitive.content to inv["inviteId"]!!.jsonPrimitive.content
    }

    /** 注册并确认恢复码。返回用户名和恢复码。 */
    private suspend fun newUser(d: Device): Pair<String, String> {
        serial += 1
        val username = "ktuser_${System.currentTimeMillis().toString(36)}$serial"
        val (code) = invite(1)
        val r = d.account.register(username, PASSWORD, inviteCode = code)
        assertNotNull(r.recoveryCode)
        assertNull(d.store.saved) // 确认前不落盘
        d.account.ackRecoveryCode(r.recoveryCodeGen!!)
        assertEquals("account", d.store.saved?.mode?.wire)
        return username to r.recoveryCode!!
    }

    private fun rawPost(path: String, body: String, bearer: String? = null): JdkResponse<String> {
        val b = JdkRequest.newBuilder(URI("$base$path")).header("Content-Type", "application/json").POST(JdkRequest.BodyPublishers.ofString(body))
        bearer?.let { b.header("Authorization", "Bearer $it") }
        return raw.send(b.build(), JdkResponse.BodyHandlers.ofString())
    }

    private fun rawEvents(bearer: String): JdkResponse<String> =
        raw.send(JdkRequest.newBuilder(URI("$base/v1/account/events")).header("Authorization", "Bearer $bearer").build(), JdkResponse.BodyHandlers.ofString())

    private fun JdkResponse<String>.field(k: String): String? = Json.parseToJsonElement(body()).jsonObject[k]?.jsonPrimitive?.content

    // ---- 注册、登录、配置 ----

    @Test
    fun `TC-ACC-09-01 getConfig；邀请码 maxUses=2：前两个成功，第三个 403，后台已用 2 次`() = runBlocking {
        val d = Device()
        val cfg = d.account.getConfig()
        assertEquals("invite", cfg.registerMode)
        assertTrue(cfg.limits.passwordMinLen > 0)
        val (code, id) = invite(2)
        val stamp = System.currentTimeMillis().toString(36)
        for (n in listOf(1, 2)) assertNotNull(Device().account.register("kinv_$stamp$n", PASSWORD, inviteCode = code).recoveryCode)
        val third = assertFailsWith<AccountError> { d.account.register("kinv_${stamp}3", PASSWORD, inviteCode = code) }
        assertEquals(403, third.status)
        assertEquals(4030, third.code)
        assertEquals("inviteInvalid", third.reason)
        assertNull(d.session)
        val row = srv.signedListInvites()["invites"]!!.jsonArray.map { it.jsonObject }.first { it["inviteId"]!!.jsonPrimitive.content == id }
        assertEquals("2", row["usedCount"]!!.jsonPrimitive.content)
    }

    @Test
    fun `TC-ACC-05-14 密码错误 4018；用户名被占 4095；recoveryCodeStale`() = runBlocking {
        val d = Device()
        val (username) = newUser(d)
        val bad = assertFailsWith<AccountError> { Device().account.login(username, "wrong-password-1") }
        assertEquals(401 to 4018, bad.status to bad.code)
        val (code) = invite(1)
        val taken = assertFailsWith<AccountError> { Device().account.register(username, PASSWORD, inviteCode = code) }
        assertEquals(409 to 4095, taken.status to taken.code)

        val d1 = Device()
        d1.account.login(username, PASSWORD)
        val first = d1.account.regenerateRecoveryCode(PASSWORD)
        val again = d1.account.regenerateRecoveryCode(PASSWORD)
        assertTrue(again.recoveryCodeGen > first.recoveryCodeGen)
        val stale = assertFailsWith<AccountError> { d1.account.ackRecoveryCode(first.recoveryCodeGen) }
        assertEquals("recoveryCodeStale", stale.reason)
        assertEquals(SessionDialog.RECOVERY_CODE_STALE, d1.ended.single().dialog)
        assertNull(d1.session)
    }

    @Test
    fun `TC-ACC-02-13 新设备登录：在线设备收到 securityNotice，账号安全有记录`() = runBlocking {
        val d1 = Device()
        val (username) = newUser(d1)
        d1.connect()
        val started = System.currentTimeMillis()
        Device(Platform.IOS).account.login(username, PASSWORD)
        until("securityNotice", 2_500) { d1.events.any { it is IMEvent.SecurityNotice } }
        assertTrue(System.currentTimeMillis() - started < 2_500)
        val n = d1.events.filterIsInstance<IMEvent.SecurityNotice>().first()
        assertEquals("newDeviceLogin" to "ios", n.kind to n.platform)
        val events = d1.account.listEvents()
        assertTrue(events.otherSessions.count >= 1)
        assertTrue(events.events.isNotEmpty())
    }

    // ---- 刷新 ----

    @Test
    fun `TC-ACC-03-10 刷新后 1000 重连，用新一代，消息照常收发，不提示`() = runBlocking {
        val d = Device()
        val (username) = newUser(d)
        val peer = Device()
        val (peerName) = newUser(peer)
        peer.connect()
        d.connect()
        val before = lineageOf(d.session!!.accessToken)
        d.account.refresh()
        val after = lineageOf(d.session!!.accessToken)
        assertEquals(before.sid, after.sid)
        assertTrue(after.gen > before.gen)
        assertEquals(d.session?.accessToken, d.store.saved?.accessToken)
        delay(50)
        until("reconnected") { d.im.state.value == ConnState.CONNECTED }
        delay(1_200)
        assertEquals(emptyList(), d.banner.filter { it })
        assertEquals(emptyList(), d.fatal)

        // 真实服务端上 HTTP 也接受新一代（转正之后），WS 收发正常。
        d.account.listEvents()
        val conv = com.tianting.sdk.c2cConvId(username, peerName)
        d.im.send(conv, "after rotate")
        until("peer receives") { peer.im.messages(conv).any { it.body.text == "after rotate" } }
        d.account.refresh()
        delay(50)
        until("reconnected again") { d.im.state.value == ConnState.CONNECTED }
        d.account.listEvents()
        assertEquals(emptyList(), d.ended)
    }

    @Test
    fun `规则 4、6、7 攻击者转正：在线受害者收到 4011，刷新得到 superseded，窗口后 replayDetected`() = runBlocking {
        val victim = Device()
        newUser(victim)
        victim.connect()
        val stolen = victim.session!!.refreshToken!!
        // 攻击者用偷来的 refresh 刷新，并用新 access 转正（标准 HTTP，不经过 SDK）。
        val res = rawPost("/v1/account/token/refresh", """{"refreshToken":"$stolen"}""")
        assertEquals(200, res.statusCode())
        assertEquals(200, rawEvents(res.field("accessToken")!!).statusCode())
        until("victim ended", 75_000) { victim.ended.isNotEmpty() }
        val e = victim.ended.single()
        assertEquals(SessionEnd(SessionEndAction.LOGIN, SessionDialog.CREDENTIAL_ANOMALY, e.recoverBefore, "replayDetected", 4019), e)
        assertTrue(sessionDialogText(e.dialog)!!.contains("检测到登录凭证异常"))
        assertTrue(victim.fatal.single() is FatalReason.Session)
        assertTrue(victim.logs.any { it.contains("superseded") })
        assertNull(victim.store.saved)
        until("stopped") { victim.im.state.value == ConnState.DISCONNECTED }
    }

    @Test
    fun `TC-ACC-03-44 access 被替换后 HTTP 收到 4011 tokenSuperseded：用本地新值重试`() = runBlocking {
        val d = Device()
        newUser(d)
        val old = d.session!!.accessToken
        d.account.refresh()
        d.account.listEvents() // 新一代转正，旧 access 从此被拒绝
        val r = rawEvents(old)
        assertEquals(401, r.statusCode())
        assertEquals("4011" to "tokenSuperseded", r.field("code") to r.field("reason"))
        assertEquals(com.tianting.sdk.SessionOutcome.UseLocal, d.account.http4011(lineageOf(old).let { com.tianting.sdk.RejectedToken(it.sid, it.gen) }))
    }

    @Test
    fun `TC-ACC-03-07 access 已过期的冷启动：先刷新再 auth（用已保存的过期时间模拟本地过期）`() = runBlocking {
        val d = Device()
        newUser(d)
        val saved = d.session!!
        val d2 = Device()
        d2.store.saved = saved.copy(accessExpiresAt = System.currentTimeMillis() - 1_000)
        d2.account.start()
        d2.connect()
        assertTrue(lineageOf(d2.session!!.accessToken).gen > lineageOf(saved.accessToken).gen)
        assertEquals(emptyList(), d2.fatal)
    }

    @Test
    fun `TC-ACC-03-13 新 token 落盘后、使用前杀 App：重启用新 token，不被吊销`() = runBlocking {
        val d = Device()
        newUser(d)
        val saved = d.session!!
        // 刷新成功并落盘，旧实例还没用就没了：只保留存储里的值。
        val res = rawPost("/v1/account/token/refresh", """{"refreshToken":"${saved.refreshToken}"}""")
        assertEquals(200, res.statusCode())
        val store = Store().apply { this.saved = AccountSession.fromJson(res.body())!!.copy(mode = com.tianting.sdk.SessionMode.ACCOUNT) }
        val again = Device(store = store)
        again.account.start()
        again.connect()
        assertEquals(res.field("accessToken"), again.session?.accessToken)
        assertEquals(200, rawEvents(again.session!!.accessToken).statusCode())
        assertEquals(emptyList(), again.fatal)
        assertEquals(emptyList(), again.ended)
    }

    @Test
    fun `TC-ACC-03-20 刷新请求发出后杀 App：用旧 refresh 重试成功（服务端时钟没有推进，时间部分在 TC-ACC-03-19）`() = runBlocking {
        val d = Device()
        newUser(d)
        val saved = d.session!!
        assertEquals(200, rawPost("/v1/account/token/refresh", """{"refreshToken":"${saved.refreshToken}"}""").statusCode()) // 响应丢了
        val again = Device(store = Store().apply { this.saved = saved })
        again.account.start()
        again.account.refresh()
        assertTrue(lineageOf(again.session!!.accessToken).gen > lineageOf(saved.accessToken).gen)
        again.connect()
        assertEquals(emptyList(), again.ended)
        assertEquals(emptyList(), again.fatal)
    }

    // ---- 改密、退出其他设备、找回、退出、注销 ----

    @Test
    fun `TC-ACC-04-02、03-34 改密：当前设备换 sid 重连不提示；其他设备 passwordChanged 带日期；旧 refresh credentialsChanged`() = runBlocking {
        val d1 = Device()
        val (username) = newUser(d1)
        val d2 = Device(Platform.IOS)
        d2.account.login(username, PASSWORD)
        d1.connect()
        d2.connect()
        val oldRefresh = d1.session!!.refreshToken!!
        val oldSid = lineageOf(d1.session!!.accessToken).sid

        assertEquals(4018, assertFailsWith<AccountError> { d1.account.changePassword("wrong-password-1", NEW_PASSWORD) }.code)
        assertEquals(emptyList(), d1.fatal)

        val r = d1.account.changePassword(PASSWORD, NEW_PASSWORD)
        assertNotNull(r.recoveryCode)
        delay(50)
        until("d1 reconnected", 1_000) { d1.im.state.value == ConnState.CONNECTED }
        assertNotEquals(oldSid, lineageOf(d1.session!!.accessToken).sid)
        assertNull(d1.store.saved) // 确认恢复码之前不落盘
        delay(1_200)
        assertEquals(emptyList(), d1.fatal)
        assertEquals(emptyList(), d1.ended)
        assertEquals(emptyList(), d1.banner.filter { it })

        until("d2 kicked") { d2.fatal.isNotEmpty() }
        val f = d2.fatal.single() as FatalReason.Kicked
        assertEquals("passwordChanged" to 4012, f.reason to f.code)
        assertTrue(f.recoverBefore!! > System.currentTimeMillis())
        assertEquals(SessionDialog.RECOVER_BEFORE, d2.ended.single().dialog)
        assertEquals(f.recoverBefore, d2.ended.single().recoverBefore)
        assertNull(d2.session)

        // 响应丢失的那台设备：用旧 refresh 刷新，不算重放（TC-ACC-04-15）。
        val lost = Device()
        lost.account.restore(d1.session!!.copy(refreshToken = oldRefresh))
        assertFailsWith<Exception> { lost.account.refresh() }
        val e = lost.ended.single()
        assertEquals(SessionDialog.RECOVER_BEFORE to "credentialsChanged", e.dialog to e.reason)
        assertEquals(4019, e.code)

        d1.account.ackRecoveryCode(r.recoveryCodeGen!!)
        assertEquals(d1.session?.accessToken, d1.store.saved?.accessToken)
        assertEquals(username, Device().account.login(username, NEW_PASSWORD).userId)
    }

    @Test
    fun `TC-ACC-03-34 离线设备 access 未过期，别的设备改密后上线：kicked passwordChanged 带日期，不重连`() = runBlocking {
        val d1 = Device()
        val (username) = newUser(d1)
        val offline = Device(Platform.IOS)
        offline.account.login(username, PASSWORD)
        d1.account.changePassword(PASSWORD, NEW_PASSWORD)
        offline.im.connect(offline.account.tokenProvider)
        until("offline device kicked") { offline.fatal.isNotEmpty() }
        val f = offline.fatal.single() as FatalReason.Kicked
        assertEquals("passwordChanged" to 4012, f.reason to f.code)
        assertTrue(f.recoverBefore!! > System.currentTimeMillis())
        assertEquals(SessionDialog.RECOVER_BEFORE, offline.ended.single().dialog)
        delay(1_200)
        assertEquals(ConnState.DISCONNECTED, offline.im.state.value)
    }

    @Test
    fun `TC-ACC-04-09 退出其他设备：密码错 4018；正确时 D2 sessionRevoked，D1 换 sid 重连`() = runBlocking {
        val d1 = Device()
        val (username) = newUser(d1)
        val d2 = Device(Platform.IOS)
        d2.account.login(username, PASSWORD)
        d1.connect()
        d2.connect()
        val sid = lineageOf(d1.session!!.accessToken).sid
        assertEquals(4018, assertFailsWith<AccountError> { d1.account.logoutOthers("wrong-password-1") }.code)
        assertEquals(emptyList(), d2.fatal)
        d1.account.logoutOthers(PASSWORD)
        delay(50)
        until("d1 reconnected", 1_000) { d1.im.state.value == ConnState.CONNECTED }
        assertNotEquals(sid, lineageOf(d1.session!!.accessToken).sid)
        assertEquals(d1.session?.accessToken, d1.store.saved?.accessToken)
        until("d2 kicked") { d2.fatal.isNotEmpty() }
        val f = d2.fatal.single() as FatalReason.Kicked
        assertEquals("sessionRevoked" to 4012, f.reason to f.code)
        delay(1_200)
        assertEquals(emptyList(), d1.fatal)
        assertEquals(emptyList(), d1.banner.filter { it })
        d1.account.listEvents()
        Unit
    }

    @Test
    fun `TC-ACC-05-05 找回：新密码可用，旧设备被踢，新恢复码`() = runBlocking {
        val d1 = Device()
        val (username, code) = newUser(d1)
        d1.connect()
        val lost = Device(Platform.IOS)
        val r = lost.account.recover(username, code, NEW_PASSWORD)
        assertNotNull(r.recoveryCode)
        assertNotEquals(code, r.recoveryCode)
        until("old device kicked") { d1.fatal.isNotEmpty() }
        assertEquals("passwordChanged", (d1.fatal.single() as FatalReason.Kicked).reason)
        lost.account.ackRecoveryCode(r.recoveryCodeGen!!)
        assertEquals(username, Device().account.login(username, NEW_PASSWORD).userId)
        assertEquals(4018, assertFailsWith<AccountError> { Device().account.login(username, PASSWORD) }.code)
        assertEquals(4018, assertFailsWith<AccountError> { Device().account.recover(username, code, PASSWORD) }.code)
    }

    @Test
    fun `TC-ACC-06-05 改昵称：本端立即显示，对方查询拿到新昵称`() = runBlocking {
        val d = Device()
        val (username) = newUser(d)
        val peer = Device()
        val (peerName) = newUser(peer)
        srv.admin("POST", "/admin/groups", obj("groupId" to "g${System.currentTimeMillis().toString(36)}", "name" to "测试群", "members" to listOf(username, peerName)))
        peer.connect()
        d.connect()
        d.account.updateProfile(nickname = "小明")
        until("displayName") { runBlocking { d.im.displayName(username) } == "小明" }
        assertEquals("小明", peer.im.getUsers(listOf(username)).single().nickname)
    }

    @Test
    fun `TC-ACC-04-01 退出：refresh 失效，连接 1000 断开，不当成被踢（SDK 部分）`() = runBlocking {
        val d = Device()
        newUser(d)
        d.connect()
        val refresh = d.session!!.refreshToken!!
        assertTrue(d.account.logout())
        until("disconnected") { d.im.state.value == ConnState.DISCONNECTED }
        assertEquals(emptyList(), d.fatal)
        assertEquals(listOf(SessionEnd(SessionEndAction.LOGOUT)), d.ended)
        assertNull(d.store.saved)
        assertEquals(emptyList(), d.pending.items)
        val again = rawPost("/v1/account/token/refresh", """{"refreshToken":"$refresh"}""")
        assertEquals(401, again.statusCode())
        assertEquals("4019" to "revoked", again.field("code") to again.field("reason"))
    }

    @Test
    fun `TC-ACC-04-06 断网退出：本地立即退出，只留待退出；联网后补发，sid 被吊销，记录清除（AC-ACC-04-6 的 SDK 部分）`() = runBlocking {
        val d = Device()
        newUser(d)
        val refresh = d.session!!.refreshToken!!
        val sid = lineageOf(d.session!!.accessToken).sid
        d.http.online.set(false)
        assertFalse(d.account.logout())
        assertNull(d.session)
        assertNull(d.store.saved)
        assertEquals(listOf(PendingLogout(sid, refresh)), d.pending.items)
        // 服务端这时还不知道：refresh 还有效。这里不去刷新它，下面看补发的效果。
        d.http.online.set(true)
        d.account.networkOnline()
        until("pending flushed") { d.pending.items.isEmpty() }
        val again = rawPost("/v1/account/token/refresh", """{"refreshToken":"$refresh"}""")
        assertEquals(401, again.statusCode())
        assertEquals("4019" to "revoked", again.field("code") to again.field("reason"))
    }

    @Test
    fun `TC-ACC-04-14 待退出 access 已过期也能补发（只带 refresh），任何一代都按退出处理`() = runBlocking {
        val d = Device()
        newUser(d)
        val oldRefresh = d.session!!.refreshToken!!
        d.account.refresh() // oldRefresh 已经不是最新的
        val sid = lineageOf(d.session!!.accessToken).sid
        val store = Pending().apply { items = listOf(PendingLogout(sid, oldRefresh)) }
        val other = Device(pending = store)
        other.account.start()
        until("pending flushed") { store.items.isEmpty() }
        until("revoked") { d.session == null || rawEvents(d.session!!.accessToken).statusCode() == 401 }
    }

    @Test
    fun `F-ACC-08 注销：冷静期登录 403 带 deleteAt，撤销后能登录`() = runBlocking {
        val d = Device()
        val (username) = newUser(d)
        d.connect()
        assertEquals(4018, assertFailsWith<AccountError> { d.account.requestDelete("wrong-password-1") }.code)
        assertNotNull(d.session)
        d.account.requestDelete(PASSWORD)
        assertNull(d.session)
        until("disconnected") { d.im.state.value == ConnState.DISCONNECTED }
        until("fatal") { d.fatal.size == 1 }
        val pending = assertFailsWith<AccountError> { Device().account.login(username, PASSWORD) }
        assertEquals(Triple(403, 4030, "accountDeletionPending"), Triple(pending.status, pending.code, pending.reason))
        Device().account.cancelDelete(username, PASSWORD)
        assertEquals(username, Device().account.login(username, PASSWORD).userId)
    }

    @Test
    fun `第 13-1 节 跑完流程，SDK 日志里没有 token、refresh、恢复码、URL 查询串`() = runBlocking {
        val d = Device()
        val (_, code) = newUser(d)
        d.connect()
        val access = d.session!!.accessToken
        val refresh = d.session!!.refreshToken!!
        d.account.refresh()
        assertEquals(4018, assertFailsWith<AccountError> { d.account.changePassword("wrong-password-1", NEW_PASSWORD) }.code)
        d.account.changePassword(PASSWORD, NEW_PASSWORD)
        assertTrue(d.logs.isNotEmpty())
        val logs = d.logs.toList()
        for (l in logs) {
            for (secret in listOf(access, refresh, code, PASSWORD, NEW_PASSWORD)) assertFalse(l.contains(secret), l)
            assertFalse(l.contains("eyJ"), l)
            assertFalse(l.contains("?"), l)
        }
    }
}
