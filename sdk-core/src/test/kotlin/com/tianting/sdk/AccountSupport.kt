// FE-611 测试设施：假 HTTP + FakeTransport + 假时钟 + http-client.schema.json 校验。
package com.tianting.sdk

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.tianting.sdk.testing.FakeClock
import com.tianting.sdk.testing.FakeConnection
import com.tianting.sdk.testing.FakeHttp
import com.tianting.sdk.testing.FakeHttpCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import java.io.File
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.fail

const val HOUR = 3_600_000L
const val DAY = 24 * HOUR
const val MIN = 60_000L
const val USER = "alice_1"
const val REFRESH = "/v1/account/token/refresh"
const val PASSWORD = "/v1/account/password"
const val LOGOUT = "/v1/account/logout"
const val START_MS = 1_800_000_000_000L

private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

/** 和服务端形状相同的 access token（只有 sid、gen 对 SDK 有意义）。 */
fun jwt(sid: String, gen: Long, sub: String = USER): String =
    """${b64("""{"alg":"HS256"}""")}.${b64("""{"sub":"$sub","sid":"$sid","gen":$gen}""")}.SIGNATURE-$sid-$gen"""

fun refreshToken(sid: String, gen: Long): String = "k1.$sid.$gen.NONCE$gen.MAC$gen"

object HttpSchema {
    private val mapper = ObjectMapper()
    private val root = mapper.readTree(File(protocolDir, "schema/http-client.schema.json").readText())
    private val cache = HashMap<String, JsonSchema>()

    val endpoints: Set<String> = root["endpoints"].fieldNames().asSequence().toSet()

    fun requestDef(endpoint: String): String? = root["endpoints"][endpoint]?.get("request")?.asText()

    fun errors(body: String, def: String): List<String> {
        val schema = cache.getOrPut(def) {
            val text = """{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","${'$'}defs":${root["\$defs"]},"${'$'}ref":"#/${'$'}defs/$def"}"""
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(text)
        }
        return schema.validate(mapper.readTree(body)).map { it.message }
    }
}

/** 刷新、登录等的响应。按 Schema 校验，假数据不会和真实服务端走样。 */
fun authBody(
    gen: Long,
    accessExpiresAt: Long,
    sid: String = "sid1",
    absoluteExpiresAt: Long = accessExpiresAt + 365 * DAY,
    recoveryCode: String? = null,
    recoveryCodeGen: Long = 2,
    recoveryCodeUnacked: Boolean = false,
): JsonObject {
    val body = buildJsonObject {
        put("userId", USER)
        put("accessToken", jwt(sid, gen))
        put("accessExpiresAt", accessExpiresAt)
        put("refreshToken", refreshToken(sid, gen))
        put("refreshExpiresAt", accessExpiresAt + DAY)
        put("absoluteExpiresAt", absoluteExpiresAt)
        if (recoveryCode != null) {
            put("recoveryCode", recoveryCode)
            put("recoveryCodeGen", recoveryCodeGen)
        }
        if (recoveryCodeUnacked) put("recoveryCodeUnacked", true)
    }
    val errs = HttpSchema.errors(body.toString(), "AuthResponse")
    check(errs.isEmpty()) { "authBody: $errs" }
    return body
}

class MemoryStore : SessionStore {
    var saved: AccountSession? = null
    val saves = ArrayList<AccountSession>()
    var clears = 0
    var failSave = false

    /** 设置后，save 写完之后停在这里（"落盘后"的暂停点）。 */
    var pauseAfterSave: CompletableDeferred<Unit>? = null

    /** 设置后，save 写之前停在这里（慢存储）。 */
    var pauseBeforeSave: CompletableDeferred<Unit>? = null

    override suspend fun load(): AccountSession? = saved
    override suspend fun save(session: AccountSession) {
        if (failSave) throw java.io.IOException("disk full ${session.accessToken}")
        pauseBeforeSave?.await()
        saved = session
        saves.add(session)
        pauseAfterSave?.await()
    }

    override suspend fun clear() {
        saved = null
        clears += 1
    }
}

class MemoryPendingStore : PendingLogoutStore {
    var items: List<PendingLogout> = emptyList()
    var failSave = false

    /** 设置后，save 写之前停在这里（慢存储）。 */
    var pauseSave: CompletableDeferred<Unit>? = null
    override suspend fun load(): List<PendingLogout> = items
    override suspend fun save(items: List<PendingLogout>) {
        if (failSave) throw java.io.IOException("disk full")
        pauseSave?.await()
        this.items = items
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccountHarness(val h: Harness, val store: MemoryStore = MemoryStore(), val pendingStore: MemoryPendingStore = MemoryPendingStore()) {
    val clock: FakeClock get() = h.clock
    val transport get() = h.transport
    val im get() = h.im
    val logs get() = h.logs
    val http = FakeHttp { h.flush() }
    val badRequests = ArrayList<String>()
    val ended = ArrayList<SessionEnd>()
    val outcomes = ArrayList<SessionOutcome>()
    val accountEvents = ArrayList<AccountEvent>()
    val revokes = ArrayList<String>()
    val banner = ArrayList<Boolean>()
    private var peak = 0
    val maxPending: Int get() = peak

    val account: AccountClient = newClient()

    init {
        http.onRequest = { call -> check(call) }
        h.callScope.launch(start = CoroutineStart.UNDISPATCHED) { h.im.banner.collect { banner.add(it) } }
        h.flush()
    }

    fun check(call: FakeHttpCall) {
        peak = maxOf(peak, http.pending.size)
        val ep = "${call.req.method} ${call.path}"
        if (ep !in HttpSchema.endpoints) {
            badRequests.add("no endpoint $ep")
        } else {
            HttpSchema.requestDef(ep)?.let { def -> badRequests.addAll(HttpSchema.errors(call.req.body ?: "{}", def).map { "$ep $it" }) }
        }
    }

    /** 同样的存储、新的实例：相当于杀掉 App 再启动。 */
    fun newClient(): AccountClient = AccountClient(
        AccountClientOptions(
            baseUrl = "https://im.test",
            platform = Platform.ANDROID,
            store = store,
            pendingLogoutStore = pendingStore,
            systemTokenRevoker = { revokes.add("revoked") },
            http = http,
            clock = h.clock,
            logger = object : Logger {
                override fun debug(message: String) { h.logs.add(message) }
                override fun warn(message: String) { h.logs.add(message) }
                override fun error(message: String) { h.logs.add(message) }
            },
            random = { 0.5 },
            dispatcher = h.dispatcher,
        ),
    ).also { c ->
        c.eventTap = { e ->
            accountEvents.add(e)
            if (e is AccountEvent.Ended) ended.add(e.end)
            if (e is AccountEvent.Outcome) outcomes.add(e.outcome)
        }
    }

    fun <T> start(block: suspend AccountClient.() -> T): Deferred<T> {
        val d = h.callScope.async { account.block() }
        h.flush()
        return d
    }

    fun <T> call(block: suspend AccountClient.() -> T): T = await(start(block))

    fun <T> await(d: Deferred<T>): T {
        h.flush()
        if (!d.isCompleted) fail("还没有完成")
        return d.getCompleted()
    }

    /** 这个操作以 E 失败。 */
    inline fun <reified E : Throwable> failure(d: Deferred<*>): E {
        h.flush()
        if (!d.isCompleted) fail("还没有完成")
        val e = d.getCompletionExceptionOrNull() ?: fail("没有失败")
        if (e !is E) fail("异常类型不对: ${e.javaClass.simpleName}")
        return e
    }

    fun foreground() {
        account.foreground()
        h.flush()
    }

    fun refresh(n: Int = -1): FakeHttpCall = if (n < 0) http.last(REFRESH) else http.to(REFRESH)[n]

    /** 恢复一个账号会话。 */
    fun session(gen: Long, accessLeftMs: Long, sid: String = "sid1"): AccountSession {
        val s = AccountSession(
            mode = SessionMode.ACCOUNT,
            userId = USER,
            accessToken = jwt(sid, gen),
            accessExpiresAt = clock.now() + accessLeftMs,
            refreshToken = refreshToken(sid, gen),
            refreshExpiresAt = clock.now() + DAY,
            absoluteExpiresAt = clock.now() + 365 * DAY,
        )
        call { restore(s) }
        store.saved = s
        return s
    }

    fun connect() {
        im.connect(account.tokenProvider)
        h.flush()
    }

    /** 用 tokenProvider 连接并完成 auth。 */
    fun online(): FakeConnection {
        connect()
        return h.login(USER)
    }

    /** 新连接完成 auth。 */
    fun authNew(): FakeConnection = h.login(USER)

    fun fatals(): List<FatalReason> = h.fatals()

    fun kicked(c: FakeConnection, reason: String, code: Int = 4012, recoverBefore: Long? = null) {
        c.receive(objNoNull("cmd" to "event", "data" to objNoNull("type" to "kicked", "reason" to reason, "recoverBefore" to recoverBefore)))
        c.drop(code, reason)
    }

    val current: AccountSession? get() = account.session.value
}

fun authToken(c: FakeConnection): String = c.last("auth").dataObj["token"]!!.jsonPrimitive.content

val FakeHttpCall.refreshField: String get() = json["refreshToken"]!!.jsonPrimitive.content

/** 判定服务端的假实现（规则 4）：对照 server/e2e/accountmodel/server.go，account-session.json 的 serverCases 约束它。 */
class FakeAccountSession {
    var current = 1L
    private var next = 2L
    var overlapUntil = 0L
    var overlapMinGen = 0L
    var pendings = ArrayList<Long>()
    var revoked = false
    private var issued = 0
    var now = 0L

    private fun promote(gen: Long) {
        overlapUntil = now + 120_000
        overlapMinGen = current
        current = gen
        pendings = ArrayList()
    }

    fun refresh(gen: Long): Pair<String, Long?> {
        if (revoked) return "alreadyRevoked" to null
        val pending = gen in pendings
        if (gen != current && !pending) {
            if (gen >= overlapMinGen && now < overlapUntil) return "superseded" to null
            revoked = true
            return "replayDetected" to null
        }
        if (issued >= 20) return "rateLimited" to null
        issued += 1
        var result = "issued"
        if (pending) {
            promote(gen)
            result = "issuedPromoted"
        }
        val newGen = next++
        pendings.add(newGen)
        if (pendings.size > 4) pendings = ArrayList(pendings.takeLast(4))
        return result to newGen
    }

    fun access(gen: Long): String = when {
        revoked -> "sessionRevoked"
        gen == current -> "ok"
        gen in pendings -> {
            promote(gen)
            "promoted"
        }
        else -> "tokenExpired"
    }
}

/** 所有账号用例的基类：每个用例发出的请求体都要符合 http-client.schema.json。 */
abstract class AccountTest : SdkTest() {
    private val made = ArrayList<AccountHarness>()

    protected fun setupAccount(database: LocalDatabase? = null): AccountHarness =
        AccountHarness(setup(database = database, clock = FakeClock(START_MS), timings = IMClientTimings(heartbeatMs = 10 * 365 * DAY))).also { made.add(it) }

    @AfterEach
    fun requestsMatchSchema() {
        for (a in made) assertEquals(emptyList(), a.badRequests)
    }
}

fun jsonOf(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
