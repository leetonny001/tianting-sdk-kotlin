package com.tianting.sdk

import com.tianting.sdk.testing.FakeClock
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val AB = "c2c:alice:bob"
private const val G1 = "group:g1"
private const val G2 = "group:g2"
private const val SECRET = "ZXQ-DEL-555"

private fun deletedEvent(convId: String, msgIds: List<String>, seqs: List<Long>) =
    event("msgDeleted", "convId" to convId, "msgIds" to msgIds, "seqs" to seqs)

/** PRD-admin 第二批：封禁、消息删除、禁言（FE-435，行为同 sdk-ts 的 FE-431～433）。 */
class ModerationTest : SdkTest() {
    private fun expectClean(h: Harness) {
        val dump = h.call { debugExport() }
        assertFalse(dump.toString().contains(SECRET), "debugExport 里还有原文")
        assertFalse(h.im.conversations.value.toString().contains(SECRET), "会话摘要里还有原文")
        assertFalse(holds(storeOf(h.im), SECRET), "Store 对象图里还有原文")
        assertFalse(holds(h.im, SECRET), "IMClient 对象图（含在途请求的回调、消息快照）里还有原文")
    }

    // ---- 封禁 ----

    @Test
    fun `kicked suspended + 4012：fatal 带 until，退避时间过后、网络恢复时都不重连`() {
        val h = setup()
        h.connect()
        val c = h.login()
        c.receive(event("kicked", "reason" to "suspended", "until" to 1_730_000_000_000L))
        c.drop(4012, "suspended")
        assertEquals(listOf<FatalReason>(FatalReason.Kicked("suspended", 4012, 1_730_000_000_000L)), h.fatals())
        h.clock.advance(300_000)
        h.im.networkOnline()
        h.flush()
        assertEquals(1, h.transport.connections.size)
        assertEquals(ConnState.DISCONNECTED, h.im.state.value)
    }

    @Test
    fun `永久封禁 until = null`() {
        val h = setup()
        h.connect()
        val c = h.login()
        c.receive(event("kicked", "reason" to "suspended", "until" to null))
        c.drop(4012)
        assertEquals(FatalReason.Kicked("suspended", 4012, null), h.fatals()[0])
    }

    @Test
    fun `握手阶段用旧 token：auth 的 ack 之前收到 kicked，同样不重连`() {
        val h = setup()
        h.connect("old")
        val c = h.transport.current
        c.open()
        c.receive(event("kicked", "reason" to "suspended", "until" to null))
        c.drop(4012)
        assertEquals(FatalReason.Kicked("suspended", 4012, null), h.fatals()[0])
        h.clock.advance(60_000)
        assertEquals(1, h.transport.connections.size)
    }

    @Test
    fun `不认识的 reason + 4012、以及只有关闭码 4012：都不重连`() {
        for (kicked in listOf(true, false)) {
            val h = setup()
            h.connect()
            val c = h.login()
            if (kicked) c.receive(event("kicked", "reason" to "xyz"))
            c.drop(4012)
            assertEquals(if (kicked) FatalReason.Kicked("xyz", 4012, null) else FatalReason.Closed(4012, ""), h.fatals()[0])
            h.clock.advance(60_000)
            assertEquals(1, h.transport.connections.size)
        }
    }

    // ---- 消息删除 ----

    private fun chat(h: Harness) = run {
        h.connect()
        val c = h.login("alice", listOf(conv(G1)))
        c.receive(msgFrame(msg(G1, 1, "bob", "keep me")))
        c.receive(msgFrame(msg(G1, 2, "bob", "秘密 $SECRET")))
        c.receive(msgFrame(msg(G1, 3, "bob", "tail")))
        c
    }

    @Test
    fun `msgDeleted：原文从内存消失，seq 不留空洞，别的消息不受影响，未读不变（AC-ADM-31-1）`() {
        val h = setup()
        val c = chat(h)
        val unread = h.convOf(G1).unread
        assertEquals(3, unread)
        assertTrue(holds(storeOf(h.im), SECRET), "对照：删除之前确实在内存里")
        c.receive(deletedEvent(G1, listOf("$G1#2"), listOf(2)))
        expectClean(h)
        val list = h.im.messages(G1)
        assertEquals(listOf(1L, 2L, 3L), list.map { it.seq })
        assertEquals(true, list[1].deleted)
        assertEquals(MsgBody(), list[1].body)
        assertEquals("bob", list[1].from)
        assertEquals("keep me", list[0].body.text)
        assertEquals("tail", list[2].body.text)
        assertEquals(unread, h.convOf(G1).unread)
        assertEquals("tail", h.convOf(G1).lastMsg?.text) // 不是最后一条：摘要不动
    }

    @Test
    fun `删的是最后一条：摘要改成占位（没有 text），未读不变；新消息之后摘要恢复（AC-ADM-31-5）`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(G1)))
        c.receive(msgFrame(msg(G1, 1, "bob", SECRET)))
        assertEquals(SECRET, h.convOf(G1).lastMsg?.text)
        c.receive(deletedEvent(G1, listOf("$G1#1"), listOf(1)))
        val l = h.convOf(G1).lastMsg!!
        assertEquals(true, l.deleted)
        assertEquals("$G1#1", l.msgId)
        assertNull(l.text)
        assertEquals(1, h.convOf(G1).unread)
        expectClean(h)
        c.receive(msgFrame(msg(G1, 2, "bob", "fresh")))
        assertEquals("fresh", h.convOf(G1).lastMsg?.text)
        assertEquals(false, h.convOf(G1).lastMsg?.deleted)
    }

    @Test
    fun `sync 回来的 deleted 消息（没有 body）：不崩溃，占位`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(G1, maxSeq = 2, unread = 2)))
        val p = h.start { loadHistory(G1) }
        c.ack("sync", obj("messages" to listOf(msg(G1, 1), deletedMsg(G1, 2)), "hasMore" to false))
        h.await(p)
        val m = h.im.messages(G1)[1]
        assertEquals(listOf<Any?>(2L, true, 5000L, MsgBody(), MessageStatus.SENT), listOf(m.seq, m.deleted, m.deletedAt, m.body, m.status))
        assertEquals(2, h.convOf(G1).unread)
    }

    @Test
    fun `删除之前发出的 sync 晚到，带着原文：不能写回来（含还没加载过的消息）`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(G1, maxSeq = 2)))
        val p = h.start { loadHistory(G1) }
        c.receive(deletedEvent(G1, listOf("$G1#2"), listOf(2)))
        c.ack("sync", obj("messages" to listOf(msg(G1, 1), msg(G1, 2, "bob", SECRET)), "hasMore" to false))
        h.await(p)
        expectClean(h)
        assertEquals(true, h.im.messages(G1)[1].deleted)
    }

    @Test
    fun `删除之前生成的 convs 晚到，摘要带着原文：不能写回来`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(G1)))
        c.receive(msgFrame(msg(G1, 1, "bob", SECRET)))
        c.receive(deletedEvent(G1, listOf("$G1#1"), listOf(1)))
        h.clock.advance(31_000)
        val p = h.start { refreshConversations() }
        c.ack("convs", obj("convs" to listOf(conv(G1, maxSeq = 1, lastMsg = obj("msgId" to "$G1#1", "from" to "bob", "type" to "text", "text" to SECRET, "ts" to 1))), "truncated" to false))
        h.await(p)
        expectClean(h)
        assertEquals(true, h.convOf(G1).lastMsg?.deleted)
    }

    @Test
    fun `自己刚发的消息：删除事件先于 ack 到达，ack 不把原文写回来`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(AB)))
        h.call { send(AB, SECRET) }
        c.receive(deletedEvent(AB, listOf("m9"), listOf(9)))
        c.ack("send", obj("msgId" to "m9", "convId" to AB, "seq" to 9, "ts" to 5))
        expectClean(h)
        assertEquals(1, h.im.messages(AB).size)
        assertEquals(listOf<Any?>(9L, true, MessageStatus.SENT), h.im.messages(AB)[0].let { listOf(it.seq, it.deleted, it.status) })
        assertEquals(true, h.convOf(AB).lastMsg?.deleted)
    }

    @Test
    fun `超时标记失败的本地消息：事件只有 msgId，补拉后按 clientMsgId 对上，队列里的原文也清掉，不能再重发`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(AB)))
        val r = h.call { send(AB, SECRET) } as SendResult.Ok
        h.clock.advance(10_000)
        assertEquals(SendError.Timeout, h.im.messages(AB)[0].error)
        c.receive(deletedEvent(AB, listOf("m9"), listOf(9)))
        assertEquals(obj("convId" to AB, "afterSeq" to 8, "limit" to 200), c.last("sync").dataObj)
        c.ack("sync", obj("messages" to listOf(deletedMsg(AB, 9, "alice", msgId = "m9", clientMsgId = r.message.clientMsgId)), "hasMore" to false))
        expectClean(h)
        assertEquals(1, h.im.messages(AB).size)
        assertEquals(0, h.im.pendingCount.value)
        val before = c.frames("send").size
        h.call { retry(AB, r.message.clientMsgId) }
        assertEquals(before, c.frames("send").size)
    }

    @Test
    fun `断开期间被删除（没有事件）：重连后重新拉已加载的范围，清掉原文`() {
        val h = setup()
        val c1 = chat(h)
        c1.drop(1006)
        val c2 = h.reconnectAuth()
        c2.ack("convs", obj("convs" to listOf(conv(G1, maxSeq = 3, unread = 3)), "truncated" to false))
        assertEquals(obj("convId" to G1, "afterSeq" to 0, "limit" to 200), c2.last("sync").dataObj)
        c2.ack("sync", obj("messages" to listOf(msg(G1, 1), deletedMsg(G1, 2), msg(G1, 3)), "hasMore" to false))
        expectClean(h)
        assertEquals(listOf(1L, 2L, 3L), h.im.messages(G1).map { it.seq })
        assertEquals(3, h.convOf(G1).unread)
    }

    @Test
    fun `convs 的 lastMsg deleted：错过事件时本地那条也清掉，摘要占位`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(G1)))
        c.receive(msgFrame(msg(G1, 1, "bob", SECRET)))
        h.clock.advance(31_000)
        val p = h.start { refreshConversations() }
        c.ack("convs", obj("convs" to listOf(conv(G1, maxSeq = 1, unread = 1, lastMsg = obj("msgId" to "$G1#1", "from" to "bob", "type" to "text", "ts" to 1, "deleted" to true))), "truncated" to false))
        h.await(p)
        expectClean(h)
        assertEquals(true, h.convOf(G1).lastMsg?.deleted)
        assertEquals(1, h.convOf(G1).unread)
    }

    @Test
    fun `debugExport 不包含 token`() {
        val h = setup()
        h.connect("secret-token-xyz")
        val c = h.login("alice", listOf(conv(G1)))
        c.receive(msgFrame(msg(G1, 1)))
        assertFalse(h.call { debugExport() }.toString().contains("secret-token-xyz"))
    }

    // ---- 禁言 ----

    private fun muted(vararg p: Pair<String, Any?>) = event("muted", *p)
    private fun unmuted(vararg p: Pair<String, Any?>) = event("unmuted", *p)
    private val convs get() = listOf(conv(AB), conv(G1), conv(G2))

    private fun online(h: Harness) = run {
        h.connect()
        h.login("alice", convs)
    }

    private fun Harness.mute(id: String) = call { muteState(id) }

    @Test
    fun `用户级：所有会话都禁言；永久为 null；unmuted 解除并发事件（AC-ADM-40-4）`() {
        val h = setup()
        val c = online(h)
        val base = h.muteEvents()
        val until = h.clock.now() + 600_000
        c.receive(muted("scope" to "user", "mutedUntil" to until))
        for (id in listOf(AB, G1, G2)) assertEquals(MuteInfo(MuteScope.USER, null, until), h.mute(id))
        assertEquals(base + 1, h.muteEvents())
        c.receive(muted("scope" to "user", "mutedUntil" to null))
        assertEquals(MuteInfo(MuteScope.USER, null, null), h.mute(AB))
        c.receive(unmuted("scope" to "user"))
        for (id in listOf(AB, G1, G2)) assertNull(h.mute(id))
        assertEquals(base + 3, h.muteEvents())
    }

    @Test
    fun `群内禁言只影响该群；用户级优先；解除用户级后群内禁言还在（AC-ADM-41-6）`() {
        val h = setup()
        val c = online(h)
        val until = h.clock.now() + 3_600_000
        c.receive(muted("scope" to "group", "groupId" to "g1", "mutedUntil" to until))
        assertEquals(MuteInfo(MuteScope.GROUP, "g1", until), h.mute(G1))
        assertNull(h.mute(G2))
        assertNull(h.mute(AB))
        c.receive(muted("scope" to "user", "mutedUntil" to h.clock.now() + 60_000))
        assertEquals(MuteScope.USER, h.mute(G1)?.scope)
        c.receive(unmuted("scope" to "user"))
        assertEquals(MuteInfo(MuteScope.GROUP, "g1", until), h.mute(G1))
        c.receive(unmuted("scope" to "group", "groupId" to "g2")) // 别的群解除不影响 g1
        assertEquals(MuteScope.GROUP, h.mute(G1)?.scope)
        c.receive(unmuted("scope" to "group", "groupId" to "g1"))
        assertNull(h.mute(G1))
    }

    @Test
    fun `不认识的 scope 忽略`() {
        val h = setup()
        val c = online(h)
        c.receive(muted("scope" to "groupAll", "groupId" to "g1", "mutedUntil" to null))
        assertNull(h.mute(G1))
    }

    @Test
    fun `auth ack 的 mutedUntil：离线被禁言上线即禁用；永久 null；重连时没有这个字段表示已解除（AC-ADM-40-6）`() {
        val h = setup()
        h.connect()
        val c1 = h.transport.current
        c1.open()
        val until = h.clock.now() + 86_400_000
        c1.ack("auth", obj("userId" to "alice", "serverTime" to h.clock.now(), "mutedUntil" to until))
        assertEquals(MuteInfo(MuteScope.USER, null, until), h.mute(AB))
        c1.drop(1006)
        h.reconnectAuth(extra = mapOf("mutedUntil" to null))
        assertEquals(MuteInfo(MuteScope.USER, null, null), h.mute(AB))
        h.transport.current.drop(1006)
        h.reconnectAuth(waitMs = 5_000)
        assertNull(h.mute(AB))
    }

    @Test
    fun `convs 的 mutedUntil：只有该群；永久 null；下一次 convs 没有这个字段时解除（AC-ADM-41-6）`() {
        val h = setup()
        h.connect()
        val until = h.clock.now() + 3_600_000
        val c = h.login("alice", listOf(conv(G1, mutedUntil = until), conv(G2), conv(AB)))
        assertEquals(MuteInfo(MuteScope.GROUP, "g1", until), h.mute(G1))
        assertNull(h.mute(G2))
        assertNull(h.mute(AB))
        h.clock.advance(31_000)
        var p = h.start { refreshConversations() }
        c.ack("convs", obj("convs" to listOf(conv(G1, mutedUntil = null), conv(G2)), "truncated" to false))
        h.await(p)
        assertEquals(MuteInfo(MuteScope.GROUP, "g1", null), h.mute(G1))
        c.receive("""{"cmd":"pong"}""") // 60 秒内要回一次心跳，否则连接按断线处理
        h.clock.advance(31_000)
        p = h.start { refreshConversations() }
        c.ack("convs", obj("convs" to listOf(conv(G1), conv(G2)), "truncated" to false))
        h.await(p)
        assertNull(h.mute(G1))
    }

    @Test
    fun `发送返回 4031：消息失败、状态按 details 更新，sendFailed 一次；重连后不自动重发（AC-ADM-40-5）`() {
        val h = setup()
        val c1 = online(h)
        val r = h.call { send(G1, "x") } as SendResult.Ok
        val until = h.clock.now() + 600_000
        c1.receive(obj("id" to c1.last("send")["id"], "cmd" to "ack", "error" to obj("code" to 4031, "msg" to "muted", "details" to obj("scope" to "group", "groupId" to "g1", "mutedUntil" to until))))
        assertEquals(SendError.Code(4031), h.im.messages(G1)[0].error)
        assertEquals(MuteInfo(MuteScope.GROUP, "g1", until), h.mute(G1))
        assertNull(h.mute(G2))
        assertEquals(listOf(IMEvent.SendFailed(G1, r.message.clientMsgId, SendError.Code(4031))), h.failed())
        c1.drop(1006)
        val c2 = h.reconnectAuth()
        assertEquals(0, c2.frames("send").size)
    }

    @Test
    fun `4031 永久（用户级）：mutedUntil = null；缺 details 时只标记失败；details 缺 mutedUntil 不改状态`() {
        val h = setup()
        val c = online(h)
        h.call { send(AB, "x") }
        c.receive(obj("id" to c.last("send")["id"], "cmd" to "ack", "error" to obj("code" to 4031, "msg" to "muted", "details" to obj("scope" to "user", "mutedUntil" to null))))
        assertEquals(MuteInfo(MuteScope.USER, null, null), h.mute(G2))

        val h2 = setup()
        val c2 = online(h2)
        h2.call { send(AB, "x") }
        c2.ackError("send", 4031)
        assertEquals(SendError.Code(4031), h2.im.messages(AB)[0].error)
        assertNull(h2.mute(AB))

        val h3 = setup()
        val c3 = online(h3)
        h3.call { send(AB, "x") }
        c3.receive(obj("id" to c3.last("send")["id"], "cmd" to "ack", "error" to obj("code" to 4031, "msg" to "muted", "details" to obj("scope" to "user"))))
        assertNull(h3.mute(AB))
    }

    @Test
    fun `4031 的 ack 超时后才到：同样更新禁言状态`() {
        val h = setup()
        val c = online(h)
        h.call { send(AB, "x") }
        h.clock.advance(10_000)
        c.receive(obj("id" to c.last("send")["id"], "cmd" to "ack", "error" to obj("code" to 4031, "msg" to "muted", "details" to obj("scope" to "user", "mutedUntil" to null))))
        assertEquals(MuteScope.USER, h.mute(AB)?.scope)
    }

    @Test
    fun `到期按服务端时间恢复：本机时钟慢或快 1 小时`() {
        for (skew in listOf(3_600_000L, -3_600_000L)) {
            val h = setup()
            h.connect()
            val c = h.transport.current
            c.open()
            val serverNow = h.clock.now() + skew
            c.ack("auth", obj("userId" to "alice", "serverTime" to serverNow))
            c.ack("convs", obj("convs" to convs, "truncated" to false))
            val base = h.muteEvents()
            c.receive(muted("scope" to "user", "mutedUntil" to serverNow + 600_000))
            assertEquals(base + 1, h.muteEvents())
            h.clock.advance(599_999)
            assertTrue(h.mute(AB) != null)
            assertEquals(base + 1, h.muteEvents())
            h.clock.advance(1)
            assertNull(h.mute(AB))
            assertEquals(base + 2, h.muteEvents()) // 没有收到 unmuted，也发事件让界面恢复
        }
    }

    @Test
    fun `30 天禁言：不会因为定时器上限提前解除；群内和用户级各自到期`() {
        class OverflowClock : FakeClock() {
            override fun schedule(delayMs: Long, fn: () -> Unit) = super.schedule(if (delayMs > 2_147_483_647) 1 else delayMs, fn)
        }
        val h = setup(clock = OverflowClock())
        val c = online(h)
        val day = 86_400_000L
        c.receive(muted("scope" to "user", "mutedUntil" to h.clock.now() + 30 * day))
        c.receive(muted("scope" to "group", "groupId" to "g1", "mutedUntil" to h.clock.now() + 40 * day))
        h.clock.advance(29 * day)
        assertEquals(MuteScope.USER, h.mute(AB)?.scope)
        h.clock.advance(day)
        assertNull(h.mute(AB))
        assertEquals(MuteScope.GROUP, h.mute(G1)?.scope)
        h.clock.advance(10 * day)
        assertNull(h.mute(G1))
    }

    @Test
    fun `被移出群清除群内禁言，再次入群不继承（F-ADM-41 规则 4）；退出登录清空`() {
        val h = setup()
        val c = online(h)
        c.receive(muted("scope" to "group", "groupId" to "g1", "mutedUntil" to null))
        c.receive(event("convRemoved", "convId" to G1, "reason" to "removed"))
        c.receive(event("convAdded", "conv" to conv(G1)))
        assertNull(h.mute(G1))
        c.receive(muted("scope" to "user", "mutedUntil" to null))
        h.call { disconnect() }
        assertNull(h.mute(AB))
    }

    @Test
    fun `muteState 读取本身按服务端时间判断（定时器晚到也不会显示已过期的禁言）`() {
        val h = setup()
        val c = online(h)
        c.receive(muted("scope" to "user", "mutedUntil" to h.clock.now() - 1))
        assertNull(h.mute(AB))
    }
}
