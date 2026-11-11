package com.tianting.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

/** 不走 StateFlow 的一次性事件。 */
public sealed interface IMEvent {
    /** 某个会话的消息有变化。 */
    public data class MessagesChanged(val convId: String, val messages: List<Message>) : IMEvent

    /** 一条消息这一次发送失败（PRD-ui G-UI-22）：每次失败只发一次。 */
    public data class SendFailed(val convId: String, val clientMsgId: String, val error: SendError) : IMEvent

    /** 被移出群（服务端的 convRemoved，或重连 / 刷新时推断出来的，G-UI-21）。reason 透传服务端的值；推断出来的为 null。 */
    public data class ConvRemoved(val convId: String, val reason: String?) : IMEvent

    /** 禁言状态变了：用 muteState(convId) 读当前状态。 */
    public data object MuteChanged : IMEvent

    /** 群资料或成员变了（M4）：SDK 已经更新了本地会话；打开着群设置的页面直接用 info。 */
    public data class GroupChanged(val groupId: String, val info: GroupInfo) : IMEvent

    /** 被踢或收到不应重连的关闭码，连接已停止，需要回到登录页（C-01）。 */
    public data class Fatal(val reason: FatalReason) : IMEvent

    /**
     * 这些文件已过期或所在消息被删除（F-FILE-06 规则 3、AC-FILE-06-12）：SDK 已经丢掉它们的地址和下载缓存，
     * App 自己另外缓存过（例如图片库的内存缓存）的也要清掉。
     */
    public data class FilesExpired(val fileIds: List<String>) : IMEvent

    /**
     * 会话被删除（PRD-chat F-CVS-03、F-CVS-04 规则 1）：本端 deleteConv 成功，或同一账号的其他设备删除了它。本地消息已清除，
     * 页面清除这个会话的草稿（规则 7 的"清除本地草稿"按会话算，不只是操作的那台，frontend-chat 第 55 条）。
     */
    public data class ConvDeleted(val convId: String) : IMEvent

    /** 账号在新设备登录等安全提醒（PRD-chat F-ACC-02 规则 3b）。不认识的 kind 不发。 */
    public data class SecurityNotice(val kind: String, val at: Long, val platform: String?) : IMEvent

    /** auth ack 说最新一代恢复码没有确认（F-ACC-05 规则 1）：App 强制进保存页。 */
    public data object RecoveryCodeUnacked : IMEvent

    /** 某人的资料变了，读 displayName（F-ACC-06：自己改昵称后立即显示）。 */
    public data class ProfileChanged(val userId: String) : IMEvent

    /**
     * 正在下载的文件所在的消息被撤回（recalled = true，AC-RCL-03-12：提示"该消息已被撤回"）或被删除、过期（false）：
     * 下载已中止、下了一半的文件已删除，download 抛 DownloadAbortedError。
     */
    public data class DownloadAborted(val fileId: String, val recalled: Boolean) : IMEvent
}

/** 下载中途所在的消息被撤回（recalled）或删除、过期：已中止，部分文件已删除（AC-RCL-03-12）。 */
public class DownloadAbortedError(public val fileId: String, public val recalled: Boolean) :
    Exception(if (recalled) "download aborted: message recalled" else "download aborted: file unavailable")

/** 消息菜单里 SDK 判断的几项（F-UI-09 扩展，PRD-chat 第 13.3 节）。举报随 C6；失败消息的重发、删除由页面按 status 判断。 */
public enum class MessageAction { COPY, QUOTE, RECALL }

/** 撤回时限（PRD-chat F-RCL-01 规则 1、F-RCL-02 规则 2 的默认值）。服务端可以配置，不一致时以服务端的 4097 为准（问题 25）。 */
public const val RECALL_WINDOW_MS: Long = 120_000L
public const val GROUP_RECALL_WINDOW_MS: Long = 7 * 86_400_000L

public sealed interface SendResult {
    public data class Ok(val message: Message) : SendResult
    public data class Rejected(val reason: TextRejectReason, val over: Int) : SendResult
}

public data class ApplyFriendOptions(
    val message: String? = null,
    val source: ApplySource? = null,
    /** source=GROUP 时为 groupId。 */
    val sourceRef: String? = null,
    /** 对方同意后我给对方的备注。 */
    val remark: String? = null,
)

public enum class FriendAction(public val wire: String) { ACCEPT("accept"), REJECT("reject") }

/** 调试导出（AC-ADM-31-3）：内存里的会话和全部消息副本（含发送队列）。不包含 token。 */
@Serializable
public data class DebugExport(val conversations: List<Conversation>, val removed: List<String>, val messages: Map<String, List<Message>>)

public class IMClientOptions(
    /** WebSocket 地址，例如 wss://im.example.com/ws（不带 token，C-52）。 */
    public val url: String,
    public val platform: Platform = Platform.ANDROID,
    /** 本地库；为 null 时只在内存（测试、不需要离线缓存的场景）。SDK 负责打开后的建表和关闭。 */
    public val database: LocalDatabase? = null,
    public val transport: Transport? = null,
    public val clock: Clock? = null,
    public val random: () -> Double = { Random.nextDouble() },
    public val newId: () -> String = { UUID.randomUUID().toString() },
    public val logger: Logger = SilentLogger,
    public val timings: IMClientTimings = IMClientTimings(),
    /** App 是否在前台（已读上报依据）。前后台切换时调用 visibilityChanged()。 */
    public val isVisible: () -> Boolean = { true },
    /** SDK 的单线程调度器；为 null 时 SDK 自己开一条线程。测试时注入 TestDispatcher。 */
    public val dispatcher: CoroutineDispatcher? = null,
    /** 本机没有游标时从哪里开始（PRD-live C10）：LATEST 只取最新序号、不回放，灰度首次启动用。已有持久化游标时不生效。 */
    public val pushStart: PushStart = PushStart.BEGINNING,
    /** 推送游标的持久化，独立于 database（PRD-live 4.9 节）。为 null 时游标只在内存，每个新实例从 afterSeq=0 开始。 */
    public val pushCursorStore: PushCursorStore? = null,
    public val pushLimits: PushLimits = PushLimits(),
    /** 图片和文件（PRD-chat 第 13.1 节）。为 null 时 sendImage、sendFile、getFileUrls、download 抛 IllegalStateException。 */
    public val files: FileClient? = null,
    /**
     * 附件的沙盒目录（F-FILE-07 规则 2）：发送前 SDK 把 App 给的文件复制到这里，发出后删除，失败的保留供重发，退出登录时清空。
     * 只给 SDK 用（App 的私有目录下单独一个子目录，例如 filesDir/tianting-attachments）。sendImage、sendFile 需要它。
     */
    public val attachmentDir: java.io.File? = null,
    /** 下载缓存目录（F-FILE-04 规则 5：键是 fileId + variant），过期、被删时清除，退出登录时清空。download 需要它。 */
    public val fileCacheDir: java.io.File? = null,
    /** 复制文件用的调度器；默认 Dispatchers.IO，测试时注入 TestDispatcher。 */
    public val ioDispatcher: CoroutineDispatcher? = null,
)

/** sendImage、sendFile 的上传进度：已发送字节、总字节（原图 + 缩略图）。可以在任意线程回调。 */
public typealias UploadProgress = (sent: Long, total: Long) -> Unit

/** 缺口超过这么多条时，只加载最新一页，更早的靠向上翻页（C-24）。 */
private const val MAX_CATCH_UP = 200L
private const val PAGE = 200
private const val LATEST_PAGE = 20
private const val REFRESH_THROTTLE_MS = 30_000L // refreshConversations 的节流（G-UI-24）
private const val MUTE_TIMER_MAX_MS = 86_400_000L // 禁言最长 30 天，分段等，到点再算一次（设备休眠后定时器可能晚到）
private const val SOCIAL_DEBOUNCE_MS = 200L // socialChanged 去抖（F-SOC-06 规则 6）
private const val SOCIAL_RETRY_MS = 6_000L // socialFull 限流每 6 秒恢复一轮
private const val SOCIAL_PAGE = 500
private const val USERS_BATCH = 100
private const val USERS_MERGE_MS = 16L
// 这一批认识的 securityNotice：服务端实际发的全部（server/internal/account 的 notify 调用）。
private val SECURITY_NOTICE_KINDS = setOf(
    "newDeviceLogin", "passwordChanged", "passwordRecovered", "recoveryCodeRegenerated", "refreshReplay", "refreshRateLimited", "newIpPrefix",
)
private const val MAX_SAFE_INTEGER = 9007199254740991L
/** 提及计数的刷新节流（F-MEN-03 规则 1，未校准）：5 秒内最多拉一次 convs，窗口内的事件在窗口结束时补拉一次。 */
private const val MENTION_REFRESH_MS = 5_000L
private const val URL_REUSE_MARGIN_MS = 15_000L // 地址剩余不到这么久就不再复用，重新申请（下载要时间） // 打开未知会话时的 beforeSeq，与 sdk-ts 相同

/**
 * IM 统一 SDK 的入口（docs/sdk/README.md 的 IMClient）。不依赖任何 UI 框架：界面订阅 StateFlow / events、调 suspend 方法。
 *
 * 线程：SDK 内部所有状态都只在一条线程（dispatcher）上访问，行为与 sdk-ts 的事件循环一一对应。
 * suspend 方法可以从任意线程调用；StateFlow 和 messages(convId) 可以从任意线程读。
 *
 *     val im = IMClient(IMClientOptions(url = "wss://host/ws", database = AndroidLocalDatabase.open(context, "tianting.db")))
 *     im.conversations.collect(render)
 *     im.connect(token)
 */
public class IMClient(private val o: IMClientOptions) {
    private val executor: ExecutorService? = if (o.dispatcher == null) {
        Executors.newSingleThreadExecutor { r -> Thread(r, "tianting-sdk").apply { isDaemon = true } }
    } else {
        null
    }
    private val dispatcher: CoroutineDispatcher = o.dispatcher ?: executor!!.asCoroutineDispatcher()
    private val log = o.logger

    // 协程里漏出来的异常不能走默认处理：Android 上默认处理会让 App 崩溃。
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> log.error("im: unexpected ${safeDescribe(e)}") })
    private val t = o.timings
    private val clock: Clock = o.clock ?: CoroutineClock(scope)
    private val local: LocalDb? = o.database?.let { LocalDb(it, log) }
    private var store = Store({ self }, local ?: NoStoreSink)
    private val social = SocialState(local)
    private val profiles = ProfileCache { clock.now() }
    private val mutes = MuteTable()
    private var muteKey = muteKeyNow()
    private var muteTimer: Timer? = null

    @Volatile
    private var self = ""

    @Volatile
    private var serverOffset = 0L // 服务端时间 − 本地时间（auth ack 的 serverTime）

    private val autoUsed = HashSet<String>() // 已经自动重发过一次的 clientMsgId
    private val pendingProfileRefetch = LinkedHashSet<String>() // 改资料时没有连接：连上后重新拉（问题 H13）
    private val gapTimers = HashMap<String, Timer>()
    private var active: String? = null
    private var lastRead = Long.MIN_VALUE / 2
    private var readTimer: Timer? = null
    private var bannerTimer: Timer? = null
    private var refreshTimer: Timer? = null
    private var lastRefreshAt = Long.MIN_VALUE / 2
    private val knownLists = LinkedHashSet<String>()
    private var socialTimer: Timer? = null
    private var socialRunning: CompletableDeferred<Unit>? = null
    private var usersQueue = LinkedHashMap<String, MutableList<CompletableDeferred<Unit>>>()
    private val groupInfoInFlight = HashMap<String, Boolean>()
    private var wipedByFatal = false
    private var closed = false
    private var provider: TokenProvider? = null
    private var unsubscribeProvider: List<() -> Unit> = emptyList()
    private val io: CoroutineDispatcher = o.ioDispatcher ?: Dispatchers.IO
    private val files: FileClient? = o.files?.also { f ->
        f.random = o.random
        f.log = o.logger
    }
    private val sources = HashMap<String, Source>() // clientMsgId → 附件的源（沙盒副本或还没调用的处理函数）
    private val uploadQueue = ArrayDeque<Pair<String, String>>()
    private var job: UploadJob? = null
    private val modVers = HashMap<String, Long>() // 最近一次重拉时各会话的 modVer（F-RCL-03 规则 2）
    private val urlCache = HashMap<String, Pair<String, Long>>() // fileId + variant → 地址、到期时间（只在内存）
    private val urlWaiting = HashMap<String, CompletableDeferred<FileUrlResult>>()
    private val urlQueue = ArrayList<FileUrlItem>()
    private var urlFlushScheduled = false
    private var mentionFetchAt = Long.MIN_VALUE / 2
    private var mentionTimer: Timer? = null

    // 正在下载的文件：fileId → 这些下载的中止开关（撤回、删除、过期时中止，AC-RCL-03-12）。
    private class Download(val job: Job) {
        var abortedRecalled: Boolean? = null
    }
    private val downloads = HashMap<String, MutableSet<Download>>()

    private class Source(var rec: AttachmentRecord, var produce: (suspend () -> ProcessedImage)?, var onProgress: UploadProgress?)

    private class UploadJob(val convId: String, val clientMsgId: String) {
        var fileId: String? = null
        var completed = false
        var coroutine: Job? = null
    }

    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    private val _banner = MutableStateFlow(false)
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    private val _totalUnread = MutableStateFlow(0L)
    private val _pendingCount = MutableStateFlow(0)
    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    private val _friendRequests = MutableStateFlow(FriendRequests())
    private val _blocks = MutableStateFlow<List<BlockedUser>>(emptyList())
    private val _socialSettings = MutableStateFlow(SocialSettings())
    private val _socialLoaded = MutableStateFlow(false)
    private val _official = MutableStateFlow(false)
    private val _fatal = MutableStateFlow<FatalReason?>(null)
    private val _truncated = MutableStateFlow(false)
    private val _userId = MutableStateFlow("")
    private val _events = MutableSharedFlow<IMEvent>(extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val messageLock = Any()
    private val messageSnapshots = HashMap<String, List<Message>>()
    private val messageFlows = HashMap<String, MutableStateFlow<List<Message>>>()

    /** 测试用：同步观察事件（events 是 SharedFlow，没有订阅者时不缓存）。 */
    internal var eventTap: ((IMEvent) -> Unit)? = null

    /** 连接状态。 */
    public val state: StateFlow<ConnState> = _state.asStateFlow()

    /** 是否显示"连接中…/网络不可用"提示：断开持续超过 bannerDelayMs 才为 true，恢复后立即为 false。 */
    public val banner: StateFlow<Boolean> = _banner.asStateFlow()

    /** 会话列表（已排序，不可变）。 */
    public val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    /**
     * 总未读（与 conversations 同一轮更新，PRD-ui G-UI-15）：普通会话的 unread 之和 + 免打扰会话的 mentionUnread 之和
     * （PRD-chat F-CVS-02 规则 4，@ 我计入）。标签角标用它（AC-CVS-02-2、02-5）。
     */
    public val totalUnread: StateFlow<Long> = _totalUnread.asStateFlow()

    /** 发送中 / 排队 / 失败的消息总数（退出登录确认、离开页面提示用，G-UI-20）。 */
    public val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    /** 好友（不含墓碑）。 */
    public val friends: StateFlow<List<Friend>> = _friends.asStateFlow()

    /** "新的朋友"（30 天内，按 updatedAt 倒序）与红点数。 */
    public val friendRequests: StateFlow<FriendRequests> = _friendRequests.asStateFlow()
    public val blocks: StateFlow<List<BlockedUser>> = _blocks.asStateFlow()
    public val socialSettings: StateFlow<SocialSettings> = _socialSettings.asStateFlow()

    /** 是否完成过至少一轮关系同步（通讯录首轮未结束时显示骨架屏）。 */
    public val socialLoaded: StateFlow<Boolean> = _socialLoaded.asStateFlow()

    /** 我自己是否为官方账号。 */
    public val isOfficial: StateFlow<Boolean> = _official.asStateFlow()

    /** 最近一次致命断开的原因；connect 时清除。 */
    public val fatal: StateFlow<FatalReason?> = _fatal.asStateFlow()

    /** 最近一次会话列表是否被截断（只返回最近 500 个，C-03）。 */
    public val truncated: StateFlow<Boolean> = _truncated.asStateFlow()

    /** 当前用户（第一次连接成功后，或从本地库恢复后可用）。 */
    public val userId: StateFlow<String> = _userId.asStateFlow()

    /** 一次性事件。SharedFlow 没有回放：要在 connect 之前开始收集。 */
    public val events: SharedFlow<IMEvent> = _events.asSharedFlow()

    private val conn = Connection(
        url = o.url,
        platform = o.platform,
        transport = o.transport ?: OkHttpTransport(),
        clock = clock,
        log = log,
        random = o.random,
        t = t,
        post = { fn -> scope.launch { guard(fn) } },
        launch = { block -> launchNow(block) },
        listener = object : ConnectionListener {
            override fun onState(state: ConnState) = this@IMClient.onState(state)
            override fun onConnected(ack: AuthAck) = launchNow { this@IMClient.onConnected(ack) }.let { }
            override fun onFrame(frame: InboundFrame) = this@IMClient.onFrame(frame)
            override fun onFatal(reason: FatalReason) = this@IMClient.onFatal(reason)
        },
    )

    private val _pushSynced = MutableSharedFlow<Long>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val pushSync = PushSync(
        scope = scope,
        clock = clock,
        log = log,
        pushStart = o.pushStart,
        store = o.pushCursorStore,
        limits = o.pushLimits,
        request = { data -> conn.request("pushSync", data) },
        onSynced = { _pushSynced.tryEmit(it) },
    )

    /**
     * 系统推送（PRD-live 4.8 节）。只有这个流的收集者算订阅者：没有收集者时不补拉，带 pushSeq 的帧直接丢弃（之后靠补拉）。
     * 带 pushSeq 的至少一次，业务按 pushId 幂等；不带的至多一次。收集者处理完一条才交付下一条，一页交付完才前进游标、取下一页。
     * 收集者抛错只结束它自己，SDK 状态不受影响，重新 collect 可以继续收（F-PUSH-08 规则 2）。
     */
    public val pushes: Flow<Push> = pushSync.flow

    /** 一轮补拉结束，值是 max(latestSeq, 本轮已交付的最大 pushSeq)。订阅是可选的，不算 pushes 的订阅者（4.8 节）。 */
    public val pushSynced: Flow<Long> = _pushSynced.asSharedFlow()

    init {
        files?.clock = clock
        scope.launch { guard { restore() } }
    }

    // ---- 公开接口 ----

    /** 当前的推送游标（已交付的最大连续序号）。没有登录过，或者 pushStart=LATEST 还没拿到最新序号时为 null。 */
    public fun pushCursor(): Long? = pushSync.cursor

    /** 开始连接（演示模式）。token 只放在第一帧 auth 里（C-52）。 */
    public fun connect(token: String) {
        post {
            _fatal.value = null
            bindProvider(null)
            conn.start(token)
        }
    }

    /**
     * 账号模式：传 AccountClient.tokenProvider。自动刷新、换 token 重连、4011 比较代号（PRD-chat 第 13.1 节，F-ACC-03 规则 5、7）。
     */
    public fun connect(tokenProvider: TokenProvider) {
        post {
            _fatal.value = null
            bindProvider(tokenProvider)
            conn.start(tokenProvider)
        }
    }

    private fun bindProvider(p: TokenProvider?) {
        if (p === provider) return
        unsubscribeProvider.forEach { it() }
        provider = p
        unsubscribeProvider = if (p == null) {
            emptyList()
        } else {
            listOf(
                p.onEnded { e -> post { if (provider === p) onSessionEnded(e) } },
                p.onProfileChanged { userId, change ->
                    post {
                        val nickname = change.nickname
                        if (nickname != null) {
                            profiles.put((profiles.last(userId) ?: UserProfile(userId)).copy(nickname = nickname))
                            emit(IMEvent.ProfileChanged(userId))
                        }
                        // 头像地址由服务端生成，PATCH 的响应里没有，只有 users 能拿到：马上重新拉一次自己的资料（问题 H13）。
                        if (change.avatarChanged && provider === p) launchNow { refetchProfile(userId) }
                    }
                },
            )
        }
    }

    /** 账号会话结束：退出时清空数据（同 disconnect）；其他情况和被踢一样回登录页。来自踢线的，连接已经发过 Fatal。 */
    private fun onSessionEnded(e: SessionEnd) {
        if (e.fromKick) return
        bindProvider(null)
        conn.stop()
        if (e.action == SessionEndAction.LOGOUT) {
            wipeAfterLogout()
            return
        }
        onFatal(FatalReason.Session(e))
    }

    /** 退出登录：以 1000 关闭，不再重连，清空内存和本地库（PRD F-AUTH-05、FE-131）。 */
    public suspend fun disconnect() {
        onSdk {
            bindProvider(null)
            conn.stop()
            wipeAfterLogout()
        }
    }

    private fun wipeAfterLogout() {
        reset()
        clearFileState(deleteDirs = true) // 沙盒副本、下载缓存（AC-FILE-07-9）
        local?.let { db -> dbSafe { db.wipeAll() } }
        local?.paused = false
        wipedByFatal = false
        self = ""
        _userId.value = ""
    }

    /** 释放 SDK：断开连接（不清本地库，下次启动还能恢复）、关闭本地库、结束 SDK 线程。之后这个对象不能再用。 */
    public suspend fun close() {
        onSdk {
            if (closed) return@onSdk
            closed = true
            bindProvider(null)
            conn.stop()
            local?.close()
        }
        scope.cancel()
        executor?.shutdown()
    }

    /** 某个会话当前的消息（不可变快照，任意线程可读）。 */
    public fun messages(convId: String): List<Message> = synchronized(messageLock) { messageSnapshots[convId] } ?: emptyList()

    /** 某个会话的消息流。 */
    public fun messagesFlow(convId: String): StateFlow<List<Message>> = synchronized(messageLock) {
        messageFlows.getOrPut(convId) { MutableStateFlow(messageSnapshots[convId] ?: emptyList()) }
    }.asStateFlow()

    /**
     * 发送文本。先在本地插入一条"发送中"（离线时"排队"）的消息，ack 后更新为已发送。
     * 文本为空或超过 5000 字时不发送，返回原因。还不知道自己是谁（没连接成功过，本地库也没有）时抛 NotConnectedError；
     * 会话 ID 不合法时抛 IllegalArgumentException。
     * C4（PRD-chat 第 13.1 节）：quote 引用一条已发送的消息（F-QUO-01），上行只发 msgId，本地待发的消息用已加载的原消息先拼出引用条；
     * mentions 是 @ 的人（F-MEN-01、02），只在群聊，单聊带了抛 IllegalArgumentException；userIds 去重，全空且没有 all 时不带。
     * 正文里的 "@昵称 " 由 App 插入（用资料昵称，不用备注，R-CF-03）。4040（errorReason=quoteUnavailable）、4293（@ 所有人次数用完）、
     * 4131 和其他错误码一样标记失败，不自动重发。
     * 4510（内容被拦截，C6）：标记失败，error=SendError.Code(4510)，不自动重发，也不能手动重发（canRetry 为 false，frontend-chat 第 81 条）；
     * 错误里没有命中的词（服务端不给 details）。文案 messageFor("send", 4510)。sendImage、sendFile 同样，不重新上传。
     */
    public suspend fun send(convId: String, text: String, quote: QuoteRef? = null, mentions: Mentions? = null): SendResult = onSdk {
        when (val check = checkText(text)) {
            is TextCheck.Rejected -> SendResult.Rejected(check.reason, check.over)
            is TextCheck.Ok -> {
                if (self.isEmpty()) throw NotConnectedError()
                val parsed = parseConvId(convId, self) // 不合法的会话 ID 在这里抛出，不能留下一条永远"发送中"的消息
                val men = normalizeMentions(mentions)
                require(men == null || parsed.convType == ConvType.GROUP) { "mentions are only allowed in group conversations" }
                val msg = Message(
                    clientMsgId = o.newId(),
                    convId = convId,
                    from = self,
                    type = MsgType.TEXT,
                    body = MsgBody(text = check.text, quote = quote?.let { localQuote(convId, it.msgId) }, mentions = men),
                    ts = clock.now(),
                    status = if (conn.state == ConnState.CONNECTED) MessageStatus.SENDING else MessageStatus.QUEUED,
                )
                store.list(convId).addLocal(msg)
                emitMessages(convId)
                if (msg.status == MessageStatus.SENDING) dispatch(convId, msg.clientMsgId)
                SendResult.Ok(msg)
            }
        }
    }

    /** 本地待发消息里的引用条：被引用的消息已加载时按服务端同样的规则拼摘要（F-QUO-02 规则 2），否则只有 msgId。 */
    private fun localQuote(convId: String, msgId: String): QuoteView {
        val m = store.listIfExists(convId)?.findByMsgId(msgId) ?: return QuoteView(msgId, state = QuoteState.NORMAL)
        val base = QuoteView(msgId, m.seq, m.from, m.type, QuoteState.NORMAL)
        return when {
            m.deleted -> base.copy(state = if (m.recalled) QuoteState.RECALLED else QuoteState.DELETED)
            else -> base.copy(text = quoteSummary(m))
        }
    }

    // ---- 置顶、免打扰、删除会话（FE-681，PRD-chat 第 9 节 C5） ----

    /**
     * 置顶 / 取消置顶（F-CVS-01，第 13.1 节）。乐观更新：本地先改（新置顶的 pinnedAt 先用校正后的服务端时间，已置顶的再置顶不变），
     * ack 的 conv 覆盖本地；失败（5000、4220 置顶已满、4290、4030 开关关闭或没有权限、超时、未连接）回滚并抛出，
     * 不自动重试（frontend-chat 第 56 条）。等待期间其他端的 convSettingsChanged 改过的不回滚。
     */
    public suspend fun setConvPinned(convId: String, pinned: Boolean): Unit = onSdk {
        val data = buildJsonObject {
            put("convId", convId)
            put("pinned", pinned)
        }
        changeSettings(convId, data) { s ->
            if (pinned) s.copy(pinned = true, pinnedAt = if (s.pinned) s.pinnedAt else serverNow()) else s.copy(pinned = false, pinnedAt = null)
        }
    }

    /** 免打扰 / 取消免打扰（F-CVS-02）。乐观更新和失败处理同 setConvPinned。 */
    public suspend fun setConvNotifyOff(convId: String, on: Boolean): Unit = onSdk {
        val data = buildJsonObject {
            put("convId", convId)
            put("notifyOff", on)
        }
        changeSettings(convId, data) { it.copy(notifyOff = on) }
    }

    private suspend fun changeSettings(convId: String, data: JsonObject, next: (ConvSettings) -> ConvSettings) {
        if (self.isEmpty()) throw NotConnectedError()
        val before = store.settings(convId)
        val optimistic = before?.let(next)
        if (optimistic != null) {
            var changed = false
            store.transaction { changed = store.setSettings(convId, optimistic) }
            if (changed) emitConversations()
        }
        val ack = try {
            req("convSet", data, ConvSetAck.serializer())
        } catch (e: Exception) {
            // 回滚只在本地还是这次乐观的值时做：等待期间其他端的 convSettingsChanged 已经改过的不覆盖（后到的覆盖，F-CVS-04 异常）。
            if (before != null && optimistic != null && store.settings(convId) == optimistic) {
                var changed = false
                store.transaction { changed = store.setSettings(convId, before) }
                if (changed) emitConversations()
            }
            throw e
        }
        var purged = false
        store.transaction { purged = store.applyConvAck(ack.conv) }
        if (purged) emitMessages(convId)
        setGroupMute(ack.conv.convId, ack.conv.mutedUntil, ack.conv.allMuted)
        mutesChanged()
        emitConversations()
    }

    /**
     * 删除会话（F-CVS-03，第 13.1 节）：upToSeq 是本地看到的最大 seq。乐观更新：会话先从列表消失；成功后按 clearedSeq 物理清除
     * 本地消息、本地库的行和文件缓存（FilesExpired），这个会话还没发出的附件取消上传（DELETE 临时对象）、排队和失败的消息丢弃
     * （frontend-chat 第 59 条），然后发 ConvDeleted 让页面清草稿。失败时会话恢复、抛出，不自动重试（第 56 条）。确认框由页面弹。
     */
    public suspend fun deleteConv(convId: String): Unit = onSdk {
        if (self.isEmpty()) throw NotConnectedError()
        val upToSeq = maxOf(store.maxSeq(convId), store.conversation(convId)?.maxSeq ?: 0)
        store.hideForDelete(convId)
        emitConversations()
        val data = buildJsonObject {
            put("convId", convId)
            put("upToSeq", upToSeq)
        }
        val ack = try {
            req("convDelete", data, ConvDeleteAck.serializer())
        } catch (e: Exception) {
            if (store.cancelDelete(convId)) emitConversations()
            throw e
        }
        discardUnsent(convId)
        onConvCleared(convId, ack.clearedSeq)
    }

    /** 丢掉这个会话还没发出的消息：附件按 cancelUpload（已申请的发 DELETE），文本的排队、失败消息直接移除。发送中的不动。 */
    private suspend fun discardUnsent(convId: String) {
        val list = store.listIfExists(convId) ?: return
        for (m in store.messages(convId)) {
            if (m.seq != null) continue
            if (m.type == MsgType.IMAGE || m.type == MsgType.FILE) {
                cancelUpload(convId, m.clientMsgId)
            } else {
                var removed = false
                store.transaction { removed = list.removeUnsent(m.clientMsgId, setOf(MessageStatus.QUEUED, MessageStatus.FAILED)) }
                if (removed) dropSource(m.clientMsgId)
            }
        }
    }

    /**
     * 本端删除成功或收到其他端的 convDeleted（AC-CVS-04-5）：先在本地库记"待清除"，再清除、移除，通知页面清草稿。
     * 其他端删除时本机没发出的消息不丢（问题 63）：它们发出后 seq > clearedSeq，会话本来就会重新出现。
     */
    private fun onConvCleared(convId: String, clearedSeq: Long) {
        markPurges(mapOf(convId to clearedSeq + 1))
        store.transaction { store.clearConversation(convId, clearedSeq) }
        emitMessages(convId)
        emitConversations()
        emit(IMEvent.ConvDeleted(convId))
    }

    /** 清除之前在本地库记"待清除"（单独提交）：清除失败或进程在中间结束时，下次启动补清（F-CVS-03 异常）。 */
    private fun markPurges(mins: Map<String, Long>) {
        val db = local ?: return
        db.markPurges(store.purgeTargets(mins))
    }

    // ---- 举报、内容拦截（FE-691，PRD-chat 第 10 节 C6） ----

    /**
     * 举报（PRD-chat F-MOD-03，第 13.1 节）：返回 reportId。参数在本地先校验（targetType 互斥、msgIds 1～20 条且格式合法、
     * note 不超过 200 个码点、convId 严格格式，单聊自己必须是参与者，聊天室不能举报），不合法直接抛 IMError 4000（details.field），不发帧。
     * 服务端的错误原样抛出，不自动重试：4292（今天的举报次数已用完）、4040 reportTargetInvalid（"无法举报该内容"）、
     * 4030 featureDisabled（"举报功能暂不可用"）、4290、5000、TimeoutError、NotConnectedError；文案 messageFor("report", code)。
     * 被举报人、举报人都不会收到帧。入口是否显示看 GET /v1/config 的 features.moderation，消息菜单再看 canReport。
     * note 按正文对待：不进日志、不进错误信息。
     */
    public suspend fun report(target: ReportTarget, reason: ReportReason, note: String? = null): String =
        reportJson(reportData(target, reason, note))

    /** report 的实现：data 是 report 命令的 data（共享用例 frames.json 的非法 report 直接从这里进，本地拒绝）。 */
    internal suspend fun reportJson(data: JsonObject): String = onSdk {
        checkReport(data, self)
        req("report", data, ReportAck.serializer()).reportId
    }

    /**
     * 消息菜单里有没有"举报"（frontend-chat 第 77、88 条，三套 SDK 同一口径）：别人发的、已经发送成功的 text、image、file；
     * 自己的、tip、custom、发送中和失败的都没有；后台删除的没有。撤回提示行只在**别人**撤回的消息上有（隔离原文过期后点了
     * 仍会成功，证据只有元数据，AC-MOD-03-12），我自己（群主、管理员）撤回的别人的消息没有，别人撤回的我的消息也没有。
     * 只有已读范围内的（seq ≤ 会话的 readSeq，服务端按 readSeq ≥ seq 校验，问题 92）；会话不在本地列表里的一律没有。
     * 不看 features.moderation：开关关闭时页面不显示任何举报入口（第 76 条）。资料卡、群信息页的入口由页面判断。可以从任意线程调。
     */
    public fun canReport(m: Message): Boolean {
        val me = self
        if (m.status != MessageStatus.SENT || m.msgId == null || me.isEmpty() || m.from == me) return false
        if (m.type != MsgType.TEXT && m.type != MsgType.IMAGE && m.type != MsgType.FILE) return false
        // 只有看过的才能举报（服务端校验 readSeq ≥ seq，问题 92）；会话不在本地列表里的不显示。
        val seq = m.seq ?: return false
        val conv = _conversations.value.firstOrNull { it.convId == m.convId } ?: return false
        if (seq > conv.readSeq) return false
        if (m.recalled) return m.recalledBy != me
        return !m.deleted
    }

    /**
     * 失败的消息能不能"重发"（失败气泡的按钮）。4510 被拦截的不能（原样重发一定还是 4510，frontend-chat 第 81 条），
     * 只有"复制""删除"，冷启动后也一样（失败原因存在本地库里）；SourceMissing（原文件已不可用）也不能，只能删除。其余失败的可以。
     */
    public fun canRetry(m: Message): Boolean =
        m.status == MessageStatus.FAILED && m.error != SendError.Code(ErrorCode.CONTENT_BLOCKED) && m.error != SendError.SourceMissing

    // ---- 撤回（FE-661，PRD-chat F-RCL-01～03） ----

    /**
     * 撤回（PRD-chat F-RCL-01、02，第 13.1 节）。成功后本地立即按撤回处理：清除原文、引用它的原文、本地库和文件缓存
     * （服务端不给发起撤回的这条连接发 msgDeleted，只回 ack，问题 27）。失败抛 IMError，不自动重试（问题 26）：
     * 4097 带 details.windowSeconds（messageFor("recall", 4097, windowSeconds = …) 给文案）；4030 是没有权限
     * （"你没有权限撤回这条消息"，AC-RCL-02-5）；4044 消息不存在或已被后台删除。重复撤回是幂等成功。
     */
    public suspend fun recall(convId: String, msgId: String): Unit = onSdk {
        if (self.isEmpty()) throw NotConnectedError()
        reqRaw(
            "recall",
            buildJsonObject {
                put("convId", convId)
                put("msgId", msgId)
            },
        )
        val seq = store.listIfExists(convId)?.findByMsgId(msgId)?.seq
        applyDeleted(convId, listOf(msgId), listOfNotNull(seq), RecallInfo(self, serverNow()))
    }

    /**
     * 这条消息现在能不能撤回（菜单里显示"撤回"，AC-RCL-01-8）：按消息 ts 和 auth ack 校正后的服务端时间判断，不用设备时钟。
     * 自己的消息 2 分钟内；群主、管理员撤回自己的取更宽的 7 天；群主 7 天内任何人的；管理员 7 天内别人的
     * （对方是管理员或群主时服务端回 4030，客户端不知道对方角色，F-RCL-02 规则 1b）。
     * 只有已发送的 text、image、file；撤回、删除的消息和提示行不能。myRole 为 null 时用会话列表里的角色。可以从任意线程调。
     */
    public fun canRecall(m: Message, myRole: GroupRole? = null): Boolean {
        if (m.status != MessageStatus.SENT || m.msgId == null || m.deleted) return false
        if (m.type != MsgType.TEXT && m.type != MsgType.IMAGE && m.type != MsgType.FILE) return false
        val group = m.convId.startsWith("group:")
        val role = if (group) myRole ?: _conversations.value.firstOrNull { it.convId == m.convId }?.myRole else null
        val manager = role == GroupRole.OWNER || role == GroupRole.ADMIN
        val window = when {
            m.from == self -> if (manager) maxOf(RECALL_WINDOW_MS, GROUP_RECALL_WINDOW_MS) else RECALL_WINDOW_MS
            group && manager -> GROUP_RECALL_WINDOW_MS
            else -> return false
        }
        return serverNow() - m.ts <= window
    }

    /**
     * 长按菜单里由 SDK 判断的项（F-RCL-03 规则 6）：撤回、删除的消息和提示行没有菜单（空列表，AC-RCL-03-10）。
     * 复制只有文本；引用要已发送的 text、image、file（F-QUO-01 规则 5）；撤回按 canRecall。
     * 服务端关了 msgActions（GET /v1/config 的 features）时页面只留"复制"。可以从任意线程调。
     */
    public fun messageActions(m: Message, myRole: GroupRole? = null): List<MessageAction> {
        if (m.deleted || m.type == MsgType.TIP) return emptyList()
        val out = ArrayList<MessageAction>()
        if (m.type == MsgType.TEXT) out.add(MessageAction.COPY)
        if (m.status == MessageStatus.SENT && m.msgId != null && (m.type == MsgType.TEXT || m.type == MsgType.IMAGE || m.type == MsgType.FILE)) {
            out.add(MessageAction.QUOTE)
        }
        if (canRecall(m, myRole)) out.add(MessageAction.RECALL)
        return out
    }

    /**
     * 撤回文案要的判断数据（F-RCL-02 规则 1a，聊天页和列表摘要同一张表）：单聊 / 群、操作者是不是自己、被撤回的是不是自己的。
     * 不是撤回的返回 null（后台删除显示"该消息已被删除"）。可以从任意线程调。
     */
    public fun recallNotice(m: Message): RecallNotice? = recallNoticeOf(m.convId, m, self)

    /** 会话摘要的撤回文案数据（lastMsg）。 */
    public fun recallNotice(convId: String, last: LastMsg): RecallNotice? = recallNoticeOf(convId, last, self)

    /** 撤回文案（F-RCL-02 规则 1a 的表）：显示名按 displayName（备注只在本地显示，问题 19）。不是撤回的返回 null。 */
    public suspend fun recallText(m: Message): String? = onSdk {
        recallNotice(m)?.let { n -> com.tianting.sdk.recallText(n) { u -> displayNameNow(u) } }
    }

    public suspend fun recallText(convId: String, last: LastMsg): String? = onSdk {
        recallNotice(convId, last)?.let { n -> com.tianting.sdk.recallText(n) { u -> displayNameNow(u) } }
    }

    /**
     * 手动重发失败的消息，沿用原来的 clientMsgId（PRD F-MSG-03 规则 2）。
     * 附件（F-FILE-07 规则 4）：已经 complete 的复用同一 fileId，不重传（AC-FILE-07-2）；没上传完的用沙盒副本从头上传（新的 fileId，
     * AC-FILE-07-1、07-5）；沙盒副本已经不在的不动，error 改成 SourceMissing（"原文件已不可用，请重新选择"，只能删除）。
     * canRetry 为 false 的（4510、SourceMissing）不动：不发帧、不重新上传。
     */
    public suspend fun retry(convId: String, clientMsgId: String): Unit = onSdk {
        val m = store.listIfExists(convId)?.local(clientMsgId)
        if (m == null || !canRetry(m)) return@onSdk
        if (needsUpload(m)) {
            if (!hasSource(clientMsgId) || (sources[clientMsgId]?.rec?.copied == true && !withContext(io) { java.io.File(sandboxOf(clientMsgId), "original").isFile })) {
                store.list(convId).update(clientMsgId) { it.copy(error = SendError.SourceMissing, errorReason = null) }
                emitMessages(convId)
                return@onSdk
            }
            val st = if (sources[clientMsgId]?.rec?.copied == true) MessageStatus.UPLOADING else MessageStatus.PROCESSING
            store.list(convId).update(clientMsgId) { it.copy(status = st, error = null, errorReason = null) }
            emitMessages(convId)
            enqueueUpload(convId, clientMsgId)
            return@onSdk
        }
        if (conn.state == ConnState.CONNECTED) {
            dispatch(convId, clientMsgId)
        } else {
            autoUsed.add(clientMsgId) // 手动重发也只自动补发一次
            store.list(convId).update(clientMsgId) { it.copy(status = MessageStatus.QUEUED, error = null, errorReason = null) }
            emitMessages(convId)
        }
    }

    /**
     * 从本地移除一条失败的消息（PRD-ui G-UI-14）：不发任何帧，不影响服务端。只对失败状态有效，否则不动并返回 false。
     * 移除后 pendingCount 减 1；之后晚到的 ack、sync 不会让它再出现，冷启动后也不会（退出登录、被踢清库时这个记录一起清掉）。
     */
    public suspend fun removeFailed(convId: String, clientMsgId: String): Boolean = onSdk {
        val list = store.listIfExists(convId) ?: return@onSdk false
        var removed = false
        store.transaction { removed = list.removeFailed(clientMsgId) }
        if (removed) dropSource(clientMsgId)
        if (removed) emitMessages(convId)
        removed
    }

    // ---- 图片和文件（FE-631，PRD-chat F-FILE-01～07） ----

    /**
     * 发图片（第 13.1 节，F-FILE-02、07）：图片已经处理好写成文件。SDK 先把原图和缩略图复制进沙盒（attachmentDir，规则 2），
     * 插入一条"上传中"的消息并返回；然后按全局并发 1、选择顺序（规则 5、6）申请 → PUT 原图、缩略图 → complete，再按文本的规则 send。
     * 文字可以越过上传中的图片；上传中不算 10 秒发送超时（规则 8）。复制失败：失败，error=Storage。
     * 4292、4290（uploadPendingLimit）不自动重试（F-FILE-01 规则 6、6a）；complete 的 5032 退避重试 3 次。
     */
    public suspend fun sendImage(convId: String, processed: ProcessedImage, onProgress: UploadProgress? = null): Message = onSdk {
        val body = MsgBody(image = ImageBody("", processed.width, processed.height, processed.original.file.length(), processed.original.mime))
        val m = addAttachment(convId, MsgType.IMAGE, body, MessageStatus.UPLOADING, imageRecord(convId, "", processed, false), null, onProgress)
        if (copyIn(m.clientMsgId, processed.original.file, processed.thumb.file)) enqueueUpload(convId, m.clientMsgId)
        store.listIfExists(convId)?.local(m.clientMsgId) ?: m
    }

    /**
     * 发图片，图片还要处理：先插入一条"处理中"的消息并返回，轮到它时（并发 1）才调用 produce（在 SDK 线程上调用，
     * 解码、压缩要自己切到后台调度器），拿到结果后复制进沙盒再上传。produce 抛异常：失败，error=Process
     * （"无法处理这张图片，请用文件方式发送"）。width、height 是占位用的尺寸（可以为 null）。
     */
    public suspend fun sendImage(
        convId: String,
        width: Int?,
        height: Int?,
        produce: suspend () -> ProcessedImage,
        onProgress: UploadProgress? = null,
    ): Message = onSdk {
        val body = MsgBody(image = ImageBody("", width, height))
        val rec = AttachmentRecord(convId, "", "image", "image", "image/jpeg")
        val m = addAttachment(convId, MsgType.IMAGE, body, MessageStatus.PROCESSING, rec, produce, onProgress)
        enqueueUpload(convId, m.clientMsgId)
        m
    }

    /** 发文件（F-FILE-03、07）：内容不重新编码。其余同 sendImage。 */
    public suspend fun sendFile(convId: String, file: FileInput, onProgress: UploadProgress? = null): Message = onSdk {
        val body = MsgBody(file = FileBody("", file.name, file.file.length(), file.mime))
        val rec = AttachmentRecord(convId, "", "file", file.name, file.mime)
        val m = addAttachment(convId, MsgType.FILE, body, MessageStatus.UPLOADING, rec, null, onProgress)
        if (copyIn(m.clientMsgId, file.file, null)) enqueueUpload(convId, m.clientMsgId)
        store.listIfExists(convId)?.local(m.clientMsgId) ?: m
    }

    /**
     * 取消一条还没发出的附件（AC-FILE-02-5、AC-FILE-01-17）：中止上传，已经申请过的发 DELETE /v1/files/uploads/{id}，
     * 消息从列表消失，不发 send，沙盒副本删除。已经在 send 的不能取消，返回 false。失败的附件也可以用它删除。
     */
    public suspend fun cancelUpload(convId: String, clientMsgId: String): Boolean = onSdk {
        val list = store.listIfExists(convId) ?: return@onSdk false
        val m = list.local(clientMsgId) ?: return@onSdk false
        if (m.type != MsgType.IMAGE && m.type != MsgType.FILE) return@onSdk false
        var removed = false
        val statuses = setOf(MessageStatus.PROCESSING, MessageStatus.UPLOADING, MessageStatus.QUEUED, MessageStatus.FAILED)
        store.transaction { removed = list.removeUnsent(clientMsgId, statuses) }
        if (!removed) return@onSdk false
        val j = job
        val fileId = if (j != null && j.clientMsgId == clientMsgId) {
            j.coroutine?.cancel()
            job = null
            j.fileId
        } else {
            m.body.fileId?.takeIf { it.isNotEmpty() }
        }
        if (fileId != null) launchNow { files?.cancelUpload(fileId) }
        uploadQueue.removeAll { it.second == clientMsgId }
        dropSource(clientMsgId)
        emitMessages(convId)
        pumpUploads()
        true
    }

    /**
     * 取下载地址（F-FILE-04）。SDK 线程上同一轮的调用合并成一批（每批 ≤ 50，规则 6），没过期的地址用内存缓存（AC-FILE-04-10）。
     * 已知过期的文件不发请求，直接 code=4048（AC-FILE-06-6）。地址不要持久化、打日志（规则 5）。整批失败时抛出。
     */
    public suspend fun getFileUrls(items: List<FileUrlItem>): List<FileUrlResult> = onSdk {
        requireFiles()
        val waits = items.map { item ->
            if (store.fileExpired(item.fileId)) return@map CompletableDeferred(FileUrlResult(item.fileId, item.variant, code = 4048))
            val key = urlKey(item)
            val hit = urlCache[key]
            if (hit != null && hit.second - clock.now() > URL_REUSE_MARGIN_MS) {
                return@map CompletableDeferred(FileUrlResult(item.fileId, item.variant, hit.first, hit.second))
            }
            urlWaiting.getOrPut(key) {
                urlQueue.add(item)
                if (!urlFlushScheduled) {
                    urlFlushScheduled = true
                    scope.launch { guard { flushUrls() } }
                }
                CompletableDeferred()
            }
        }
        waits.map { it.await() }
    }

    /**
     * 下载一个文件或缩略图到缓存（第 13.2 节：App 内 OkHttp 到私有缓存，键 fileId + variant，F-FILE-04 规则 5）。
     * 已经缓存的直接返回（AC-FILE-04-10）。地址过期（对象存储 403）重新申请一次（F-FILE-03 异常）。
     * 失败时删掉下了一半的文件、丢掉地址，再调一次就从 0 开始、重新申请地址（AC-FILE-03-5）；取消调用方的协程即中止下载。
     * 不可访问、已过期：IMError(4048)；对象存储或网络失败：ObjectTransferError / AccountNetworkError。onProgress 可以在任意线程回调。
     */
    public suspend fun download(fileId: String, variant: FileVariant, onProgress: ((Long) -> Unit)? = null): java.io.File = onSdk {
        val f = requireFiles()
        val dir = o.fileCacheDir ?: throw IllegalStateException("fileCacheDir not configured")
        val dest = cacheFile(dir, fileId, variant)
        if (dest.isFile) return@onSdk dest
        if (store.fileExpired(fileId)) throw IMError(4048, "file unavailable")
        val item = FileUrlItem(fileId, variant)
        var attempt = 0
        while (true) {
            val r = getFileUrls(listOf(item)).first()
            val url = r.url ?: throw IMError(r.code ?: 4048, "file unavailable")
            withContext(io) { dir.mkdirs() }
            val tmp = java.io.File(dir, dest.name + ".part" + o.newId().filter { it.isLetterOrDigit() }.take(12))
            try {
                var d: Download? = null
                try {
                    // 传输放在子协程里：所在消息被撤回、删除时只中止它（forgetFiles），调用方的协程不受影响。
                    kotlinx.coroutines.coroutineScope {
                        val transfer = async { f.get(url, tmp) { onProgress?.invoke(it) } }
                        d = Download(transfer).also { downloads.getOrPut(fileId) { HashSet() }.add(it) }
                        transfer.await()
                    }
                } catch (e: CancellationException) {
                    val r = d?.abortedRecalled ?: throw e
                    throw DownloadAbortedError(fileId, r) // 调用方自己没有取消
                } finally {
                    downloads[fileId]?.let { set ->
                        set.remove(d)
                        if (set.isEmpty()) downloads.remove(fileId)
                    }
                }
                if (store.fileExpired(fileId)) throw IMError(4048, "file unavailable")
                withContext(io) { check(tmp.renameTo(dest) || (tmp.copyTo(dest, true).exists() && tmp.delete())) }
                return@onSdk dest
            } catch (e: Throwable) {
                withContext(kotlinx.coroutines.NonCancellable + io) { tmp.delete() }
                urlCache.remove(urlKey(item)) // 再试一次时重新申请地址
                if (e is ObjectTransferError && e.status == 403 && attempt == 0) {
                    attempt++
                    continue
                }
                throw e
            }
        }
        @Suppress("UNREACHABLE_CODE")
        dest
    }

    /** 已经缓存的文件（没有时为 null）。 */
    public suspend fun cachedFile(fileId: String, variant: FileVariant): java.io.File? = onSdk {
        o.fileCacheDir?.let { cacheFile(it, fileId, variant) }?.takeIf { withContext(io) { it.isFile } }
    }

    private fun requireFiles(): FileClient = files ?: throw IllegalStateException("files not configured: pass IMClientOptions.files")

    private fun urlKey(i: FileUrlItem) = "${i.fileId}\u0000${i.variant.wire}"

    private suspend fun flushUrls() {
        urlFlushScheduled = false
        val all = ArrayList(urlQueue)
        urlQueue.clear()
        for (batch in all.chunked(URLS_BATCH)) {
            launchNow {
                try {
                    val res = requireFiles().urls(batch)
                    for (r in res) {
                        val key = urlKey(FileUrlItem(r.fileId, r.variant))
                        if (r.url != null && r.expiresAt != null) urlCache[key] = r.url to r.expiresAt
                        urlWaiting.remove(key)?.complete(r)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("files: urls failed: ${if (e is IMError) e.code else "network"}")
                    for (i in batch) urlWaiting.remove(urlKey(i))?.completeExceptionally(e)
                }
            }
        }
    }

    private fun addAttachment(
        convId: String,
        type: MsgType,
        body: MsgBody,
        status: MessageStatus,
        rec: AttachmentRecord,
        produce: (suspend () -> ProcessedImage)?,
        onProgress: UploadProgress?,
    ): Message {
        requireFiles()
        if (o.attachmentDir == null) throw IllegalStateException("attachmentDir not configured")
        if (self.isEmpty()) throw NotConnectedError()
        parseConvIdStrict(convId, self)
        val msg = Message(clientMsgId = o.newId(), convId = convId, from = self, type = type, body = body, ts = clock.now(), status = status)
        val r = rec.copy(clientMsgId = msg.clientMsgId)
        store.transaction {
            store.list(convId).addLocal(msg)
            local?.saveAttachment(r)
        }
        sources[msg.clientMsgId] = Source(r, produce, onProgress)
        emitMessages(convId)
        return msg
    }

    private fun imageRecord(convId: String, clientMsgId: String, p: ProcessedImage, copied: Boolean) = AttachmentRecord(
        convId, clientMsgId, "image", p.name ?: "image", p.original.mime, p.thumb.mime, p.width, p.height, copied,
    )

    /** 沙盒目录：按 clientMsgId 的摘要命名（clientMsgId 可以是任何字符串，不能直接当路径）。 */
    private fun sandboxOf(clientMsgId: String): java.io.File {
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(clientMsgId.toByteArray()).joinToString("") { "%02x".format(it) }
        return java.io.File(o.attachmentDir!!, "a-" + h.take(32))
    }

    /** 复制进沙盒（规则 2）。失败：消息失败，error=Storage（"存储空间不足"）。返回是否成功。 */
    private suspend fun copyIn(clientMsgId: String, original: java.io.File, thumb: java.io.File?): Boolean {
        val dir = sandboxOf(clientMsgId)
        val ok = try {
            withContext(io) {
                dir.mkdirs()
                original.copyTo(java.io.File(dir, "original"), overwrite = true)
                thumb?.copyTo(java.io.File(dir, "thumb"), overwrite = true)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("files: copy to sandbox failed: ${safeDescribe(e)}")
            withContext(io) { dir.deleteRecursively() }
            false
        }
        val src = sources[clientMsgId]
        val m = src?.rec?.convId?.let { store.listIfExists(it)?.local(clientMsgId) }
        if (src == null || m == null) {
            withContext(io) { dir.deleteRecursively() } // 复制期间被取消了
            return false
        }
        if (!ok) {
            failAttachment(m.convId, clientMsgId, SendError.Storage)
            return false
        }
        src.rec = src.rec.copy(copied = true)
        local?.saveAttachment(src.rec)
        return true
    }

    private fun dropSource(clientMsgId: String) {
        val had = sources.remove(clientMsgId)
        local?.deleteAttachment(clientMsgId)
        if (o.attachmentDir != null) {
            val dir = sandboxOf(clientMsgId)
            scope.launch(io) { dir.deleteRecursively() }
        }
        had?.onProgress = null
    }

    private fun enqueueUpload(convId: String, clientMsgId: String) {
        uploadQueue.addLast(convId to clientMsgId)
        pumpUploads()
    }

    /** 全局一个上传（F-FILE-07 规则 6），按入队顺序（规则 5）。 */
    private fun pumpUploads() {
        if (job != null) return
        val next = uploadQueue.removeFirstOrNull() ?: return
        val j = UploadJob(next.first, next.second)
        job = j
        j.coroutine = launchNow {
            try {
                runUpload(j)
            } finally {
                if (job === j) {
                    job = null
                    pumpUploads()
                }
            }
        }
    }

    private suspend fun runUpload(j: UploadJob) {
        val f = requireFiles()
        val convId = j.convId
        val id = j.clientMsgId
        fun alive() = job === j && store.listIfExists(convId)?.local(id) != null
        val src = sources[id] ?: return
        if (!alive()) return
        if (!src.rec.copied) {
            val produce = src.produce
            if (produce == null) {
                failAttachment(convId, id, SendError.SourceMissing)
                return
            }
            val img = try {
                produce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (alive()) failAttachment(convId, id, SendError.Process)
                return
            }
            if (!alive()) return
            src.rec = imageRecord(convId, id, img, false)
            src.produce = null
            store.list(convId).update(id) {
                it.copy(
                    status = MessageStatus.UPLOADING,
                    body = MsgBody(image = ImageBody("", img.width, img.height, img.original.file.length(), img.original.mime)),
                )
            }
            emitMessages(convId)
            if (!copyIn(id, img.original.file, img.thumb.file) || !alive()) return
        } else {
            store.list(convId).update(id) { it.copy(status = MessageStatus.UPLOADING) }
            emitMessages(convId)
        }
        val rec = src.rec
        val dir = sandboxOf(id)
        val main = java.io.File(dir, "original")
        val thumb = if (rec.kind == "image") java.io.File(dir, "thumb") else null
        val (mainSize, thumbSize) = withContext(io) { main.length() to (thumb?.length() ?: 0L) }
        if (withContext(io) { !main.isFile || (thumb != null && !thumb.isFile) }) {
            failAttachment(convId, id, SendError.SourceMissing)
            return
        }
        val total = mainSize + thumbSize
        try {
            val up = f.createUpload(
                UploadRequest(rec.kind, rec.name, mainSize, rec.mime, if (thumb != null) thumbSize else null, if (thumb != null) rec.thumbMime ?: rec.mime else null),
            )
            j.fileId = up.fileId
            if (!alive()) return
            f.put(up.put, main) { n -> src.onProgress?.invoke(n, total) }
            if (thumb != null) {
                val tp = up.thumbPut ?: throw ObjectTransferError(0)
                if (!alive()) return
                f.put(tp, thumb) { n -> src.onProgress?.invoke(mainSize + n, total) }
            }
            if (!alive()) return
            val done = f.complete(up.fileId)
            j.completed = true
            // 记下"这台设备自己上传的 fileId"（规则 4）：之后 send 回 4048 才按已清理重新上传。
            src.rec = src.rec.copy(uploadedFileId = done.fileId)
            if (sources[id] === src) local?.saveAttachment(src.rec)
            if (!alive()) return
            store.list(convId).update(id) { m ->
                val body = if (rec.kind == "image") {
                    MsgBody(
                        image = (m.body.image ?: ImageBody("")).copy(
                            fileId = done.fileId, size = done.size, mime = done.mime,
                            width = done.width ?: m.body.image?.width, height = done.height ?: m.body.image?.height,
                        ),
                    )
                } else {
                    MsgBody(file = (m.body.file ?: FileBody("")).copy(fileId = done.fileId, size = done.size, mime = done.mime))
                }
                // 待发送（规则 1）：在线就发，否则排队，连上后按现有规则自动发一次（规则 3）。
                m.copy(body = body, status = if (conn.state == ConnState.CONNECTED) m.status else MessageStatus.QUEUED)
            }
            if (conn.state == ConnState.CONNECTED) dispatch(convId, id) else emitMessages(convId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!alive()) return
            // 没完成的上传尽力取消，立刻释放未完成名额（F-FILE-01 规则 6a）。
            val fid = j.fileId
            if (fid != null && !j.completed) launchNow { f.cancelUpload(fid) }
            when (e) {
                is IMError -> failAttachment(convId, id, SendError.Code(e.code), (e as? AccountError)?.reason)
                else -> failAttachment(convId, id, SendError.Network)
            }
        }
    }

    private fun failAttachment(convId: String, clientMsgId: String, error: SendError, reason: String? = null) {
        val list = store.listIfExists(convId) ?: return
        val m = list.local(clientMsgId) ?: return
        if (m.seq != null || m.status == MessageStatus.FAILED) return
        list.update(clientMsgId) { it.copy(status = MessageStatus.FAILED, error = error, errorReason = reason) }
        emitMessages(convId)
        emit(IMEvent.SendFailed(convId, clientMsgId, error))
    }

    /** 附件消息还没有 fileId（没上传完）。 */
    private fun needsUpload(m: Message): Boolean =
        (m.type == MsgType.IMAGE || m.type == MsgType.FILE) && m.body.fileId.isNullOrEmpty()

    /** 源还在：沙盒副本已复制，或者处理函数还在内存里。 */
    private fun hasSource(clientMsgId: String): Boolean {
        val s = sources[clientMsgId] ?: return false
        return s.produce != null || s.rec.copied
    }

    /**
     * send 的错误是不是"文件已被清理"（F-FILE-07 规则 4）：4096；或者 4048 且 fileId 是这台设备自己上传得到的
     * （24 小时没绑定的上传被 file_purge 删了行，服务端分不清，v1.4，P-37 ①）。别人的 fileId（转发等）回 4048 照常失败。
     */
    private fun purgedOnServer(code: Int, clientMsgId: String, m: Message): Boolean {
        if (code == ErrorCode.FILE_NOT_READY) return true
        if (code != ErrorCode.FILE_NOT_FOUND) return false
        val fileId = m.body.fileId
        return !fileId.isNullOrEmpty() && sources[clientMsgId]?.rec?.uploadedFileId == fileId
    }

    /**
     * send 返回 4096（文件超过 24 小时被清理），或自己上传的 fileId 返回 4048：有沙盒副本的回到上传中、从头上传，不算自动重发次数（RC2-23）；
     * 没有的转失败，error=SourceMissing（F-FILE-07 规则 4，AC-FILE-07-10）。
     */
    private fun reupload(convId: String, clientMsgId: String) {
        val list = store.list(convId)
        val clear: (Message) -> Message = { m ->
            m.copy(body = if (m.body.image != null) MsgBody(image = m.body.image.copy(fileId = "")) else MsgBody(file = m.body.file?.copy(fileId = "")))
        }
        if (!hasSource(clientMsgId)) {
            list.update(clientMsgId, clear)
            fail(convId, clientMsgId, SendError.SourceMissing)
            return
        }
        list.update(clientMsgId) { clear(it).copy(status = MessageStatus.UPLOADING, error = null, errorReason = null) }
        emitMessages(convId)
        enqueueUpload(convId, clientMsgId)
    }

    private fun cacheFile(dir: java.io.File, fileId: String, variant: FileVariant): java.io.File {
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(fileId.toByteArray()).joinToString("") { "%02x".format(it) }
        return java.io.File(dir, "f-${h.take(40)}-${variant.wire}")
    }

    /** 过期或被删（含撤回）的文件：中止进行中的下载，丢掉地址和下载缓存，通知 App（AC-FILE-06-12、AC-RCL-03-12）。 */
    private fun forgetFiles(ids: List<String>, recalled: Set<String> = emptySet()) {
        if (ids.isEmpty()) return
        for (id in ids) {
            val running = downloads.remove(id) ?: continue
            for (d in running) {
                d.abortedRecalled = id in recalled
                d.job.cancel()
            }
            emit(IMEvent.DownloadAborted(id, id in recalled))
        }
        for (id in ids) for (v in FileVariant.entries) urlCache.remove(urlKey(FileUrlItem(id, v)))
        o.fileCacheDir?.let { dir ->
            val targets = ids.flatMap { id -> FileVariant.entries.map { cacheFile(dir, id, it) } }
            scope.launch(io) { targets.forEach { it.delete() } }
        }
        emit(IMEvent.FilesExpired(ids))
    }

    /** 退出登录：沙盒副本和下载缓存全部删除（AC-FILE-07-9），上传中止。 */
    private fun clearFileState(deleteDirs: Boolean) {
        uploadQueue.clear()
        job?.coroutine?.cancel()
        job?.let { j -> j.fileId?.takeIf { !j.completed }?.let { fid -> files?.let { f -> scope.launch { f.cancelUpload(fid) } } } }
        job = null
        sources.clear()
        urlCache.clear()
        urlWaiting.values.forEach { it.completeExceptionally(SessionEndedError()) }
        urlWaiting.clear()
        urlQueue.clear()
        modVers.clear()
        if (deleteDirs) {
            val dirs = listOfNotNull(o.attachmentDir, o.fileCacheDir)
            scope.launch(io) { dirs.forEach { d -> d.listFiles()?.forEach { it.deleteRecursively() } } }
        }
    }

    /** 这个会话当前的禁言：没有被禁言时为 null。按服务端时间判断到期；用户级优先，群内禁言只影响该群；全群禁言不限制群主、管理员。 */
    public suspend fun muteState(convId: String): MuteInfo? = onSdk { mutes.get(convId, serverNow(), store.conversation(convId)?.myRole) }

    /** 调试接口（AC-ADM-31-3）：导出内存里的会话和全部消息副本（含发送队列）。不包含 token。 */
    public suspend fun debugExport(): DebugExport = onSdk {
        DebugExport(store.debugConversations(), store.debugSnapshots().map { it.convId }, store.debugMessages())
    }

    /**
     * 向前翻页加载历史（PRD F-MSG-05）：第一次从最新开始（beforeSeq = maxSeq + 1，20 条），
     * 之后从已加载的最早一条继续往前。返回是否还有更早的。
     */
    public suspend fun loadHistory(convId: String, limit: Int = LATEST_PAGE): Boolean = onSdk {
        val list = store.list(convId)
        val c = store.conversation(convId)
        if (!list.hasMoreHistory) return@onSdk false
        if (c == null && list.oldestSeq == 0L && store.maxSeq(convId) == 0L) return@onSdk loadUnknown(convId, limit)
        val before = if (list.oldestSeq != 0L) list.oldestSeq else maxOf(c?.maxSeq ?: 0, store.maxSeq(convId)) + 1
        if (before <= store.minSeqOf(convId)) {
            list.hasMoreHistory = false
            return@onSdk false
        }
        val ack = syncReq(convId, before, limit)
        applyAll(ack.messages)
        list.hasMoreHistory = ack.hasMore
        emitMessages(convId)
        emitConversations()
        ack.hasMore
    }

    /**
     * 打开本地没有的会话（PRD-ui G-UI-26）：向服务端要最新一页，不按本地 minSeq 提前返回。
     * 4032 / 4033 / 4043 以 IMError 抛给界面，不吞、不重试；有消息时把会话补进列表；单聊没有消息时保持本地临时会话。
     */
    private suspend fun loadUnknown(convId: String, limit: Int): Boolean {
        val list = store.list(convId)
        val ack = syncReq(convId, MAX_SAFE_INTEGER, limit)
        applyAll(ack.messages)
        list.hasMoreHistory = ack.hasMore
        emitMessages(convId)
        if (ack.messages.isNotEmpty()) {
            emitConversations()
            refreshConversationsSoon() // 用服务端数据替换临时补的会话
        }
        return ack.hasMore
    }

    private suspend fun syncReq(convId: String, before: Long, limit: Int): SyncAck =
        req(
            "sync",
            buildJsonObject {
                put("convId", convId)
                put("beforeSeq", before)
                put("limit", limit)
            },
            SyncAck.serializer(),
        )

    private fun applyAll(messages: List<ServerMessage>): Boolean {
        var changed = false
        store.transaction { for (m in messages) if (store.applyServerMessage(m)) changed = true }
        return changed
    }

    /** 设置当前打开的会话（null 表示不在聊天页），用于已读上报。 */
    public fun setActiveConversation(convId: String?) {
        post {
            active = convId
            maybeReportRead()
        }
    }

    /** App 前后台变化时调用。 */
    public fun visibilityChanged() {
        post {
            if (o.isVisible()) provider?.foreground()
            maybeReportRead()
            expireMutes() // 休眠后定时器可能晚到
        }
    }

    /** 网络恢复时调用：立即重连，不等退避（sdk-android 的 bindNetwork 自动调用）。 */
    public fun networkOnline() {
        post {
            conn.reconnectNow()
            expireMutes()
        }
    }

    /** 重新拉取会话列表（PRD-ui G-UI-24）。30 秒内只拉一次，节流期内直接返回当前快照。 */
    public suspend fun refreshConversations(): List<Conversation> = onSdk {
        if (clock.now() - lastRefreshAt < REFRESH_THROTTLE_MS || conn.state != ConnState.CONNECTED) {
            return@onSdk store.conversations()
        }
        lastRefreshAt = clock.now()
        applyConvs(req("convs", JsonObject(emptyMap()), ConvsAck.serializer()))
        store.conversations()
    }

    /** 被移出群后保留的快照（本次登录期间有效）；没有被移出时返回 null。 */
    public suspend fun conversationSnapshot(convId: String): ConversationSnapshot? = onSdk { store.snapshot(convId) }

    /** 按服务端时间的"现在"：申请过期、30 天、禁言到期都用它判断，不用本地时钟（PRD-core 3.1 节）。 */
    public fun serverNow(): Long = clock.now() + serverOffset

    // ---- 社交（PRD-social） ----

    /** 立即从本地 socialVer 同步一轮（例如 friendRespond 返回 4045 之后刷新状态，同时按服务端时间重算过期）。 */
    public suspend fun refreshSocial(): Unit = onSdk {
        social.needSync = true
        syncSocial().await()
    }

    /** 只看本地的关系（与 userSearch 的 relation 同一枚举）。 */
    public suspend fun relationOf(userId: String): Relation = onSdk { social.relationOf(userId, self, serverNow()) }

    /** 最近一次拿到的资料（缓存，可能已过期；没有为 null）。 */
    public suspend fun profileOf(userId: String): UserProfile? = onSdk { profiles.last(userId) }

    /** 显示名：好友备注 → 昵称 → userId（备注只在本机显示，PRD-social 5.5 节）。 */
    public suspend fun displayName(userId: String): String = onSdk { displayNameNow(userId) }

    private fun displayNameNow(userId: String): String {
        val f = social.friends[userId]
        if (f != null && !f.deleted && f.remark.isNotEmpty()) return f.remark
        return profiles.last(userId)?.nickname?.takeIf { it.isNotEmpty() } ?: userId
    }

    public suspend fun applyFriend(userId: String, options: ApplyFriendOptions = ApplyFriendOptions()): RequestStatus = onSdk {
        val ack = socialCmd(
            "friendApply",
            buildJsonObject {
                put("to", userId)
                options.message?.let { put("message", it) }
                options.source?.let { put("source", it.wire) }
                options.sourceRef?.let { put("sourceRef", it) }
                options.remark?.let { put("remark", it) }
            },
        )
        ack.status ?: RequestStatus.PENDING
    }

    public suspend fun respondFriend(userId: String, action: FriendAction, remark: String? = null): Unit = onSdk {
        socialCmd(
            "friendRespond",
            buildJsonObject {
                put("from", userId)
                put("action", action.wire)
                remark?.let { put("remark", it) }
            },
        )
    }

    public suspend fun deleteFriend(userId: String): Unit = onSdk { socialCmd("friendDelete", buildJsonObject { put("userId", userId) }) }

    public suspend fun setFriendRemark(userId: String, remark: String): Unit = onSdk {
        socialCmd(
            "friendRemark",
            buildJsonObject {
                put("userId", userId)
                put("remark", remark)
            },
        )
    }

    public suspend fun block(userId: String): Unit = onSdk { socialCmd("block", buildJsonObject { put("userId", userId) }) }

    public suspend fun unblock(userId: String): Unit = onSdk { socialCmd("unblock", buildJsonObject { put("userId", userId) }) }

    /** 进入"新的朋友"时调用：本地红点立即清零，上报当前 socialVer（F-SOC-04 规则 3）。 */
    public suspend fun markFriendRequestsSeen(): Unit = onSdk {
        val ver = social.ver
        if (ver <= social.requestSeenVer) return@onSdk
        social.requestSeenVer = ver
        local?.saveMeta(social)
        emitSocial()
        launchNow {
            try {
                val ack = req("friendRequestsSeen", buildJsonObject { put("ver", ver) }, SeenAck.serializer())
                store.transaction { social.applyAck(ack.ver, ack.changed, null) }
                afterSocialAck()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log.warn("im: friendRequestsSeen failed: ${safeDescribe(e)}")
            }
        }
    }

    public suspend fun setSearchable(searchable: Boolean): Unit = onSdk {
        val ack = req("setPrivacy", buildJsonObject { put("searchable", searchable) }, PrivacyAck.serializer())
        social.settings = social.settings.copy(searchable = searchable)
        store.transaction {
            local?.saveMeta(social)
            social.applyAck(ack.ver, ack.changed, null)
        }
        afterSocialAck()
    }

    /** 精确搜索 userId（不存在、不可被搜索都是 null）。结果里的资料写进缓存，供申请页 / 资料卡使用。 */
    public suspend fun searchUser(keyword: String): SearchedUser? = onSdk {
        val user = req("userSearch", buildJsonObject { put("keyword", keyword) }, SearchAck.serializer()).user
        user?.let { profiles.put(UserProfile(it.userId, it.nickname, it.avatar, it.official)) }
        user
    }

    /**
     * 批量查资料（F-SOC-10）：命中缓存的不请求；16ms 内的调用合并（一帧里各个列表项的请求），每批最多 100 个。
     * 返回可见的用户；不在可见范围内、不存在的不返回（两者无法区分）。
     */
    public suspend fun getUsers(userIds: List<String>): List<UserProfile> = onSdk {
        val ids = userIds.distinct()
        val missing = ids.filter { profiles.needsFetch(it) }
        if (missing.isNotEmpty()) {
            val first = usersQueue.isEmpty()
            val done = CompletableDeferred<Unit>()
            for (id in missing) usersQueue.getOrPut(id) { ArrayList() }.add(done)
            if (first) clock.schedule(USERS_MERGE_MS) { launchNow { flushUsers() } }
            done.await()
        }
        ids.mapNotNull { profiles.fresh(it) }
    }

    /**
     * 自己改了资料之后重新拉一次（问题 H13）：不看缓存有效期。没有连接时等连上再拉（这期间先显示本地的新昵称）。
     * 拿到后通知 App（ProfileChanged）；失败只记日志，之后照常按 10 分钟缓存刷新。
     */
    private suspend fun refetchProfile(userId: String) {
        if (conn.state != ConnState.CONNECTED) {
            pendingProfileRefetch.add(userId)
            return
        }
        try {
            val ack = req("users", buildJsonObject { put("userIds", buildJsonArray { add(JsonPrimitive(userId)) }) }, UsersAck.serializer())
            ack.users.firstOrNull { it.userId == userId }?.let { profiles.put(it) }
            emit(IMEvent.ProfileChanged(userId))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.warn("im: profile refetch failed: ${safeDescribe(e)}")
        }
    }

    private suspend fun flushUsers() {
        val queue = usersQueue
        usersQueue = LinkedHashMap()
        for (batch in queue.keys.toList().chunked(USERS_BATCH)) {
            try {
                val ack = req(
                    "users",
                    buildJsonObject { put("userIds", buildJsonArray { batch.forEach { add(JsonPrimitive(it)) } }) },
                    UsersAck.serializer(),
                )
                ack.users.forEach { profiles.put(it) }
                for (id in batch) if (profiles.fresh(id) == null) profiles.markMissing(id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log.warn("im: users failed: ${safeDescribe(e)}")
            }
            // 一个 getUsers 可能跨两批：等它所有的 id 都处理完才放行。
            for (id in batch) queue.getValue(id).forEach { d -> d.takeIf { pendingElsewhere(queue, batch, d).not() }?.complete(Unit) }
        }
    }

    private fun pendingElsewhere(
        queue: Map<String, List<CompletableDeferred<Unit>>>,
        doneBatch: List<String>,
        d: CompletableDeferred<Unit>,
    ): Boolean {
        val last = doneBatch.last()
        var after = false
        for ((id, waiters) in queue) {
            if (after && d in waiters) return true
            if (id == last) after = true
        }
        return false
    }

    /** 我的群聊（F-SOC-11）：自动翻完全部页，按 groupId 升序。 */
    public suspend fun loadMyGroups(): List<MyGroup> = onSdk {
        val out = ArrayList<MyGroup>()
        var cursor = ""
        while (true) {
            val ack = req(
                "myGroups",
                buildJsonObject { if (cursor.isNotEmpty()) put("cursor", cursor) },
                MyGroupsAck.serializer(),
            )
            out.addAll(ack.groups)
            if (ack.nextCursor.isEmpty()) break
            cursor = ack.nextCursor
        }
        out
    }

    // ---- 群（PRD-social M4） ----

    /** 建群：自动生成 clientReqId，超时重试用同一个（不会重复建群，F-SOC-21 规则 1）。 */
    public suspend fun createGroup(members: List<String>, name: String? = null): CreatedGroup = onSdk {
        val data = buildJsonObject {
            put("clientReqId", o.newId())
            put("members", buildJsonArray { members.forEach { add(JsonPrimitive(it)) } })
            if (!name.isNullOrEmpty()) put("name", name)
        }
        var attempt = 0
        while (true) {
            try {
                val ack = req("groupCreate", data, GroupCreateAck.serializer())
                store.upsertConversation(ack.conv)
                setGroupMute(ack.conv.convId, ack.conv.mutedUntil, ack.conv.allMuted)
                mutesChanged()
                emitConversations()
                return@onSdk CreatedGroup(ack.groupId, store.conversation(ack.conv.convId)!!)
            } catch (e: TimeoutError) {
                if (attempt >= 2) throw e
                attempt++
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException()
    }

    public suspend fun inviteToGroup(groupId: String, members: List<String>): GroupInviteResult = onSdk {
        req(
            "groupInvite",
            buildJsonObject {
                put("groupId", groupId)
                put("members", buildJsonArray { members.forEach { add(JsonPrimitive(it)) } })
            },
            GroupInviteResult.serializer(),
        )
    }

    /** 退群：本连接不会收到 convRemoved，本地直接按 left 处理。 */
    public suspend fun leaveGroup(groupId: String): Unit = onSdk {
        reqRaw("groupLeave", buildJsonObject { put("groupId", groupId) })
        removeLocally(groupId, "left")
    }

    public suspend fun kickFromGroup(groupId: String, members: List<String>): List<String> = onSdk {
        req(
            "groupKick",
            buildJsonObject {
                put("groupId", groupId)
                put("members", buildJsonArray { members.forEach { add(JsonPrimitive(it)) } })
            },
            GroupKickAck.serializer(),
        ).removed
    }

    public suspend fun transferGroupOwner(groupId: String, to: String): Unit = onSdk {
        reqRaw(
            "groupTransfer",
            buildJsonObject {
                put("groupId", groupId)
                put("to", to)
            },
        )
        onGroupChanged(groupId)
    }

    public suspend fun setGroupAdmin(groupId: String, userId: String, admin: Boolean): Unit = onSdk {
        reqRaw(
            "groupSetAdmin",
            buildJsonObject {
                put("groupId", groupId)
                put("userId", userId)
                put("admin", admin)
            },
        )
    }

    public suspend fun dissolveGroup(groupId: String): Unit = onSdk {
        reqRaw("groupDissolve", buildJsonObject { put("groupId", groupId) })
        removeLocally(groupId, "dissolved")
    }

    public suspend fun updateGroup(groupId: String, name: String? = null, notice: String? = null): Unit = onSdk {
        reqRaw(
            "groupUpdate",
            buildJsonObject {
                put("groupId", groupId)
                name?.let { put("name", it) }
                notice?.let { put("notice", it) }
            },
        )
    }

    /** 群资料；同时更新本地会话的 myRole、memberCount、name。 */
    public suspend fun getGroupInfo(groupId: String): GroupInfo = onSdk { groupInfoNow(groupId) }

    private suspend fun groupInfoNow(groupId: String): GroupInfo {
        val info = req("groupInfo", buildJsonObject { put("groupId", groupId) }, GroupInfo.serializer())
        var changed = false
        changed = store.updateGroupMeta(groupConvId(groupId), info.name, info.memberCount, info.myRole)
        if (changed) emitConversations()
        mutes.clearGroupAllDenied(groupId)
        mutesChanged()
        return info
    }

    /** 一页群成员（群主 → 管理员 → 成员）；资料写进缓存。 */
    public suspend fun loadGroupMembers(groupId: String, cursor: String? = null): GroupMembersPage = onSdk {
        val page = req(
            "groupMembers",
            buildJsonObject {
                put("groupId", groupId)
                if (!cursor.isNullOrEmpty()) put("cursor", cursor)
            },
            GroupMembersPage.serializer(),
        )
        page.members.forEach { profiles.put(UserProfile(it.userId, it.nickname, it.avatar, it.official)) }
        page
    }

    /**
     * 搜索群成员（@ 选人，PRD-chat F-MEN-01 规则 6）：keyword 是 userId 前缀（区分大小写）或昵称前缀（ASCII 不区分大小写），
     * 空串等于不搜索。每人每分钟 30 次（4290），不自动重试，抛 IMError 给页面。资料写进缓存。
     */
    public suspend fun searchGroupMembers(groupId: String, keyword: String, cursor: String? = null): GroupMembersPage = onSdk {
        val page = req(
            "groupMembers",
            buildJsonObject {
                put("groupId", groupId)
                if (keyword.isNotEmpty()) put("keyword", keyword)
                if (!cursor.isNullOrEmpty()) put("cursor", cursor)
            },
            GroupMembersPage.serializer(),
        )
        page.members.forEach { profiles.put(UserProfile(it.userId, it.nickname, it.avatar, it.official)) }
        page
    }

    // ---- 内部：线程与工具 ----

    private fun post(fn: () -> Unit) {
        scope.launch { guard(fn) }
    }

    /** SDK 线程上不让未预期的异常结束协程作用域之外的状态：记日志（只写类型）继续跑。 */
    private inline fun guard(fn: () -> Unit) {
        try {
            fn()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("im: unexpected ${safeDescribe(e)}")
        }
    }

    private suspend inline fun guardSuspend(fn: () -> Unit) {
        try {
            fn()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("im: unexpected ${safeDescribe(e)}")
        }
    }

    private suspend fun <T> onSdk(block: suspend () -> T): T = withContext(dispatcher) { block() }

    /** 在 SDK 线程上立即开始执行，直到第一次挂起（与 sdk-ts 的 async 函数同步部分一致）。 */
    private fun launchNow(block: suspend () -> Unit): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        guardSuspend { block() }
    }

    /** 清库失败不能影响退出流程。普通写入的失败由 LocalDb 自己处理（回滚、记日志，不抛出）。 */
    private inline fun dbSafe(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("im: local database failed: ${safeDescribe(e)}")
        }
    }

    private fun <T> decode(cmd: String, des: DeserializationStrategy<T>, data: JsonElement?): T = try {
        ImJson.decodeFromJsonElement(des, data ?: JsonObject(emptyMap()))
    } catch (_: IllegalArgumentException) {
        throw ProtocolDecodeError(cmd) // 序列化异常的信息里有 JSON 片段，不往外传
    }

    private suspend fun reqRaw(cmd: String, data: JsonObject): JsonElement? = conn.request(cmd, data)

    private suspend fun <T> req(cmd: String, data: JsonObject, des: DeserializationStrategy<T>): T = decode(cmd, des, conn.request(cmd, data))

    // ---- 冷启动恢复 ----

    /** 从本地库恢复上次的状态。库损坏时清空：本地库只是缓存。 */
    private fun restore() {
        val db = local ?: return
        try {
            db.open()
            val r = db.load()
            val owner = r.owner ?: return
            self = owner
            _userId.value = owner
            for ((view, base, placeholder) in r.conversations) store.restoreConversation(view, base, placeholder)
            for (h in r.hidden) store.restoreHidden(h.convId, h.clearedSeq, h.view, h.readBase)
            // 上次没清完、这次启动在 open() 里补清了的：缓存文件也删掉（F-CVS-03 异常）。
            o.fileCacheDir?.let { dir ->
                val targets = r.purgedFiles.flatMap { id -> FileVariant.entries.map { cacheFile(dir, id, it) } }
                if (targets.isNotEmpty()) scope.launch(io) { targets.forEach { it.delete() } }
            }
            for ((convId, refs) in r.deletedRefs) store.list(convId).restoreRefs(refs)
            // 上次进程结束时还没发出去的消息：标记失败，由用户决定是否重发（不在用户不知情时发出旧消息）。
            val unsentToFail = ArrayList<Message>()
            for (m in r.messages) {
                val unsent = m.seq == null && (m.status == MessageStatus.QUEUED || m.status == MessageStatus.SENDING)
                // 待发送的附件（已 complete，有 fileId），以及有 fileId 的发送中：按排队，连上后自动发 1 次（F-FILE-07 规则 3，
                // 问题 D3 按 PRD 字面统一，同 sdk-swift；服务端按 clientMsgId 去重）。其他排队、发送中的规则不变。
                val readyAttachment = unsent && (m.type == MsgType.IMAGE || m.type == MsgType.FILE) && !m.body.fileId.isNullOrEmpty()
                val pending = unsent && !readyAttachment
                // 处理中、上传中被进程结束打断（F-FILE-07 规则 3）：一律失败，不自动上传，点重发用沙盒副本从头传。
                val interrupted = m.seq == null && (m.status == MessageStatus.PROCESSING || m.status == MessageStatus.UPLOADING)
                val fixed = when {
                    readyAttachment -> m.copy(status = MessageStatus.QUEUED, error = null, errorReason = null)
                    pending -> m.copy(status = MessageStatus.FAILED, error = SendError.Timeout)
                    interrupted -> m.copy(status = MessageStatus.FAILED, error = SendError.Network)
                    else -> m
                }
                if (pending || interrupted || (readyAttachment && fixed != m)) unsentToFail.add(fixed)
                store.restoreMessage(fixed)
            }
            for (a in r.attachments) sources[a.clientMsgId] = Source(a, null, null)
            modVers.putAll(r.modVers)
            o.attachmentDir?.let { dir ->
                // 上次没删掉的沙盒副本（发出后删除失败、记录已经没有了）：这次启动再删（F-RCL-03 规则 9 同理）。
                val keep = r.attachments.map { sandboxOf(it.clientMsgId).name }.toSet()
                scope.launch(io) { dir.listFiles()?.filter { it.name !in keep }?.forEach { it.deleteRecursively() } }
            }
            db.transaction { unsentToFail.forEach { db.upsertMessage(it) } }
            social.restore(r.socialVer, r.requestSeenVer, r.settings, r.official, r.socialLoaded, r.friends, r.requests, r.blocks)
            knownLists.addAll(store.knownConvIds())
            for (id in knownLists) emitMessagesQuiet(id)
            emitConversations()
            emitPendingCount()
            emitSocial()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.error("im: local database unreadable, wiping: ${safeDescribe(e)}")
            store = Store({ self }, db)
            social.clear()
            self = ""
            _userId.value = ""
            dbSafe { db.wipeAll() }
        }
    }

    // ---- 连接事件 ----

    private fun onState(s: ConnState) {
        _state.value = s
        if (s == ConnState.DISCONNECTED) pushSync.onDisconnected()
        if (s == ConnState.CONNECTED) {
            bannerTimer?.cancel()
            bannerTimer = null
            _banner.value = false
        } else if (!_banner.value && bannerTimer == null) {
            bannerTimer = clock.schedule(t.bannerDelayMs) {
                bannerTimer = null
                if (conn.state != ConnState.CONNECTED) _banner.value = true
            }
        }
    }

    /** 连接（含重连）成功：拉会话列表 → 补拉落后的会话 → 发出排队的消息（PRD F-CONN-01 规则 4）。 */
    private suspend fun onConnected(a: AuthAck) {
        if (self.isNotEmpty() && self != a.userId) {
            // 换了账号：上一个账号的数据不能留。
            reset()
            clearFileState(deleteDirs = true)
            local?.let { db -> dbSafe { db.wipeAll() } }
        }
        self = a.userId
        _userId.value = a.userId
        serverOffset = a.serverTime - clock.now()
        if (a.recoveryCodeUnacked) {
            provider?.recoveryCodeUnacked()
            emit(IMEvent.RecoveryCodeUnacked)
        }
        local?.let { db ->
            db.paused = false
            db.setOwner(a.userId)
            if (wipedByFatal) store.persistAll()
        }
        wipedByFatal = false
        pushSync.onAuth(a.userId, a.serverTime) // 不等 convs、socialSync，互不阻塞（AC-PUSH-03-24）
        if (pendingProfileRefetch.isNotEmpty()) {
            val ids = pendingProfileRefetch.toList()
            pendingProfileRefetch.clear()
            for (id in ids) if (id == a.userId) launchNow { refetchProfile(id) }
        }
        store.transaction { social.purgeOldRequests(serverNow()) }
        mutes.setUser(a.mutedUntil) // 没有这个字段表示没有用户级禁言（离线期间可能已经解除）
        mutesChanged()
        emitSocial()
        flushQueued()
        var convs: List<ServerConversation>? = null
        try {
            val ack = req("convs", JsonObject(emptyMap()), ConvsAck.serializer())
            convs = ack.convs
            applyConvs(ack)
            for (c in ack.convs) catchUp(c)
            maybeReportRead()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.warn("im: initial sync failed: ${safeDescribe(e)}")
        }
        // 先 convs，再从本地 socialVer 开始一轮关系同步（F-SOC-06 规则 10）。
        social.needSync = true
        syncSocial()
        val list = convs
        launchNow { recheckDeleted(list) }
    }

    /**
     * 重连后重拉已加载的范围（F-RCL-03 规则 2）：只重拉 modVer 变了的会话（文件过期等），没变的不拉。
     * 服务端没有 modVer（老服务端）或这次没拿到会话列表时，按原来的做法全部重拉：断开期间被删除的消息
     * 不会再发 msgDeleted，只有 sync 才能拿到（F-ADM-31 规则 5）。基线存在本地库里，冷启动后同样适用。
     * 按会话依次重拉；一个失败只影响它自己（P-39 ②）：不记新的 modVer，下次重连再拉，其余会话照拉。
     */
    private suspend fun recheckDeleted(convs: List<ServerConversation>?) {
        val byId = convs?.associateBy { it.convId } ?: emptyMap()
        for (r in store.loadedRanges()) {
            val ver = byId[r.convId]?.modVer
            if (convs != null && r.convId in byId && ver != null && modVers[r.convId] == ver) continue
            try {
                if (syncAfter(r.convId, r.from - 1, r.to)) {
                    emitMessages(r.convId)
                    emitConversations()
                }
                if (ver != null) setModVer(r.convId, ver)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log.warn("im: recheck deleted failed: ${safeDescribe(e)}")
            }
        }
    }

    private fun setModVer(convId: String, ver: Long) {
        modVers[convId] = ver
        local?.setModVer(convId, ver)
    }

    /**
     * 补拉一个会话。只补拉本地已经加载过消息的会话；没打开过的会话等打开时再 loadHistory，
     * 否则首次登录要为每个会话拉一遍历史。
     */
    private suspend fun catchUp(c: ServerConversation) {
        val localMax = store.maxSeq(c.convId)
        if (localMax == 0L || c.maxSeq <= localMax) return
        val start = maxOf(localMax, c.minSeq - 1) // 群补拉起点（C-24）
        val list = store.list(c.convId)
        if (c.maxSeq - start > MAX_CATCH_UP) {
            // 缺口太大：只加载最新一页，中间的靠向上翻页（AC-CONN-01-8）。
            list.resetServer()
            val ack = syncReq(c.convId, c.maxSeq + 1, LATEST_PAGE)
            applyAll(ack.messages)
            list.hasMoreHistory = ack.hasMore
        } else {
            syncAfter(c.convId, start, c.maxSeq)
        }
        emitMessages(c.convId)
        emitConversations()
    }

    /** 从 after 开始补拉，直到拿到 until 或者没有更多。返回本地消息是否有变化。 */
    private suspend fun syncAfter(convId: String, afterSeq: Long, until: Long): Boolean {
        var after = afterSeq
        var changed = false
        while (true) {
            val ack = req(
                "sync",
                buildJsonObject {
                    put("convId", convId)
                    put("afterSeq", after)
                    put("limit", PAGE)
                },
                SyncAck.serializer(),
            )
            if (applyAll(ack.messages)) changed = true
            val last = ack.messages.lastOrNull()?.seq ?: after
            if (!ack.hasMore || last >= until || last <= after) return changed
            after = last
        }
    }

    private fun onFrame(f: InboundFrame) {
        try {
            when (f.cmd) {
                "msg" -> {
                    val m = ImJson.decodeFromJsonElement(ServerMessage.serializer(), f.data ?: return)
                    // 聊天室是二期：这里没有 seq 语义，不进会话列表。
                    if (m.seq > 0 && !m.convId.startsWith("room:")) onMessage(m)
                }
                "event" -> onEvent(f.data as? JsonObject ?: return)
                "push" -> pushSync.onLive(f.data)
                // 不认识的下行命令（聊天室等二期功能）忽略（AC-PUSH-01-5）。
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            log.warn("im: malformed ${f.cmd} frame ignored")
        }
    }

    private fun string(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun onEvent(ev: JsonObject) {
        when (string(ev, "type")) {
            "securityNotice" -> {
                val kind = string(ev, "kind")
                val at = (ev["at"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                if (kind in SECURITY_NOTICE_KINDS && at != null) emit(IMEvent.SecurityNotice(kind!!, at, string(ev, "platform")))
            }
            "convAdded" -> {
                val conv = ImJson.decodeFromJsonElement(ServerConversation.serializer(), ev["conv"] ?: return)
                markPurges(mapOf(conv.convId to conv.minSeq))
                if (store.upsertConversation(conv)) emitMessages(conv.convId)
                setGroupMute(conv.convId, conv.mutedUntil, conv.allMuted)
                mutesChanged()
                emitConversations()
            }
            "convRemoved" -> {
                val convId = string(ev, "convId") ?: return
                val reason = string(ev, "reason")
                store.removeConversation(convId, reason)
                setGroupMute(convId, Deadline.Absent) // 被移出时清除该群的禁言（F-ADM-41 规则 4）
                mutesChanged()
                emitConversations()
                emit(IMEvent.ConvRemoved(convId, reason))
            }
            "readUpdated" -> {
                val convId = string(ev, "convId") ?: return
                val seq = (ev["readSeq"] as? JsonPrimitive)?.longOrNull ?: return
                // 另一端已读：提及数本地推算不了（不知道已读位置前后各有几条提及），有提及时重新拉 convs（F-MEN-03 规则 1）。
                if (hasMentions(convId)) refreshMentions()
                if (store.advanceRead(convId, seq)) emitConversations()
            }
            "groupChanged" -> onGroupChanged(string(ev, "groupId") ?: return)
            "msgDeleted" -> {
                val convId = string(ev, "convId") ?: return
                val ids = (ev["msgIds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
                val seqs = (ev["seqs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull } ?: emptyList()
                val r = ev["recalled"] as? JsonObject
                val recall = r?.let { RecallInfo(string(it, "by") ?: "", (it["at"] as? JsonPrimitive)?.takeIf { p -> !p.isString }?.longOrNull) }
                onMsgDeleted(convId, ids, seqs, recall)
            }
            "muted" -> applyMute(string(ev, "scope"), string(ev, "groupId"), deadlineOf(ev, "mutedUntil") ?: return)
            "unmuted" -> applyMute(string(ev, "scope"), string(ev, "groupId"), Deadline.Absent)
            "socialChanged" -> {
                val ver = (ev["ver"] as? JsonPrimitive)?.longOrNull ?: return
                social.onSocialChanged(ver)
                if (social.needSync) syncSocialSoon()
            }
            "socialSettingsChanged" -> {
                val mode = ev["mode"]?.let { ImJson.decodeFromJsonElement(SocialMode.serializer(), it) } ?: return
                val limit = (ev["strangerMsgLimit"] as? JsonPrimitive)?.longOrNull?.toInt() ?: return
                social.settings = social.settings.copy(mode = mode, strangerMsgLimit = limit)
                local?.saveMeta(social)
                emitSocial()
            }
            "convSettingsChanged" -> {
                // 其他端改的置顶、免打扰：以事件为准覆盖本地（AC-CVS-04-1）。本地不认识的会话等下次 convs。
                val convId = string(ev, "convId") ?: return
                val pinned = (ev["pinned"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return
                val off = (ev["notifyOff"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return
                val at = if (pinned) (ev["pinnedAt"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull else null
                var changed = false
                store.transaction { changed = store.setSettings(convId, ConvSettings(pinned, at, off)) }
                if (changed) emitConversations()
            }
            "convDeleted" -> {
                // 其他端删除了会话（AC-CVS-04-5）：已读到 clearedSeq、不再置顶、minSeq = clearedSeq + 1（backend-chat 第 84 条）。
                val convId = string(ev, "convId") ?: return
                val cleared = (ev["clearedSeq"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 } ?: return
                onConvCleared(convId, cleared)
            }
            // 不认识的事件类型忽略（协议 README 第 3 节规则 9）。
        }
    }

    /** 读到期字段：JSON null 是永久，数字是时间；没有这个字段或类型不对返回 null。 */
    private fun deadlineOf(o: JsonObject, key: String): Deadline? {
        val v = o[key] ?: return null
        if (v is JsonNull) return Deadline.Forever
        return (v as? JsonPrimitive)?.longOrNull?.let { Deadline.At(it) }
    }

    /**
     * 消息被删除（PRD-admin F-ADM-31）或撤回（recall 不为 null，PRD-chat F-RCL-03 规则 1）：清除内存和本地库里的原文和引用它的原文，
     * 摘要改成占位，未读不变；文件的地址、下载缓存清掉，通过 FilesExpired 让 App 丢掉缓存（规则 3）。
     * 这个会话有提及我的未读时重新拉 convs（F-MEN-03 规则 1）。
     * 还有没拿到 ack 的本地消息时（超时标记失败、ack 丢了），被删的可能正是其中一条：事件里只有 msgId 和 seq，
     * 要向服务端补拉才能按 clientMsgId 对上，并清掉发送队列里的那份原文。
     */
    private fun onMsgDeleted(convId: String, msgIds: List<String>, seqs: List<Long>, recall: RecallInfo? = null) {
        val mentioned = hasMentions(convId)
        applyDeleted(convId, msgIds, seqs, recall)
        if (mentioned) refreshMentions()
        val list = store.list(convId)
        val top = seqs.maxOrNull() ?: 0
        val localMax = store.maxSeq(convId)
        if (!list.hasUnsent || top <= localMax) return
        launchNow {
            try {
                syncAfter(convId, if (localMax != 0L) localMax else top - 1, top)
                emitMessages(convId)
                emitConversations()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log.warn("im: deleted sync failed: ${safeDescribe(e)}")
            }
        }
    }

    private fun applyDeleted(convId: String, msgIds: List<String>, seqs: List<Long>, recall: RecallInfo?) {
        var changed = false
        store.transaction { changed = store.deleteMessages(convId, msgIds, seqs, recall) }
        if (changed) emitMessages(convId)
        emitConversations()
    }

    private fun hasMentions(convId: String): Boolean = (store.conversation(convId)?.mentionUnread ?: 0) > 0

    /** 按节流重新拉 convs 更新提及数（F-MEN-03 规则 1，R-CF-18）：窗口外立即拉；窗口内只记一次，窗口结束（上次拉取后 5 秒）时补拉。 */
    private fun refreshMentions() {
        if (mentionTimer != null) return
        val wait = mentionFetchAt + MENTION_REFRESH_MS - clock.now()
        if (wait <= 0) {
            fetchMentions()
            return
        }
        mentionTimer = clock.schedule(wait) {
            mentionTimer = null
            fetchMentions()
        }
    }

    private fun fetchMentions() {
        if (conn.state != ConnState.CONNECTED) return // 重连时的 convs 会带最新的计数
        mentionFetchAt = clock.now()
        launchNow {
            try {
                applyConvs(req("convs", JsonObject(emptyMap()), ConvsAck.serializer()))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log.warn("im: mention refresh failed: ${safeDescribe(e)}")
            }
        }
    }

    /** 群消息 @ 了我或所有人（自己发的不算，F-MEN-03 规则 6）。 */
    private fun mentionsMe(m: ServerMessage): Boolean {
        val men = m.body?.mentions ?: return false
        if (m.deleted || m.from == self || !m.convId.startsWith("group:")) return false
        return men.all || self in men.userIds
    }

    /** 更新禁言表；不认识的 scope 忽略（协议 README 第 3 节规则 9）。until = Absent 表示解除。 */
    private fun applyMute(scope: String?, groupId: String?, until: Deadline) {
        when {
            scope == "user" -> mutes.setUser(until)
            scope == "group" && groupId != null -> mutes.setGroup(groupId, until)
            scope == "groupAll" && groupId != null -> mutes.setGroupAll(groupId, until != Deadline.Absent)
            else -> return
        }
        mutesChanged()
    }

    /** 发送返回 4031：按 details 更新状态，界面显示禁用条。这条消息只标记失败，不自动重发。 */
    private fun onMutedAck(details: JsonObject?) {
        details ?: return
        val groupId = string(details, "groupId")
        if (string(details, "scope") == "groupAll" && groupId != null) {
            onGroupAllDenied(groupId)
            return
        }
        val until = deadlineOf(details, "mutedUntil") ?: return
        applyMute(string(details, "scope"), string(details, "groupId"), until)
    }

    /** 全群禁言下发送被拒：以服务端为准显示禁用条。本地角色还是群主 / 管理员说明角色过期了，补拉一次。 */
    private fun onGroupAllDenied(groupId: String) {
        mutes.setGroupAll(groupId, true, denied = true)
        mutesChanged()
        val role = store.conversation(groupConvId(groupId))?.myRole
        if (role == GroupRole.OWNER || role == GroupRole.ADMIN) onGroupChanged(groupId)
    }

    private fun setGroupMute(convId: String, until: Deadline, allMuted: Boolean = false) {
        if (!convId.startsWith("group:")) return
        val groupId = convId.removePrefix("group:")
        mutes.setGroup(groupId, until)
        mutes.setGroupAll(groupId, allMuted)
    }

    /** 全群禁言是否限制我还取决于我的角色：角色变了也要通知界面。 */
    private fun muteKeyNow(): String = mutes.key() + mutes.groupAllIds().map { store.conversation(groupConvId(it))?.myRole }

    /** 表有变化时发事件，并按最早的到期时间重新定时。 */
    private fun mutesChanged() {
        muteTimer?.cancel()
        muteTimer = null
        val key = muteKeyNow()
        if (key != muteKey) {
            muteKey = key
            emit(IMEvent.MuteChanged)
        }
        val next = mutes.nextExpiry() ?: return
        val wait = (next - serverNow()).coerceIn(0, MUTE_TIMER_MAX_MS)
        muteTimer = clock.schedule(wait) {
            muteTimer = null
            expireMutes()
        }
    }

    private fun expireMutes() {
        mutes.purge(serverNow())
        mutesChanged()
    }

    /** 实时消息：合并、检测缺口（F-MSG-04 规则 2）、必要时上报已读。 */
    private fun onMessage(m: ServerMessage) {
        val prev = store.maxSeq(m.convId)
        var fresh = false
        store.transaction {
            fresh = store.applyServerMessage(m)
            if (fresh && mentionsMe(m)) store.bumpMention(m.convId, m.seq)
        }
        if (!fresh) return
        emitMessages(m.convId)
        // 提示消息的摘要：本地按服务端同样的规则（只用昵称，F-SOC-29 规则 6）生成，下次 convs 时被权威文本覆盖。
        // 不在这里重拉 convs：大群里一次操作会让所有在线成员同时请求。
        val tip = m.body?.tip
        if (m.type == MsgType.TIP && tip != null) {
            store.setLastMsgText(m.convId, m.msgId, tipSummary(tip) { u -> profiles.last(u)?.nickname?.takeIf { it.isNotEmpty() } ?: u })
        }
        emitConversations()
        if (store.hasPlaceholder(m.convId)) refreshConversationsSoon()
        // 只对已经加载过的会话检测缺口：prev 为 0 说明本地还没有这个会话的消息。
        if (prev > 0 && m.seq > prev + 1) scheduleGapCheck(m.convId, prev, m.seq)
        if (m.convId == active) maybeReportRead()
    }

    /**
     * 本地临时补了会话（第一次和某人聊天、群消息比 convAdded 先到）时，稍后重新拉一次 convs，
     * 用服务端的权威数据（昵称、minSeq、未读）替换临时条目。200ms 内的多次触发合并为一次。
     */
    private fun refreshConversationsSoon() {
        if (refreshTimer != null || conn.state != ConnState.CONNECTED) return
        refreshTimer = clock.schedule(200) {
            refreshTimer = null
            launchNow {
                try {
                    applyConvs(req("convs", JsonObject(emptyMap()), ConvsAck.serializer()))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    log.warn("im: refresh conversations failed: ${safeDescribe(e)}")
                }
            }
        }
    }

    /**
     * 用 convs 的结果覆盖会话列表；原来在、新列表没有的群视为被移出（截断时不推断）。deleted 里的不是被移出（AC-CVS-03-5）。
     * 物理清除 seq < minSeq 的本地消息（F-CVS-03 规则 6 ②）：先记"待清除"，再在同一个事务里清除。
     */
    private fun applyConvs(ack: ConvsAck) {
        var applied = Store.ConvsApplied(emptyList(), emptyList())
        markPurges(store.convsPurgeMins(ack.convs, ack.deleted))
        store.transaction {
            applied = store.setConversations(ack.convs, ack.truncated, ack.deleted, ack.deletedTruncated)
            for (c in ack.convs) {
                // 还没加载过消息的会话：之后加载到的就是这个版本以后的内容，记下作为基线。已加载的等重拉成功再更新。
                if (c.modVer != null && !store.isLoaded(c.convId) && modVers[c.convId] != c.modVer) setModVer(c.convId, c.modVer)
                // 错过了 msgDeleted 的话，摘要是唯一的线索：本地已加载的那条也要清掉。
                val recall = c.lastMsg?.takeIf { it.recalled }?.let { RecallInfo(it.recalledBy ?: "") }
                if (c.lastMsg?.deleted == true && store.deleteMessages(c.convId, listOf(c.lastMsg.msgId), emptyList(), recall)) {
                    emitMessages(c.convId)
                }
            }
        }
        val gone = applied.gone
        for (id in applied.purged) emitMessages(id)
        lastRefreshAt = clock.now()
        _truncated.value = store.truncated
        for (c in ack.convs) setGroupMute(c.convId, c.mutedUntil, c.allMuted)
        for (id in gone) setGroupMute(id, Deadline.Absent)
        mutesChanged()
        emitConversations()
        for (id in gone) emit(IMEvent.ConvRemoved(id, null))
    }

    private fun scheduleGapCheck(convId: String, after: Long, until: Long) {
        if (convId in gapTimers) return
        gapTimers[convId] = clock.schedule(t.gapWaitMs) {
            gapTimers.remove(convId)
            val list = store.list(convId)
            if ((after + 1 until until).any { !list.has(it) }) {
                launchNow {
                    try {
                        syncAfter(convId, after, until)
                        emitMessages(convId)
                        emitConversations()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        log.warn("im: gap sync failed: ${safeDescribe(e)}")
                    }
                }
            }
        }
    }

    /**
     * 被踢或不可重连的关闭：发送中 / 排队的消息全部标记失败（AC-AUTH-03-6），清社交状态和本地库（FE-131），通知界面回到登录页。
     * 内存里的消息留给界面显示，下次同一账号登录时再写回本地库。
     */
    private fun onFatal(r: FatalReason) {
        bindProvider(null)
        clearSocial()
        for (c in allConvIds()) failPending(c)
        // 被踢清库：沙盒副本和下载缓存一起删除，失败的附件之后重发提示"原文件已不可用"。
        clearFileState(deleteDirs = true)
        local?.let { db ->
            dbSafe { db.wipeAll() }
            db.paused = true
            wipedByFatal = true
        }
        _fatal.value = r
        emit(IMEvent.Fatal(r))
    }

    private fun failPending(convId: String) {
        val list = store.listIfExists(convId) ?: return
        val pending = list.pendingMessages()
        for (m in pending) {
            uploadQueue.removeAll { it.second == m.clientMsgId }
            val j = job
            if (j != null && j.clientMsgId == m.clientMsgId) {
                j.coroutine?.cancel()
                job = null
                j.fileId?.takeIf { !j.completed }?.let { fid -> files?.let { f -> scope.launch { f.cancelUpload(fid) } } }
            }
        }
        for (m in pending) list.update(m.clientMsgId) { it.copy(status = MessageStatus.FAILED, error = SendError.Kicked) }
        if (pending.isNotEmpty()) emitMessages(convId)
        for (m in pending) emit(IMEvent.SendFailed(convId, m.clientMsgId, SendError.Kicked))
    }

    // ---- 发送 ----

    private fun dispatch(convId: String, clientMsgId: String) {
        val list = store.list(convId)
        var m: Message? = null
        m = list.update(clientMsgId) { it.copy(status = MessageStatus.SENDING, error = null, errorReason = null) }
        val msg = m ?: return
        emitMessages(convId)
        val parsed = parseConvId(convId, self)
        val type = msg.type
        val onAck: (SendAck) -> Unit = { ack ->
            if (sources.containsKey(clientMsgId)) dropSource(clientMsgId) // 已发送：删除沙盒副本（F-FILE-07 规则 2）
            // 正文在 ack 到的时候再取：等待期间消息可能已经被删除，回调不能留着一份原文。
            store.transaction {
                store.applyServerMessage(
                    ServerMessage(ack.msgId, clientMsgId, ack.convId, ack.seq, self, type, list.local(clientMsgId)?.body, ack.ts),
                )
            }
            emitMessages(convId)
            emitConversations()
            if (store.hasPlaceholder(convId)) refreshConversationsSoon()
        }
        val data = buildJsonObject {
            put("convType", parsed.convType.wire)
            put("to", parsed.to)
            put("clientMsgId", clientMsgId)
            put("type", msg.type.wire)
            // 附件只发 fileId，其他字段由服务端按记录填写（F-FILE-02 规则 1）。
            val wire = when (type) {
                MsgType.IMAGE -> ImJson.encodeToJsonElement(MsgBody.serializer(), MsgBody(image = ImageBody(msg.body.image?.fileId ?: "")))
                MsgType.FILE -> ImJson.encodeToJsonElement(MsgBody.serializer(), MsgBody(file = FileBody(msg.body.file?.fileId ?: "")))
                MsgType.TEXT -> textWire(msg.body)
                else -> ImJson.encodeToJsonElement(MsgBody.serializer(), msg.body)
            }
            put("body", wire)
        }
        launchNow {
            try {
                val raw = conn.request(
                    "send",
                    data,
                    timeoutMs = t.sendTimeoutMs,
                    // 超时后 ack 才到：以 ack 为准，变为已发送（C-30）。
                    onLateAck = { lateData, err ->
                        if (err == null) {
                            guard { onAck(decode("send", SendAck.serializer(), lateData)) }
                        } else if (err.code == ErrorCode.MUTED) {
                            onMutedAck(err.details)
                        } else if (err.code == ErrorCode.CONTENT_BLOCKED) {
                            guard { lateBlocked(convId, clientMsgId) }
                        }
                    },
                )
                onAck(decode("send", SendAck.serializer(), raw))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 失败回调是异步的：这期间消息可能已经被别的路径改过状态（例如被踢时标记为失败），只处理仍在发送中的。
                if (e is IMError && e.code == ErrorCode.MUTED) onMutedAck(e.details)
                if (list.local(clientMsgId)?.status != MessageStatus.SENDING) return@launchNow
                if (e is IMError && (type == MsgType.IMAGE || type == MsgType.FILE) && purgedOnServer(e.code, clientMsgId, list.local(clientMsgId)!!)) {
                    reupload(convId, clientMsgId)
                    return@launchNow
                }
                when {
                    e is IMError -> fail(convId, clientMsgId, SendError.Code(e.code), e.reason)
                    e is TimeoutError -> fail(convId, clientMsgId, SendError.Timeout)
                    (e is ConnectionClosedError || e is NotConnectedError) && clientMsgId !in autoUsed -> {
                        // 发出后连接断了：重连后自动补发一次（PRD F-MSG-03 规则 2）。
                        autoUsed.add(clientMsgId)
                        list.update(clientMsgId) { it.copy(status = MessageStatus.QUEUED) }
                        emitMessages(convId)
                    }
                    else -> fail(convId, clientMsgId, SendError.Timeout)
                }
            }
        }
    }

    /**
     * 超时标记失败之后才到的 4510：失败原因改成 4510，气泡不再提供"重发"（frontend-chat 第 81 条），写进本地库。
     * 只改仍然是超时失败的；用户已经手动重发（又在发送中）的以那次的结果为准。不再发 SendFailed（页面已经提示过失败）。
     */
    private fun lateBlocked(convId: String, clientMsgId: String) {
        val list = store.listIfExists(convId) ?: return
        val m = list.local(clientMsgId)
        if (m == null || m.status != MessageStatus.FAILED || m.error != SendError.Timeout) return
        list.update(clientMsgId) { it.copy(error = SendError.Code(ErrorCode.CONTENT_BLOCKED), errorReason = null) }
        emitMessages(convId)
    }

    private fun fail(convId: String, clientMsgId: String, error: SendError, reason: String? = null) {
        val list = store.list(convId)
        val m = list.local(clientMsgId)
        if (m == null || m.status != MessageStatus.SENDING) return // 已经被晚到的 ack 更新为已发送
        list.update(clientMsgId) { it.copy(status = MessageStatus.FAILED, error = error, errorReason = reason) }
        emitMessages(convId)
        emit(IMEvent.SendFailed(convId, clientMsgId, error))
    }

    /** 重连后发出排队的消息，每条只自动发一次。 */
    private fun flushQueued() {
        for (c in allConvIds()) {
            for (m in store.list(c).pendingMessages()) {
                if (m.status != MessageStatus.QUEUED) continue
                autoUsed.add(m.clientMsgId)
                dispatch(c, m.clientMsgId)
            }
        }
    }

    private fun allConvIds(): List<String> = (store.conversations().map { it.convId } + knownLists).distinct()

    // ---- 已读 ----

    /**
     * 页面可见、停在该会话、有未读时上报已读，节流为每秒最多一次（PRD F-CONV-02 规则 3）。
     * 本地立即清零（界面不等 ack），上报值为当前最新 seq。
     */
    private fun maybeReportRead() {
        val convId = active ?: return
        if (!o.isVisible() || conn.state != ConnState.CONNECTED) return
        val c = store.conversation(convId) ?: return
        val latest = maxOf(c.maxSeq, store.maxSeq(convId))
        if (latest <= c.readSeq) return
        val wait = lastRead + t.readThrottleMs - clock.now()
        if (wait > 0) {
            if (readTimer == null) {
                readTimer = clock.schedule(wait) {
                    readTimer = null
                    maybeReportRead()
                }
            }
            return
        }
        lastRead = clock.now()
        store.advanceRead(convId, latest)
        store.clearMentions(convId)
        emitConversations()
        launchNow {
            try {
                reqRaw(
                    "read",
                    buildJsonObject {
                        put("convId", convId)
                        put("seq", latest)
                    },
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log.warn("im: read failed: ${safeDescribe(e)}")
            }
        }
    }

    // ---- 其他 ----

    private fun emit(e: IMEvent) {
        eventTap?.invoke(e)
        _events.tryEmit(e)
    }

    private fun emitConversations() {
        val list = store.conversations()
        _conversations.value = list
        // F-CVS-02 规则 4：免打扰会话只计 @ 我的未读。
        _totalUnread.value = list.sumOf { if (it.notifyOff) it.mentionUnread ?: 0L else it.unread }
    }

    private fun emitMessagesQuiet(convId: String) {
        val list = store.messages(convId)
        synchronized(messageLock) {
            messageSnapshots[convId] = list
            messageFlows[convId]?.value = list
        }
    }

    private fun emitMessages(convId: String) {
        knownLists.add(convId)
        emitMessagesQuiet(convId)
        emit(IMEvent.MessagesChanged(convId, store.messages(convId)))
        emitPendingCount()
        val recalled = store.takeRecalledFiles()
        forgetFiles(store.takeExpiredFiles(), recalled)
    }

    private fun emitPendingCount() {
        _pendingCount.value = knownLists.sumOf { id -> store.messages(id).count { it.status != MessageStatus.SENT } }
    }

    private fun reset() {
        pushSync.logout()
        gapTimers.values.forEach { it.cancel() }
        gapTimers.clear()
        readTimer?.cancel()
        readTimer = null
        refreshTimer?.cancel()
        refreshTimer = null
        mentionTimer?.cancel()
        mentionTimer = null
        mentionFetchAt = Long.MIN_VALUE / 2
        store = Store({ self }, local ?: NoStoreSink)
        mutes.clear()
        mutesChanged()
        clearSocial()
        knownLists.clear()
        autoUsed.clear()
        pendingProfileRefetch.clear()
        clearFileState(deleteDirs = false)
        active = null
        lastRead = Long.MIN_VALUE / 2
        synchronized(messageLock) {
            messageSnapshots.clear()
            messageFlows.values.forEach { it.value = emptyList() }
        }
        _truncated.value = false
        emitConversations()
        emitPendingCount()
    }

    // ---- 社交内部 ----

    private fun removeLocally(groupId: String, reason: String) {
        val convId = groupConvId(groupId)
        store.removeConversation(convId, reason)
        setGroupMute(convId, Deadline.Absent)
        mutesChanged()
        emitConversations()
        emit(IMEvent.ConvRemoved(convId, reason))
    }

    /** groupChanged：刷新这个群的资料，不重拉 convs；同一个群的在途请求合并（F-SOC-20 规则 5）。 */
    private fun onGroupChanged(groupId: String) {
        if (groupId in groupInfoInFlight) {
            groupInfoInFlight[groupId] = true
            return
        }
        groupInfoInFlight[groupId] = false
        launchNow {
            var info: GroupInfo? = null
            try {
                info = groupInfoNow(groupId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log.warn("im: groupInfo failed: ${safeDescribe(e)}")
            }
            val again = groupInfoInFlight.remove(groupId) == true
            if (again) onGroupChanged(groupId) else info?.let { emit(IMEvent.GroupChanged(groupId, it)) }
        }
    }

    private suspend fun socialCmd(cmd: String, data: JsonObject): SocialChangeAck {
        val ack = req(cmd, data, SocialChangeAck.serializer())
        cacheProfiles(ack.items.friends, ack.items.requests, ack.items.blocks)
        store.transaction { social.applyAck(ack.ver, ack.changed, ack.items) }
        afterSocialAck()
        return ack
    }

    private fun afterSocialAck() {
        emitSocial()
        if (social.needSync) syncSocial()
    }

    private fun cacheProfiles(friends: List<FriendItem>, requests: List<RequestItem>, blocks: List<BlockItem>) {
        friends.forEach { profiles.putProfile(it.userId, it.profile) }
        requests.forEach { profiles.putProfile(it.userId, it.profile) }
        blocks.forEach { profiles.putProfile(it.userId, it.profile) }
    }

    private fun syncSocialSoon() {
        if (socialTimer != null) return
        socialTimer = clock.schedule(SOCIAL_DEBOUNCE_MS) {
            socialTimer = null
            syncSocial()
        }
    }

    /** 跑同步轮直到不再需要（F-SOC-06 规则 3、8）。同一时刻只有一个在跑；期间收到的事件在本轮结束后处理。 */
    private fun syncSocial(): CompletableDeferred<Unit> {
        socialRunning?.let { return it }
        val done = CompletableDeferred<Unit>()
        socialRunning = done
        launchNow {
            try {
                var rounds = 0
                while (social.needSync && conn.state == ConnState.CONNECTED && rounds < 20) {
                    rounds++
                    val since = social.beginRound()
                    var data = socialFirstPage(since)
                    try {
                        while (true) {
                            val page = req("socialSync", data, SocialSyncAck.serializer())
                            cacheProfiles(page.friends, page.requests, page.blocks)
                            var next = PageResult.DONE
                            store.transaction { next = social.applyPage(page) }
                            if (next == PageResult.DONE) break
                            data = if (next == PageResult.CONTINUE) {
                                buildJsonObject {
                                    put("cursor", page.cursor)
                                    put("limit", SOCIAL_PAGE)
                                }
                            } else {
                                socialFirstPage(since)
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        social.abortRound()
                        log.warn("im: socialSync failed: ${safeDescribe(e)}")
                        // 全量轮被限流（每分钟 10 轮）：稍后重试，否则首轮同步一直结束不了。其他失败等下一次事件或重连。
                        if (e is IMError && e.code == ErrorCode.RATE_LIMITED) {
                            social.needSync = true
                            if (socialTimer == null) {
                                socialTimer = clock.schedule(SOCIAL_RETRY_MS) {
                                    socialTimer = null
                                    syncSocial()
                                }
                            }
                        }
                        break
                    }
                    emitSocial()
                }
            } finally {
                socialRunning = null
                done.complete(Unit)
            }
        }
        return done
    }

    private fun socialFirstPage(since: Long) = buildJsonObject {
        put("sinceVer", since)
        put("limit", SOCIAL_PAGE)
    }

    /** 重新计算社交快照。没有变化时 StateFlow 不会通知（data class 相等）。 */
    private fun emitSocial() {
        val now = serverNow()
        val s = social
        val friendList = s.friends.values.filter { !it.deleted }.map { f ->
            val name = f.remark.ifEmpty { f.profile?.nickname?.ifEmpty { null } ?: profiles.last(f.userId)?.nickname?.ifEmpty { null } ?: f.userId }
            Friend(f.userId, f.remark, f.source, f.createdAt, f.ver, f.profile, name, s.isBlocked(f.userId))
        }
        val items = s.requestList(now).map { r ->
            // 发出的申请对方同意前没有资料：用手里已有的。
            val nick = r.profile?.nickname ?: profiles.last(r.userId)?.nickname
            FriendRequest(
                r.userId, r.direction, r.status, r.message, r.source, r.applyCount, r.createdAt, r.updatedAt, r.expireAt, r.ver, r.profile,
                nick?.ifEmpty { null } ?: r.userId,
            )
        }
        val blockList = s.blocks.values.filter { !it.deleted }.sortedByDescending { it.createdAt }.map { b ->
            BlockedUser(b.userId, b.createdAt, b.ver, b.profile, b.profile?.nickname?.ifEmpty { null } ?: b.userId)
        }
        _friends.value = friendList
        _friendRequests.value = FriendRequests(items, s.unseen(now))
        _blocks.value = blockList
        _socialSettings.value = s.settings
        _socialLoaded.value = s.loaded
        _official.value = s.official
    }

    private fun clearSocial() {
        socialTimer?.cancel()
        socialTimer = null
        social.clear()
        profiles.clear()
        emitSocial()
    }
}
