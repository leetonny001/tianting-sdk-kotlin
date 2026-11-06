package com.tianting.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

/** 账号接口返回的错误：HTTP 状态 + {code, msg, reason?, details?}。 */
public class AccountError(
    public val status: Int,
    code: Int,
    msg: String,
    public val reason: String? = null,
    details: JsonObject? = null,
) : IMError(code, msg, null, details)

/** 没有拿到服务端的错误码：网络不通，或者响应不是约定的 JSON（例如代理返回的 HTML）。 */
public open class AccountNetworkError(message: String = "network error") : Exception(message)

/** 等了 30 秒没有响应。迟到的响应会被丢弃。 */
public class AccountTimeoutError : AccountNetworkError("request timed out")

/** 没有可用的账号会话：没登录，或者已经结束（Ended 事件）。end：accessToken() 抛出时带上最近一次结束的原因，没有就是没登录过。 */
public class SessionEndedError(public val end: SessionEnd? = null) : Exception("account session ended")

public enum class SessionMode(public val wire: String) { ACCOUNT("account"), DEMO("demo") }

/**
 * 本地保存的会话。存储由 App 决定（Android 用 Keystore 加密文件，F-ACC-03 规则 8）。
 * mode 为 null 的老数据按 DEMO（R-CF-11）。toString 不带 token：data class 默认的 toString 会把凭证带进日志。
 */
public data class AccountSession(
    val mode: SessionMode? = null,
    val userId: String,
    val accessToken: String,
    val accessExpiresAt: Long = 0,
    val refreshToken: String? = null,
    val refreshExpiresAt: Long? = null,
    val absoluteExpiresAt: Long? = null,
) {
    override fun toString(): String = "AccountSession(mode=$mode, accessExpiresAt=$accessExpiresAt)"

    /** App 把它加密后写进存储。 */
    public fun toJson(): String = buildJsonObject {
        mode?.let { put("mode", it.wire) }
        put("userId", userId)
        put("accessToken", accessToken)
        put("accessExpiresAt", accessExpiresAt)
        refreshToken?.let { put("refreshToken", it) }
        refreshExpiresAt?.let { put("refreshExpiresAt", it) }
        absoluteExpiresAt?.let { put("absoluteExpiresAt", it) }
    }.toString()

    public companion object {
        /**
         * 读存储里的 JSON，内容不对时返回 null（当没有登录）。认识老版本的 {userId, token}：mode 为 null，按演示会话处理（R-CF-11），
         * 到期时间取 JWT 的 exp，取不到是 0。
         */
        public fun fromJson(text: String): AccountSession? {
            val o = try {
                ImJson.parseToJsonElement(text) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            } ?: return null
            val userId = o.str("userId") ?: return null
            val access = o.str("accessToken") ?: o.str("token") ?: return null
            val mode = when (o.str("mode")) {
                "account" -> SessionMode.ACCOUNT
                "demo" -> SessionMode.DEMO
                else -> null
            }
            return AccountSession(
                mode = mode,
                userId = userId,
                accessToken = access,
                accessExpiresAt = o.long("accessExpiresAt") ?: jwtExpiresAt(access) ?: 0,
                refreshToken = o.str("refreshToken"),
                refreshExpiresAt = o.long("refreshExpiresAt"),
                absoluteExpiresAt = o.long("absoluteExpiresAt"),
            )
        }
    }
}

/** "待退出"（F-ACC-04 规则 1）：断网退出时只留这两项，不算登录状态，联网后补发 logout。 */
public data class PendingLogout(val sid: String, val refreshToken: String) {
    override fun toString(): String = "PendingLogout(***)"
}

/** 新 token 先写这里再使用（规则 5，R-CF-09）。确认恢复码之前不写（F-ACC-05 规则 1）。可以挂起：SDK 保证同一时间只有一个写。 */
public interface SessionStore {
    public suspend fun load(): AccountSession?
    public suspend fun save(session: AccountSession)
    public suspend fun clear()
}

/** "待退出"的持久化。和会话一样要加密（里面有 refresh token）。 */
public interface PendingLogoutStore {
    public suspend fun load(): List<PendingLogout>
    public suspend fun save(items: List<PendingLogout>)
}

/** 系统令牌注销（FCM deleteToken，F-ACC-04 规则 1）。调用点在 SDK，FCM 接入随 C3。 */
public fun interface SystemTokenRevoker {
    public suspend fun revoke()
}

public class AuthResult(
    public val userId: String,
    /** 这一代恢复码要让用户保存，确认后调 ackRecoveryCode。 */
    public val recoveryCode: String? = null,
    public val recoveryCodeGen: Long? = null,
    public val recoveryCodeUnacked: Boolean = false,
) {
    override fun toString(): String = "AuthResult(recoveryCodeGen=$recoveryCodeGen, recoveryCodeUnacked=$recoveryCodeUnacked)"
}

public class RecoveryCode(public val recoveryCode: String, public val recoveryCodeGen: Long) {
    override fun toString(): String = "RecoveryCode(gen=$recoveryCodeGen)"
}

@Serializable
public data class AccountConfig(
    val registerMode: String,
    val demoLogin: Boolean,
    val features: Features,
    val limits: Limits,
    val privacyNotice: PrivacyNotice? = null,
) {
    @Serializable
    public data class Features(val files: Boolean)

    @Serializable
    public data class Limits(
        val usernameMinLen: Int,
        val usernameMaxLen: Int,
        val passwordMinLen: Int,
        val passwordMaxLen: Int,
        val nicknameMaxLen: Int,
        val deviceNameMaxLen: Int,
    )

    @Serializable
    public data class PrivacyNotice(val version: String, val url: String? = null)
}

@Serializable
public data class AccountEvents(
    val events: List<Item>,
    val otherSessions: OtherSessions,
    val recoveryCodeUnacked: Boolean = false,
) {
    @Serializable
    public data class Item(val kind: String, val at: Long, val platform: String? = null, val ipPrefix: String? = null)

    @Serializable
    public data class OtherSessions(val count: Int, val lastActiveAt: Long? = null)
}

/** 不走 StateFlow 的一次性事件。 */
public sealed interface AccountEvent {
    /** 会话结束，回登录页。文字用 sessionDialogText 取。 */
    public data class Ended(val end: SessionEnd) : AccountEvent

    /** token 换了（已经写入 store）。不带 token 本身，要用时读 session。 */
    public data object TokensChanged : AccountEvent

    /** 服务端说最新一代恢复码没有确认：App 强制进保存页（F-ACC-05 规则 1）。 */
    public data object RecoveryCodeUnacked : AccountEvent

    /** 每一步判断的结果：只有动作和代号，没有凭证。调试和协议用例用。 */
    public data class Outcome(val outcome: SessionOutcome) : AccountEvent
}

public class AccountClientOptions(
    /** 例如 https://im.example.com（不带 /v1）。 */
    public val baseUrl: String,
    public val platform: Platform = Platform.ANDROID,
    public val deviceName: String? = null,
    public val store: SessionStore? = null,
    /** "待退出"的持久化。为 null 时只在内存：杀 App 后不再补发。 */
    public val pendingLogoutStore: PendingLogoutStore? = null,
    /** 断网退出时调用（F-ACC-04 规则 1），失败不影响退出。 */
    public val systemTokenRevoker: SystemTokenRevoker? = null,
    /** 以下用于测试注入。 */
    public val http: HttpTransport? = null,
    public val clock: Clock? = null,
    public val logger: Logger = SilentLogger,
    public val random: () -> Double = { Random.nextDouble() },
    /** 请求超时，默认 30 秒。 */
    public val requestTimeoutMs: Long = ACCOUNT_REQUEST_TIMEOUT_MS,
    /** SDK 的单线程调度器；为 null 时自己开一条线程。测试时注入 TestDispatcher。 */
    public val dispatcher: CoroutineDispatcher? = null,
)

private enum class RefreshResult { OK, RETRY, ENDED }

private class Inflight(val attempt: Long, val timer: Timer, val done: CompletableDeferred<RefreshResult>) {
    var call: HttpCall? = null
}

private class TokenWaiter(val rejected: RejectedToken, val done: CompletableDeferred<Unit>) {
    var timer: Timer? = null
}

private class AuthResponse(
    val userId: String,
    val accessToken: String,
    val accessExpiresAt: Long,
    val refreshToken: String,
    val refreshExpiresAt: Long,
    val absoluteExpiresAt: Long,
    val recoveryCode: String?,
    val recoveryCodeGen: Long?,
    val recoveryCodeUnacked: Boolean,
)

private const val BACKOFF_BASE_MS = 1_000L
private const val BACKOFF_MAX_MS = 30_000L
private const val JITTER = 0.2
private const val RETRY_AFTER_MAX_S = 600 // retryAfterSeconds 来自响应：窗口只有 2 分钟，防止一个异常值让重试永远不发生
private const val TIMER_MAX_MS = 86_400_000L // 设备休眠后定时器可能晚到：分段等，到点再算一次
private const val HTTP_4011_RETRIES = 2 // 同一个请求最多因为 4011 重试这么多次
private const val PENDING_LOGOUT_MAX = 8 // "待退出"的上限，防止文件无限长；挤掉的会话到期后由 session_purge 清除
private const val PENDING_FLUSH_RETRIES = 5 // 补发失败后按退避重试这么多次，之后等下一次回前台或联网

private const val PATH_REFRESH = "/v1/account/token/refresh"
private const val PATH_LOGOUT = "/v1/account/logout"

/**
 * 账号 HTTP 接口和账号会话（PRD-chat 第 13.1 节，F-ACC-03 规则 5～7、F-ACC-04 规则 3）。独立于 IMClient。
 *
 *     val account = AccountClient(AccountClientOptions(baseUrl = "https://im.example.com", store = store))
 *     account.login(username, password)
 *     im.connect(account.tokenProvider)
 *
 * 线程：状态只在一条线程（dispatcher）上访问，和 sdk-ts 的事件循环一一对应；suspend 方法可以从任意线程调用，
 * 调用方的协程被取消不影响已经开始的操作（响应回来还是要换 token、释放队列）。
 *
 * 不变量（语义说明见 tianting-server 的 docs/sdk/README.md 第 6 节）：
 * - 同一时间最多一个刷新请求（单飞）。超时放弃之后这个序号的响应全部丢弃。
 * - 刷新、改密、找回、退出其他设备、登录、注册、注销申请排在同一个串行队列（slot）。
 * - 改密等换 sid 的操作进行中，缓存连接上的踢线，等响应再决定。
 * - 新 token 先落盘再使用；恢复码确认之前只放内存。
 * - token、refresh、恢复码不进 URL，不进日志，不进错误信息。
 */
public class AccountClient(private val o: AccountClientOptions) {
    private val executor: ExecutorService? = if (o.dispatcher == null) {
        Executors.newSingleThreadExecutor { r -> Thread(r, "tianting-account").apply { isDaemon = true } }
    } else {
        null
    }
    private val dispatcher: CoroutineDispatcher = o.dispatcher ?: executor!!.asCoroutineDispatcher()
    private val log = o.logger
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> log.error("account: unexpected ${safeDescribe(e)}") },
    )
    private val baseUrl = o.baseUrl.trimEnd('/')
    private val store = o.store
    private val http: HttpTransport = o.http ?: OkHttpHttpTransport()
    private val clock: Clock = o.clock ?: CoroutineClock(scope)
    private val random = o.random
    private val timeoutMs = o.requestTimeoutMs
    private val storeLock = Mutex() // 存储可以挂起：同一时间一个写，先发起的先落地，退出时的清除不会被更早的保存盖掉

    private val _session = MutableStateFlow<AccountSession?>(null)
    private val _ended = MutableStateFlow<SessionEnd?>(null)
    private val _events = MutableSharedFlow<AccountEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Volatile
    private var lineage = TokenLineage("", 0)
    private var memoryOnly = false // 恢复码确认之前 token 只放内存（F-ACC-05 规则 1）

    private var attempt = 0L
    private var inflight: Inflight? = null
    private var superseded = 0 // 连续收到 superseded 的次数
    private var backoffN = 0
    private var mustRefresh = false // 不看剩余时间也要刷新：token 被拒绝过，或者已经过期
    private var retryTimer: Timer? = null

    /**
     * 刷新刚返回的 token 按本地时钟已经不足 6 小时（时钟偏快，或者 TTL 配得短，问题 S1）。再刷新也是同样的答案，又要重连，
     * 会一直循环到服务端限流。所以这个 token 不再主动刷新，也不按本地时间当成过期，等服务端的 4011（规则 7）。
     */
    private var shortOnArrival = false
    private var rereadStore = false // superseded 之后重读本地最新 token（规则 6）
    private var lastFailure: Exception? = null

    private var slotBusy = false // 串行队列的 slot：刷新或者队列里的操作占着
    private val queue = ArrayDeque<() -> Unit>()
    private var refreshQueued = false
    private var epoch = 0 // 每次会话结束加一：进行中的操作回来时，响应不能把已经退出的会话救回来

    private var holdDepth = 0 // 换 sid 的操作进行中（F-ACC-04 规则 3）
    private var heldKicks = ArrayList<Pair<KickInfo, CompletableDeferred<KickDecision>>>()

    private var waiters = ArrayList<TokenWaiter>()
    private var connected = false
    private var lifeTimer: Timer? = null
    private val tokenListeners = CopyOnWriteArraySet<() -> Unit>()
    private val endedListeners = CopyOnWriteArraySet<(SessionEnd) -> Unit>()
    private val profileListeners = CopyOnWriteArraySet<(String, String) -> Unit>()

    private val memoryPending = ArrayList<PendingLogout>() // 没有 pendingLogoutStore 时
    private var flushing = false
    private var flushFailures = 0
    private var flushTimer: Timer? = null

    /** 测试用：同步观察事件（events 是 SharedFlow，没有订阅者时不缓存）。 */
    internal var eventTap: ((AccountEvent) -> Unit)? = null

    /** 当前会话，没有时为 null。里面有 token，不要打日志（toString 已经去掉了）。 */
    public val session: StateFlow<AccountSession?> = _session.asStateFlow()

    /** 最近一次会话结束的原因；有新会话时清除。 */
    public val ended: StateFlow<SessionEnd?> = _ended.asStateFlow()

    /** 一次性事件。SharedFlow 没有回放：要提前开始收集。 */
    public val events: SharedFlow<AccountEvent> = _events.asSharedFlow()

    /** 本地 token 的代号。会话结束后保留最后的值（只是一个数字）。 */
    public val localGen: Long get() = lineage.gen

    /** 有进行中的刷新请求。 */
    public val refreshInflight: Boolean get() = inflight != null

    public val tokenProvider: TokenProvider = object : TokenProvider {
        override suspend fun accessToken(): String = onAccount { accessTokenNow() }
        override suspend fun tokenRejected(token: String) = onAccount { tokenRejectedNow(token) }
        override suspend fun kickReceived(info: KickInfo): KickDecision = onAccount { kickReceivedNow(info) }
        override fun connectionChanged(connected: Boolean) = post {
            this@AccountClient.connected = connected
            armLifeTimer()
        }

        override fun foreground() = this@AccountClient.foreground()
        override fun onTokenChanged(listener: () -> Unit): () -> Unit = subscribe(tokenListeners, listener)
        override fun onEnded(listener: (SessionEnd) -> Unit): () -> Unit = subscribe(endedListeners, listener)
        override fun onProfileChanged(listener: (String, String) -> Unit): () -> Unit = subscribe(profileListeners, listener)
        override fun recoveryCodeUnacked() = post { emit(AccountEvent.RecoveryCodeUnacked) }
    }

    private val session_: AccountSession? get() = _session.value

    // ---- 线程 ----

    private fun post(fn: () -> Unit) {
        scope.launch { guard(fn) }
    }

    private inline fun guard(fn: () -> Unit) {
        try {
            fn()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("account: unexpected ${safeDescribe(e)}")
        }
    }

    /** 在 SDK 线程上立即开始执行，直到第一次挂起（和 sdk-ts 的 async 函数的同步部分一致）。 */
    private fun launchNow(block: suspend () -> Unit): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("account: unexpected ${safeDescribe(e)}")
        }
    }

    /** 切到 SDK 线程执行。放在 scope 里：调用方的协程被取消，已经开始的操作照常走完。 */
    private suspend fun <T> onAccount(block: suspend () -> T): T = withContext(dispatcher) {
        scope.async(start = CoroutineStart.UNDISPATCHED) { block() }.await()
    }

    // ---- 启动和入口（规则 5） ----

    /** 启动：从 store 读会话，检查是否要刷新，补发"待退出"。已过期的先刷新，连接时 accessToken() 会等它。 */
    public suspend fun start(): AccountSession? = onAccount {
        val saved = storeLock.withLock { store?.load() }
        if (saved != null) restoreNow(saved)
        if (session_ != null) triggerNow(RefreshSource.START)
        wakePendingLogouts()
        session_
    }

    /** 恢复一个已有的会话，不写 store，不发事件。没有 mode 的老数据按 demo（R-CF-11）。 */
    public suspend fun restore(saved: AccountSession): Unit = onAccount { restoreNow(saved) }

    private fun restoreNow(saved: AccountSession) {
        _session.value = saved.copy(mode = saved.mode ?: SessionMode.DEMO)
        lineage = lineageOf(saved.accessToken)
        shortOnArrival = false
        resetFailures()
        armLifeTimer()
    }

    /** 回到前台：检查是否要刷新，补发"待退出"。 */
    public fun foreground() {
        post {
            triggerNow(RefreshSource.FOREGROUND)
            wakePendingLogouts()
        }
    }

    /** 网络恢复（sdk-android 的 bindNetwork 自动调用）：补发"待退出"。刷新不提前，等到点的定时器。 */
    public fun networkOnline() {
        post { wakePendingLogouts() }
    }

    /**
     * 四个入口合并成一次（单飞）。source=KICKED 时 rejected 是被拒绝的 token 的身份（规则 7）。
     * 返回这一步的判断。TIMER 也是退避和 superseded 重试到点时走的入口。
     */
    public suspend fun trigger(source: RefreshSource, rejected: RejectedToken? = null): SessionOutcome =
        onAccount { triggerNow(source, rejected) }

    private fun triggerNow(source: RefreshSource, rejected: RejectedToken? = null): SessionOutcome {
        val s = session_ ?: return record(SessionOutcome.None)
        if (s.mode != SessionMode.ACCOUNT) {
            if (source != RefreshSource.KICKED) return record(SessionOutcome.None)
            endSession(SessionEnd(SessionEndAction.LOGIN, SessionDialog.SESSION_EXPIRED, reason = "tokenExpired", code = 4011))
            return record(SessionOutcome.Login(SessionDialog.SESSION_EXPIRED))
        }
        if (source == RefreshSource.KICKED) {
            if (isLocalNewer(lineage, rejected ?: RejectedToken())) return record(SessionOutcome.UseLocal)
            mustRefresh = true
        }
        return record(decideRefresh(source))
    }

    /** /v1 接口返回 401 / 4011（规则 7、RS5-10）：本地比被拒绝的新就直接用本地值，否则刷新一次（和其他入口合并）。 */
    public suspend fun http4011(rejected: RejectedToken): SessionOutcome = onAccount { http4011Now(rejected) }

    private fun http4011Now(rejected: RejectedToken): SessionOutcome {
        if (isLocalNewer(lineage, rejected)) return record(SessionOutcome.UseLocal)
        if (session_ == null) return record(SessionOutcome.None)
        mustRefresh = true
        return record(decideRefresh(RefreshSource.KICKED))
    }

    private fun decideRefresh(source: RefreshSource): SessionOutcome {
        if (inflight != null) return SessionOutcome.None
        // 等重试时只有到点才发：superseded 要按 retryAfterSeconds（AC-ACC-03-39），退避也不提前，避免切前台刷出多个请求。
        if (retryTimer != null && source != RefreshSource.TIMER) return SessionOutcome.None
        val s = session_ ?: return SessionOutcome.None
        if (!mustRefresh) {
            val now = clock.now()
            if (s.accessExpiresAt == s.absoluteExpiresAt && now >= s.accessExpiresAt) {
                endSession(SessionEnd(SessionEndAction.LOGIN, SessionDialog.SESSION_EXPIRED, reason = "expired"))
                return SessionOutcome.Login(SessionDialog.SESSION_EXPIRED)
            }
            if (shortOnArrival || !shouldRefresh(s.accessExpiresAt, s.absoluteExpiresAt, now)) return SessionOutcome.None
        }
        if (slotBusy) {
            refreshQueued = true
            return SessionOutcome.None
        }
        clearRetryTimer()
        slotBusy = true
        val (outcome, done) = beginRefresh()
        launchNow {
            done.await()
            releaseSlot()
        }
        return outcome
    }

    /** 发一个刷新请求。调用方负责 slot：普通刷新占整个 slot；队列内的改密在自己的 slot 里直接调（RS5-15）。 */
    private fun beginRefresh(): Pair<SessionOutcome, CompletableDeferred<RefreshResult>> {
        attempt += 1
        val a = attempt
        val done = CompletableDeferred<RefreshResult>()
        val timer = clock.schedule(timeoutMs) { onRefreshTimeout(a) }
        val f = Inflight(a, timer, done)
        inflight = f
        launchNow { sendRefresh(f) }
        return SessionOutcome.Refresh(a) to done
    }

    private suspend fun sendRefresh(f: Inflight) {
        val a = f.attempt
        if (rereadStore) reloadFromStore()
        rereadStore = false
        if (inflight?.attempt != a) return
        val refreshToken = session_?.refreshToken
        if (refreshToken == null) {
            settleRefresh(a, RefreshResult.ENDED)
            endSession(SessionEnd(SessionEndAction.LOGIN, SessionDialog.SESSION_EXPIRED, code = 4019))
            return
        }
        val resp = try {
            parseAuthResponse(send("POST", PATH_REFRESH, buildJsonObject { put("refreshToken", refreshToken) }, inflight = f))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onRefreshFailed(a, e)
            return
        }
        if (inflight?.attempt != a) {
            log.debug("account: late refresh response $a dropped")
            record(SessionOutcome.None)
            return
        }
        val next = sessionFrom(resp)
        try {
            if (!memoryOnly) storeLock.withLock { store?.save(next) } // 新 token 先落盘再使用（R-CF-09）
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("account: session save failed: ${safeDescribe(e)}")
            onRefreshFailed(a, AccountNetworkError("session save failed"))
            return
        }
        if (inflight?.attempt != a) return // 落盘期间超时放弃了
        superseded = 0
        backoffN = 0
        lastFailure = null
        settleRefresh(a, RefreshResult.OK)
        adopt(next, resp, fromRefresh = true)
        record(SessionOutcome.KeepToken)
    }

    private fun onRefreshTimeout(a: Long) {
        val f = inflight
        if (f?.attempt != a) return
        log.warn("account: refresh timed out")
        f.call?.cancel()
        lastFailure = AccountTimeoutError()
        scheduleBackoff()
        settleRefresh(a, RefreshResult.RETRY)
        record(SessionOutcome.Backoff)
    }

    private fun onRefreshFailed(a: Long, err: Exception) {
        if (inflight?.attempt != a) {
            log.debug("account: late refresh failure $a dropped")
            record(SessionOutcome.None)
            return
        }
        val failure = failureOf(err)
        lastFailure = err
        val o = classifyRefreshFailure(failure, superseded)
        log.warn("account: refresh failed ${failure.code} ${failure.reason ?: ""} -> ${o.javaClass.simpleName}")
        when (o) {
            SessionOutcome.Backoff -> {
                scheduleBackoff()
                settleRefresh(a, RefreshResult.RETRY)
            }
            is SessionOutcome.RetryAfter -> {
                superseded += 1
                rereadStore = true
                scheduleRetry(retryAfterMs(failure.retryAfterSeconds))
                settleRefresh(a, RefreshResult.RETRY)
            }
            SessionOutcome.BanNotice -> {
                settleRefresh(a, RefreshResult.ENDED)
                endSession(SessionEnd(SessionEndAction.BAN_NOTICE, code = failure.code))
            }
            is SessionOutcome.Login -> {
                settleRefresh(a, RefreshResult.ENDED)
                endSession(SessionEnd(SessionEndAction.LOGIN, o.dialog, o.recoverBefore, failure.reason, failure.code))
            }
            else -> Unit
        }
        record(o)
    }

    private fun settleRefresh(a: Long, r: RefreshResult) {
        val f = inflight
        if (f?.attempt != a) return
        f.timer.cancel()
        inflight = null
        f.done.complete(r)
    }

    private suspend fun reloadFromStore() {
        try {
            val latest = storeLock.withLock { store?.load() }
            val s = session_
            if (latest?.refreshToken == null || s == null || latest.userId != s.userId || latest.refreshToken == s.refreshToken) return
            val l = lineageOf(latest.accessToken)
            if (isLocalNewer(l, lineage.asRejected())) {
                _session.value = latest.copy(mode = SessionMode.ACCOUNT)
                lineage = l
                shortOnArrival = false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("account: session load failed: ${safeDescribe(e)}")
        }
    }

    private fun scheduleBackoff() {
        val base = min(BACKOFF_BASE_MS * 2.0.pow(backoffN), BACKOFF_MAX_MS.toDouble())
        backoffN += 1
        scheduleRetry((base * (1 + JITTER * (2 * random() - 1))).roundToLong())
    }

    private fun scheduleRetry(ms: Long) {
        clearRetryTimer()
        retryTimer = clock.schedule(ms) {
            retryTimer = null
            triggerNow(RefreshSource.TIMER)
        }
    }

    private fun clearRetryTimer() {
        retryTimer?.cancel()
        retryTimer = null
    }

    private fun resetFailures() {
        superseded = 0
        backoffN = 0
        mustRefresh = false
        rereadStore = false
        lastFailure = null
        clearRetryTimer()
    }

    // ---- 换 token ----

    private fun sessionFrom(r: AuthResponse) = AccountSession(
        mode = SessionMode.ACCOUNT,
        userId = r.userId,
        accessToken = r.accessToken,
        accessExpiresAt = r.accessExpiresAt,
        refreshToken = r.refreshToken,
        refreshExpiresAt = r.refreshExpiresAt,
        absoluteExpiresAt = r.absoluteExpiresAt,
    )

    private fun adopt(next: AccountSession, resp: AuthResponse, fromRefresh: Boolean = false) {
        _session.value = next
        _ended.value = null
        lineage = lineageOf(next.accessToken)
        shortOnArrival = fromRefresh && shouldRefresh(next.accessExpiresAt, next.absoluteExpiresAt, clock.now())
        mustRefresh = false
        clearRetryTimer()
        armLifeTimer()
        // 先告诉连接再放行等待的人：连接处理"token 换了"要排在"被拒绝后等到了新 token"前面，否则在等退避的重连会被提前。
        for (fn in tokenListeners) guard { fn() }
        resolveWaiters()
        emit(AccountEvent.TokensChanged)
        if (resp.recoveryCodeUnacked) emit(AccountEvent.RecoveryCodeUnacked)
    }

    /** 登录、注册、改密、找回、退出其他设备的响应。没有恢复码才落盘；有恢复码时清掉旧的，确认之后才写（F-ACC-05 规则 1）。 */
    private suspend fun applyAuth(resp: AuthResponse, keepMemoryOnly: Boolean = false): AuthResult {
        val startEpoch = epoch
        val next = sessionFrom(resp)
        memoryOnly = resp.recoveryCode != null || (keepMemoryOnly && memoryOnly)
        try {
            storeLock.withLock { if (memoryOnly) store?.clear() else store?.save(next) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 服务端已经换了凭证，旧 token 回不去了：继续用新的，下一次换 token 时再写。
            log.warn("account: session save failed: ${safeDescribe(e)}")
        }
        if (epoch != startEpoch) {
            // 落盘期间退出或被踢了：这份不能留（F-ACC-04 规则 1：退出优先）。
            clearStore()
            throw SessionEndedError()
        }
        resetFailures()
        adopt(next, resp)
        return AuthResult(resp.userId, resp.recoveryCode, resp.recoveryCode?.let { resp.recoveryCodeGen }, resp.recoveryCodeUnacked)
    }

    /** 已连接时的定时器（规则 5）：不足 6 小时刷新；到期等于绝对上限时不再刷新，到期回登录页（RS5-11）。 */
    private fun armLifeTimer() {
        lifeTimer?.cancel()
        lifeTimer = null
        val s = session_
        if (s == null || s.mode != SessionMode.ACCOUNT || !connected || shortOnArrival) return
        val final = s.accessExpiresAt == s.absoluteExpiresAt
        // 剩余恰好 6 小时不刷新，所以要过这个点 1 毫秒。
        val at = if (final) s.accessExpiresAt else s.accessExpiresAt - REFRESH_BELOW_MS + 1
        val delay = maxOf(0, at - clock.now())
        lifeTimer = clock.schedule(min(delay, TIMER_MAX_MS)) {
            lifeTimer = null
            if (delay > TIMER_MAX_MS) {
                armLifeTimer()
            } else if (retryTimer == null) {
                triggerNow(RefreshSource.TIMER) // 正在等重试时不提前，到点的定时器会发
            }
        }
    }

    // ---- token 来源（HTTP 和 WS 共用，RS5-10） ----

    private suspend fun accessTokenNow(): String {
        val s = session_ ?: throw SessionEndedError(_ended.value)
        if (s.mode == SessionMode.ACCOUNT && !shortOnArrival && clock.now() >= s.accessExpiresAt) {
            if (s.accessExpiresAt == s.absoluteExpiresAt) {
                endSession(SessionEnd(SessionEndAction.LOGIN, SessionDialog.SESSION_EXPIRED, reason = "expired"))
                throw SessionEndedError()
            }
            // 已过期：先刷新再用（规则 5）。
            val wait = waitForNewer(lineage.asRejected())
            mustRefresh = true
            record(decideRefresh(RefreshSource.START))
            wait.await()
            // 不再检查一次：本地时钟走得太快时，新 token 看起来也过期了，再查就会不停刷新。
            return session_?.accessToken ?: throw SessionEndedError()
        }
        if (s.mode == SessionMode.ACCOUNT) triggerNow(RefreshSource.START)
        return session_?.accessToken ?: throw SessionEndedError()
    }

    private suspend fun tokenRejectedNow(token: String) {
        val l = lineageOf(token)
        val wait = waitForNewer(l.asRejected())
        triggerNow(RefreshSource.KICKED, l.asRejected())
        wait.await()
    }

    /** 先登记再触发刷新：刷新可能同步结束。 */
    private fun waitForNewer(rejected: RejectedToken, timeout: Long? = null): CompletableDeferred<Unit> {
        val done = CompletableDeferred<Unit>()
        if (session_ == null) {
            done.completeExceptionally(SessionEndedError())
            return done
        }
        if (isLocalNewer(lineage, rejected)) {
            done.complete(Unit)
            return done
        }
        val w = TokenWaiter(rejected, done)
        if (timeout != null) {
            w.timer = clock.schedule(timeout) {
                waiters.remove(w)
                done.completeExceptionally(lastFailure ?: AccountTimeoutError())
            }
        }
        waiters.add(w)
        return done
    }

    private fun resolveWaiters() {
        val ready = waiters.filter { isLocalNewer(lineage, it.rejected) }
        waiters.removeAll(ready.toSet())
        for (w in ready) {
            w.timer?.cancel()
            w.done.complete(Unit)
        }
    }

    // ---- 踢线（F-ACC-04 规则 3，RS5-15） ----

    private suspend fun kickReceivedNow(info: KickInfo): KickDecision {
        // 换 sid 的响应已经处理完，这个踢线才从连接那边过来（真实服务端先踢后回，两条线程上谁先到都有可能）：旧 sid 的踢线作废。
        val sid = info.sid
        if (!sid.isNullOrEmpty() && session_ != null && sid != lineage.sid) {
            log.debug("account: kick of an old sid ignored")
            return KickDecision.IGNORE
        }
        if (holdDepth > 0 && kickIsHoldable(info.reason, info.code)) {
            log.debug("account: kick held while credentials are changing")
            val d = CompletableDeferred<KickDecision>()
            heldKicks.add(info to d)
            return d.await()
        }
        applyKick(info)
        return KickDecision.PASS
    }

    private fun applyKick(info: KickInfo) {
        if (session_ == null || !kickEndsSession(info.reason, info.code)) return
        val o = kickOutcome(info.reason, info.recoverBefore) as? SessionOutcome.Login
        endSession(SessionEnd(SessionEndAction.LOGIN, o?.dialog, o?.recoverBefore, info.reason, info.code, fromKick = true))
    }

    /** 换 sid 的操作结束：成功时旧 sid 的踢线作废，失败或超时才生效（RS5-15）。 */
    private fun releaseHeld(succeeded: Boolean) {
        holdDepth -= 1
        if (holdDepth > 0) return
        val held = heldKicks
        heldKicks = ArrayList()
        for ((info, d) in held) {
            if (succeeded) {
                d.complete(KickDecision.IGNORE)
            } else {
                applyKick(info)
                d.complete(KickDecision.PASS)
            }
        }
    }

    // ---- 串行队列（RS4-03） ----

    /** 排进串行队列。hold：进行中缓存踢线。 */
    private suspend fun <T> enqueue(hold: Boolean = false, fn: suspend () -> T): T = onAccount {
        val result = CompletableDeferred<T>()
        val run = {
            if (hold) holdDepth += 1
            launchNow {
                var ok = false
                try {
                    val v = fn()
                    ok = true
                    result.complete(v)
                } catch (e: Throwable) {
                    result.completeExceptionally(e)
                    if (e is CancellationException) throw e
                } finally {
                    if (hold) releaseHeld(ok)
                    releaseSlot()
                }
            }
            Unit
        }
        if (slotBusy) {
            queue.addLast(run)
        } else {
            slotBusy = true
            run()
        }
        result.await()
    }

    private fun releaseSlot() {
        val next = queue.removeFirstOrNull()
        if (next != null) {
            next()
            return
        }
        slotBusy = false
        if (refreshQueued) {
            refreshQueued = false
            if (retryTimer == null) triggerNow(RefreshSource.TIMER) // 正在等重试时不提前，到点的定时器会发
        }
    }

    /** 换 sid 的操作。等响应期间退出了就不用这个响应（F-ACC-04 规则 1：退出优先）。 */
    private suspend fun rotateInSlot(path: String, body: JsonObject): AuthResult {
        val e = epoch
        val resp = parseAuthResponse(inSlot("POST", path, body))
        if (e != epoch) throw SessionEndedError()
        return applyAuth(resp, keepMemoryOnly = true) // 恢复码还没确认时，退出其他设备换来的 token 同样只放内存
    }

    /** 队列内的请求：要先刷新时直接在自己的 slot 里刷新，不重新排队，否则就等自己（RS5-15）。 */
    private suspend fun inSlot(method: String, path: String, body: JsonElement?): JsonElement {
        var i = 0
        while (true) {
            val s = session_ ?: throw SessionEndedError()
            if (!shortOnArrival && clock.now() >= s.accessExpiresAt) refreshInSlot()
            val token = session_?.accessToken ?: throw SessionEndedError()
            try {
                return sendAuthed(method, path, body, token)
            } catch (e: AccountError) {
                if (i >= HTTP_4011_RETRIES || e.code != 4011) throw e
                val out = record(if (isLocalNewer(lineage, lineageOf(token).asRejected())) SessionOutcome.UseLocal else SessionOutcome.None)
                if (out != SessionOutcome.UseLocal) refreshInSlot()
            }
            i++
        }
    }

    private suspend fun refreshInSlot() {
        clearRetryTimer()
        mustRefresh = true
        val (outcome, done) = beginRefresh()
        record(outcome)
        when (done.await()) {
            RefreshResult.ENDED -> throw SessionEndedError()
            RefreshResult.RETRY -> throw lastFailure ?: AccountNetworkError()
            RefreshResult.OK -> Unit
        }
    }

    // ---- 账号接口 ----

    /** GET /v1/config。失败时不猜测模式，由调用方处理（F-ACC-07 规则 4）。 */
    public suspend fun getConfig(): AccountConfig = onAccount { decode(AccountConfig.serializer(), send("GET", "/v1/config")) }

    public suspend fun register(username: String, password: String, nickname: String? = null, inviteCode: String? = null): AuthResult = enqueue {
        val body = buildJsonObject {
            put("username", username)
            put("password", password)
            nickname?.let { put("nickname", it) }
            inviteCode?.let { put("inviteCode", it) }
            deviceFields()
        }
        applyAuth(parseAuthResponse(send("POST", "/v1/account/register", body)))
    }

    public suspend fun login(username: String, password: String): AuthResult = enqueue {
        val body = buildJsonObject {
            put("username", username)
            put("password", password)
            deviceFields()
        }
        applyAuth(parseAuthResponse(send("POST", "/v1/account/login", body)))
    }

    /** 找回（F-ACC-05）。成功后换 sid，响应带新恢复码。 */
    public suspend fun recover(username: String, recoveryCode: String, newPassword: String): AuthResult = enqueue(hold = true) {
        val body = buildJsonObject {
            put("username", username)
            put("recoveryCode", recoveryCode)
            put("newPassword", newPassword)
            deviceFields()
        }
        applyAuth(parseAuthResponse(send("POST", "/v1/account/recover", body)))
    }

    /** 主动刷新一次（和其他入口合并）。token 换了才返回；30 秒内没换上时抛错，后台还会按规则继续重试。 */
    public suspend fun refresh(): Unit = onAccount {
        if (session_?.mode != SessionMode.ACCOUNT) throw SessionEndedError()
        val wait = waitForNewer(lineage.asRejected(), timeoutMs)
        mustRefresh = true
        record(decideRefresh(RefreshSource.FOREGROUND))
        wait.await()
    }

    /** 改密（F-ACC-04 规则 3）：进行中缓存踢线，成功先换 token（新 sid），再忽略旧 sid 的踢线；失败或超时才按踢线处理。 */
    public suspend fun changePassword(oldPassword: String, newPassword: String): AuthResult = enqueue(hold = true) {
        rotateInSlot(
            "/v1/account/password",
            buildJsonObject {
                put("oldPassword", oldPassword)
                put("newPassword", newPassword)
            },
        )
    }

    /** 退出其他设备（F-ACC-04 规则 6）：同改密的换 sid，不改密码。 */
    public suspend fun logoutOthers(password: String): AuthResult = enqueue(hold = true) {
        rotateInSlot("/v1/account/logout-others", buildJsonObject { put("password", password) })
    }

    /**
     * 退出（F-ACC-04 规则 1）：先通知服务端，再清本地；连不上也本地退出，sid 和 refresh 存为"待退出"，联网后补发。返回值表示服务端确认了。
     * 进行中的刷新作废，排队的操作失败。断网时最长等 30 秒：界面不要等它，先回登录页。
     */
    public suspend fun logout(): Boolean = onAccount {
        val s = session_ ?: return@onAccount true
        epoch += 1 // 下面写存储可能挂起：这期间回来的响应不能再采用
        abandonRefresh()
        if (s.mode != SessionMode.ACCOUNT) {
            endSession(SessionEnd(SessionEndAction.LOGOUT))
            return@onAccount true
        }
        val refresh = s.refreshToken
        val pending = refresh?.let { PendingLogout(lineage.sid, it) }
        val unacked = memoryOnly
        // 先记下来再清登录态：请求还没回来就被杀，重启后也不会回到登录状态，还能补发。恢复码没确认时 token 只放内存，断网才写。
        if (pending != null && !unacked) addPending(pending)
        clearStore()
        var confirmed = false
        var keep = false
        holdDepth += 1
        try {
            send("POST", PATH_LOGOUT, buildJsonObject { refresh?.let { put("refreshToken", it) } }, bearer = s.accessToken)
            confirmed = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("account: logout not confirmed: ${safeDescribe(e)}")
            keep = shouldRetryLogout(e)
        }
        holdDepth -= 1
        if (pending != null) {
            if (keep) {
                if (unacked) addPending(pending)
                revokeSystemToken()
            } else {
                removePending(pending)
            }
        }
        endSession(SessionEnd(SessionEndAction.LOGOUT))
        confirmed
    }

    /** 重新生成恢复码（F-ACC-05 规则 5）。token 不变。 */
    public suspend fun regenerateRecoveryCode(password: String): RecoveryCode = onAccount {
        val r = authed("POST", "/v1/account/recovery-code", buildJsonObject { put("password", password) }) as? JsonObject
        val code = r?.str("recoveryCode")?.takeIf { it.isNotEmpty() }
        val gen = r?.long("recoveryCodeGen")
        if (code == null || gen == null) throw AccountNetworkError("invalid response")
        RecoveryCode(code, gen)
    }

    /**
     * 确认已保存恢复码。确认前只放内存的 token 这时才落盘。
     * 收到 recoveryCodeStale：恢复码已更新，会话结束，回登录页（AC-ACC-05-15）。
     */
    public suspend fun ackRecoveryCode(gen: Long): Unit = onAccount {
        try {
            authed("POST", "/v1/account/recovery-code/ack", buildJsonObject { put("gen", gen) })
        } catch (e: AccountError) {
            if (e.reason == "recoveryCodeStale") {
                endSession(SessionEnd(SessionEndAction.LOGIN, SessionDialog.RECOVERY_CODE_STALE, reason = e.reason, code = e.code))
            }
            throw e
        }
        if (!memoryOnly) return@onAccount
        // 写到"存的就是在用的"才清标记：写的时候刷新还是只放内存，写失败抛给调用方，再调一次能重写（问题 S3）。
        // 写的时候会话结束：存储有锁，结束时的清除排在这次写入后面，所以不用再清。
        while (true) {
            val s = session_ ?: break
            storeLock.withLock { store?.save(s) }
            if (session_ === s) {
                memoryOnly = false
                break
            }
        }
    }

    /** 改资料（F-ACC-06）。本端立即显示；其他端按 10 分钟缓存更新。 */
    public suspend fun updateProfile(nickname: String? = null, avatarFileId: String? = null): Unit = onAccount {
        authed(
            "PATCH",
            "/v1/account/profile",
            buildJsonObject {
                nickname?.let { put("nickname", it) }
                avatarFileId?.let { put("avatarFileId", it) }
            },
        )
        val userId = session_?.userId
        if (userId != null && nickname != null) for (fn in profileListeners) guard { fn(userId, nickname) }
    }

    /** 最近登录记录和其他会话数量。 */
    public suspend fun listEvents(): AccountEvents = onAccount {
        val r = decode(AccountEvents.serializer(), authed("GET", "/v1/account/events"))
        if (r.recoveryCodeUnacked) emit(AccountEvent.RecoveryCodeUnacked)
        r
    }

    /** 申请注销（F-ACC-08）：成功后全部会话吊销，这里结束本地会话。进行中缓存踢线，失败才生效。 */
    public suspend fun requestDelete(password: String): Unit = enqueue(hold = true) {
        inSlot("POST", "/v1/account/delete", buildJsonObject { put("password", password) })
        endSession(SessionEnd(SessionEndAction.LOGIN, reason = "accountDeleted"))
    }

    /** 撤销注销：匿名接口。 */
    public suspend fun cancelDelete(username: String, password: String): Unit = onAccount {
        send(
            "POST",
            "/v1/account/delete/cancel",
            buildJsonObject {
                put("username", username)
                put("password", password)
            },
        )
    }

    /** 释放线程。之后这个对象不能再用。没有落盘的东西不用收尾：响应没回来的请求，下次启动按旧值重试（AC-ACC-03-20）。 */
    public fun close() {
        scope.cancel()
        executor?.shutdown()
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.deviceFields() {
        put("platform", o.platform.wire)
        o.deviceName?.let { put("deviceName", it) }
    }

    /** 不在队列里的带 Bearer 请求：带旧 token 得到 4011 时用本地值重试，或者等一次刷新（规则 7）。 */
    private suspend fun authed(method: String, path: String, body: JsonElement? = null): JsonElement {
        var i = 0
        while (true) {
            if (session_ == null) throw SessionEndedError()
            val token = accessTokenNow()
            try {
                return sendAuthed(method, path, body, token)
            } catch (e: AccountError) {
                if (i >= HTTP_4011_RETRIES || e.code != 4011) throw e
                val rejected = lineageOf(token).asRejected()
                val wait = waitForNewer(rejected, timeoutMs)
                http4011Now(rejected)
                wait.await()
            }
            i++
        }
    }

    private suspend fun sendAuthed(method: String, path: String, body: JsonElement?, token: String): JsonElement {
        try {
            return send(method, path, body, bearer = token)
        } catch (e: AccountError) {
            // access 已被吊销：和连接上的 4012 一样，回登录页。token 已经不是当前的（换过 sid）就不算。
            if ((e.code == 4012 || e.code == 4010) && token == session_?.accessToken && holdDepth == 0) {
                endSession(SessionEnd(SessionEndAction.LOGIN, SessionDialog.SESSION_EXPIRED, code = e.code))
            }
            throw e
        }
    }

    /**
     * 发一个请求。超时用 Clock；路径里没有凭证；日志只有方法、路径和错误码。
     * 刷新（inflight）有自己的超时和序号，其他请求在这里定时：迟到的响应到这里就结束了，到不了调用方。
     */
    private suspend fun send(
        method: String,
        path: String,
        body: JsonElement? = null,
        bearer: String? = null,
        inflight: Inflight? = null,
    ): JsonElement {
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/json"
        if (body != null) headers["Content-Type"] = "application/json"
        if (bearer != null) headers["Authorization"] = "Bearer $bearer"
        val req = HttpRequest(method, baseUrl + path, headers, body?.toString())
        if (inflight != null) return exchange(req, path) { inflight.call = it }
        val result = CompletableDeferred<JsonElement>()
        var call: HttpCall? = null
        val timer = clock.schedule(timeoutMs) {
            log.warn("account: $method $path timed out")
            call?.cancel()
            result.completeExceptionally(AccountTimeoutError())
        }
        launchNow {
            try {
                val v = exchange(req, path) { call = it }
                timer.cancel()
                result.complete(v)
            } catch (e: CancellationException) {
                result.completeExceptionally(e)
                throw e
            } catch (e: Exception) {
                timer.cancel()
                result.completeExceptionally(e)
            }
        }
        return result.await()
    }

    private suspend fun exchange(req: HttpRequest, path: String, onCall: (HttpCall) -> Unit): JsonElement {
        val done = CompletableDeferred<HttpResponse?>()
        val call = http.request(
            req,
            object : HttpCallback {
                override fun onResponse(response: HttpResponse) {
                    done.complete(response)
                }

                override fun onFailure() {
                    done.complete(null)
                }
            },
        )
        onCall(call)
        val r = done.await() ?: throw AccountNetworkError() // 不带原始错误：错误信息里可能有 URL
        val data: JsonElement? = try {
            if (r.text.isEmpty()) JsonObject(emptyMap()) else ImJson.parseToJsonElement(r.text)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (r.status in 200..299) return data ?: throw AccountNetworkError("invalid response")
        val e = data as? JsonObject
        val code = e?.int("code")
        if (code == null) {
            log.warn("account: ${req.method} $path status ${r.status}")
            throw AccountNetworkError("status ${r.status}") // 代理返回的页面不算服务端的判断，不能据此回登录页
        }
        log.debug("account: ${req.method} $path ${r.status} $code")
        throw AccountError(r.status, code, e.str("msg") ?: "", e.str("reason"), e["details"] as? JsonObject)
    }

    // ---- 待退出（F-ACC-04 规则 1） ----

    private suspend fun loadPending(): List<PendingLogout> {
        val ps = o.pendingLogoutStore ?: return memoryPending.toList()
        return storeLock.withLock { ps.load() }
    }

    private suspend fun savePending(items: List<PendingLogout>) {
        val ps = o.pendingLogoutStore
        if (ps == null) {
            memoryPending.clear()
            memoryPending.addAll(items)
        } else {
            storeLock.withLock { ps.save(items) }
        }
    }

    private suspend fun addPending(p: PendingLogout) {
        try {
            savePending((loadPending().filter { it != p } + p).takeLast(PENDING_LOGOUT_MAX))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("account: pending logout save failed: ${safeDescribe(e)}")
        }
    }

    private suspend fun removePending(p: PendingLogout) {
        try {
            savePending(loadPending().filter { it != p })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("account: pending logout save failed: ${safeDescribe(e)}")
        }
    }

    private suspend fun revokeSystemToken() {
        try {
            o.systemTokenRevoker?.revoke()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("account: system token revoke failed: ${safeDescribe(e)}")
        }
    }

    /** 服务端的判断（refresh 的 mac 不对等）重试也没用；网络、限流、5xx 要留着。 */
    private fun shouldRetryLogout(e: Exception): Boolean = e !is AccountError || e.code == 4290 || e.code >= 5000

    /** 一个外部事件（启动、回前台、联网）：重新开始补发，重试次数清零。 */
    private fun wakePendingLogouts() {
        flushTimer?.cancel()
        flushTimer = null
        flushFailures = 0
        flushPendingLogouts()
    }

    private fun flushPendingLogouts() {
        if (flushing) return
        flushing = true
        launchNow {
            var failed = false
            try {
                for (p in loadPending()) {
                    try {
                        send("POST", PATH_LOGOUT, buildJsonObject { put("refreshToken", p.refreshToken) })
                        removePending(p)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("account: pending logout not sent: ${safeDescribe(e)}")
                        if (shouldRetryLogout(e)) {
                            failed = true
                            break
                        }
                        removePending(p)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("account: pending logout load failed: ${safeDescribe(e)}")
            } finally {
                flushing = false
            }
            if (failed && flushFailures < PENDING_FLUSH_RETRIES) {
                val base = min(BACKOFF_BASE_MS * 2.0.pow(flushFailures), BACKOFF_MAX_MS.toDouble())
                flushFailures += 1
                flushTimer = clock.schedule((base * (1 + JITTER * (2 * random() - 1))).roundToLong()) {
                    flushTimer = null
                    flushPendingLogouts()
                }
            }
        }
    }

    // ---- 结束 ----

    private fun abandonRefresh() {
        val f = inflight ?: return
        f.call?.cancel()
        settleRefresh(f.attempt, RefreshResult.ENDED)
    }

    /** 清掉本地凭证，告诉所有等待的人。localGen 保留。 */
    private fun endSession(e: SessionEnd) {
        if (session_ == null) return
        epoch += 1
        _session.value = null
        memoryOnly = false
        shortOnArrival = false
        abandonRefresh()
        resetFailures()
        refreshQueued = false
        lifeTimer?.cancel()
        lifeTimer = null
        launchNow { clearStore() }
        val ws = waiters
        waiters = ArrayList()
        for (w in ws) {
            w.timer?.cancel()
            w.done.completeExceptionally(SessionEndedError())
        }
        val held = heldKicks
        heldKicks = ArrayList()
        // 已经结束了：缓存的踢线不用再处理，连接停在 stop 或者随后的 Fatal。
        for ((_, d) in held) d.complete(if (e.action == SessionEndAction.LOGOUT) KickDecision.IGNORE else KickDecision.PASS)
        _ended.value = e
        for (fn in endedListeners) guard { fn(e) }
        emit(AccountEvent.Ended(e))
    }

    private suspend fun clearStore() {
        try {
            storeLock.withLock { store?.clear() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("account: session clear failed: ${safeDescribe(e)}")
        }
    }

    private fun emit(e: AccountEvent) {
        eventTap?.invoke(e)
        _events.tryEmit(e)
    }

    private fun <T : SessionOutcome> record(o: T): T {
        emit(AccountEvent.Outcome(o))
        return o
    }

    private fun <T> decode(serializer: kotlinx.serialization.DeserializationStrategy<T>, data: JsonElement): T = try {
        ImJson.decodeFromJsonElement(serializer, data)
    } catch (_: IllegalArgumentException) {
        throw AccountNetworkError("invalid response") // 不带原始异常：序列化异常会带 JSON 片段
    }
}

private fun <F> subscribe(set: CopyOnWriteArraySet<F>, fn: F): () -> Unit {
    set.add(fn)
    return { set.remove(fn) }
}

private fun retryAfterMs(seconds: Int?): Long {
    val s = if (seconds != null && seconds > 0) min(seconds, RETRY_AFTER_MAX_S) else 1
    return s * 1000L
}

/** 响应的 details 带 retryAfterSeconds、recoverBefore（server/internal/account/refresh.go）。 */
private fun failureOf(err: Exception): RefreshFailure {
    if (err !is AccountError) return RefreshFailure(0)
    return RefreshFailure(err.code, err.reason, err.details?.int("retryAfterSeconds"), err.details?.long("recoverBefore"))
}

/** 响应不完整按网络错误处理：留在当前页面，不当成服务端的判断。 */
private fun parseAuthResponse(v: JsonElement): AuthResponse {
    val r = v as? JsonObject ?: throw AccountNetworkError("invalid response")
    val userId = r.str("userId")
    val access = r.str("accessToken")?.takeIf { it.isNotEmpty() }
    val refresh = r.str("refreshToken")?.takeIf { it.isNotEmpty() }
    val accessAt = r.long("accessExpiresAt")
    val refreshAt = r.long("refreshExpiresAt")
    val absolute = r.long("absoluteExpiresAt")
    if (userId == null || access == null || refresh == null || accessAt == null || refreshAt == null || absolute == null) {
        throw AccountNetworkError("invalid response")
    }
    return AuthResponse(
        userId, access, accessAt, refresh, refreshAt, absolute,
        r.str("recoveryCode")?.takeIf { it.isNotEmpty() },
        r.long("recoveryCodeGen"),
        (r["recoveryCodeUnacked"] as? JsonPrimitive)?.booleanOrNull == true,
    )
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
