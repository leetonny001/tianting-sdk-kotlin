package com.tianting.sdk

public enum class MuteScope { USER, GROUP }

/** 当前会话的禁言状态（PRD-admin F-ADM-40、41）：mutedUntil 是服务端时间（毫秒），永久为 null。 */
public data class MuteInfo(val scope: MuteScope, val groupId: String? = null, val mutedUntil: Long? = null)

private fun Deadline.activeAt(now: Long) = when (this) {
    Deadline.Absent -> false
    Deadline.Forever -> true
    is Deadline.At -> ms > now
}

private fun Deadline.untilMs(): Long? = (this as? Deadline.At)?.ms

/** 禁言表：一条用户级记录、每个群一条群内记录。所有 now 都是服务端时间，由 IMClient 传入。 */
internal class MuteTable {
    private var user: Deadline = Deadline.Absent
    private val groups = HashMap<String, Deadline>()

    fun setUser(until: Deadline) {
        user = until
    }

    fun setGroup(groupId: String, until: Deadline) {
        if (until == Deadline.Absent) groups.remove(groupId) else groups[groupId] = until
    }

    /** 这个会话当前的禁言：用户级优先（F-ADM-41 规则 2），群内禁言只影响该群。 */
    fun get(convId: String, now: Long): MuteInfo? {
        if (user.activeAt(now)) return MuteInfo(MuteScope.USER, null, user.untilMs())
        if (!convId.startsWith("group:")) return null
        val groupId = convId.removePrefix("group:")
        val until = groups[groupId] ?: return null
        return if (until.activeAt(now)) MuteInfo(MuteScope.GROUP, groupId, until.untilMs()) else null
    }

    /** 最早的到期时间；没有会到期的记录时为 null。 */
    fun nextExpiry(): Long? = (groups.values + user).mapNotNull { (it as? Deadline.At)?.ms }.minOrNull()

    /** 去掉已经到期的记录。 */
    fun purge(now: Long) {
        if (!user.activeAt(now)) user = Deadline.Absent
        groups.entries.removeAll { !it.value.activeAt(now) }
    }

    fun clear() {
        user = Deadline.Absent
        groups.clear()
    }

    /** 比较用的稳定表示：没有变化时不发事件。 */
    fun key(): String = "$user|${groups.toSortedMap()}"
}
