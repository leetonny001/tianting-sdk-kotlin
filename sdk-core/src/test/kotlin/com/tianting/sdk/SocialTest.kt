package com.tianting.sdk

import com.tianting.sdk.testing.FakeConnection
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val G = "group:g1"

/** 社交同步引擎、红点、资料缓存、群（FE-131～FE-133）。 */
class SocialTest : SdkTest() {
    private fun loggedIn(): Pair<Harness, FakeConnection> {
        val h = setup()
        h.connect()
        return h to h.login("alice", listOf(conv("c2c:alice:bob", maxSeq = 1)))
    }

    @Test
    fun `连接成功：先 convs，再从 socialVer 开始全量轮；翻页直到 hasMore=false，收齐才对外可见`() {
        val (h, c) = loggedIn()
        val cmds = c.frames().map { it["cmd"]!!.str }
        assertTrue(cmds.indexOf("socialSync") > cmds.indexOf("convs"))
        assertEquals(obj("sinceVer" to 0, "limit" to 500), c.last("socialSync").dataObj)
        assertFalse(h.im.socialLoaded.value)
        c.ack("socialSync", page(untilVer = 3, hasMore = true, cursor = "c1", friends = listOf(friend("bob", 2))))
        assertEquals(obj("cursor" to "c1", "limit" to 500), c.last("socialSync").dataObj)
        assertEquals(emptyList(), h.im.friends.value) // 全量轮收齐才替换
        c.ack("socialSync", page(untilVer = 3, friends = listOf(friend("carol", 3)), mode = "friends", strangerMsgLimit = 3))
        assertEquals(listOf("BOB", "CAROL"), h.im.friends.value.map { it.displayName })
        assertEquals(SocialSettings(true, SocialMode.FRIENDS, 3), h.im.socialSettings.value)
        assertTrue(h.im.socialLoaded.value)
    }

    @Test
    fun `socialChanged：ver 更大时去抖 200ms 后从本地 ver 增量同步；不大于本地 ver 的忽略`() {
        val (h, c) = loggedIn()
        c.ack("socialSync", page(untilVer = 5))
        fun n() = c.frames("socialSync").size
        val before = n()
        c.receive(event("socialChanged", "ver" to 5))
        h.clock.advance(300)
        assertEquals(before, n())
        c.receive(event("socialChanged", "ver" to 6))
        c.receive(event("socialChanged", "ver" to 7))
        h.clock.advance(199)
        assertEquals(before, n())
        h.clock.advance(1)
        assertEquals(before + 1, n())
        assertEquals(obj("sinceVer" to 5, "limit" to 500), c.last("socialSync").dataObj)
    }

    @Test
    fun `socialSettingsChanged 更新本地设置（AC-SOC-01-6）`() {
        val (h, c) = loggedIn()
        c.receive(event("socialSettingsChanged", "mode" to "open", "strangerMsgLimit" to 3))
        assertEquals(SocialSettings(true, SocialMode.OPEN, 3), h.im.socialSettings.value)
    }

    @Test
    fun `自己发起的修改：ver 连续时直接应用 items；不连续时再补一轮`() {
        val (h, c) = loggedIn()
        c.ack("socialSync", page(untilVer = 5))
        val p = h.start { respondFriend("bob", FriendAction.ACCEPT, remark = "同学") }
        c.ack("friendRespond", obj("ver" to 6, "changed" to true, "items" to items(friends = listOf(friend("bob", 6, remark = "同学")))))
        h.await(p)
        assertEquals("同学", h.im.friends.value[0].displayName)
        val n = c.frames("socialSync").size
        val q = h.start { deleteFriend("bob") }
        c.ack("friendDelete", obj("ver" to 9, "changed" to true, "items" to items(friends = listOf(friend("bob", 9, deleted = true)))))
        h.await(q)
        assertEquals(emptyList(), h.im.friends.value)
        assertEquals(n + 1, c.frames("socialSync").size)
        assertEquals(obj("sinceVer" to 6, "limit" to 500), c.last("socialSync").dataObj)
    }

    @Test
    fun `restart：丢掉本轮已收到的页，用同一个 sinceVer 重新开始（F-SOC-06 规则 8）`() {
        val (h, c) = loggedIn()
        c.ack("socialSync", page(untilVer = 5))
        c.receive(event("socialChanged", "ver" to 6))
        h.clock.advance(200)
        c.ack("socialSync", page(untilVer = 6, hasMore = true, cursor = "c1", friends = listOf(friend("stale", 6))))
        assertEquals(obj("cursor" to "c1", "limit" to 500), c.last("socialSync").dataObj)
        c.ack("socialSync", obj("restart" to true, "hasMore" to true, "cursor" to "c2"))
        assertEquals(obj("sinceVer" to 5, "limit" to 500), c.last("socialSync").dataObj)
        c.ack("socialSync", page(untilVer = 7, friends = listOf(friend("fresh", 7))))
        assertEquals(listOf("fresh"), h.im.friends.value.map { it.userId })
    }

    @Test
    fun `失败的命令抛出带错误码的 IMError`() {
        val (h, c) = loggedIn()
        val p = h.start { runCatching { respondFriend("carol", FriendAction.ACCEPT) } }
        c.ackError("friendRespond", ErrorCode.FRIEND_REQUEST_NOT_FOUND)
        assertEquals(4045, (h.await(p).exceptionOrNull() as IMError).code)
    }

    @Test
    fun `全量轮被限流（4290）：6 秒后重试，首轮最终能结束`() {
        val (h, c) = loggedIn()
        c.ackError("socialSync", ErrorCode.RATE_LIMITED)
        assertFalse(h.im.socialLoaded.value)
        val n = c.frames("socialSync").size
        h.clock.advance(5_999)
        assertEquals(n, c.frames("socialSync").size)
        h.clock.advance(1)
        assertEquals(n + 1, c.frames("socialSync").size)
        c.ack("socialSync", page(untilVer = 1))
        assertTrue(h.im.socialLoaded.value)
    }

    @Test
    fun `配额 4292 不重试（FE-130）`() {
        val (h, c) = loggedIn()
        c.ackError("socialSync", ErrorCode.QUOTA_EXCEEDED)
        val n = c.frames("socialSync").size
        h.clock.advance(20_000)
        assertEquals(n, c.frames("socialSync").size)
    }

    @Test
    fun `被踢时清空社交状态`() {
        val (h, c) = loggedIn()
        c.ack("socialSync", page(untilVer = 1, friends = listOf(friend("bob", 1))))
        assertEquals(1, h.im.friends.value.size)
        c.receive(event("kicked", "reason" to "banned"))
        c.drop(4012, "revoked")
        assertEquals(emptyList(), h.im.friends.value)
        assertFalse(h.im.socialLoaded.value)
    }

    @Test
    fun `红点 = 收到的、pending、未过期、ver 大于 requestSeenVer；markFriendRequestsSeen 本地立即清零（AC-SOC-04-1、AC-SOC-04-2）`() {
        val (h, c) = loggedIn()
        c.ack(
            "socialSync",
            page(
                untilVer = 9,
                requestSeenVer = 2,
                requests = listOf(
                    req("old", "in", 2),
                    req("expired", "in", 3, expireAt = NOW - 1),
                    req("carol", "in", 8, updatedAt = NOW - 10),
                    req("dave", "in", 9, updatedAt = NOW - 5),
                    req("rejected", "in", 7, status = "rejected"),
                    req("mine", "out", 6),
                ),
            ),
        )
        val r = h.im.friendRequests.value
        assertEquals(2, r.unseen)
        assertEquals(RequestStatus.EXPIRED, r.items.first { it.userId == "expired" }.status)
        val order = r.items.map { it.userId }
        assertTrue(order.indexOf("dave") < order.indexOf("carol")) // 按 updatedAt 倒序
        h.call { markFriendRequestsSeen() }
        assertEquals(0, h.im.friendRequests.value.unseen)
        assertEquals(obj("ver" to 9), c.last("friendRequestsSeen").dataObj)
    }

    @Test
    fun `过期用服务端时间（auth 的 serverTime），不用本地时钟（AC-SOC-04-5、TC-SOC-04-06）`() {
        val h = setup()
        h.connect()
        val c = h.transport.current
        c.open()
        c.ack("auth", obj("userId" to "alice", "serverTime" to NOW + 8 * 86_400_000)) // 本地时钟慢了 8 天
        c.ack("convs", obj("convs" to emptyList<Any>(), "truncated" to false))
        c.ack("socialSync", page(untilVer = 1, requests = listOf(req("bob", "in", 1))))
        assertEquals(RequestStatus.EXPIRED, h.im.friendRequests.value.items[0].status)
        assertEquals(0, h.im.friendRequests.value.unseen)
    }

    @Test
    fun `relationOf：self、blocked、friend、pendingIn、pendingOut、none 的优先级（AC-SOC-09-1）`() {
        val (h, c) = loggedIn()
        c.ack(
            "socialSync",
            page(
                untilVer = 5,
                friends = listOf(friend("bob", 1), friend("dave", 1)),
                blocks = listOf(obj("userId" to "dave", "createdAt" to NOW, "ver" to 2, "profile" to null)),
                requests = listOf(req("carol", "in", 3), req("erin", "out", 4)),
            ),
        )
        assertEquals(
            listOf(Relation.SELF, Relation.BLOCKED, Relation.FRIEND, Relation.PENDING_IN, Relation.PENDING_OUT, Relation.NONE),
            listOf("alice", "dave", "bob", "carol", "erin", "frank").map { u -> h.call { relationOf(u) } },
        )
        assertEquals(true, h.im.friends.value.first { it.userId == "dave" }.blocked)
    }

    @Test
    fun `发出的申请在对方同意前没有资料：用搜索时拿到的资料显示（AC-SOC-13-7 官方标识来自搜索结果）`() {
        val (h, c) = loggedIn()
        c.ack("socialSync", page(untilVer = 1))
        val s = h.start { searchUser("erin") }
        c.ack("userSearch", obj("user" to obj("userId" to "erin", "nickname" to "艾琳", "avatar" to "", "official" to true, "relation" to "none")))
        val found = h.await(s)!!
        assertEquals("艾琳", found.nickname)
        assertEquals(true, found.official)
        assertEquals(Relation.NONE, found.relation)
        val a = h.start { applyFriend("erin", ApplyFriendOptions(message = "你好", source = ApplySource.SEARCH)) }
        assertEquals(obj("to" to "erin", "message" to "你好", "source" to "search"), c.last("friendApply").dataObj)
        c.ack("friendApply", obj("status" to "pending", "ver" to 2, "changed" to true, "items" to items(requests = listOf(req("erin", "out", 2)))))
        assertEquals(RequestStatus.PENDING, h.await(a))
        val item = h.im.friendRequests.value.items[0]
        assertEquals("erin", item.userId)
        assertNull(item.profile)
        assertEquals("艾琳", item.displayName)
    }

    @Test
    fun `userSearch 未命中返回 null`() {
        val (h, c) = loggedIn()
        val s = h.start { searchUser("nobody") }
        c.ack("userSearch", obj("user" to null))
        assertNull(h.await(s))
    }

    @Test
    fun `getUsers：16ms 内合并、每批最多 100、命中缓存不请求（AC-SOC-10-7、TC-SOC-10-09）`() {
        val (h, c) = loggedIn()
        val ids = (0 until 150).map { "u$it" }
        val a = h.start { getUsers(ids.take(120)) }
        val b = h.start { getUsers(ids.drop(100)) }
        h.clock.advance(16)
        val users = c.frames("users")
        assertEquals(1, users.size)
        assertEquals(100, users[0].dataObj["userIds"]!!.jsonArray.size)
        c.ack("users", obj("users" to ids.take(100).filterIndexed { i, _ -> i % 2 == 0 }.map { obj("userId" to it, "nickname" to it, "avatar" to "", "official" to false) }))
        assertEquals(50, c.last("users").dataObj["userIds"]!!.jsonArray.size)
        assertFalse(a.isCompleted) // 跨两批：等第二批回来才返回，不能漏掉第二批的人
        c.ack("users", obj("users" to listOf(obj("userId" to "u110", "nickname" to "u110", "avatar" to "", "official" to false))))
        assertEquals(51, h.await(a).size)
        assertEquals(listOf("u110"), h.await(b).map { it.userId })
        val sent = c.frames("users").size
        h.call { getUsers(listOf("u0")) }
        assertEquals(sent, c.frames("users").size)
    }

    @Test
    fun `users 没有返回的人 10 分钟内不重查；之后同步里拿到资料立即可见`() {
        val (h, c) = loggedIn()
        val a = h.start { getUsers(listOf("ghost")) }
        h.clock.advance(16)
        c.ack("users", obj("users" to emptyList<Any>()))
        assertEquals(emptyList(), h.await(a))
        val sent = c.frames("users").size
        assertEquals(emptyList(), h.call { getUsers(listOf("ghost")) })
        assertEquals(sent, c.frames("users").size)
        c.ack("socialSync", page(untilVer = 1, friends = listOf(friend("ghost", 1))))
        assertEquals("GHOST", h.call { getUsers(listOf("ghost")) }[0].nickname)
    }

    @Test
    fun `资料缓存 10 分钟有效；过期后 last 仍可用于显示（AC-SOC-10-8）`() {
        var now = 0L
        val cache = ProfileCache { now }
        cache.put(UserProfile("bob", "B"))
        now = 10 * 60 * 1000 - 1
        assertEquals("B", cache.fresh("bob")?.nickname)
        now += 1
        assertNull(cache.fresh("bob"))
        assertEquals("B", cache.last("bob")?.nickname)
        assertTrue(cache.needsFetch("bob"))
    }

    @Test
    fun `测试时钟推进超过 10 分钟：getUsers 重新查询并拿到新昵称（AC-SOC-10-8）`() {
        val (h, c) = loggedIn()
        val a = h.start { getUsers(listOf("bob")) }
        h.clock.advance(16)
        c.ack("users", obj("users" to listOf(obj("userId" to "bob", "nickname" to "旧", "avatar" to "", "official" to false))))
        assertEquals("旧", h.await(a)[0].nickname)
        // 推进时钟，每次 ping 都回 pong 保持连接
        fun tick(ms: Long) {
            var left = ms
            while (left > 0) {
                val step = minOf(left, 10_000)
                h.clock.advance(step)
                c.receive("""{"cmd":"pong"}""")
                left -= step
            }
        }
        tick(10 * 60 * 1000 - 1)
        val n = c.frames("users").size
        assertEquals("旧", h.call { getUsers(listOf("bob")) }[0].nickname) // 10 分钟以内：用缓存
        assertEquals(n, c.frames("users").size)
        tick(1)
        val b = h.start { getUsers(listOf("bob")) }
        h.clock.advance(16)
        assertEquals(n + 1, c.frames("users").size)
        c.ack("users", obj("users" to listOf(obj("userId" to "bob", "nickname" to "新", "avatar" to "", "official" to false))))
        assertEquals("新", h.await(b)[0].nickname)
    }

    @Test
    fun `同步应答里的资料直接覆盖缓存，不等过期（AC-SOC-10-9）`() {
        val (h, c) = loggedIn()
        val a = h.start { getUsers(listOf("bob")) }
        h.clock.advance(16)
        c.ack("users", obj("users" to listOf(obj("userId" to "bob", "nickname" to "旧", "avatar" to "", "official" to false))))
        h.await(a)
        c.ack("socialSync", page(untilVer = 1, friends = listOf(friend("bob", 1, profile = profile("新的")))))
        assertEquals("新的", h.call { profileOf("bob") }?.nickname)
        assertEquals("新的", h.call { displayName("bob") })
        assertEquals("新的", h.call { getUsers(listOf("bob")) }[0].nickname)
    }

    @Test
    fun `显示名：备注、昵称、userId（AC-SOC-05-4 的本机显示）`() {
        val (h, c) = loggedIn()
        c.ack("socialSync", page(untilVer = 1, friends = listOf(friend("bob", 1, remark = "老板"), friend("carol", 1), friend("dave", 1, profile = null))))
        assertEquals(listOf("老板", "CAROL", "dave"), h.im.friends.value.map { it.displayName })
        assertEquals("老板", h.call { displayName("bob") })
        assertEquals("nobody", h.call { displayName("nobody") })
    }

    @Test
    fun `loadMyGroups 自动翻完全部页（AC-SOC-11-3）`() {
        val (h, c) = loggedIn()
        val p = h.start { loadMyGroups() }
        assertEquals(obj(), c.last("myGroups").dataObj)
        c.ack("myGroups", obj("groups" to listOf(obj("groupId" to "g1", "name" to "A", "memberCount" to 3, "joinedAt" to 1)), "nextCursor" to "g1"))
        assertEquals(obj("cursor" to "g1"), c.last("myGroups").dataObj)
        c.ack("myGroups", obj("groups" to listOf(obj("groupId" to "g2", "name" to "B", "memberCount" to 5, "joinedAt" to 2)), "nextCursor" to ""))
        assertEquals(listOf("g1", "g2"), h.await(p).map { it.groupId })
    }

    @Test
    fun `AC-SOC-11-3：3 个好友、2 个群、1 条未读申请`() {
        val (h, c) = loggedIn()
        c.ack("socialSync", page(untilVer = 4, friends = listOf(friend("a", 1), friend("b", 2), friend("c", 3)), requests = listOf(req("d", "in", 4))))
        assertEquals(3, h.im.friends.value.size)
        assertEquals(1, h.im.friendRequests.value.unseen)
    }

    @Test
    fun `setSearchable：更新本地设置；ack 的 ver 连续时前进（AC-SOC-09-7 本端）`() {
        val (h, c) = loggedIn()
        c.ack("socialSync", page(untilVer = 1))
        val p = h.start { setSearchable(false) }
        assertEquals(obj("searchable" to false), c.last("setPrivacy").dataObj)
        c.ack("setPrivacy", obj("ver" to 2, "changed" to true))
        h.await(p)
        assertEquals(false, h.im.socialSettings.value.searchable)
    }

    @Test
    fun `2000 个好友的全量轮：一次替换（FE-101 第 7 条）`() {
        val (h, c) = loggedIn()
        val base = (0 until 500).map { it }
        for (p in 0 until 4) {
            c.ack("socialSync", page(untilVer = 1, hasMore = p < 3, cursor = "c$p", friends = base.map { friend("f$it-$p", 1) }))
            assertEquals(if (p < 3) 0 else 2000, h.im.friends.value.size)
        }
    }

    // ---- 群 ----

    @Test
    fun `createGroup：超时重试用同一个 clientReqId；ack 的 conv 进入会话列表（AC-SOC-21-1）`() {
        val (h, c) = loggedIn()
        c.ack("socialSync", page(untilVer = 0))
        val p = h.start { createGroup(listOf("bob", "carol")) }
        val first = c.last("groupCreate").dataObj["clientReqId"]!!.str
        h.clock.advance(10_001)
        val sent = c.frames("groupCreate")
        assertEquals(2, sent.size)
        assertEquals(first, sent[1].dataObj["clientReqId"]!!.str)
        c.ack("groupCreate", obj("groupId" to "g1", "conv" to conv(G, name = "x", memberCount = 3, myRole = "owner")))
        val r = h.await(p)
        assertEquals(G, r.conv.convId)
        assertEquals(GroupRole.OWNER, r.conv.myRole)
        assertEquals(3, r.conv.memberCount)
        assertTrue(h.im.conversations.value.any { it.convId == G })
    }

    @Test
    fun `错误带 userId（兼容 details userId）`() {
        val (h, c) = loggedIn()
        val a = h.start { runCatching { createGroup(listOf("bob", "dave")) } }
        c.receive(obj("id" to c.last("groupCreate")["id"], "cmd" to "ack", "error" to obj("code" to 4035, "msg" to "x", "userId" to "dave")))
        val e1 = h.await(a).exceptionOrNull() as IMError
        assertEquals(4035, e1.code)
        assertEquals("dave", e1.userId)
        val b = h.start { runCatching { createGroup(listOf("bob", "eve")) } }
        c.receive(obj("id" to c.last("groupCreate")["id"], "cmd" to "ack", "error" to obj("code" to 4035, "msg" to "x", "details" to obj("userId" to "eve"))))
        assertEquals("eve", (h.await(b).exceptionOrNull() as IMError).userId)
    }

    @Test
    fun `tip 消息入库、计入未读、from 可以为空；摘要本地生成，不重拉 convs（AC-SOC-29-5）`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(G, name = "g")))
        val n = c.frames("convs").size
        c.receive(msgFrame(obj("msgId" to "m1", "clientMsgId" to "tip-1", "convId" to G, "seq" to 1, "from" to "", "type" to "tip", "body" to obj("tip" to obj("kind" to "ownerChanged", "actor" to "", "targets" to emptyList<Any>(), "text" to "")), "ts" to 5)))
        val v = h.convOf(G)
        assertEquals(1, v.unread)
        assertEquals(MsgType.TIP, v.lastMsg?.type)
        assertEquals("群主已退出群聊", v.lastMsg?.text)
        h.clock.advance(200)
        assertEquals(n, c.frames("convs").size)
    }

    private val groupInfo = obj(
        "groupId" to "g1", "name" to "新名", "memberCount" to 5, "myRole" to "admin", "notice" to "", "noticeBy" to "", "noticeAt" to 0,
        "owner" to "bob", "managed" to false, "maxMembers" to 500, "historyVisible" to false, "createdAt" to 1,
    )

    @Test
    fun `groupChanged：groupInfo 更新本地会话；在途请求合并；不重拉 convs（AC-SOC-20-10、TC-SOC-20-11）`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(G, name = "g", memberCount = 3, myRole = "member")))
        val convs = c.frames("convs").size
        repeat(3) { c.receive(event("groupChanged", "groupId" to "g1")) }
        assertEquals(1, c.frames("groupInfo").size)
        c.ack("groupInfo", groupInfo)
        assertEquals(2, c.frames("groupInfo").size) // 期间又有变化：再查一次
        c.ack("groupInfo", obj(*groupInfo.entries.map { it.key to it.value }.toTypedArray(), "memberCount" to 6))
        val v = h.convOf(G)
        assertEquals(listOf("新名", "6", "ADMIN"), listOf(v.name, v.memberCount.toString(), v.myRole?.name))
        val changed = h.events.filterIsInstance<IMEvent.GroupChanged>()
        assertEquals(listOf("g1"), changed.map { it.groupId })
        assertEquals(6, changed[0].info.memberCount)
        assertEquals(convs, c.frames("convs").size)
    }

    @Test
    fun `退群：本地按 left 移除并保留快照（AC-SOC-23-1）；解散按 dissolved（AC-SOC-26-1）`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(G, name = "g"), conv("group:g2", name = "g2")))
        val p = h.start { leaveGroup("g1") }
        c.ack("groupLeave")
        h.await(p)
        assertFalse(h.im.conversations.value.any { it.convId == G })
        assertEquals("left", h.call { conversationSnapshot(G) }?.reason)
        assertEquals("g", h.call { conversationSnapshot(G) }?.name)
        val q = h.start { dissolveGroup("g2") }
        c.ack("groupDissolve")
        h.await(q)
        assertEquals("dissolved", h.call { conversationSnapshot("group:g2") }?.reason)
        assertEquals(listOf("left", "dissolved"), h.removed().map { it.reason })
    }

    @Test
    fun `群接口发出的帧和 ack 解析（AC-SOC-22-1、24-1、25-1、27-1、28-5）`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(G)))
        val inv = h.start { inviteToGroup("g1", listOf("dave", "erin")) }
        assertEquals(obj("groupId" to "g1", "members" to listOf("dave", "erin")), c.last("groupInvite").dataObj)
        c.ack("groupInvite", obj("added" to listOf("dave"), "failed" to listOf(obj("userId" to "erin", "code" to 4035))))
        assertEquals(GroupInviteResult(listOf("dave"), listOf(GroupInviteFailure("erin", 4035))), h.await(inv))

        val kick = h.start { kickFromGroup("g1", listOf("dave")) }
        c.ack("groupKick", obj("removed" to listOf("dave")))
        assertEquals(listOf("dave"), h.await(kick))

        val tr = h.start { transferGroupOwner("g1", "bob") }
        assertEquals(obj("groupId" to "g1", "to" to "bob"), c.last("groupTransfer").dataObj)
        c.ack("groupTransfer")
        h.await(tr)
        assertEquals(1, c.frames("groupInfo").size) // 转让后刷新 myRole
        c.ack("groupInfo", groupInfo)

        val ad = h.start { setGroupAdmin("g1", "bob", true) }
        assertEquals(obj("groupId" to "g1", "userId" to "bob", "admin" to true), c.last("groupSetAdmin").dataObj)
        c.ack("groupSetAdmin")
        h.await(ad)

        val up = h.start { updateGroup("g1", name = "周末球局") }
        assertEquals(obj("groupId" to "g1", "name" to "周末球局"), c.last("groupUpdate").dataObj)
        c.ack("groupUpdate")
        h.await(up)

        val info = h.start { getGroupInfo("g1") }
        c.ack("groupInfo", groupInfo)
        assertEquals("bob", h.await(info).owner)
        assertEquals("新名", h.convOf(G).name)
    }

    @Test
    fun `loadGroupMembers 的资料覆盖缓存；翻页带 cursor（AC-SOC-28-5）`() {
        val (h, c) = loggedIn()
        val p = h.start { loadGroupMembers("g1") }
        c.ack("groupMembers", obj("members" to listOf(obj("userId" to "bob", "role" to "owner", "joinedAt" to 1, "nickname" to "阿博", "avatar" to "", "official" to false)), "nextCursor" to "n1"))
        val page = h.await(p)
        assertEquals("n1", page.nextCursor)
        assertEquals("阿博", h.call { profileOf("bob") }?.nickname)
        val q = h.start { loadGroupMembers("g1", "n1") }
        assertEquals(obj("groupId" to "g1", "cursor" to "n1"), c.last("groupMembers").dataObj)
        c.ack("groupMembers", obj("members" to emptyList<Any>(), "nextCursor" to ""))
        h.await(q)
    }

    @Test
    fun `公告提示消息的正文可从 groupInfo 取（AC-SOC-27-4 的 SDK 部分）`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(G)))
        c.receive(msgFrame(obj("msgId" to "m1", "clientMsgId" to "tip-1", "convId" to G, "seq" to 1, "from" to "bob", "type" to "tip", "body" to obj("tip" to obj("kind" to "noticeUpdated", "actor" to "bob", "targets" to emptyList<Any>(), "text" to "摘要")), "ts" to 5)))
        val tip = h.im.messages(G)[0].body.tip!!
        assertEquals("摘要", tip.text)
        assertEquals("bob 更新了群公告", tipText(tip, "alice") { it })
    }
}
