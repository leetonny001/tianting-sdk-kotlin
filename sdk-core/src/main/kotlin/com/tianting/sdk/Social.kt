// 社交关系（PRD-social 第 9、10 节）：协议类型、同步状态机、资料缓存。
// SocialState 是纯状态机（不做网络），与服务端的 Go 参考客户端（server/e2e/socialmodel）逐条对应，
// 跑同一份 protocol/vectors/social.json；网络与定时由 IMClient 驱动，持久化经 SocialSink。
package com.tianting.sdk

import kotlinx.serialization.Serializable

@Serializable
public data class Profile(val nickname: String = "", val avatar: String = "", val official: Boolean = false)

@Serializable(with = ApplySourceSerializer::class)
public enum class ApplySource(override val wire: String) : WireEnum {
    SEARCH("search"), GROUP("group"), CARD("card"), CONV("conv"), OTHER("other"), ADMIN("admin"), UNKNOWN("unknown"),
}

internal object ApplySourceSerializer : WireEnumSerializer<ApplySource>("ApplySource", ApplySource.entries.toTypedArray(), ApplySource.UNKNOWN)

@Serializable(with = RequestStatusSerializer::class)
public enum class RequestStatus(override val wire: String) : WireEnum {
    PENDING("pending"), ACCEPTED("accepted"), REJECTED("rejected"), EXPIRED("expired"), UNKNOWN("unknown"),
}

internal object RequestStatusSerializer : WireEnumSerializer<RequestStatus>("RequestStatus", RequestStatus.entries.toTypedArray(), RequestStatus.UNKNOWN)

@Serializable(with = RequestDirectionSerializer::class)
public enum class RequestDirection(override val wire: String) : WireEnum {
    IN("in"), OUT("out"), UNKNOWN("unknown"),
}

internal object RequestDirectionSerializer : WireEnumSerializer<RequestDirection>("RequestDirection", RequestDirection.entries.toTypedArray(), RequestDirection.UNKNOWN)

@Serializable(with = SocialModeSerializer::class)
public enum class SocialMode(override val wire: String) : WireEnum {
    OPEN("open"), FRIENDS("friends"), UNKNOWN("unknown"),
}

internal object SocialModeSerializer : WireEnumSerializer<SocialMode>("SocialMode", SocialMode.entries.toTypedArray(), SocialMode.UNKNOWN)

@Serializable(with = RelationSerializer::class)
public enum class Relation(override val wire: String) : WireEnum {
    SELF("self"), BLOCKED("blocked"), FRIEND("friend"), PENDING_IN("pendingIn"), PENDING_OUT("pendingOut"), NONE("none"), UNKNOWN("unknown"),
}

internal object RelationSerializer : WireEnumSerializer<Relation>("Relation", Relation.entries.toTypedArray(), Relation.UNKNOWN)

@Serializable
internal data class FriendItem(
    val userId: String,
    val remark: String = "",
    val source: ApplySource = ApplySource.OTHER,
    val createdAt: Long = 0,
    val ver: Long,
    val deleted: Boolean = false,
    val profile: Profile? = null,
)

@Serializable
internal data class RequestItem(
    val userId: String,
    val direction: RequestDirection,
    val status: RequestStatus,
    val message: String = "",
    val source: ApplySource = ApplySource.OTHER,
    val applyCount: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val expireAt: Long = 0,
    val ver: Long,
    /** direction=out 且对方还没同意时服务端不给资料（null）。 */
    val profile: Profile? = null,
)

@Serializable
internal data class BlockItem(
    val userId: String,
    val createdAt: Long = 0,
    val ver: Long,
    val deleted: Boolean = false,
    /** 对方不在我的可见范围内时为 null。 */
    val profile: Profile? = null,
)

@Serializable
internal data class SocialItems(
    val friends: List<FriendItem> = emptyList(),
    val requests: List<RequestItem> = emptyList(),
    val blocks: List<BlockItem> = emptyList(),
)

@Serializable
internal data class PrivacySettings(val searchable: Boolean = true)

@Serializable
internal data class SocialSyncAck(
    val full: Boolean = false,
    val untilVer: Long = 0,
    val latestVer: Long = 0,
    val hasMore: Boolean = false,
    val cursor: String = "",
    val restart: Boolean = false,
    val friends: List<FriendItem> = emptyList(),
    val requests: List<RequestItem> = emptyList(),
    val blocks: List<BlockItem> = emptyList(),
    val requestSeenVer: Long = 0,
    val settings: PrivacySettings = PrivacySettings(),
    val mode: SocialMode = SocialMode.OPEN,
    val strangerMsgLimit: Int = 0,
    val official: Boolean = false,
)

@Serializable
internal data class SocialChangeAck(
    val status: RequestStatus? = null,
    val ver: Long = 0,
    val changed: Boolean = false,
    val items: SocialItems = SocialItems(),
)

@Serializable
internal data class SeenAck(val requestSeenVer: Long = 0, val ver: Long = 0, val changed: Boolean = false)

@Serializable
internal data class PrivacyAck(val ver: Long = 0, val changed: Boolean = false)

/** userSearch 的结果。 */
@Serializable
public data class SearchedUser(
    val userId: String,
    val nickname: String = "",
    val avatar: String = "",
    val official: Boolean = false,
    val relation: Relation = Relation.NONE,
)

@Serializable
internal data class SearchAck(val user: SearchedUser? = null)

/** users 的一项、资料缓存的值。 */
@Serializable
public data class UserProfile(
    val userId: String,
    val nickname: String = "",
    val avatar: String = "",
    val official: Boolean = false,
)

@Serializable
internal data class UsersAck(val users: List<UserProfile> = emptyList())

@Serializable
public data class MyGroup(val groupId: String, val name: String = "", val memberCount: Int = 0, val joinedAt: Long = 0)

@Serializable
internal data class MyGroupsAck(val groups: List<MyGroup> = emptyList(), val nextCursor: String = "")

@Serializable
public data class SocialSettings(
    val searchable: Boolean = true,
    val mode: SocialMode = SocialMode.OPEN,
    val strangerMsgLimit: Int = 0,
)

// ---- 群（M4） ----

/** owner 为空字符串表示没有群主；managed 表示托管群（业务方建的）。 */
@Serializable
public data class GroupInfo(
    val groupId: String,
    val name: String = "",
    val notice: String = "",
    val noticeBy: String = "",
    val noticeAt: Long = 0,
    val owner: String = "",
    val managed: Boolean = false,
    val myRole: GroupRole = GroupRole.UNKNOWN,
    val memberCount: Int = 0,
    val maxMembers: Int = 0,
    val historyVisible: Boolean = false,
    val createdAt: Long = 0,
)

@Serializable
public data class GroupMemberInfo(
    val userId: String,
    val role: GroupRole = GroupRole.UNKNOWN,
    val joinedAt: Long = 0,
    val nickname: String = "",
    val avatar: String = "",
    val official: Boolean = false,
)

@Serializable
public data class GroupMembersPage(val members: List<GroupMemberInfo> = emptyList(), val nextCursor: String = "")

@Serializable
public data class GroupInviteFailure(val userId: String, val code: Int)

@Serializable
public data class GroupInviteResult(val added: List<String> = emptyList(), val failed: List<GroupInviteFailure> = emptyList())

@Serializable
internal data class GroupKickAck(val removed: List<String> = emptyList())

@Serializable
internal data class GroupCreateAck(val groupId: String, val conv: ServerConversation)

public data class CreatedGroup(val groupId: String, val conv: Conversation)

/**
 * 提示消息的本地文案（PRD-ui F-UI-40，用例 protocol/vectors/social.json 的 tips）：
 * actor / targets 是自己时用"你"，其他人用 nameOf（可以是本机备注）。不认识的 kind 显示"[群通知]"。
 */
public fun tipText(tip: TipBody, me: String, nameOf: (String) -> String): String {
    fun who(u: String) = if (u == me) "你" else nameOf(u)
    val shown = tip.targets.take(3).joinToString("、") { who(it) }
    val total = maxOf(tip.count ?: 0, tip.targets.size)
    val targets = if (total > 3) "$shown 等 $total 人" else shown
    val actor = who(tip.actor)
    val you = tip.actor == me
    val spaced = if (you) "你" else "$actor "
    return when (tip.kind) {
        "created" -> "${spaced}创建了群聊"
        "joined" -> if (you) "你邀请 $targets 加入了群聊" else "$actor 邀请 $targets 加入了群聊"
        "ownerChanged" -> when {
            tip.actor.isNotEmpty() -> "${spaced}将群主转让给 $targets"
            tip.targets.isNotEmpty() -> "$targets 已成为群主"
            else -> "群主已退出群聊"
        }
        "adminSet" -> "${spaced}将 $targets 设为管理员"
        "adminUnset" -> "${spaced}取消了 $targets 的管理员"
        "renamed" -> "${spaced}修改群名为\"${tip.text}\""
        "noticeUpdated" -> "${spaced}更新了群公告"
        else -> "[群通知]"
    }
}

/**
 * 会话列表摘要的本地版本（与服务端 lastMsg.text 同一规则，PRD-social 5.5 节：只用昵称，不用"你"）。
 * 收到提示消息时先用它，下次 convs 时被服务端文本覆盖。
 */
internal fun tipSummary(tip: TipBody, nick: (String) -> String): String {
    val shown = tip.targets.take(3).joinToString("、") { nick(it) }
    val total = maxOf(tip.count ?: 0, tip.targets.size)
    val list = if (total > 3) "${shown}等 $total 人" else shown
    val actor = if (tip.actor.isNotEmpty()) nick(tip.actor) else ""
    return when (tip.kind) {
        "created", "joined" -> "$actor 邀请 $list 加入了群聊"
        "ownerChanged" -> if (tip.targets.isNotEmpty()) "$list 已成为群主" else "群主已退出群聊"
        "adminSet" -> "$list 已成为管理员"
        "adminUnset" -> "$list 不再是管理员"
        "renamed" -> "$actor 修改群名为\"${tip.text}\""
        "noticeUpdated" -> "$actor 更新了群公告"
        else -> "[群通知]"
    }
}

// ---- 界面看到的快照 ----

/** 好友（不含墓碑）。displayName 按 备注 → 昵称 → userId。 */
public data class Friend(
    val userId: String,
    val remark: String,
    val source: ApplySource,
    val createdAt: Long,
    val ver: Long,
    val profile: Profile?,
    val displayName: String,
    val blocked: Boolean,
)

/** "新的朋友"的一项：状态按服务端时间算出 EXPIRED；out 行没有服务端资料时用资料缓存。 */
public data class FriendRequest(
    val userId: String,
    val direction: RequestDirection,
    val status: RequestStatus,
    val message: String,
    val source: ApplySource,
    val applyCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val expireAt: Long,
    val ver: Long,
    val profile: Profile?,
    val displayName: String,
)

public data class FriendRequests(val items: List<FriendRequest> = emptyList(), val unseen: Int = 0)

public data class BlockedUser(
    val userId: String,
    val createdAt: Long,
    val ver: Long,
    val profile: Profile?,
    val displayName: String,
)

/** 申请记录在本地保留的时长（F-SOC-04 规则 1）。 */
public const val REQUEST_RETENTION_MS: Long = 30L * 24 * 3600 * 1000

internal enum class PageResult(val wire: String) { CONTINUE("continue"), RESTART("restart"), DONE("done") }

/** SocialState 的持久化出口。一次状态变化由调用方包在同一个事务里（socialVer 与数据同事务，PRD-social 10.2 节）。 */
internal interface SocialSink {
    fun upsertFriend(f: FriendItem)
    fun upsertRequest(r: RequestItem)
    fun upsertBlock(b: BlockItem)
    fun replaceAll(friends: Collection<FriendItem>, requests: Collection<RequestItem>, blocks: Collection<BlockItem>)
    fun saveMeta(state: SocialState)
    fun deleteRequest(userId: String, direction: RequestDirection)
}

/** 关系同步的本地状态（PRD-social 10.2 节）。原生 SDK 持久化：冷启动从本地 ver 增量同步。 */
internal class SocialState(var sink: SocialSink? = null) {
    var ver = 0L
    var friends = LinkedHashMap<String, FriendItem>() // 含墓碑：按 ver 取较大者时要用
    var requests = LinkedHashMap<String, RequestItem>()
    var blocks = LinkedHashMap<String, BlockItem>()
    var requestSeenVer = 0L
    var settings = SocialSettings()
    var official = false

    /** 是否完成过至少一轮同步（通讯录骨架屏用）。 */
    var loaded = false

    /** 需要（再）开始一轮。 */
    var needSync = false

    private var inRound = false
    private var full = false
    private var untilVer = 0L
    private var staged: SocialState? = null
    private var pendingEv = 0L

    private fun reqKey(userId: String, direction: RequestDirection) = "$userId/${direction.wire}"

    /** 开始一轮，返回第一页的 sinceVer。 */
    fun beginRound(): Long {
        inRound = true
        staged = null
        needSync = false
        return ver
    }

    /** 应用一页（F-SOC-06 规则 3、8）。增量轮逐页写入，全量轮收齐后整体替换。 */
    fun applyPage(p: SocialSyncAck): PageResult {
        if (p.restart) {
            staged = null
            return PageResult.RESTART
        }
        if (staged == null) {
            full = p.full
            untilVer = p.untilVer
            staged = if (p.full) SocialState() else this
        }
        val t = staged!!
        p.friends.forEach { t.mergeFriend(it) }
        p.requests.forEach { t.mergeRequest(it) }
        p.blocks.forEach { t.mergeBlock(it) }
        if (p.hasMore) return PageResult.CONTINUE
        if (full) {
            friends = t.friends
            requests = t.requests
            blocks = t.blocks
            sink?.replaceAll(friends.values, requests.values, blocks.values)
        }
        ver = untilVer
        requestSeenVer = p.requestSeenVer
        settings = SocialSettings(p.settings.searchable, p.mode, p.strangerMsgLimit)
        official = p.official
        inRound = false
        staged = null
        loaded = true
        needSync = p.latestVer > untilVer || pendingEv > untilVer
        pendingEv = 0
        sink?.saveMeta(this)
        return PageResult.DONE
    }

    /** 放弃进行中的一轮（请求失败）。 */
    fun abortRound() {
        inRound = false
        staged = null
    }

    /** socialChanged {ver}（F-SOC-06 规则 6；去抖由调用方负责）。 */
    fun onSocialChanged(ver: Long) {
        if (inRound) {
            pendingEv = maxOf(pendingEv, ver)
            return
        }
        if (ver > this.ver) needSync = true
    }

    /** 自己发起的修改的 ack（F-SOC-06 规则 7）。 */
    fun applyAck(ver: Long, changed: Boolean, items: SocialItems?) {
        if (!changed) return
        items?.friends?.forEach { mergeFriend(it) }
        items?.requests?.forEach { mergeRequest(it) }
        items?.blocks?.forEach { mergeBlock(it) }
        if (ver == this.ver + 1) {
            this.ver = ver
            sink?.saveMeta(this)
        } else if (ver > this.ver + 1) {
            needSync = true
        }
    }

    private fun mergeFriend(f: FriendItem) {
        val old = friends[f.userId]
        if (old == null || f.ver >= old.ver) {
            friends[f.userId] = f
            sink?.upsertFriend(f)
        }
    }

    private fun mergeRequest(r: RequestItem) {
        val k = reqKey(r.userId, r.direction)
        val old = requests[k]
        if (old == null || r.ver >= old.ver) {
            requests[k] = r
            sink?.upsertRequest(r)
        }
    }

    private fun mergeBlock(b: BlockItem) {
        val old = blocks[b.userId]
        if (old == null || b.ver >= old.ver) {
            blocks[b.userId] = b
            sink?.upsertBlock(b)
        }
    }

    fun isFriend(userId: String): Boolean = friends[userId]?.let { !it.deleted } == true

    fun isBlocked(userId: String): Boolean = blocks[userId]?.let { !it.deleted } == true

    /** 30 天内的申请，按 updatedAt 倒序（F-SOC-04 规则 1）。 */
    fun requestList(serverNow: Long): List<RequestItem> {
        val cutoff = serverNow - REQUEST_RETENTION_MS
        return requests.values.filter { it.updatedAt >= cutoff }
            .map { r -> statusAt(r, serverNow).let { if (it == r.status) r else r.copy(status = it) } }
            .sortedWith(compareByDescending<RequestItem> { it.updatedAt }.thenBy { it.userId })
    }

    /** 红点 = 收到的、pending、未过期、ver > requestSeenVer（F-SOC-04 规则 2）。 */
    fun unseen(serverNow: Long): Int = requestList(serverNow).count {
        it.direction == RequestDirection.IN && it.status == RequestStatus.PENDING && it.ver > requestSeenVer
    }

    /** 只看本地的关系（与 userSearch.relation 同一枚举、同一优先级）。 */
    fun relationOf(userId: String, self: String, serverNow: Long): Relation {
        if (userId == self) return Relation.SELF
        if (isBlocked(userId)) return Relation.BLOCKED
        if (isFriend(userId)) return Relation.FRIEND
        fun pending(d: RequestDirection): Boolean {
            val r = requests[reqKey(userId, d)] ?: return false
            return statusAt(r, serverNow) == RequestStatus.PENDING && r.updatedAt >= serverNow - REQUEST_RETENTION_MS
        }
        if (pending(RequestDirection.IN)) return Relation.PENDING_IN
        if (pending(RequestDirection.OUT)) return Relation.PENDING_OUT
        return Relation.NONE
    }

    /** 本地按服务端时间丢弃超过 30 天的申请（F-SOC-04 规则 1：本地库不留过期的附言）。 */
    fun purgeOldRequests(serverNow: Long) {
        val cutoff = serverNow - REQUEST_RETENTION_MS
        val old = requests.values.filter { it.updatedAt < cutoff }
        for (r in old) {
            requests.remove(reqKey(r.userId, r.direction))
            sink?.deleteRequest(r.userId, r.direction)
        }
    }

    /** 被踢、退出登录时清空（F-AUTH-03）。 */
    fun clear() {
        ver = 0
        friends = LinkedHashMap()
        requests = LinkedHashMap()
        blocks = LinkedHashMap()
        requestSeenVer = 0
        settings = SocialSettings()
        official = false
        loaded = false
        needSync = false
        inRound = false
        full = false
        untilVer = 0
        staged = null
        pendingEv = 0
    }

    /** 冷启动恢复：写入内存，不写回本地库。 */
    fun restore(
        ver: Long,
        requestSeenVer: Long,
        settings: SocialSettings,
        official: Boolean,
        loaded: Boolean,
        friends: List<FriendItem>,
        requests: List<RequestItem>,
        blocks: List<BlockItem>,
    ) {
        this.ver = ver
        this.requestSeenVer = requestSeenVer
        this.settings = settings
        this.official = official
        this.loaded = loaded
        friends.forEach { this.friends[it.userId] = it }
        requests.forEach { this.requests[reqKey(it.userId, it.direction)] = it }
        blocks.forEach { this.blocks[it.userId] = it }
    }

    companion object {
        /** 申请的状态：未同意且过了 expireAt 就是 EXPIRED（按服务端时间）。 */
        fun statusAt(r: RequestItem, serverNow: Long): RequestStatus =
            if (r.status != RequestStatus.ACCEPTED && r.expireAt <= serverNow) RequestStatus.EXPIRED else r.status
    }
}

/** 资料缓存（F-SOC-10 规则 6）：每个 userId 一条，10 分钟有效；任何带资料的服务端应答都直接覆盖。 */
internal class ProfileCache(private val now: () -> Long) {
    private class Entry(val p: UserProfile, val at: Long)

    private val entries = HashMap<String, Entry>()

    /** users 没有返回的人（不可见或不存在）：有效期内不重查，避免每次渲染都发请求、耗尽限流。 */
    private val misses = HashMap<String, Long>()

    fun put(p: UserProfile) {
        entries[p.userId] = Entry(p, now())
        misses.remove(p.userId)
    }

    fun markMissing(userId: String) {
        misses[userId] = now()
    }

    /** 这个 userId 还需要向服务端查询吗（既没有有效资料，也不是最近查过没有的）。 */
    fun needsFetch(userId: String): Boolean {
        if (fresh(userId) != null) return false
        val at = misses[userId] ?: return true
        return now() - at >= TTL_MS
    }

    fun putProfile(userId: String, p: Profile?) {
        if (p != null) put(UserProfile(userId, p.nickname, p.avatar, p.official))
    }

    /** 有效期内的资料；过期返回 null（调用方重新查询）。 */
    fun fresh(userId: String): UserProfile? = entries[userId]?.takeIf { now() - it.at < TTL_MS }?.p

    /** 不论是否过期，最近一次拿到的资料（界面显示用，新的查询回来后再刷新）。 */
    fun last(userId: String): UserProfile? = entries[userId]?.p

    fun clear() {
        entries.clear()
        misses.clear()
    }

    companion object {
        const val TTL_MS: Long = 10 * 60 * 1000
    }
}
