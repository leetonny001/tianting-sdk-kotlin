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
    /** 被删除（PRD-admin F-ADM-31）：seq、msgId、发送者、时间都保留，body 为空。 */
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
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
)

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
    fun addRemovedClient(convId: String, clientMsgIds: Collection<String>)
    fun addExpiredRefs(convId: String, msgIds: Collection<String>)
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
    override fun addRemovedClient(convId: String, clientMsgIds: Collection<String>) = Unit
    override fun addExpiredRefs(convId: String, msgIds: Collection<String>) = Unit
    override fun <T> transaction(block: () -> T): T = block()
}

private const val SUMMARY_LEN = 100

/** 过期后的内容：标记 expired，文件名清空（服务端同样清空，F-FILE-06 规则 3）。 */
internal fun expireBody(body: MsgBody): MsgBody = when {
    body.image != null -> body.copy(image = body.image.copy(expired = true))
    body.file != null -> body.copy(file = body.file.copy(expired = true, name = ""))
    else -> body
}

/** 被删除的最后一条消息的摘要：不保留原文。 */
private fun deletedLast(msgId: String, from: String, type: MsgType, ts: Long) = LastMsg(msgId, from, type, null, ts, deleted = true)

/** 一个会话的消息：有 seq 的按 seq 升序，没有 seq 的（待发 / 失败）按发送顺序排在最后（F-MSG-03 规则 3）。 */
internal class MessageList(private val convId: String, private val self: () -> String, private val sinkOf: (String) -> StoreSink) {
    private val bySeq = HashMap<Long, Message>()
    private val unsent = ArrayList<Message>()
    private val byClient = HashMap<String, Message>()
    private val byMsgId = HashSet<String>()

    // 已知被删除的 msgId / seq：之后才到的 sync 结果、发送 ack 带着原文也不能写回来。
    private val deletedIds = HashSet<String>()
    private val deletedSeqs = HashSet<Long>()

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

    private val sink get() = sinkOf(convId)

    /**
     * 合并服务端消息；按 msgId 去重，按 clientMsgId 合并本地待发消息。
     * 已有的消息单调合并（F-RCL-03 规则 2a）：deleted、文件的 expired 只能从假变真，deletedIds 优先；
     * 迟到的旧 sync 应答带着原文或 expired=false 时不回写。返回合并后的消息，没有变化时为 null。
     */
    fun addServer(m: ServerMessage): Message? {
        if (m.clientMsgId in removedClient && m.from == self()) return null
        val deleted = m.deleted || isDeleted(m.msgId, m.seq)
        if (m.msgId in byMsgId) {
            val old = bySeq[m.seq]
            if (old == null || old.deleted) return null
            if (deleted) return wipe(old, m.deletedAt)
            if (m.body?.fileExpired == true && !old.body.fileExpired) {
                markExpired(m.msgId, old.body)
                val next = old.copy(body = expireBody(old.body))
                bySeq[old.seq!!] = next
                byClient[old.clientMsgId] = next
                sink.upsertMessage(next)
                return next
            }
            return null
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
        )
        bySeq[m.seq] = msg
        byClient[m.clientMsgId] = msg
        byMsgId.add(m.msgId)
        if (oldestSeq == 0L || m.seq < oldestSeq) oldestSeq = m.seq
        if (deleted) sink.wipeMessage(msg) else sink.upsertMessage(msg)
        return msg
    }

    private fun markExpired(msgId: String, body: MsgBody) {
        expiredIds.add(msgId)
        sink.addExpiredRefs(convId, listOf(msgId))
        body.fileId?.takeIf { it.isNotEmpty() }?.let { newlyExpired.add(it) }
    }

    fun hasExpiredFile(fileId: String): Boolean =
        bySeq.values.any { it.body.fileId == fileId && it.body.fileExpired }

    fun isDeleted(msgId: String, seq: Long? = null): Boolean = msgId in deletedIds || (seq != null && seq in deletedSeqs)

    /** 记下被删除的消息并清掉已加载的原文。返回是否有消息变了。 */
    fun markDeleted(msgIds: Collection<String>, seqs: Collection<Long>): Boolean {
        deletedIds.addAll(msgIds)
        deletedSeqs.addAll(seqs)
        sink.addDeletedRefs(convId, msgIds, seqs)
        var changed = false
        for (m in bySeq.values.toList()) {
            if (!m.deleted && isDeleted(m.msgId ?: "", m.seq)) {
                wipe(m, null)
                changed = true
            }
        }
        return changed
    }

    private fun wipe(old: Message, deletedAt: Long?): Message {
        old.body.fileId?.takeIf { it.isNotEmpty() }?.let { newlyExpired.add(it) } // 文件的缓存同样要清（F-RCL-03 规则 3）
        val next = old.copy(body = MsgBody(), deleted = true, deletedAt = deletedAt ?: old.deletedAt)
        bySeq[old.seq!!] = next
        byClient[old.clientMsgId] = next
        sink.wipeMessage(next)
        return next
    }

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

    /**
     * 用服务端的会话列表覆盖本地（convs 的结果）。返回推断为"被移出"的群：原来在、新列表里没有的群会话
     * （服务端不为离线期间的移出补发 convRemoved，G-UI-21 ②）。列表被截断时不推断，单聊不推断。
     */
    fun setConversations(list: List<ServerConversation>, truncated: Boolean): List<String> {
        val next = LinkedHashMap<String, ConvState>()
        for (c in list) next[c.convId] = fromServer(c)
        val gone = ArrayList<String>()
        if (!truncated) {
            for ((id, c) in convs) {
                if (c.view.type == ConvType.GROUP && id !in next && id !in placeholders) {
                    snapshots[id] = ConversationSnapshot(id, c.view.type, c.view.name)
                    gone.add(id)
                }
            }
        }
        for (id in next.keys) {
            if (snapshots.remove(id) != null) msgs[id]?.persistAll()
        }
        convs = next
        placeholders.clear()
        this.truncated = truncated
        sink.replaceConversations(next.values.map { it to false })
        for (id in gone) sink.deleteConversation(id)
        return gone
    }

    fun upsertConversation(c: ServerConversation) {
        val state = fromServer(c)
        convs[c.convId] = state
        placeholders.remove(c.convId)
        // 重新被拉进群：清除快照（G-UI-21 ③），内存里的消息重新写回本地库，否则下次冷启动中间会缺一段。
        if (snapshots.remove(c.convId) != null) msgs[c.convId]?.persistAll()
        sink.upsertConversation(state, false)
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
        placeholders.remove(convId)
        sink.deleteConversation(convId)
    }

    fun snapshot(convId: String): ConversationSnapshot? = snapshots[convId]

    fun conversation(convId: String): Conversation? = convs[convId]?.view

    private var lastList: List<Conversation>? = null

    /**
     * 按 updatedAt 倒序、convId 升序（与服务端一致）。
     * 快照稳定（G-UI-19）：没有变化的会话返回同一个对象，整体没有变化时返回同一个列表，界面可以按引用比较。
     */
    fun conversations(): List<Conversation> {
        val sorted = convs.values.map { it.view }.sortedWith(compareByDescending<Conversation> { it.updatedAt }.thenBy { it.convId })
        val prev = lastList
        if (prev != null && prev.size == sorted.size && prev.indices.all { prev[it] === sorted[it] }) return prev
        lastList = sorted
        return sorted
    }

    private fun fromServer(c: ServerConversation): ConvState {
        val last = c.lastMsg
        // 这份 convs 可能是删除之前生成的：已知被删除的消息不能把原文带回摘要。
        val stale = last != null && !last.deleted && msgs[c.convId]?.isDeleted(last.msgId) == true
        val view = Conversation(
            c.convId, c.type, c.name, c.maxSeq, c.readSeq, c.minSeq, c.unread,
            if (stale) deletedLast(last!!.msgId, last.from, last.type, last.ts) else last,
            c.updatedAt, c.memberCount, c.myRole, c.modVer,
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

    private fun recount(c: ConvState) {
        val unread = maxOf(0, c.view.maxSeq - maxOf(c.readBase, c.view.readSeq))
        if (unread != c.view.unread) c.view = c.view.copy(unread = unread)
    }

    private fun saveConv(c: ConvState) {
        if (c.view.convId !in snapshots) sink.upsertConversation(c, c.view.convId in placeholders)
    }

    // ---- 消息 ----

    fun list(convId: String): MessageList = msgs.getOrPut(convId) { MessageList(convId, self, sinkFor) }

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
        if (m.seq > c.view.maxSeq) {
            c.view = c.view.copy(
                maxSeq = m.seq,
                lastMsg = if (merged.deleted) {
                    deletedLast(m.msgId, m.from, m.type, m.ts)
                } else {
                    LastMsg(m.msgId, m.from, m.type, takeCodePoints(merged.body.text ?: "", SUMMARY_LEN), m.ts)
                },
                updatedAt = maxOf(c.view.updatedAt, m.ts),
            )
        } else if (merged.deleted && c.view.lastMsg?.msgId == m.msgId) {
            val l = c.view.lastMsg!!
            c.view = c.view.copy(lastMsg = deletedLast(l.msgId, l.from, l.type, l.ts))
        }
        if (m.from == self() && m.seq > c.view.readSeq) c.view = c.view.copy(readSeq = m.seq)
        recount(c)
        saveConv(c)
        return true
    }

    /**
     * 消息被删除（msgDeleted，或 convs 的 lastMsg.deleted）：清除原文，摘要改成占位。seq 不留空洞，未读不变（C-02）。
     * 没加载过的消息也记下来，之后到的 sync 结果、发送 ack 不会把原文写回来。返回消息列表是否有变化。
     */
    fun deleteMessages(convId: String, msgIds: Collection<String>, seqs: Collection<Long>): Boolean {
        val changed = list(convId).markDeleted(msgIds, seqs)
        val c = convs[convId]
        val l = c?.view?.lastMsg
        if (c != null && l != null && !l.deleted && (l.msgId in msgIds || c.view.maxSeq in seqs)) {
            c.view = c.view.copy(lastMsg = deletedLast(l.msgId, l.from, l.type, l.ts))
            saveConv(c)
        }
        return changed
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

    /** 第一次收到某个会话的消息、本地还没有这个会话时，先补一个临时的；重新拉取 convs 后被服务端数据覆盖。 */
    private fun newConversation(m: ServerMessage): ConvState {
        val parts = m.convId.split(":")
        val type = when (parts[0]) {
            "c2c" -> ConvType.C2C
            "group" -> ConvType.GROUP
            "room" -> ConvType.ROOM
            else -> ConvType.UNKNOWN
        }
        val a = parts.getOrElse(1) { "" }
        val b = parts.getOrElse(2) { "" }
        val name = if (type == ConvType.C2C) (if (a == self()) b else a) else a
        val c = ConvState(Conversation(m.convId, type, name, 0, 0, 1, 0, null, 0), 0)
        convs[m.convId] = c
        placeholders.add(m.convId)
        return c
    }

    /** 全部写回本地库（被踢清库之后，同一账号重新登录时用）。 */
    fun persistAll() {
        sink.replaceConversations(convs.values.map { it to (it.view.convId in placeholders) })
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
