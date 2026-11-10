package com.tianting.sdk

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 发送状态（PRD F-MSG-03；附件见 PRD-chat F-FILE-07）。服务端来的消息都是 SENT。
 * 附件：PROCESSING（App 在处理图片）→ UPLOADING（申请、PUT、complete）→ QUEUED（待发送：已 complete，等连接）→ SENDING → SENT；
 * 任何一步可以转 FAILED。文本没有前两种。冷启动时 PROCESSING、UPLOADING 一律转 FAILED，不自动上传；有 fileId 的 QUEUED、SENDING 附件按排队自动发 1 次（规则 3，D3）。
 */
@Serializable
public enum class MessageStatus { QUEUED, SENDING, SENT, FAILED, PROCESSING, UPLOADING }

/**
 * 发送失败的原因：错误码（4292 上传额度用完、4290 + errorReason=uploadPendingLimit、4139、4150、4096、5032…），
 * 或超时、被踢、Network（上传时网络或对象存储出错，或上传被进程结束打断）、Process（App 处理图片失败："无法处理这张图片，请用文件方式发送"）、
 * Storage（复制到沙盒失败："存储空间不足"）、SourceMissing（要重新上传但沙盒副本已不在："原文件已不可用，请重新选择"）。
 */
@Serializable(with = SendErrorSerializer::class)
public sealed interface SendError {
    public data class Code(val code: Int) : SendError
    public data object Timeout : SendError
    public data object Kicked : SendError
    public data object Network : SendError
    public data object Process : SendError
    public data object Storage : SendError
    public data object SourceMissing : SendError
}

internal object SendErrorSerializer : KSerializer<SendError> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): SendError {
        val p = (decoder as JsonDecoder).decodeJsonElement() as JsonPrimitive
        return p.intOrNull?.let { SendError.Code(it) } ?: when (p.contentOrNull) {
            "kicked" -> SendError.Kicked
            "network" -> SendError.Network
            "process" -> SendError.Process
            "storage" -> SendError.Storage
            "sourceMissing" -> SendError.SourceMissing
            else -> SendError.Timeout
        }
    }

    override fun serialize(encoder: Encoder, value: SendError) {
        val e = when (value) {
            is SendError.Code -> JsonPrimitive(value.code)
            SendError.Timeout -> JsonPrimitive("timeout")
            SendError.Kicked -> JsonPrimitive("kicked")
            SendError.Network -> JsonPrimitive("network")
            SendError.Process -> JsonPrimitive("process")
            SendError.Storage -> JsonPrimitive("storage")
            SendError.SourceMissing -> JsonPrimitive("sourceMissing")
        }
        (encoder as JsonEncoder).encodeJsonElement(e)
    }
}

/** 界面看到的一条消息（不可变，G-UI-19）。待发 / 失败的消息还没有 msgId 和 seq。 */
@Serializable
public data class Message(
    val clientMsgId: String,
    val msgId: String? = null,
    val seq: Long? = null,
    val convId: String,
    val from: String,
    val type: MsgType,
    val body: MsgBody = MsgBody(),
    /** 服务端时间；待发消息用本地时间临时展示（PRD 3.1）。 */
    val ts: Long,
    val status: MessageStatus,
    val error: SendError? = null,
    /** 错误码的 reason（例如 4290 的 uploadPendingLimit）。 */
    val errorReason: String? = null,
    /** 被删除（PRD-admin F-ADM-31）或撤回：seq、msgId、发送者、时间都保留，body 为空。 */
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
    /** 被撤回（PRD-chat F-RCL-01 规则 5a）：同时 deleted=true。文案用 recallNotice / recallText。 */
    val recalled: Boolean = false,
    val recalledAt: Long? = null,
    /** 撤回的操作者；老服务端或本地推断不出来时为空。 */
    val recalledBy: String? = null,
)

/** 界面看到的一个会话（不可变）。memberCount 缺失时为 null（界面只显示群名，不显示 0）。 */
@Serializable
public data class Conversation(
    val convId: String,
    val type: ConvType,
    val name: String,
    val maxSeq: Long,
    val readSeq: Long,
    val minSeq: Long,
    val unread: Long,
    val lastMsg: LastMsg? = null,
    val updatedAt: Long,
    val memberCount: Int? = null,
    val myRole: GroupRole? = null,
    /** 已有消息展示内容的版本号（F-RCL-03 规则 2）；老服务端没有。 */
    val modVer: Long? = null,
    /** 未读中提及我的数量（F-MEN-03 规则 1）：大于 0 时列表摘要前缀"[有人@我]"。服务端没给时为 null。 */
    val mentionUnread: Long? = null,
    /** 未读里第一条提及我的 seq（"有人@我"跳转按钮）。 */
    val firstMentionSeq: Long? = null,
    /** 置顶（PRD-chat F-CVS-01，C5）。 */
    val pinned: Boolean = false,
    /** 置顶时间（pinned=true 时才有）。本端刚置顶、还没收到 ack 时是本地估算的服务端时间。 */
    val pinnedAt: Long? = null,
    /** 免打扰（F-CVS-02）：列表未读显示小红点，不计入总未读（@ 我的计入）。 */
    val notifyOff: Boolean = false,
)

/** 会话设置里能改的字段（convSet、convSettingsChanged）。pinnedAt 只在 pinned 时有。 */
internal data class ConvSettings(val pinned: Boolean, val pinnedAt: Long?, val notifyOff: Boolean)

internal val Conversation.settings: ConvSettings get() = ConvSettings(pinned, if (pinned) pinnedAt else null, notifyOff)

internal fun Conversation.withSettings(s: ConvSettings): Conversation =
    copy(pinned = s.pinned, pinnedAt = if (s.pinned) s.pinnedAt else null, notifyOff = s.notifyOff)

/** 撤回的信息（msgDeleted 的 recalled，或 sync 下发的 recalled、recalledBy、recalledAt）。by 为空表示不知道是谁。 */
@Serializable
internal data class RecallInfo(val by: String, val at: Long? = null)

/**
 * 被移出群后保留的会话快照（PRD-ui G-UI-21）：会话从列表里消失，但聊天页还能显示群名和"你已不在该群"。
 * 保留到本次登录结束（只在内存）；reason 透传服务端的值（removed / dissolved / left / 不认识的值，或推断出来的没有 reason）。
 */
public data class ConversationSnapshot(val convId: String, val type: ConvType, val name: String, val reason: String? = null)

internal class ConvState(
    var view: Conversation,
    /** 计算未读的基线 = max(readSeq, joinSeq)：同步时由服务端的 maxSeq − unread 得到（C-02）。 */
    var readBase: Long,
)

/** Store 的本地持久化出口（LocalDb 实现）；内存模式下是 NoStoreSink。调用方负责事务边界。 */
internal interface StoreSink {
    fun upsertConversation(c: ConvState, placeholder: Boolean)
    fun deleteConversation(convId: String)
    fun replaceConversations(list: List<Pair<ConvState, Boolean>>)
    fun upsertMessage(m: Message)
    fun deleteUnsent(convId: String, clientMsgId: String)
    fun clearServerMessages(convId: String)
    fun wipeMessage(m: Message)
    fun addDeletedRefs(convId: String, msgIds: Collection<String>, seqs: Collection<Long>)
    fun addRecalledRefs(convId: String, msgIds: Map<String, RecallInfo>, seqs: Map<Long, RecallInfo>)
    fun addRemovedClient(convId: String, clientMsgIds: Collection<String>)
    fun addExpiredRefs(convId: String, msgIds: Collection<String>)

    /** 物理清除 seq < minSeq 的消息行（F-CVS-03 规则 6 ②），同一事务里去掉这个会话 min_seq ≤ minSeq 的"待清除"标记。 */
    fun purgeBelow(convId: String, minSeq: Long)

    /** 会话被我删除、没有更新的消息（F-CVS-03 规则 2）：从会话表移到隐藏表，有新消息时用它恢复。c 为 null 表示只从 convs 的 deleted 知道。 */
    fun hideConversation(convId: String, clearedSeq: Long, c: ConvState?)
    fun unhideConversation(convId: String)
    fun replaceHidden(list: Map<String, HiddenConv>)
    fun <T> transaction(block: () -> T): T
}

internal object NoStoreSink : StoreSink {
    override fun upsertConversation(c: ConvState, placeholder: Boolean) = Unit
    override fun deleteConversation(convId: String) = Unit
    override fun replaceConversations(list: List<Pair<ConvState, Boolean>>) = Unit
    override fun upsertMessage(m: Message) = Unit
    override fun deleteUnsent(convId: String, clientMsgId: String) = Unit
    override fun clearServerMessages(convId: String) = Unit
    override fun wipeMessage(m: Message) = Unit
    override fun addDeletedRefs(convId: String, msgIds: Collection<String>, seqs: Collection<Long>) = Unit
    override fun addRecalledRefs(convId: String, msgIds: Map<String, RecallInfo>, seqs: Map<Long, RecallInfo>) = Unit
    override fun addRemovedClient(convId: String, clientMsgIds: Collection<String>) = Unit
    override fun addExpiredRefs(convId: String, msgIds: Collection<String>) = Unit
    override fun purgeBelow(convId: String, minSeq: Long) = Unit
    override fun hideConversation(convId: String, clearedSeq: Long, c: ConvState?) = Unit
    override fun unhideConversation(convId: String) = Unit
    override fun replaceHidden(list: Map<String, HiddenConv>) = Unit
    override fun <T> transaction(block: () -> T): T = block()
}

private const val SUMMARY_LEN = 100

/**
 * F-CVS-01 规则 3：置顶在前按 pinnedAt 倒序，其余 updatedAt 倒序，同时间 convId 升序。置顶时间相同时再按 updatedAt 倒序、
 * convId 升序（和 Go 参考实现 server/e2e/chatmodel/convs.go 的 Conversations、sdk-ts 的 compareConvs 一致）。
 */
private val CONV_ORDER: Comparator<Conversation> = Comparator { a, b ->
    when {
        a.pinned != b.pinned -> if (a.pinned) -1 else 1
        a.pinned && (a.pinnedAt ?: 0) != (b.pinnedAt ?: 0) -> (b.pinnedAt ?: 0).compareTo(a.pinnedAt ?: 0)
        a.updatedAt != b.updatedAt -> b.updatedAt.compareTo(a.updatedAt)
        else -> a.convId.compareTo(b.convId)
    }
}

/** 过期后的内容：标记 expired，文件名清空（服务端同样清空，F-FILE-06 规则 3）。 */
internal fun expireBody(body: MsgBody): MsgBody = when {
    body.image != null -> body.copy(image = body.image.copy(expired = true))
    body.file != null -> body.copy(file = body.file.copy(expired = true, name = ""))
    else -> body
}

/** 被删除的最后一条消息的摘要：不保留原文。撤回时带 recalled、recalledBy（F-RCL-03 规则 4）。 */
private fun deletedLast(msgId: String, from: String, type: MsgType, ts: Long, recall: RecallInfo? = null) =
    LastMsg(msgId, from, type, null, ts, deleted = true, recalled = recall != null, recalledBy = recall?.by?.takeIf { it.isNotEmpty() })

private fun recallOf(m: ServerMessage): RecallInfo? = if (m.recalled) RecallInfo(m.recalledBy ?: "", m.recalledAt) else null

/** 引用清除原文（F-QUO-02 规则 4）：只留定位用的 msgId、seq、from、type 和新状态。 */
private fun clearedQuote(q: QuoteView, state: QuoteState): QuoteView = q.copy(state = state, text = null)

/** 被我删除、还没有新消息的会话（F-CVS-03 规则 2）。conv 是删除前本地的会话（已按 clearedView 更新）；只从 deleted 知道的没有。 */
internal class HiddenConv(val clearedSeq: Long, val conv: ConvState?)

/**
 * 删除到 clearedSeq 后的会话（frontend-chat 第 55 条，同 sdk-ts 的 clearedState）：minSeq = clearedSeq + 1、
 * readSeq = max(readSeq, clearedSeq)、不再置顶、保留免打扰。全部清除时摘要也去掉（隐藏的会话写进本地库，摘要是原文）。
 * 清除范围内的提及已读掉了：全部读完时清零；还有更新的消息时只能等下次 convs 的权威计数（问题 64）。
 */
internal fun clearedView(v: Conversation, clearedSeq: Long): Conversation {
    val maxSeq = maxOf(v.maxSeq, clearedSeq)
    val readSeq = maxOf(v.readSeq, clearedSeq)
    val allRead = readSeq >= maxSeq
    var mentions = v.mentionUnread
    var first = v.firstMentionSeq
    if (allRead || (first != null && first <= clearedSeq)) {
        first = null
        if (mentions != null && allRead) mentions = 0
    }
    return v.copy(
        minSeq = maxOf(v.minSeq, clearedSeq + 1),
        readSeq = readSeq,
        maxSeq = maxSeq,
        lastMsg = if (v.maxSeq <= clearedSeq) null else v.lastMsg,
        pinned = false,
        pinnedAt = null,
        mentionUnread = mentions,
        firstMentionSeq = first,
    )
}

private fun withRecall(m: Message, r: RecallInfo): Message =
    m.copy(recalled = true, recalledBy = r.by.takeIf { it.isNotEmpty() } ?: m.recalledBy, recalledAt = r.at ?: m.recalledAt)

/** 一个会话的消息：有 seq 的按 seq 升序，没有 seq 的（待发 / 失败）按发送顺序排在最后（F-MSG-03 规则 3）。 */
internal class MessageList(private val convId: String, private val self: () -> String, private val sinkOf: (String) -> StoreSink) {
    private val bySeq = HashMap<Long, Message>()
    private val unsent = ArrayList<Message>()
    private val byClient = HashMap<String, Message>()
    private val byMsgId = HashSet<String>()

    // 已知被删除的 msgId / seq：之后才到的 sync 结果、发送 ack 带着原文也不能写回来。
    private val deletedIds = HashSet<String>()
    private val deletedSeqs = HashSet<Long>()

    // 其中已知是撤回的（F-RCL-03 规则 2a：recalled 只能从假变真，跨冷启动保留）和撤回信息。
    private val recalledIds = HashMap<String, RecallInfo>()
    private val recalledSeqs = HashMap<Long, RecallInfo>()

    // 已知文件已过期的 msgId（F-FILE-06 规则 3）：expired 只能从假变真，迟到的旧 sync 不能把它改回去（F-RCL-03 规则 2a）。
    private val expiredIds = HashSet<String>()

    /** 这次合并里刚变成过期或被删除的 fileId，由 Store 取走（清地址和文件缓存用）。 */
    val newlyExpired = LinkedHashSet<String>()

    // 本地删除过的失败消息（G-UI-14）：它可能其实到了服务端，之后的 ack、sync 不能让它再出现。
    // 只认自己发的：clientMsgId 对同群成员可见，别人用同一个值发的消息不能被藏起来。
    private val removedClient = HashSet<String>()

    /** 已加载的最早 seq（用于向前翻页）；0 表示还没加载过。 */
    var oldestSeq = 0L

    /** 向前翻页是否还有更早的消息。 */
    var hasMoreHistory = true

    /** 本地可见的最小 seq（会话的 minSeq，F-CVS-03 规则 2、6）：更小的消息已物理清除，迟到的 sync、ack 也不再写进来。 */
    var floor = 1L
        private set

    private val sink get() = sinkOf(convId)

    /**
     * 合并服务端消息；按 msgId 去重，按 clientMsgId 合并本地待发消息。
     * 已有的消息单调合并（F-RCL-03 规则 2a）：deleted、recalled、文件的 expired 只能从假变真，deletedIds 优先；
     * quote.state 只能从 NORMAL 往后走，quote.text 清除后不恢复；迟到的旧 sync 应答带着原文、expired=false、
     * deleted=false 或 quote.state=NORMAL 时不回写。返回合并后的消息，没有变化时为 null。
     */
    fun addServer(m: ServerMessage): Message? {
        if (m.seq < floor) {
            // 清除范围内的：不写进来。本机待发的那条（ack 晚到，seq 落在清除范围里：upToSeq 之前已经看到了别人更新的消息）
            // 一起去掉，不能一直停在"发送中"（frontend-chat 第 68 条，同 sdk-swift@94fda7b、sdk-ts@a536459）。
            dropUnsentBelow(m)
            return null
        }
        if (m.clientMsgId in removedClient && m.from == self()) return null
        val deleted = m.deleted || isDeleted(m.msgId, m.seq)
        val recall = recallOf(m) ?: recallOf(m.msgId, m.seq)
        if (m.msgId in byMsgId) {
            val old = bySeq[m.seq] ?: return null
            if (old.deleted) {
                // 已删除的只接受"后台删除 → 撤回"的补充信息（recalled 从假变真），正文不会回来。
                if (recall == null || old.recalled) return null
                return replace(old, withRecall(old, recall), wiped = true)
            }
            if (deleted) return wipe(old, m.deletedAt, recall)
            var body = old.body
            if (m.body?.fileExpired == true && !old.body.fileExpired) {
                markExpired(m.msgId, old.body)
                body = expireBody(body)
            }
            val q = old.body.quote
            val nq = m.body?.quote
            var cleared = false
            if (q != null && q.state == QuoteState.NORMAL && nq != null && nq.state != QuoteState.NORMAL) {
                body = body.copy(quote = clearedQuote(q, nq.state))
                cleared = true
            }
            return if (body == old.body) null else replace(old, old.copy(body = body), wiped = cleared)
        }
        val local = byClient[m.clientMsgId]
        if (local != null && local.seq == null && local.from == m.from) {
            unsent.remove(local)
            sink.deleteUnsent(convId, m.clientMsgId)
        }
        var body = m.body ?: MsgBody()
        if (!deleted && (body.fileExpired || m.msgId in expiredIds)) {
            if (m.msgId !in expiredIds) markExpired(m.msgId, body)
            body = expireBody(body)
        }
        // 引用的消息本地已知被撤回或删除：这份应答可能是撤回之前生成的，引用原文不能写进来（AC-RCL-03-13）。
        val bq = body.quote
        if (bq != null && bq.state == QuoteState.NORMAL) goneState(bq.msgId, bq.seq)?.let { body = body.copy(quote = clearedQuote(bq, it)) }
        val msg = Message(
            clientMsgId = m.clientMsgId,
            msgId = m.msgId,
            seq = m.seq,
            convId = m.convId,
            from = m.from,
            type = m.type,
            body = if (deleted) MsgBody() else body,
            ts = m.ts,
            status = MessageStatus.SENT,
            deleted = deleted,
            deletedAt = if (deleted) m.deletedAt else null,
        ).let { if (deleted && recall != null) withRecall(it, recall) else it }
        bySeq[m.seq] = msg
        byClient[m.clientMsgId] = msg
        byMsgId.add(m.msgId)
        if (oldestSeq == 0L || m.seq < oldestSeq) oldestSeq = m.seq
        if (deleted) sink.wipeMessage(msg) else sink.upsertMessage(msg)
        return msg
    }

    /**
     * 服务端说这条本机待发的消息落在清除范围里：从列表和本地库去掉，文件经 newlyExpired 清缓存（FilesExpired）。
     * clientMsgId 只在同一发送者内唯一：别人的同名消息不算。之后的应答由下限挡住。
     */
    private fun dropUnsentBelow(m: ServerMessage) {
        val local = byClient[m.clientMsgId] ?: return
        if (local.seq != null || local.from != m.from) return
        unsent.remove(local)
        byClient.remove(m.clientMsgId)
        local.body.fileId?.takeIf { it.isNotEmpty() }?.let { newlyExpired.add(it) }
        sink.deleteUnsent(convId, m.clientMsgId)
    }

    private fun markExpired(msgId: String, body: MsgBody) {
        expiredIds.add(msgId)
        sink.addExpiredRefs(convId, listOf(msgId))
        body.fileId?.takeIf { it.isNotEmpty() }?.let { newlyExpired.add(it) }
    }

    fun hasExpiredFile(fileId: String): Boolean =
        bySeq.values.any { it.body.fileId == fileId && it.body.fileExpired }

    fun isDeleted(msgId: String, seq: Long? = null): Boolean = msgId in deletedIds || (seq != null && seq in deletedSeqs)

    /** 本地已知的撤回信息（只有撤回的有，后台删除的没有）。 */
    fun recallOf(msgId: String, seq: Long? = null): RecallInfo? = recalledIds[msgId] ?: seq?.let { recalledSeqs[it] }

    /** 引用这条消息的 quote 应有的状态：已知撤回为 RECALLED，已知删除为 DELETED，其他为 null。 */
    private fun goneState(msgId: String, seq: Long?): QuoteState? {
        if (!isDeleted(msgId, seq)) return null
        return if (recallOf(msgId, seq) != null) QuoteState.RECALLED else QuoteState.DELETED
    }

    /**
     * 记下被删除（recall 不为 null 时是撤回）的消息并清掉已加载的原文；本会话里引用这几条的消息（含待发的）清除引用原文，
     * 状态改成 RECALLED 或 DELETED（F-QUO-02 规则 4）。撤回信息只增不减：已经知道是撤回的，之后的后台删除不改回去。
     * 返回是否有消息变了。
     */
    fun markDeleted(msgIds: Collection<String>, seqs: Collection<Long>, recall: RecallInfo? = null): Boolean {
        deletedIds.addAll(msgIds)
        deletedSeqs.addAll(seqs)
        sink.addDeletedRefs(convId, msgIds, seqs)
        if (recall != null) {
            val ids = msgIds.associateWith { recall }
            val ss = seqs.associateWith { recall }
            recalledIds.putAll(ids)
            recalledSeqs.putAll(ss)
            sink.addRecalledRefs(convId, ids, ss)
        }
        var changed = false
        for (m in bySeq.values.toList()) {
            if (!isDeleted(m.msgId ?: "", m.seq)) continue
            val r = recallOf(m.msgId ?: "", m.seq)
            if (!m.deleted) {
                wipe(m, null, r)
                changed = true
            } else if (r != null && !m.recalled) {
                replace(m, withRecall(m, r), wiped = true)
                changed = true
            }
        }
        for (m in bySeq.values.toList() + unsent.toList()) {
            val q = m.body.quote ?: continue
            if (q.state != QuoteState.NORMAL) continue
            val gone = goneState(q.msgId, q.seq) ?: continue
            val body = m.body.copy(quote = clearedQuote(q, gone))
            if (m.seq != null) {
                replace(m, m.copy(body = body), wiped = true)
            } else {
                update(m.clientMsgId) { it.copy(body = body) }?.let { sink.wipeMessage(it) }
            }
            changed = true
        }
        return changed
    }

    /** 有没有 seq < minSeq 的已加载消息（要不要先记"待清除"）。 */
    fun hasBelow(minSeq: Long): Boolean = minSeq > floor && bySeq.keys.any { it < minSeq }

    /**
     * 物理清除 seq < minSeq 的消息（F-CVS-03 规则 6 ②，AC-CVS-03-10）：内存和本地库的行一起去掉，文件经 newlyExpired 清地址和缓存，
     * 之后到的 sync 结果、发送 ack 也不再写进来。还没发出的本地消息里引用了被清除消息的，清除引用原文（state=DELETED）；
     * 已发出的、seq ≥ minSeq 的消息（删除之后的新消息）的引用由服务端组装，照常显示（P-46 ④，问题 62）。返回是否有变化。
     */
    fun purgeBelow(minSeq: Long): Boolean {
        if (minSeq <= floor) return false
        floor = minSeq
        var changed = false
        val gone = HashSet<String>()
        for ((seq, m) in bySeq.entries.toList()) {
            if (seq >= minSeq) continue
            m.body.fileId?.takeIf { it.isNotEmpty() }?.let { newlyExpired.add(it) }
            bySeq.remove(seq)
            if (byClient[m.clientMsgId] === m) byClient.remove(m.clientMsgId)
            m.msgId?.let {
                byMsgId.remove(it)
                gone.add(it)
            }
            changed = true
        }
        for (m in unsent.toList()) {
            val q = m.body.quote ?: continue
            if (q.state != QuoteState.NORMAL) continue
            if (q.msgId !in gone && !(q.seq != null && q.seq < minSeq)) continue
            update(m.clientMsgId) { it.copy(body = it.body.copy(quote = clearedQuote(q, QuoteState.DELETED))) }?.let { sink.wipeMessage(it) }
            changed = true
        }
        val oldest = bySeq.keys.minOrNull() ?: 0L
        oldestSeq = oldest
        if (oldest == 0L || oldest <= minSeq) hasMoreHistory = oldest == 0L
        if (changed) sink.purgeBelow(convId, minSeq)
        return changed
    }

    /** 服务端的 minSeq 变小了（重新入群按新成员算，historyVisible=true 时能看到全部历史，F-CVS-03 规则 6a）：之前的消息又可以加载。 */
    fun lowerFloor(minSeq: Long) {
        if (minSeq >= floor) return
        floor = maxOf(1, minSeq)
        hasMoreHistory = true
    }

    /** 新建列表时的初始值（会话的 minSeq，或被删除隐藏的 clearedSeq + 1）：列表是空的，不用清除。 */
    fun initFloor(minSeq: Long) {
        floor = maxOf(1, minSeq)
    }

    /** 按 msgId 找已加载的消息（引用、撤回用）。 */
    fun findByMsgId(msgId: String): Message? {
        if (msgId !in byMsgId) return null
        return bySeq.values.firstOrNull { it.msgId == msgId }
    }

    private fun wipe(old: Message, deletedAt: Long?, recall: RecallInfo? = null): Message {
        old.body.fileId?.takeIf { it.isNotEmpty() }?.let {
            newlyExpired.add(it) // 文件的缓存同样要清（F-RCL-03 规则 3）
            if (recall != null) newlyRecalled.add(it)
        }
        var next = old.copy(body = MsgBody(), deleted = true, deletedAt = deletedAt ?: old.deletedAt)
        if (recall != null) next = withRecall(next, recall)
        return replace(old, next, wiped = true)
    }

    /** 替换一条已有 seq 的消息。wiped：清除了原文（正文、引用），本地库要把旧内容从文件里清掉。 */
    private fun replace(old: Message, next: Message, wiped: Boolean): Message {
        bySeq[old.seq!!] = next
        byClient[old.clientMsgId] = next
        if (wiped) sink.wipeMessage(next) else sink.upsertMessage(next)
        return next
    }

    /** 这次合并里因撤回而失效的 fileId（newlyExpired 的子集，下载中的要中止并告诉 App 是撤回，AC-RCL-03-12）。 */
    val newlyRecalled = LinkedHashSet<String>()

    /** 是否有还没有 seq 的本地消息（待发 / 失败），它们可能正是服务端已经存下的某一条。 */
    val hasUnsent: Boolean get() = unsent.isNotEmpty()

    /** 调试导出：这个会话内存里的全部消息副本（AC-ADM-31-3）。 */
    fun debugAll(): List<Message> = (bySeq.values + unsent + byClient.values).distinct()

    fun addLocal(m: Message) {
        unsent.add(m)
        byClient[m.clientMsgId] = m
        sink.upsertMessage(m)
    }

    fun local(clientMsgId: String): Message? = byClient[clientMsgId]

    /** 移除一条失败的本地消息，并记住它的 clientMsgId（本地库里也记，冷启动后同样不会复活）。不是失败状态时不动，返回 false。 */
    fun removeFailed(clientMsgId: String): Boolean = removeUnsent(clientMsgId, setOf(MessageStatus.FAILED))

    /** 移除一条还没有 seq、状态在 statuses 里的本地消息（取消上传，F-FILE-07、AC-FILE-02-5），并记住 clientMsgId。 */
    fun removeUnsent(clientMsgId: String, statuses: Set<MessageStatus>): Boolean {
        val m = byClient[clientMsgId] ?: return false
        if (m.status !in statuses || m.seq != null) return false
        unsent.remove(m)
        byClient.remove(clientMsgId)
        removedClient.add(clientMsgId)
        sink.deleteUnsent(convId, clientMsgId)
        sink.addRemovedClient(convId, listOf(clientMsgId))
        return true
    }

    fun has(seq: Long): Boolean = seq in bySeq

    /** 更新本地消息（状态变化）；返回新对象，旧对象保持不变，方便界面按引用比较。 */
    fun update(clientMsgId: String, patch: (Message) -> Message): Message? {
        val old = byClient[clientMsgId] ?: return null
        val next = patch(old)
        byClient[clientMsgId] = next
        val i = unsent.indexOfFirst { it === old }
        if (i >= 0) unsent[i] = next
        old.seq?.let { bySeq[it] = next }
        sink.upsertMessage(next)
        return next
    }

    /** 清空已加载的服务端消息（缺口超过 200 条时只保留最新一页，C-24），保留待发 / 失败的消息。 */
    fun resetServer() {
        for (m in bySeq.values) byClient.remove(m.clientMsgId)
        bySeq.clear()
        byMsgId.clear()
        oldestSeq = 0
        hasMoreHistory = true
        sink.clearServerMessages(convId)
    }

    /** 还在进行中的本地消息（处理、上传、排队、发送中）。 */
    fun pendingMessages(): List<Message> = unsent.filter { it.status != MessageStatus.FAILED && it.status != MessageStatus.SENT }

    fun snapshot(): List<Message> = bySeq.values.sortedBy { it.seq } + unsent

    val maxLoadedSeq: Long get() = bySeq.keys.maxOrNull() ?: 0

    val unsentCount: Int get() = unsent.size

    /** 全部持久化（被移出群的会话重新加入、被踢后同一账号重新登录时用）。 */
    fun persistAll() {
        for (m in bySeq.values) if (m.deleted) sink.wipeMessage(m) else sink.upsertMessage(m)
        for (m in unsent) sink.upsertMessage(m)
        sink.addDeletedRefs(convId, deletedIds, deletedSeqs)
        sink.addRecalledRefs(convId, recalledIds, recalledSeqs)
        sink.addRemovedClient(convId, removedClient)
        sink.addExpiredRefs(convId, expiredIds)
    }

    // ---- 冷启动恢复：只改内存，不再写回本地库 ----

    fun restore(m: Message) {
        if (m.seq == null) {
            unsent.add(m)
        } else {
            bySeq[m.seq] = m
            m.msgId?.let { byMsgId.add(it) }
            if (oldestSeq == 0L || m.seq < oldestSeq) oldestSeq = m.seq
        }
        byClient[m.clientMsgId] = m
    }

    fun restoreRefs(refs: DeletedRefs) {
        deletedIds.addAll(refs.ids)
        deletedSeqs.addAll(refs.seqs)
        recalledIds.putAll(refs.recalledIds)
        recalledSeqs.putAll(refs.recalledSeqs)
        removedClient.addAll(refs.removedClient)
        expiredIds.addAll(refs.expired)
    }
}

/** 本地数据：会话列表和每个会话的消息（FE-004 的数据部分）。内存里一份完整状态，配置了本地库时写穿到 StoreSink。 */
internal class Store(private val self: () -> String, private val sink: StoreSink = NoStoreSink) {
    private var convs = LinkedHashMap<String, ConvState>()
    private val msgs = LinkedHashMap<String, MessageList>()

    /** 每个会话本地已经知道的最大 seq（收到的消息、ack 带回的 seq 都计入）。 */
    private val localMax = HashMap<String, Long>()
    private val snapshots = HashMap<String, ConversationSnapshot>()
    private val placeholders = HashSet<String>()

    /** 最近一次 convs 是否被截断（超过 500 个会话，C-03）。 */
    var truncated = false
        private set

    /** 被移出的会话（有快照）不再写本地库：FE-435 要求删除本地库中该会话，内存里的留到本次登录结束（与 sdk-ts 一致）。 */
    private val sinkFor: (String) -> StoreSink = { convId -> if (convId in snapshots) NoStoreSink else sink }

    fun <T> transaction(block: () -> T): T = sink.transaction(block)

    // ---- 会话 ----

    /** setConversations 的结果：推断为"被移出"的群，和消息被物理清除过的会话（调用方发 MessagesChanged）。 */
    class ConvsApplied(val gone: List<String>, val purged: List<String>)

    /**
     * 用服务端的会话列表覆盖本地（convs 的结果）。返回推断为"被移出"的群：原来在、新列表里没有的群会话
     * （服务端不为离线期间的移出补发 convRemoved，G-UI-21 ②）。列表被截断时不推断，单聊不推断。
     * deleted（C5，F-CVS-03 规则 6 ①）里的会话是被我删除的：从列表移除，不生成快照、不算被移出（AC-CVS-03-5）；
     * deletedTruncated 时 deleted 不全，和 truncated 一样不推断（frontend-chat 第 54 条），原来隐藏的也保留。
     * 物理清除（规则 6 ②）：列表里的会话按 minSeq，deleted 里的按 clearedSeq + 1。调用方先用 purgeTargets 记"待清除"。
     */
    fun setConversations(
        list: List<ServerConversation>,
        truncated: Boolean,
        deleted: List<DeletedConv> = emptyList(),
        deletedTruncated: Boolean = false,
    ): ConvsApplied {
        val next = LinkedHashMap<String, ConvState>()
        for (c in list) next[c.convId] = fromServer(c)
        val nextHidden = LinkedHashMap<String, HiddenConv>()
        if (deletedTruncated) for ((id, h) in hidden) if (id !in next) nextHidden[id] = h
        for (d in deleted) {
            if (d.convId in next) continue
            val old = convs[d.convId] ?: hidden[d.convId]?.conv
            nextHidden[d.convId] = HiddenConv(d.clearedSeq, old?.let { cleared(it, d.clearedSeq) })
        }
        val gone = ArrayList<String>()
        if (!truncated && !deletedTruncated) {
            for ((id, c) in convs) {
                if (c.view.type == ConvType.GROUP && id !in next && id !in nextHidden && id !in placeholders) {
                    snapshots[id] = ConversationSnapshot(id, c.view.type, c.view.name)
                    gone.add(id)
                }
            }
        }
        val purged = ArrayList<String>()
        for ((id, c) in next) {
            val l = msgs[id] ?: continue
            // 服务端的 minSeq 比本地下限小只有一种情况：重新入群按新成员算（规则 6a）。被移出、再加入可能都发生在离线期间，
            // 本地没有快照也没有收到 convRemoved，所以不看快照，按服务端的值放低（frontend-chat 第 65 条）。
            l.lowerFloor(c.view.minSeq)
            if (l.purgeBelow(c.view.minSeq)) purged.add(id)
        }
        for ((id, h) in nextHidden) if (msgs[id]?.purgeBelow(h.clearedSeq + 1) == true) purged.add(id)
        for (id in next.keys) {
            if (snapshots.remove(id) != null) msgs[id]?.persistAll()
        }
        convs = next
        hidden = nextHidden
        placeholders.clear()
        pendingDelete.retainAll(next.keys)
        this.truncated = truncated
        sink.replaceConversations(next.values.map { it to false })
        sink.replaceHidden(nextHidden)
        for (id in gone) sink.deleteConversation(id)
        return ConvsApplied(gone, purged)
    }

    /** 这些会话按给定的 minSeq 清除时，哪些真的有消息要清（调用方先在本地库记"待清除"，单独提交，F-CVS-03 异常）。 */
    fun purgeTargets(mins: Map<String, Long>): Map<String, Long> = mins.filter { (id, min) -> msgs[id]?.hasBelow(min) == true }

    /** 下一次 convs 各会话会按什么 minSeq 清除。 */
    fun convsPurgeMins(list: List<ServerConversation>, deleted: List<DeletedConv>): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (c in list) out[c.convId] = c.minSeq
        for (d in deleted) if (d.convId !in out) out[d.convId] = d.clearedSeq + 1
        return out
    }

    /**
     * 服务端下发的完整会话（convAdded、convSet 的 ack）：服务端的 minSeq 比本地下限小（重新入群，规则 6a）时放低下限，
     * 旧历史可以重新加载（frontend-chat 第 65 条）；比本地下限大时按它物理清除。返回消息是否被物理清除过。
     */
    fun upsertConversation(c: ServerConversation): Boolean {
        val state = fromServer(c)
        convs[c.convId] = state
        placeholders.remove(c.convId)
        if (hidden.remove(c.convId) != null) sink.unhideConversation(c.convId)
        val l = msgs[c.convId]
        l?.lowerFloor(c.minSeq)
        val purged = l?.purgeBelow(c.minSeq) == true
        // 重新被拉进群：清除快照（G-UI-21 ③），内存里的消息重新写回本地库，否则下次冷启动中间会缺一段。
        if (snapshots.remove(c.convId) != null) msgs[c.convId]?.persistAll()
        sink.upsertConversation(state, false)
        return purged
    }

    // ---- 会话设置、删除（C5，PRD-chat F-CVS） ----

    /** 被我删除、还没有新消息的会话（F-CVS-03 规则 2）：有新消息时用它恢复（minSeq、已读位置、免打扰、名称）。 */
    private var hidden = LinkedHashMap<String, HiddenConv>()

    /** deleteConv 等 ack 期间先从列表里拿掉（乐观更新）；失败时 cancelDelete 恢复。 */
    private val pendingDelete = HashSet<String>()

    fun hideForDelete(convId: String) {
        pendingDelete.add(convId)
    }

    fun cancelDelete(convId: String): Boolean = pendingDelete.remove(convId)

    private fun cleared(c: ConvState, clearedSeq: Long): ConvState = ConvState(clearedView(c.view, clearedSeq), c.readBase).also { recount(it) }

    /**
     * 会话被删除到 clearedSeq（本端 deleteConv 的 ack，或其他端的 convDeleted，frontend-chat 第 55 条）：按 clearedView 更新、
     * 物理清除 seq ≤ clearedSeq 的消息；本地没有更新的消息时从列表移除（不是被移出，没有快照）。返回消息是否有变化。
     */
    fun clearConversation(convId: String, clearedSeq: Long): Boolean {
        pendingDelete.remove(convId)
        val c = convs[convId]
        val prev = hidden[convId]
        val seq = maxOf(clearedSeq, prev?.clearedSeq ?: 0)
        if (c != null) {
            val next = cleared(c, seq)
            if (next.view.maxSeq > seq) {
                convs[convId] = next
                saveConv(next)
            } else {
                convs.remove(convId)
                placeholders.remove(convId)
                hidden[convId] = HiddenConv(seq, next)
                sink.hideConversation(convId, seq, next)
            }
        } else {
            val h = HiddenConv(seq, prev?.conv?.let { cleared(it, seq) })
            hidden[convId] = h
            sink.hideConversation(convId, seq, h.conv)
        }
        val l = msgs[convId]
        if (l == null) {
            list(convId) // 还没加载过也要记下 minSeq：之后迟到的应答不能把清除范围内的消息写进来
            return false
        }
        return l.purgeBelow(seq + 1)
    }

    /** 改置顶、免打扰（本端乐观更新、回滚、ack，或其他端的 convSettingsChanged）。被删除隐藏的会话也记下（backend-chat 第 85 条）。 */
    fun setSettings(convId: String, s: ConvSettings): Boolean {
        val visible = convs[convId]
        val h = hidden[convId]
        val c = visible ?: h?.conv ?: return false
        if (c.view.settings == s) return false
        c.view = c.view.withSettings(s)
        if (visible != null) saveConv(c) else sink.hideConversation(convId, h!!.clearedSeq, c)
        return visible != null
    }

    /** 会话当前的置顶、免打扰（含被删除隐藏的会话）。不认识的会话为 null。 */
    fun settings(convId: String): ConvSettings? = (convs[convId] ?: hidden[convId]?.conv)?.view?.settings

    /**
     * convSet 的 ack（第 13.1 节：ack 的 conv 覆盖本地）。被删除隐藏的会话只更新设置，不让它重新出现；
     * 本地已经收到比 ack 更新的消息时只取设置，其他字段等下次 convs。
     */
    fun applyConvAck(conv: ServerConversation): Boolean {
        val c = convs[conv.convId]
        if ((c == null && conv.convId in hidden) || (c != null && c.view.maxSeq > conv.maxSeq)) {
            setSettings(conv.convId, ConvSettings(conv.pinned, if (conv.pinned) conv.pinnedAt else null, conv.notifyOff))
            return false
        }
        return upsertConversation(conv)
    }

    /** 本地可见的最小 seq：会话的 minSeq，被删除隐藏的是 clearedSeq + 1（翻页到这里就不再请求）。 */
    fun minSeqOf(convId: String): Long = convs[convId]?.view?.minSeq ?: hidden[convId]?.let { it.clearedSeq + 1 } ?: 1

    fun isHidden(convId: String): Boolean = convId in hidden

    fun restoreHidden(convId: String, clearedSeq: Long, view: Conversation?, readBase: Long) {
        hidden[convId] = HiddenConv(clearedSeq, view?.let { ConvState(it, readBase) })
    }

    /** 被移出群：从列表移除并保留快照；本地库里这个会话连同消息一起删除（FE-435）。 */
    fun removeConversation(convId: String, reason: String?) {
        val c = convs[convId]?.view
        snapshots[convId] = ConversationSnapshot(
            convId,
            c?.type ?: if (convId.startsWith("group:")) ConvType.GROUP else ConvType.C2C,
            c?.name ?: convId.substring(convId.indexOf(':') + 1),
            reason,
        )
        convs.remove(convId)
        hidden.remove(convId)
        pendingDelete.remove(convId)
        placeholders.remove(convId)
        sink.deleteConversation(convId)
    }

    fun snapshot(convId: String): ConversationSnapshot? = snapshots[convId]

    fun conversation(convId: String): Conversation? = convs[convId]?.view

    private var lastList: List<Conversation>? = null

    /**
     * 置顶的在最前（pinnedAt 倒序），其余按 updatedAt 倒序，时间相同按 convId 升序（F-CVS-01 规则 3，与服务端一致）。
     * 等 deleteConv 的 ack 期间的会话不在里面。
     * 快照稳定（G-UI-19）：没有变化的会话返回同一个对象，整体没有变化时返回同一个列表，界面可以按引用比较。
     */
    fun conversations(): List<Conversation> {
        val sorted = convs.values.map { it.view }.filter { it.convId !in pendingDelete }.sortedWith(CONV_ORDER)
        val prev = lastList
        if (prev != null && prev.size == sorted.size && prev.indices.all { prev[it] === sorted[it] }) return prev
        lastList = sorted
        return sorted
    }

    private fun fromServer(c: ServerConversation): ConvState {
        // 这份 convs 可能是删除之前生成的：已知被删除的消息不能把原文带回摘要；已知是撤回的，摘要也保持撤回（只能从假变真）。
        var last = c.lastMsg
        val list = msgs[c.convId]
        if (last != null && list?.isDeleted(last.msgId) == true && (!last.deleted || !last.recalled)) {
            val recall = list.recallOf(last.msgId)
            if (!last.deleted || recall != null) last = deletedLast(last.msgId, last.from, last.type, last.ts, recall)
        }
        val view = Conversation(
            c.convId, c.type, c.name, c.maxSeq, c.readSeq, c.minSeq, c.unread,
            last,
            c.updatedAt, c.memberCount, c.myRole, c.modVer, c.mentionUnread, c.firstMentionSeq,
            c.pinned, if (c.pinned) c.pinnedAt else null, c.notifyOff,
        )
        // 同一内容保留原对象（G-UI-19）：500 个会话刷新一次，没变的引用不变。
        val old = convs[c.convId]?.view
        return ConvState(if (old == view) old else view, c.maxSeq - c.unread)
    }

    /** 已读位置前进（自己上报、其他端同步、发言即已读），只进不退。 */
    fun advanceRead(convId: String, seq: Long): Boolean {
        val c = convs[convId] ?: return false
        if (seq <= c.view.readSeq) return false
        c.view = c.view.copy(readSeq = seq)
        recount(c)
        saveConv(c)
        return true
    }

    /** 本端已读到最新：提及我的未读也清零（F-MEN-03 规则 1"已读前进后减少"），跳转按钮消失。 */
    fun clearMentions(convId: String): Boolean {
        val c = convs[convId] ?: return false
        val v = c.view
        if ((v.mentionUnread ?: 0) == 0L || v.readSeq < v.maxSeq) return false
        c.view = v.copy(mentionUnread = 0, firstMentionSeq = null)
        saveConv(c)
        return true
    }

    /** 实时收到一条提及我的未读消息：本地先加 1，下次 convs 时以服务端为准（AC-MEN-01-1）。服务端没给过计数的会话不推算。 */
    fun bumpMention(convId: String, seq: Long): Boolean {
        val c = convs[convId] ?: return false
        val v = c.view
        val n = v.mentionUnread ?: return false
        if (seq <= v.readSeq) return false
        val first = v.firstMentionSeq
        c.view = v.copy(mentionUnread = n + 1, firstMentionSeq = if (first == null || first == 0L || seq < first) seq else first)
        saveConv(c)
        return true
    }

    private fun recount(c: ConvState) {
        val unread = maxOf(0, c.view.maxSeq - maxOf(c.readBase, c.view.readSeq))
        if (unread != c.view.unread) c.view = c.view.copy(unread = unread)
    }

    private fun saveConv(c: ConvState) {
        if (c.view.convId !in snapshots) sink.upsertConversation(c, c.view.convId in placeholders)
    }

    // ---- 消息 ----

    fun list(convId: String): MessageList = msgs.getOrPut(convId) { MessageList(convId, self, sinkFor).also { it.initFloor(minSeqOf(convId)) } }

    fun listIfExists(convId: String): MessageList? = msgs[convId]

    fun messages(convId: String): List<Message> = msgs[convId]?.snapshot() ?: emptyList()

    fun maxSeq(convId: String): Long = localMax[convId] ?: 0

    fun knownConvIds(): Set<String> = msgs.keys + convs.keys

    /**
     * 合并一条服务端消息，并更新会话的 maxSeq、未读、最后一条消息。
     * 自己发的消息（来自自己的其他端）不增加未读，并推进已读（C-34）。返回是否是新消息。
     */
    fun applyServerMessage(m: ServerMessage): Boolean {
        val merged = list(m.convId).addServer(m)
        bumpMax(m.convId, m.seq)
        if (merged == null) return false
        val c = convs[m.convId] ?: newConversation(m)
        val recall = if (merged.recalled) RecallInfo(merged.recalledBy ?: "") else null
        val l = c.view.lastMsg
        if (m.seq > c.view.maxSeq) {
            c.view = c.view.copy(
                maxSeq = m.seq,
                lastMsg = if (merged.deleted) {
                    deletedLast(m.msgId, m.from, m.type, m.ts, recall)
                } else {
                    LastMsg(m.msgId, m.from, m.type, takeCodePoints(merged.body.text ?: "", SUMMARY_LEN), m.ts)
                },
                updatedAt = maxOf(c.view.updatedAt, m.ts),
            )
        } else if (merged.deleted && l?.msgId == m.msgId && (!l.deleted || (recall != null && !l.recalled))) {
            c.view = c.view.copy(lastMsg = deletedLast(l.msgId, l.from, l.type, l.ts, recall))
        }
        if (m.from == self() && m.seq > c.view.readSeq) c.view = c.view.copy(readSeq = m.seq)
        recount(c)
        saveConv(c)
        return true
    }

    /**
     * 消息被删除或撤回（msgDeleted，或 convs 的 lastMsg.deleted）：清除原文和引用它的原文，摘要改成占位。
     * seq 不留空洞，未读不变、updatedAt 不变（C-02，F-RCL-01 规则 6）。recall 不为 null 表示撤回。
     * 没加载过的消息也记下来，之后到的 sync 结果、发送 ack 不会把原文写回来。返回消息列表是否有变化。
     */
    fun deleteMessages(convId: String, msgIds: Collection<String>, seqs: Collection<Long>, recall: RecallInfo? = null): Boolean {
        val changed = list(convId).markDeleted(msgIds, seqs, recall)
        val c = convs[convId]
        val l = c?.view?.lastMsg
        if (c != null && l != null && (l.msgId in msgIds || c.view.maxSeq in seqs) && (!l.deleted || (recall != null && !l.recalled))) {
            c.view = c.view.copy(lastMsg = deletedLast(l.msgId, l.from, l.type, l.ts, recall))
            saveConv(c)
        }
        return changed
    }

    /** 取走因撤回而失效的 fileId（在 takeExpiredFiles 之前调用）。 */
    fun takeRecalledFiles(): Set<String> {
        val out = LinkedHashSet<String>()
        for (l in msgs.values) {
            out.addAll(l.newlyRecalled)
            l.newlyRecalled.clear()
        }
        return out
    }

    /** 取走合并时刚变成过期（或被删除）的文件的 fileId：调用方清地址和文件缓存（F-FILE-06 规则 3、AC-FILE-06-12）。 */
    fun takeExpiredFiles(): List<String> {
        val out = ArrayList<String>()
        for (l in msgs.values) {
            out.addAll(l.newlyExpired)
            l.newlyExpired.clear()
        }
        return out
    }

    /** 这个文件是否已知过期（申请地址前看一眼，AC-FILE-06-6：不发申请地址的请求）。 */
    fun fileExpired(fileId: String): Boolean = msgs.values.any { it.hasExpiredFile(fileId) }

    fun isLoaded(convId: String): Boolean = (msgs[convId]?.oldestSeq ?: 0L) > 0L

    data class LoadedRange(val convId: String, val from: Long, val to: Long)

    /** 已加载过服务端消息的会话和已加载的 seq 范围。 */
    fun loadedRanges(): List<LoadedRange> =
        msgs.entries.filter { it.value.oldestSeq > 0 }.map { LoadedRange(it.key, it.value.oldestSeq, it.value.maxLoadedSeq) }

    /** 调试导出的数据：内存里的会话和全部消息副本（AC-ADM-31-3）。 */
    fun debugConversations(): List<Conversation> = convs.values.map { it.view }

    fun debugSnapshots(): List<ConversationSnapshot> = snapshots.values.toList()

    fun debugMessages(): Map<String, List<Message>> = msgs.mapValues { it.value.debugAll() }

    fun bumpMax(convId: String, seq: Long) {
        if (seq > maxSeq(convId)) localMax[convId] = seq
    }

    /** 是否有本地临时补的会话（服务端会话列表里还没有）：调用方应该尽快重新拉取 convs。 */
    fun hasPlaceholder(convId: String): Boolean = convId in placeholders

    fun setLastMsgText(convId: String, msgId: String, text: String) {
        val c = convs[convId] ?: return
        val l = c.view.lastMsg ?: return
        if (l.msgId == msgId && !l.deleted) {
            c.view = c.view.copy(lastMsg = l.copy(text = takeCodePoints(text, SUMMARY_LEN)))
            saveConv(c)
        }
    }

    /** groupChanged 之后用 groupInfo 的结果更新本地会话（F-SOC-20 规则 5）。返回是否有变化。 */
    fun updateGroupMeta(convId: String, name: String, memberCount: Int, myRole: GroupRole): Boolean {
        val c = convs[convId] ?: return false
        val v = c.view
        if (v.name == name && v.memberCount == memberCount && v.myRole == myRole) return false
        c.view = v.copy(name = name, memberCount = memberCount, myRole = myRole)
        saveConv(c)
        return true
    }

    /**
     * 第一次收到某个会话的消息、本地还没有这个会话时，先补一个临时的；重新拉取 convs 后被服务端数据覆盖。
     * 被我删除的会话有了新消息（seq > clearedSeq）：按删除时的状态恢复，未读只算新消息，minSeq = clearedSeq + 1
     * （F-CVS-03 规则 3，AC-CVS-03-2）。
     */
    private fun newConversation(m: ServerMessage): ConvState {
        val h = hidden.remove(m.convId)
        val c = if (h != null) {
            sink.unhideConversation(m.convId)
            cleared(h.conv ?: ConvState(bareView(m.convId).copy(maxSeq = h.clearedSeq), 0), h.clearedSeq)
        } else {
            ConvState(bareView(m.convId), 0)
        }
        convs[m.convId] = c
        placeholders.add(m.convId)
        return c
    }

    private fun bareView(convId: String): Conversation {
        val parts = convId.split(":")
        val type = when (parts[0]) {
            "c2c" -> ConvType.C2C
            "group" -> ConvType.GROUP
            "room" -> ConvType.ROOM
            else -> ConvType.UNKNOWN
        }
        val a = parts.getOrElse(1) { "" }
        val b = parts.getOrElse(2) { "" }
        val name = if (type == ConvType.C2C) (if (a == self()) b else a) else a
        return Conversation(convId, type, name, 0, 0, 1, 0, null, 0)
    }

    /** 全部写回本地库（被踢清库之后，同一账号重新登录时用）。 */
    fun persistAll() {
        sink.replaceConversations(convs.values.map { it to (it.view.convId in placeholders) })
        sink.replaceHidden(hidden)
        for (l in msgs.values) l.persistAll()
    }

    // ---- 冷启动恢复 ----

    fun restoreConversation(view: Conversation, readBase: Long, placeholder: Boolean) {
        convs[view.convId] = ConvState(view, readBase)
        if (placeholder) placeholders.add(view.convId)
    }

    fun restoreMessage(m: Message) {
        list(m.convId).restore(m)
        m.seq?.let { bumpMax(m.convId, it) }
    }
}
