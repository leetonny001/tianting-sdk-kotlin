// FE-620：securityNotice 到达 sdk-kotlin，真实 im-server（测试模式，SQLite，随机端口）。对照 sdk-ts 的 test/integration/security-notice.test.ts。
// 单独一个服务端：要调服务端时钟、信任 X-Forwarded-For，别的类用真实时钟。F-ACC-02 规则 3b、F-ACC-03 规则 4、F-ACC-05 规则 5；问题表 A13、A14。
package com.tianting.sdk.integration

import com.tianting.sdk.AccountClient
import com.tianting.sdk.AccountClientOptions
import com.tianting.sdk.AccountSession
import com.tianting.sdk.ConnState
import com.tianting.sdk.FatalReason
import com.tianting.sdk.IMClient
import com.tianting.sdk.IMClientOptions
import com.tianting.sdk.IMEvent
import com.tianting.sdk.Platform
import com.tianting.sdk.SessionStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.util.Collections
import kotlin.test.assertEquals
import java.net.http.HttpRequest as JdkRequest
import java.net.http.HttpResponse as JdkResponse

private const val PASSWORD = "Tt-good-pass-2026!"
private const val NEW_PASSWORD = "Tt-new-pass-2026!"
private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

private class MemoryStore : SessionStore {
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

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityNoticeIntegrationTest {
    private lateinit var srv: TestServer
    private val base get() = srv.wsURL.removePrefix("ws://").removeSuffix("/ws").let { "http://$it" }
    private val made = ArrayList<IMClient>()
    private val accounts = ArrayList<AccountClient>()
    private val raw = HttpClient.newHttpClient()
    private var serial = 0

    @BeforeAll
    fun up() = runBlocking {
        srv = TestServer.start(mapOf("IM_REGISTER_MODE" to "invite", "IM_TRUSTED_PROXIES" to "127.0.0.1"))
    }

    @AfterEach
    fun realClock() {
        srv.admin("POST", "/admin/test/clock", buildJsonObject { put("nowMs", JsonNull) })
    }

    @AfterAll
    fun down() = runBlocking {
        for (im in made) runCatching { im.close() }
        for (a in accounts) a.close()
        srv.stop()
    }

    /** 收到的 securityNotice 和踢线按到达顺序记在 log：kind 或 kicked/原因。 */
    private inner class Device(platform: Platform) {
        val log: MutableList<String> = Collections.synchronizedList(ArrayList())
        val platforms: MutableMap<String, String?> = Collections.synchronizedMap(HashMap())
        val account = AccountClient(AccountClientOptions(baseUrl = base, platform = platform, store = MemoryStore())).also { accounts.add(it) }
        val im = IMClient(IMClientOptions(url = srv.wsURL, platform = platform)).also { made.add(it) }

        init {
            im.eventTap = { e ->
                when (e) {
                    is IMEvent.SecurityNotice -> {
                        log.add(e.kind)
                        platforms[e.kind] = e.platform
                    }
                    is IMEvent.Fatal -> log.add((e.reason as? FatalReason.Kicked)?.let { "kicked/${it.reason}" } ?: e.reason.toString())
                    else -> Unit
                }
            }
        }

        suspend fun connect() {
            im.connect(account.tokenProvider)
            until("connected") { im.state.value == ConnState.CONNECTED }
        }
    }

    private suspend fun until(what: String, timeoutMs: Long = 5_000, check: () -> Boolean) {
        try {
            withTimeout(timeoutMs) {
                while (!check()) delay(20)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private class Pair2(val username: String, val recoveryCode: String, val d1: Device, val d2: Device)

    /** 新账号：d1（web）注册并确认恢复码，d2（ios）登录，都不连接。 */
    private suspend fun pair(): Pair2 {
        serial += 1
        val username = "notice_${System.currentTimeMillis().toString(36)}$serial"
        val code = srv.signedInvite(1)["invites"]!!.jsonArray[0].jsonObject["code"]!!.jsonPrimitive.content
        val d1 = Device(Platform.WEB)
        val r = d1.account.register(username, PASSWORD, inviteCode = code)
        d1.account.ackRecoveryCode(r.recoveryCodeGen!!)
        val d2 = Device(Platform.IOS)
        d2.account.login(username, PASSWORD)
        return Pair2(username, r.recoveryCode!!, d1, d2)
    }

    /** 不经过 SDK 的刷新：持有偷来 refresh 的人。 */
    private fun rawRefresh(token: String, ip: String? = null): JdkResponse<String> {
        val b = JdkRequest.newBuilder(URI("$base/v1/account/token/refresh"))
            .header("Content-Type", "application/json")
            .POST(JdkRequest.BodyPublishers.ofString(buildJsonObject { put("refreshToken", token) }.toString()))
        ip?.let { b.header("X-Forwarded-For", it) }
        return raw.send(b.build(), JdkResponse.BodyHandlers.ofString())
    }

    private fun rawEvents(bearer: String): JdkResponse<String> =
        raw.send(JdkRequest.newBuilder(URI("$base/v1/account/events")).header("Authorization", "Bearer $bearer").build(), JdkResponse.BodyHandlers.ofString())

    private fun setClock(nowMs: Long) {
        srv.admin("POST", "/admin/test/clock", buildJsonObject { put("nowMs", nowMs) })
    }

    private suspend fun quiet() = delay(200)

    private fun Device.refreshToken() = account.session.value!!.refreshToken!!

    @Test
    fun `refreshReplay：其他设备收到，平台是被盗的会话（AC-ACC-03-25）`() = runBlocking {
        val p = pair()
        p.d2.connect()
        val stolen = p.d1.refreshToken()
        val now = System.currentTimeMillis()
        setClock(now)
        val x = rawRefresh(stolen)
        assertEquals(200, x.statusCode())
        val access = Json.parseToJsonElement(x.body()).jsonObject["accessToken"]!!.jsonPrimitive.content
        // 攻击者转正，窗口过后手里的旧值就是重放。
        assertEquals(200, rawEvents(access).statusCode())
        setClock(now + 3 * MINUTE)
        assertEquals(401, rawRefresh(stolen).statusCode())
        until("refreshReplay") { p.d2.log.contains("refreshReplay") }
        assertEquals("web", p.d2.platforms["refreshReplay"])
    }

    @Test
    fun `refreshRateLimited：连续 6 小时限流，第 6 小时其他设备才收到（AC-ACC-03-45）`() = runBlocking {
        val p = pair()
        p.d2.connect()
        val held = p.d1.refreshToken()
        val start = System.currentTimeMillis()
        for (hour in 0 until 6) {
            setClock(start + hour * HOUR)
            for (i in 0 until 21) assertEquals(if (i == 20) 429 else 200, rawRefresh(held).statusCode())
            if (hour == 4) {
                quiet()
                assertEquals(emptyList(), p.d2.log.toList())
            }
        }
        until("refreshRateLimited") { p.d2.log.contains("refreshRateLimited") }
        assertEquals("web", p.d2.platforms["refreshRateLimited"])
    }

    @Test
    fun `newIpPrefix：刷新来自没见过的前缀，其他设备收到，发起的设备不提醒；同一前缀不再提醒（A13）`() = runBlocking {
        val p = pair()
        p.d1.connect()
        p.d2.connect()
        assertEquals(200, rawRefresh(p.d1.refreshToken(), "192.0.2.1").statusCode())
        until("newIpPrefix") { p.d2.log.contains("newIpPrefix") }
        assertEquals("web", p.d2.platforms["newIpPrefix"])
        quiet()
        assertEquals(emptyList(), p.d1.log.toList())
        rawRefresh(p.d1.refreshToken(), "192.0.2.77")
        quiet()
        assertEquals(listOf("newIpPrefix"), p.d2.log.toList())
    }

    @Test
    fun `passwordRecovered：所有在线设备先提醒再踢线（F-ACC-02 规则 3b）`() = runBlocking {
        val p = pair()
        p.d1.connect()
        p.d2.connect()
        Device(Platform.WEB).account.recover(p.username, p.recoveryCode, NEW_PASSWORD)
        until("both kicked") { p.d1.log.size >= 2 && p.d2.log.size >= 2 }
        assertEquals(listOf("passwordRecovered", "kicked/passwordChanged"), p.d1.log.toList())
        assertEquals(listOf("passwordRecovered", "kicked/passwordChanged"), p.d2.log.toList())
    }

    @Test
    fun `passwordChanged：其他设备先提醒再踢线，发起的设备都没有（F-ACC-05 规则 5）`() = runBlocking {
        val p = pair()
        p.d1.connect()
        p.d2.connect()
        p.d1.account.changePassword(PASSWORD, NEW_PASSWORD)
        until("d2 kicked") { p.d2.log.size >= 2 }
        assertEquals(listOf("passwordChanged", "kicked/passwordChanged"), p.d2.log.toList())
        assertEquals("web", p.d2.platforms["passwordChanged"])
        quiet()
        assertEquals(emptyList(), p.d1.log.toList())
    }

    @Test
    fun `recoveryCodeRegenerated：发起的设备不提醒（A14）`() = runBlocking {
        val p = pair()
        p.d1.connect()
        p.d2.connect()
        p.d1.account.regenerateRecoveryCode(PASSWORD)
        until("recoveryCodeRegenerated") { p.d2.log.contains("recoveryCodeRegenerated") }
        quiet()
        assertEquals(emptyList(), p.d1.log.toList())
    }
}
