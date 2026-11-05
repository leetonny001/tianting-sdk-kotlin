package com.tianting.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
}

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
)

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
private const val MAX_SAFE_INTEGER = 9007199254740991L // 打开未知会话时的 beforeSeq，与 sdk-ts 相同

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
    private var muteKey = mutes.key()
    private var muteTimer: Timer? = null

    @Volatile
    private var self = ""

    @Volatile
    private var serverOffset = 0L // 服务端时间 − 本地时间（auth ack 的 serverTime）

    private val autoUsed = HashSet<String>() // 已经自动重发过一次的 clientMsgId
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

    /** 全部会话的未读总数（与 conversations 同一轮更新，PRD-ui G-UI-15）。 */
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
        listener = object : ConnectionListener {
            override fun onState(state: ConnState) = this@IMClient.onState(state)
            override fun onConnected(ack: AuthAck) = launchNow { this@IMClient.onConnected(ack) }.let { }
            override fun onFrame(frame: InboundFrame) = this@IMClient.onFrame(frame)
            override fun onFatal(reason: FatalReason) = this@IMClient.onFatal(reason)
        },
    )

    init {
        scope.launch { guard { restore() } }
    }

    // ---- 公开接口 ----

    /** 开始连接。token 只放在第一帧 auth 里（C-52）。 */
    public fun connect(token: String) {
        post {
            _fatal.value = null
            conn.start(token)
        }
    }

    /** 退出登录：以 1000 关闭，不再重连，清空内存和本地库（PRD F-AUTH-05、FE-131）。 */
    public suspend fun disconnect() {
        onSdk {
            conn.stop()
            reset()
            local?.let { db -> dbSafe { db.wipeAll() } }
            local?.paused = false
            wipedByFatal = false
            self = ""
            _userId.value = ""
        }
    }

    /** 释放 SDK：断开连接（不清本地库，下次启动还能恢复）、关闭本地库、结束 SDK 线程。之后这个对象不能再用。 */
    public suspend fun close() {
        onSdk {
            if (closed) return@onSdk
            closed = true
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
     */
    public suspend fun send(convId: String, text: String): SendResult = onSdk {
        when (val check = checkText(text)) {
            is TextCheck.Rejected -> SendResult.Rejected(check.reason, check.over)
            is TextCheck.Ok -> {
                if (self.isEmpty()) throw NotConnectedError()
                parseConvId(convId, self) // 不合法的会话 ID 在这里抛出，不能留下一条永远"发送中"的消息
                val msg = Message(
                    clientMsgId = o.newId(),
                    convId = convId,
                    from = self,
                    type = MsgType.TEXT,
                    body = MsgBody(text = check.text),
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

    /** 手动重发失败的消息，沿用原来的 clientMsgId（PRD F-MSG-03 规则 2）。 */
    public suspend fun retry(convId: String, clientMsgId: String): Unit = onSdk {
        val m = store.listIfExists(convId)?.local(clientMsgId)
        if (m == null || m.status != MessageStatus.FAILED) return@onSdk
        if (conn.state == ConnState.CONNECTED) {
            dispatch(convId, clientMsgId)
        } else {
            autoUsed.add(clientMsgId) // 手动重发也只自动补发一次
            store.list(convId).update(clientMsgId) { it.copy(status = MessageStatus.QUEUED, error = null) }
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
        if (removed) emitMessages(convId)
        removed
    }

    /** 这个会话当前的禁言：没有被禁言时为 null。按服务端时间判断到期；用户级优先，群内禁言只影响该群。 */
    public suspend fun muteState(convId: String): MuteInfo? = onSdk { mutes.get(convId, serverNow()) }

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
        if (before <= (c?.minSeq ?: 1)) {
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
    public suspend fun displayName(userId: String): String = onSdk {
        val f = social.friends[userId]
        if (f != null && !f.deleted && f.remark.isNotEmpty()) return@onSdk f.remark
        profiles.last(userId)?.nickname?.takeIf { it.isNotEmpty() } ?: userId
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
                setGroupMute(ack.conv.convId, ack.conv.mutedUntil)
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
            for ((convId, refs) in r.deletedRefs) store.list(convId).restoreRefs(refs)
            // 上次进程结束时还没发出去的消息：标记失败，由用户决定是否重发（不在用户不知情时发出旧消息）。
            val unsentToFail = ArrayList<Message>()
            for (m in r.messages) {
                val pending = m.seq == null && (m.status == MessageStatus.QUEUED || m.status == MessageStatus.SENDING)
                val fixed = if (pending) m.copy(status = MessageStatus.FAILED, error = SendError.Timeout) else m
                if (pending) unsentToFail.add(fixed)
                store.restoreMessage(fixed)
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
            local?.let { db -> dbSafe { db.wipeAll() } }
        }
        self = a.userId
        _userId.value = a.userId
        serverOffset = a.serverTime - clock.now()
        local?.let { db ->
            db.paused = false
            db.setOwner(a.userId)
            if (wipedByFatal) store.persistAll()
        }
        wipedByFatal = false
        store.transaction { social.purgeOldRequests(serverNow()) }
        mutes.setUser(a.mutedUntil) // 没有这个字段表示没有用户级禁言（离线期间可能已经解除）
        mutesChanged()
        emitSocial()
        flushQueued()
        try {
            val ack = req("convs", JsonObject(emptyMap()), ConvsAck.serializer())
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
        launchNow { recheckDeleted() }
    }

    /**
     * 重连后重新拉一遍已加载的范围：断开期间被删除的消息不会再发 msgDeleted，只有 sync 才能拿到（F-ADM-31 规则 5），
     * 不拉的话内存和本地库里的原文会一直留着。首次连接没有已加载的消息，不发请求。
     */
    private suspend fun recheckDeleted() {
        for (r in store.loadedRanges()) {
            try {
                if (syncAfter(r.convId, r.from - 1, r.to)) {
                    emitMessages(r.convId)
                    emitConversations()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log.warn("im: recheck deleted failed: ${safeDescribe(e)}")
                return
            }
        }
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
                // 不认识的下行命令（push 等二期功能）忽略。
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
            "convAdded" -> {
                val conv = ImJson.decodeFromJsonElement(ServerConversation.serializer(), ev["conv"] ?: return)
                store.upsertConversation(conv)
                setGroupMute(conv.convId, conv.mutedUntil)
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
                var changed = false
                changed = store.advanceRead(convId, seq)
                if (changed) emitConversations()
            }
            "groupChanged" -> onGroupChanged(string(ev, "groupId") ?: return)
            "msgDeleted" -> {
                val convId = string(ev, "convId") ?: return
                val ids = (ev["msgIds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
                val seqs = (ev["seqs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull } ?: emptyList()
                onMsgDeleted(convId, ids, seqs)
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
     * 消息被删除（PRD-admin F-ADM-31）：清除内存和本地库里的原文，摘要改成占位，未读不变。
     * 还有没拿到 ack 的本地消息时（超时标记失败、ack 丢了），被删的可能正是其中一条：事件里只有 msgId 和 seq，
     * 要向服务端补拉才能按 clientMsgId 对上，并清掉发送队列里的那份原文。
     */
    private fun onMsgDeleted(convId: String, msgIds: List<String>, seqs: List<Long>) {
        var changed = false
        store.transaction { changed = store.deleteMessages(convId, msgIds, seqs) }
        if (changed) emitMessages(convId)
        emitConversations()
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

    /** 更新禁言表；不认识的 scope 忽略（协议 README 第 3 节规则 9）。until = Absent 表示解除。 */
    private fun applyMute(scope: String?, groupId: String?, until: Deadline) {
        when {
            scope == "user" -> mutes.setUser(until)
            scope == "group" && groupId != null -> mutes.setGroup(groupId, until)
            else -> return
        }
        mutesChanged()
    }

    /** 发送返回 4031：按 details 更新状态，界面显示禁用条。这条消息只标记失败，不自动重发。 */
    private fun onMutedAck(details: JsonObject?) {
        details ?: return
        val until = deadlineOf(details, "mutedUntil") ?: return
        applyMute(string(details, "scope"), string(details, "groupId"), until)
    }

    private fun setGroupMute(convId: String, until: Deadline) {
        if (convId.startsWith("group:")) mutes.setGroup(convId.removePrefix("group:"), until)
    }

    /** 表有变化时发事件，并按最早的到期时间重新定时。 */
    private fun mutesChanged() {
        muteTimer?.cancel()
        muteTimer = null
        val key = mutes.key()
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
        store.transaction { fresh = store.applyServerMessage(m) }
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

    /** 用 convs 的结果覆盖会话列表；原来在、新列表没有的群视为被移出（截断时不推断）。 */
    private fun applyConvs(ack: ConvsAck) {
        var gone: List<String> = emptyList()
        store.transaction {
            gone = store.setConversations(ack.convs, ack.truncated)
            for (c in ack.convs) {
                // 错过了 msgDeleted 的话，摘要是唯一的线索：本地已加载的那条也要清掉。
                if (c.lastMsg?.deleted == true && store.deleteMessages(c.convId, listOf(c.lastMsg.msgId), emptyList())) {
                    emitMessages(c.convId)
                }
            }
        }
        lastRefreshAt = clock.now()
        _truncated.value = store.truncated
        for (c in ack.convs) setGroupMute(c.convId, c.mutedUntil)
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
        clearSocial()
        for (c in allConvIds()) failPending(c)
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
        for (m in pending) list.update(m.clientMsgId) { it.copy(status = MessageStatus.FAILED, error = SendError.Kicked) }
        if (pending.isNotEmpty()) emitMessages(convId)
        for (m in pending) emit(IMEvent.SendFailed(convId, m.clientMsgId, SendError.Kicked))
    }

    // ---- 发送 ----

    private fun dispatch(convId: String, clientMsgId: String) {
        val list = store.list(convId)
        var m: Message? = null
        m = list.update(clientMsgId) { it.copy(status = MessageStatus.SENDING, error = null) }
        val msg = m ?: return
        emitMessages(convId)
        val parsed = parseConvId(convId, self)
        val type = msg.type
        val onAck: (SendAck) -> Unit = { ack ->
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
            put("body", ImJson.encodeToJsonElement(MsgBody.serializer(), msg.body))
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
                when {
                    e is IMError -> fail(convId, clientMsgId, SendError.Code(e.code))
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

    private fun fail(convId: String, clientMsgId: String, error: SendError) {
        val list = store.list(convId)
        val m = list.local(clientMsgId)
        if (m == null || m.status != MessageStatus.SENDING) return // 已经被晚到的 ack 更新为已发送
        list.update(clientMsgId) { it.copy(status = MessageStatus.FAILED, error = error) }
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
        _totalUnread.value = list.sumOf { it.unread }
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
    }

    private fun emitPendingCount() {
        _pendingCount.value = knownLists.sumOf { id -> store.messages(id).count { it.status != MessageStatus.SENT } }
    }

    private fun reset() {
        gapTimers.values.forEach { it.cancel() }
        gapTimers.clear()
        readTimer?.cancel()
        readTimer = null
        refreshTimer?.cancel()
        refreshTimer = null
        store = Store({ self }, local ?: NoStoreSink)
        mutes.clear()
        mutesChanged()
        clearSocial()
        knownLists.clear()
        autoUsed.clear()
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
