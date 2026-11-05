package com.tianting.sdk

import com.tianting.sdk.jdbc.JdbcLocalDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val AB = "c2c:alice:bob"
private const val G1 = "group:g1"
private const val SECRET = "ZXQ-DEL-777"

/** 本地持久化（FE-131、FE-435）：真 SQLite 文件，重新打开就是"进程被杀后重启"。 */
class PersistenceTest : SdkTest() {
    @TempDir
    lateinit var dir: File

    private fun open(): Harness = setup(database = tempDb(dir))

    private fun Harness.closeDb() {
        call { close() }
    }

    private fun assertClean(needle: String = SECRET) = assertEquals(emptyList(), filesContaining(dir, needle), "数据库文件里还能搜到特征串")

    private fun assertPresent(needle: String = SECRET) = assertTrue(filesContaining(dir, needle).isNotEmpty(), "对照：应该能在文件里搜到")

    // ---- AC-ADM-31-4：删除本地库中的原文 ----

    @Test
    fun `AC-ADM-31-4：收到 msgDeleted 后数据库文件原始字节里搜不到特征串`() {
        val h = open()
        h.connect()
        val c = h.login("alice", listOf(conv(G1)))
        c.receive(msgFrame(msg(G1, 1, "bob", "keep me")))
        c.receive(msgFrame(msg(G1, 2, "bob", "原文 $SECRET 结尾")))
        c.receive(msgFrame(msg(G1, 3, "bob", "tail")))
        assertPresent()
        c.receive(event("msgDeleted", "convId" to G1, "msgIds" to listOf("$G1#2"), "seqs" to listOf(2)))
        assertClean()
        assertPresent("keep me") // 别的消息还在
        assertEquals(true, h.im.messages(G1)[1].deleted)
        h.closeDb()
        assertClean()

        // 重启后：占位还在，原文没有回来
        val h2 = open()
        assertEquals(listOf(1L, 2L, 3L), h2.im.messages(G1).map { it.seq })
        assertEquals(true, h2.im.messages(G1)[1].deleted)
        assertEquals(MsgBody(), h2.im.messages(G1)[1].body)
    }

    @Test
    fun `AC-ADM-31-4：长消息（溢出页）和多次写入后的旧版本也搜不到`() {
        val h = open()
        h.connect()
        val c = h.login("alice", listOf(conv(G1)))
        val long = (SECRET + "-").repeat(600) // 约 7KB，超过一页
        c.receive(msgFrame(msg(G1, 1, "bob", long)))
        c.receive(event("readUpdated", "convId" to G1, "readSeq" to 1)) // 会话行被重写过，摘要的旧版本可能留在空闲区
        assertPresent()
        c.receive(event("msgDeleted", "convId" to G1, "msgIds" to listOf("$G1#1"), "seqs" to listOf(1)))
        assertClean()
    }

    @Test
    fun `AC-ADM-31-4：最后一条的摘要（conversations 表）也不留原文`() {
        val h = open()
        h.connect()
        val c = h.login("alice", listOf(conv(G1)))
        c.receive(msgFrame(msg(G1, 1, "bob", SECRET)))
        assertEquals(SECRET, h.convOf(G1).lastMsg?.text)
        c.receive(event("msgDeleted", "convId" to G1, "msgIds" to listOf("$G1#1"), "seqs" to listOf(1)))
        assertClean()
        h.closeDb()
        val h2 = open()
        assertEquals(true, h2.convOf(G1).lastMsg?.deleted)
        assertNull(h2.convOf(G1).lastMsg?.text)
    }

    @Test
    fun `AC-ADM-31-4：发送队列里的那份（超时失败、待发）补拉对上后也清掉`() {
        val h = open()
        h.connect()
        val c = h.login("alice", listOf(conv(AB)))
        val r = h.call { send(AB, SECRET) } as SendResult.Ok
        h.clock.advance(10_000)
        assertPresent()
        c.receive(event("msgDeleted", "convId" to AB, "msgIds" to listOf("m9"), "seqs" to listOf(9)))
        c.ack("sync", obj("messages" to listOf(deletedMsg(AB, 9, "alice", "m9", r.message.clientMsgId)), "hasMore" to false))
        assertClean()
    }

    @Test
    fun `晚到的 sync 和 ack 不把原文写回本地库；重启后墓碑还在`() {
        val h = open()
        h.connect()
        val c = h.login("alice", listOf(conv(G1, maxSeq = 2)))
        c.receive(event("msgDeleted", "convId" to G1, "msgIds" to listOf("$G1#2"), "seqs" to listOf(2)))
        val p = h.start { loadHistory(G1) }
        c.ack("sync", obj("messages" to listOf(msg(G1, 1), msg(G1, 2, "bob", SECRET)), "hasMore" to false))
        h.await(p)
        assertClean()
        h.closeDb()

        // 重启后服务端的旧 sync 结果（比如 CDN、旧节点）也不能写回来
        val h2 = open()
        h2.connect()
        val c2 = h2.login("alice", listOf(conv(G1, maxSeq = 3)))
        c2.receive(msgFrame(msg(G1, 3)))
        val q = h2.start { loadHistory(G1) }
        h2.flush()
        c2.ack("sync", obj("messages" to listOf(msg(G1, 2, "bob", SECRET)), "hasMore" to false))
        h2.await(q)
        assertClean()
    }

    @Test
    fun `还没加载过的消息的墓碑也持久化：重启后 sync 带着原文也写不回来`() {
        val h = open()
        h.connect()
        val c = h.login("alice", listOf(conv(G1, maxSeq = 9)))
        c.receive(event("msgDeleted", "convId" to G1, "msgIds" to listOf("$G1#9"), "seqs" to listOf(9)))
        h.closeDb()
        val h2 = open()
        h2.connect()
        val c2 = h2.login("alice", listOf(conv(G1, maxSeq = 9)))
        val p = h2.start { loadHistory(G1) }
        c2.ack("sync", obj("messages" to listOf(msg(G1, 9, "bob", SECRET)), "hasMore" to false))
        h2.await(p)
        assertEquals(true, h2.im.messages(G1)[0].deleted)
        assertClean()
    }

    @Test
    fun `离线期间被删除：冷启动后重连，重新拉已加载范围，本地库的原文被清掉`() {
        val h = open()
        h.connect()
        val c = h.login("alice", listOf(conv(G1)))
        c.receive(msgFrame(msg(G1, 1, "bob", SECRET)))
        c.receive(msgFrame(msg(G1, 2, "bob", "tail")))
        h.closeDb()
        assertPresent()

        val h2 = open()
        h2.connect()
        val c2 = h2.transport.current
        c2.open()
        c2.ack("auth", obj("userId" to "alice", "serverTime" to h2.clock.now()))
        c2.ack("convs", obj("convs" to listOf(conv(G1, maxSeq = 2)), "truncated" to false))
        h2.flush()
        assertEquals(obj("convId" to G1, "afterSeq" to 0, "limit" to 200), c2.last("sync").dataObj)
        c2.ack("sync", obj("messages" to listOf(deletedMsg(G1, 1), msg(G1, 2, "bob", "tail")), "hasMore" to false))
        assertClean()
    }

    // ---- convRemoved 删除本地库中该会话（FE-435） ----

    @Test
    fun `convRemoved：本地库里这个会话连同消息一起删除，内存快照保留；重启后没有`() {
        val h = open()
        h.connect()
        val c = h.login("alice", listOf(conv(G1, name = "球局"), conv(AB)))
        c.receive(msgFrame(msg(G1, 1, "bob", SECRET)))
        assertPresent()
        c.receive(event("convRemoved", "convId" to G1, "reason" to "removed"))
        assertClean()
        assertEquals("球局", h.call { conversationSnapshot(G1) }?.name)
        // 晚到的消息不再写库
        c.receive(msgFrame(msg(G1, 2, "bob", SECRET)))
        assertClean()
        h.closeDb()
        val h2 = open()
        assertFalse(h2.im.conversations.value.any { it.convId == G1 })
        assertEquals(emptyList(), h2.im.messages(G1))
    }

    @Test
    fun `离线期间被移出：convs 推断出来的也删本地库`() {
        val h = open()
        h.connect()
        val c = h.login("alice", listOf(conv(G1)))
        c.receive(msgFrame(msg(G1, 1, "bob", SECRET)))
        c.drop(1006)
        val c2 = h.reconnectAuth()
        c2.ack("convs", obj("convs" to emptyList<Any>(), "truncated" to false))
        assertClean()
    }

    @Test
    fun `重新被拉进群：内存里的消息写回本地库，下次冷启动不缺`() {
        val h = open()
        h.connect()
        val c = h.login("alice", listOf(conv(G1)))
        c.receive(msgFrame(msg(G1, 1, "bob", "m1")))
        c.receive(event("convRemoved", "convId" to G1, "reason" to "removed"))
        c.receive(event("convAdded", "conv" to conv(G1, maxSeq = 1)))
        h.closeDb()
        val h2 = open()
        assertEquals(listOf(1L), h2.im.messages(G1).map { it.seq })
    }

    // ---- 被踢 / 退出清库（FE-131） ----

    @Test
    fun `退出登录清库`() {
        val h = open()
        h.connect()
        val c = h.login("alice", listOf(conv(G1)))
        c.receive(msgFrame(msg(G1, 1, "bob", SECRET)))
        c.ack("socialSync", page(untilVer = 1, friends = listOf(friend("bob", 1, remark = "REMARK-777"))))
        assertPresent()
        assertPresent("REMARK-777")
        h.call { disconnect() }
        assertClean()
        assertClean("REMARK-777")
        h.closeDb()
        val h2 = open()
        assertEquals(emptyList(), h2.im.conversations.value)
        assertEquals("", h2.im.userId.value)
        assertEquals(emptyList(), h2.im.friends.value)
    }

    @Test
    fun `被踢清库；内存里的失败消息留给界面；之后不再写库；同一账号重新登录时写回`() {
        val h = open()
        h.connect()
        val c = h.login("alice", listOf(conv(AB)))
        h.call { send(AB, SECRET) }
        c.ack("socialSync", page(untilVer = 1, friends = listOf(friend("bob", 1, remark = "REMARK-777"))))
        c.receive(event("kicked", "reason" to "suspended", "until" to null))
        c.drop(4012)
        assertClean()
        assertClean("REMARK-777")
        assertEquals(SendError.Kicked, h.im.messages(AB)[0].error)
        assertEquals(1, h.im.pendingCount.value)
        h.call { retry(AB, "cid-0001") } // 被踢之后的状态变化只改内存，不写库
        assertClean()
        h.connect("t2")
        val c2 = h.transport.current
        c2.open()
        c2.ack("auth", obj("userId" to "alice", "serverTime" to h.clock.now()))
        assertPresent() // 同一账号重新登录：失败消息写回，可以手动重发
    }

    @Test
    fun `换账号：上一个账号的数据全部清掉`() {
        val h = open()
        h.connect()
        val c = h.login("alice", listOf(conv(G1)))
        c.receive(msgFrame(msg(G1, 1, "bob", SECRET)))
        h.closeDb()
        val h2 = open()
        assertEquals("alice", h2.im.userId.value)
        h2.connect()
        val c2 = h2.transport.current
        c2.open()
        c2.ack("auth", obj("userId" to "mallory", "serverTime" to h2.clock.now()))
        assertClean()
        assertEquals(emptyList(), h2.im.messages(G1))
        h2.closeDb()
        val h3 = open()
        assertEquals("mallory", h3.im.userId.value)
    }

    // ---- 冷启动 ----

    @Test
    fun `冷启动恢复会话、消息、未读基线；离线也能读`() {
        val h = open()
        h.connect()
        val c = h.login("alice", listOf(conv(G1, name = "球局", maxSeq = 15, minSeq = 11, unread = 5, updatedAt = 3, memberCount = 4, myRole = "admin")))
        c.receive(msgFrame(msg(G1, 16, "bob", "hello")))
        assertEquals(6, h.convOf(G1).unread)
        h.closeDb()

        val h2 = open()
        assertEquals("alice", h2.im.userId.value)
        val v = h2.convOf(G1)
        assertEquals(listOf<Any?>("球局", 16L, 6L, 4, GroupRole.ADMIN, "hello"), listOf(v.name, v.maxSeq, v.unread, v.memberCount, v.myRole, v.lastMsg?.text))
        assertEquals(6, h2.im.totalUnread.value)
        assertEquals(listOf("hello"), h2.im.messages(G1).map { it.body.text })
        assertEquals(ConnState.DISCONNECTED, h2.im.state.value)
    }

    @Test
    fun `冷启动后未读基线有效：已读低于 joinSeq 不会让未读变多（C-02）`() {
        val h = open()
        h.connect()
        h.login("alice", listOf(conv(G1, maxSeq = 15, minSeq = 11, unread = 5)))
        h.closeDb()
        val h2 = open()
        h2.connect()
        val c = h2.transport.current
        c.open()
        c.ack("auth", obj("userId" to "alice", "serverTime" to h2.clock.now()))
        // convs 还没回来，readUpdated 先到
        c.receive(event("readUpdated", "convId" to G1, "readSeq" to 3))
        assertEquals(5, h2.convOf(G1).unread)
    }

    @Test
    fun `冷启动后补拉和翻页从本地 maxSeq、最早 seq 继续`() {
        val h = open()
        h.connect()
        val c = h.login("alice", listOf(conv(AB, maxSeq = 5, minSeq = 1)))
        c.receive(msgFrame(msg(AB, 6)))
        c.receive(msgFrame(msg(AB, 7)))
        h.closeDb()

        val h2 = open()
        h2.connect()
        val c2 = h2.transport.current
        c2.open()
        c2.ack("auth", obj("userId" to "alice", "serverTime" to h2.clock.now()))
        c2.ack("convs", obj("convs" to listOf(conv(AB, maxSeq = 9, minSeq = 1)), "truncated" to false))
        assertEquals(obj("convId" to AB, "afterSeq" to 7, "limit" to 200), c2.last("sync").dataObj)
        c2.ack("sync", obj("messages" to listOf(msg(AB, 8), msg(AB, 9)), "hasMore" to false))
        val p = h2.start { loadHistory(AB) }
        h2.flush()
        val before = c2.last("sync").dataObj
        assertEquals(listOf(6L, 7L, 8L, 9L), h2.im.messages(AB).map { it.seq })
        assertTrue(before.isNotEmpty())
        c2.frames("sync").map { it.dataObj }.let { list ->
            assertTrue(list.any { it["beforeSeq"]?.str == "6" }, "翻页从最早的 6 继续：$list")
        }
        c2.frames("sync").lastOrNull { it.dataObj["beforeSeq"]?.str == "6" }?.let {
            c2.receive(obj("id" to it["id"], "cmd" to "ack", "data" to obj("messages" to listOf(msg(AB, 5)), "hasMore" to true)))
        }
        assertTrue(h2.await(p))
    }

    @Test
    fun `每个会话最多恢复 200 条，更早的从库里删掉；待发和失败的保留`() {
        val h = open()
        h.connect()
        val c = h.login("alice", listOf(conv(AB)))
        for (s in 1L..230L) c.receive(msgFrame(msg(AB, s, text = if (s == 5L) SECRET else "m$s")))
        h.closeDb()
        val h2 = open()
        val seqs = h2.im.messages(AB).map { it.seq }
        assertEquals((31L..230L).toList(), seqs)
        h2.closeDb()
        assertClean()
    }

    @Test
    fun `进程结束时还没发出去的消息：恢复后标记失败，不自动发出，可以手动重发并沿用 clientMsgId`() {
        val h = open()
        h.connect()
        val c = h.login("alice", listOf(conv(AB)))
        c.drop(1006)
        h.call { send(AB, "queued") }
        h.closeDb()

        val h2 = open()
        val m = h2.im.messages(AB)[0]
        assertEquals(MessageStatus.FAILED, m.status)
        assertEquals(1, h2.im.pendingCount.value)
        h2.connect()
        val c2 = h2.login("alice", listOf(conv(AB)))
        assertEquals(0, c2.frames("send").size)
        h2.call { retry(AB, m.clientMsgId) }
        assertEquals(m.clientMsgId, c2.last("send").dataObj["clientMsgId"]!!.str)
        c2.ack("send", obj("msgId" to "m1", "convId" to AB, "seq" to 1, "ts" to 1))
        assertEquals(1, h2.im.messages(AB).size)
        h2.closeDb()
        // 本地库里 clientMsgId 那一行被换成了 seq 行，没有重复
        val h3 = open()
        assertEquals(listOf(1L), h3.im.messages(AB).map { it.seq })
    }

    @Test
    fun `离线时可以用恢复出来的身份发送（排队）`() {
        val h = open()
        h.connect()
        h.login("alice", listOf(conv(AB)))
        h.closeDb()
        val h2 = open()
        val r = h2.call { send(AB, "offline") } as SendResult.Ok
        assertEquals(MessageStatus.QUEUED, r.message.status)
        assertEquals("alice", r.message.from)
    }

    @Test
    fun `临时会话的标记也持久化：不会被推断成被移出`() {
        val h = open()
        h.connect()
        val c = h.login("alice")
        c.receive(msgFrame(msg(G1, 1, "bob")))
        h.closeDb()
        val h2 = open()
        h2.connect()
        val c2 = h2.login("alice", emptyList())
        assertEquals(emptyList(), h2.removed())
        assertNull(h2.call { conversationSnapshot(G1) })
        assertEquals(1, c2.frames("convs").size)
    }

    // ---- 社交持久化（FE-131） ----

    @Test
    fun `进程被杀后重启：从持久化的 ver 增量同步（TC FE-131）`() {
        val h = open()
        h.connect()
        val c = h.login("alice")
        c.ack(
            "socialSync",
            page(
                untilVer = 7,
                requestSeenVer = 4,
                friends = listOf(friend("bob", 3, remark = "老板")),
                requests = listOf(req("carol", "in", 6, updatedAt = h.clock.now())),
                blocks = listOf(obj("userId" to "dave", "createdAt" to NOW, "ver" to 7, "profile" to null)),
                mode = "friends",
                strangerMsgLimit = 2,
                official = true,
                searchable = false,
            ),
        )
        h.closeDb()

        val h2 = open()
        assertEquals(true, h2.im.socialLoaded.value)
        assertEquals(listOf("老板"), h2.im.friends.value.map { it.displayName })
        assertEquals(SocialSettings(false, SocialMode.FRIENDS, 2), h2.im.socialSettings.value)
        assertEquals(1, h2.im.friendRequests.value.unseen)
        assertEquals(listOf("dave"), h2.im.blocks.value.map { it.userId })
        assertEquals(true, h2.im.isOfficial.value)
        h2.connect()
        val c2 = h2.login("alice")
        assertEquals(obj("sinceVer" to 7, "limit" to 500), c2.last("socialSync").dataObj)
        c2.ack("socialSync", page(full = false, untilVer = 8, friends = listOf(friend("erin", 8)), requestSeenVer = 4, mode = "friends", strangerMsgLimit = 2, official = true, searchable = false))
        assertEquals(setOf("老板", "ERIN"), h2.im.friends.value.map { it.displayName }.toSet())
        h2.closeDb()
        val h3 = open()
        assertEquals(setOf("老板", "ERIN"), h3.im.friends.value.map { it.displayName }.toSet())
    }

    @Test
    fun `socialVer 与数据同一事务：写 ver 时失败，数据也回滚；重启后 ver 和数据一致`() {
        val real = tempDb(dir)
        var failOnMeta = false
        val flaky = object : LocalDatabase by real {
            override fun execute(sql: String, args: List<Any?>) {
                if (failOnMeta && sql.contains("social_meta")) throw java.sql.SQLException("disk I/O error")
                real.execute(sql, args)
            }

            override fun <T> transaction(block: () -> T): T = real.transaction(block)
        }
        val h = setup(database = flaky)
        h.connect()
        val c = h.login("alice")
        c.ack("socialSync", page(untilVer = 5, friends = listOf(friend("bob", 5))))
        failOnMeta = true
        // 增量轮：friends 写成功、meta 写失败，整个事务回滚
        c.receive(event("socialChanged", "ver" to 6))
        h.clock.advance(200)
        c.ack("socialSync", page(full = false, untilVer = 6, friends = listOf(friend("carol", 6))))
        assertTrue(h.logs.any { it.contains("local database failed") })
        real.close()

        val check = JdbcLocalDatabase(File(dir, "im.db"))
        val r = LocalDb(check, SilentLogger).also { it.open() }.load()
        check.close()
        assertEquals(5, r.socialVer)
        assertEquals(listOf("bob"), r.friends.map { it.userId }) // carol 没有留下，下次从 ver 5 重新拉
    }

    @Test
    fun `全量轮中途进程被杀：本地还是旧数据和旧 ver（全量收齐后才替换）`() {
        val h = open()
        h.connect()
        val c = h.login("alice")
        c.ack("socialSync", page(untilVer = 5, friends = listOf(friend("bob", 5))))
        c.receive(event("socialChanged", "ver" to 6))
        h.clock.advance(200)
        c.ack("socialSync", page(untilVer = 6, hasMore = true, cursor = "x", friends = listOf(friend("carol", 6))))
        h.closeDb() // 第二页还没到

        val h2 = open()
        assertEquals(listOf("bob"), h2.im.friends.value.map { it.userId })
        h2.connect()
        val c2 = h2.login("alice")
        assertEquals(obj("sinceVer" to 5, "limit" to 500), c2.last("socialSync").dataObj)
    }

    @Test
    fun `增量轮中途进程被杀：ver 不前进，已写的行重放无害`() {
        val h = open()
        h.connect()
        val c = h.login("alice")
        c.ack("socialSync", page(untilVer = 5))
        c.receive(event("socialChanged", "ver" to 7))
        h.clock.advance(200)
        c.ack("socialSync", page(full = false, untilVer = 7, hasMore = true, cursor = "x", friends = listOf(friend("carol", 6))))
        h.closeDb()
        val h2 = open()
        h2.connect()
        val c2 = h2.login("alice")
        assertEquals(obj("sinceVer" to 5, "limit" to 500), c2.last("socialSync").dataObj)
        c2.ack("socialSync", page(full = false, untilVer = 7, friends = listOf(friend("carol", 6), friend("dave", 7))))
        assertEquals(listOf("carol", "dave"), h2.im.friends.value.map { it.userId }.sorted())
    }

    @Test
    fun `墓碑、ack、markFriendRequestsSeen、socialSettingsChanged 都落库`() {
        val h = open()
        h.connect()
        val c = h.login("alice")
        c.ack("socialSync", page(untilVer = 5, friends = listOf(friend("bob", 5)), requests = listOf(req("carol", "in", 4, updatedAt = h.clock.now()))))
        h.call { markFriendRequestsSeen() }
        c.ack("friendRequestsSeen", obj("requestSeenVer" to 5, "ver" to 6, "changed" to true))
        val p = h.start { deleteFriend("bob") }
        c.ack("friendDelete", obj("ver" to 7, "changed" to true, "items" to items(friends = listOf(friend("bob", 7, deleted = true)))))
        h.await(p)
        c.receive(event("socialSettingsChanged", "mode" to "friends", "strangerMsgLimit" to 9))
        h.closeDb()
        val h2 = open()
        assertEquals(emptyList(), h2.im.friends.value)
        assertEquals(0, h2.im.friendRequests.value.unseen)
        assertEquals(SocialSettings(true, SocialMode.FRIENDS, 9), h2.im.socialSettings.value)
        h2.connect()
        val c2 = h2.login("alice")
        assertEquals(obj("sinceVer" to 7, "limit" to 500), c2.last("socialSync").dataObj)
    }

    @Test
    fun `超过 30 天的申请：连接后按服务端时间从本地库丢弃（附言不留）`() {
        val h = open()
        h.connect()
        val c = h.login("alice")
        val now = h.clock.now()
        c.ack("socialSync", page(untilVer = 2, requests = listOf(req("old", "in", 1, updatedAt = now, message = "ATTACH-777"), req("new", "in", 2, updatedAt = now + 40L * 86_400_000))))
        h.closeDb()
        assertPresent("ATTACH-777")
        val h2 = open()
        h2.connect()
        val c2 = h2.transport.current
        c2.open()
        c2.ack("auth", obj("userId" to "alice", "serverTime" to now + 31L * 86_400_000))
        assertEquals(listOf("new"), h2.im.friendRequests.value.items.map { it.userId })
        assertClean("ATTACH-777")
    }

    // ---- 其他 ----

    @Test
    fun `本地库读不出来时清空重来，不影响收发`() {
        val db = tempDb(dir)
        val local = LocalDb(db, SilentLogger).also { it.open() }
        local.setOwner("alice")
        db.execute("INSERT INTO conversations(conv_id, read_base, placeholder, json) VALUES('c2c:alice:bob', 0, 0, 'not json')")
        db.close()
        val h = open()
        assertEquals(emptyList(), h.im.conversations.value)
        assertEquals("", h.im.userId.value)
        h.connect()
        val c = h.login("alice", listOf(conv(AB)))
        c.receive(msgFrame(msg(AB, 1)))
        assertEquals(1, h.im.messages(AB).size)
    }

    @Test
    fun `本地库写失败不影响收发（内存状态继续有效）`() {
        val real = tempDb(dir)
        var broken = false
        val flaky = object : LocalDatabase by real {
            override fun execute(sql: String, args: List<Any?>) {
                if (broken) throw java.sql.SQLException("disk full")
                real.execute(sql, args)
            }

            override fun <T> transaction(block: () -> T): T = real.transaction(block)
        }
        val h = setup(database = flaky)
        h.connect()
        val c = h.login("alice", listOf(conv(AB)))
        broken = true
        c.receive(msgFrame(msg(AB, 1)))
        val r = h.call { send(AB, "hi") } as SendResult.Ok
        assertEquals(MessageStatus.SENDING, r.message.status)
        c.ack("send", obj("msgId" to "m2", "convId" to AB, "seq" to 2, "ts" to 2))
        assertEquals(listOf(1L, 2L), h.im.messages(AB).map { it.seq })
        assertTrue(h.logs.any { it.contains("local database failed") })
        assertFalse(h.logs.joinToString().contains("disk full"))
    }

    @Test
    fun `secure_delete 不可用的 SQLite：清除后 VACUUM，文件里同样搜不到`() {
        val real = tempDb(dir)
        val vacuums = ArrayList<String>()
        val noSecure = object : LocalDatabase by real {
            override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> {
                if (sql.startsWith("PRAGMA secure_delete")) {
                    real.query("PRAGMA secure_delete=OFF")
                    return listOf(mapOf("secure_delete" to 0L))
                }
                return real.query(sql, args)
            }

            override fun execute(sql: String, args: List<Any?>) {
                if (sql == "VACUUM") vacuums.add(sql)
                real.execute(sql, args)
            }

            override fun <T> transaction(block: () -> T): T = real.transaction(block)
        }
        val h = setup(database = noSecure)
        h.connect()
        val c = h.login("alice", listOf(conv(G1)))
        c.receive(msgFrame(msg(G1, 1, "bob", SECRET)))
        c.receive(msgFrame(msg(G1, 2, "bob", "tail")))
        c.receive(event("msgDeleted", "convId" to G1, "msgIds" to listOf("$G1#1"), "seqs" to listOf(1)))
        assertTrue(vacuums.isNotEmpty())
        assertClean()
    }

    @Test
    fun `主库不用 WAL：没有 -wal 文件`() {
        val h = open()
        h.connect()
        val c = h.login("alice", listOf(conv(G1)))
        c.receive(msgFrame(msg(G1, 1)))
        assertEquals(emptyList(), dir.listFiles()!!.map { it.name }.filter { it.endsWith("-wal") })
    }
}
