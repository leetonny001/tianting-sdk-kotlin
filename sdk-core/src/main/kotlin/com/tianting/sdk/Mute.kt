package com.tianting.sdk

public enum class MuteScope { USER, GROUP, GROUP_ALL }

/**
 * 当前会话的禁言状态（PRD-admin F-ADM-40、41、42）：mutedUntil 是服务端时间（毫秒），永久为 null。
 * scope 是 GROUP_ALL 时界面显示"群主已开启全员禁言"，mutedUntil 一定是 null。
 */
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

    /** 开启了全群禁言的群；值为 true 表示这个端发送时服务端已经拒绝过（4031），不再按本地角色放行。 */
    private val groupAll = HashMap<String, Boolean>()

    fun setUser(until: Deadline) {
        user = until
    }

    fun setGroup(groupId: String, until: Deadline) {
        if (until == Deadline.Absent) groups.remove(groupId) else groups[groupId] = until
    }

    fun setGroupAll(groupId: String, on: Boolean, denied: Boolean = false) {
        if (on) groupAll[groupId] = denied else groupAll.remove(groupId)
    }

    /** 拿到了新的角色：之前那次拒绝可能是角色过期造成的，重新按角色判断。 */
    fun clearGroupAllDenied(groupId: String) {
        if (groupId in groupAll) groupAll[groupId] = false
    }

    /** 开启了全群禁言的群。 */
    fun groupAllIds(): List<String> = groupAll.keys.sorted()

    /**
     * 这个会话当前的禁言：用户级优先（F-ADM-41 规则 2），群内禁言只影响该群。
     * 全群禁言不限制群主、管理员（F-ADM-42），myRole 是服务端给的本端角色；发送已被 4031 拒绝时以服务端为准。
     */
    fun get(convId: String, now: Long, myRole: GroupRole? = null): MuteInfo? {
        if (user.activeAt(now)) return MuteInfo(MuteScope.USER, null, user.untilMs())
        if (!convId.startsWith("group:")) return null
        val groupId = convId.removePrefix("group:")
        val until = groups[groupId]
        if (until != null && until.activeAt(now)) return MuteInfo(MuteScope.GROUP, groupId, until.untilMs())
        val denied = groupAll[groupId] ?: return null
        val exempt = !denied && (myRole == GroupRole.OWNER || myRole == GroupRole.ADMIN)
        return if (exempt) null else MuteInfo(MuteScope.GROUP_ALL, groupId, null)
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
        groupAll.clear()
    }

    /** 比较用的稳定表示：没有变化时不发事件。 */
    fun key(): String = "$user|${groups.toSortedMap()}|${groupAll.toSortedMap()}"
}
