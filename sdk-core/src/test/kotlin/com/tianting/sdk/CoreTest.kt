package com.tianting.sdk

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val AB = "c2c:alice:bob"
private const val AC = "c2c:alice:carol"
private const val G = "group:g1"

/** 连接管理（FE-003）。 */
class ConnectionTest : SdkTest() {
    @Test
    fun `C-52：URL 只带 platform，第一帧是 auth，收到 auth 的 ack 才算已连接`() {
        val h = setup(platform = Platform.PC)
        h.im.connect("secret-token-xyz")
        h.flush()
        val c = h.transport.current
        assertEquals("ws://im.test/ws?platform=pc", c.url)
        c.open()
        assertFalse(c.url.contains("secret"))
        assertEquals(listOf(obj("id" to "auth1", "cmd" to "auth", "data" to obj("token" to "secret-token-xyz"))), c.frames())
        assertEquals(ConnState.CONNECTING, h.im.state.value)
        c.ack("auth", obj("userId" to "alice", "serverTime" to 1))
        assertEquals(ConnState.CONNECTED, h.im.state.value)
        assertEquals("alice", h.im.userId.value)
        assertNotNull(c.last("convs")) // 连上后先拉会话列表
    }

    @Test
    fun `心跳每 30 秒一次；10 秒收不到 pong 判定断线并重连（AC-CONN-01-6）`() {
        val h = setup()
        h.connect()
        val c = h.login()
        h.clock.advance(30_000)
        assertEquals(1, c.sent.count { it == """{"cmd":"ping"}""" })
        c.receive("""{"cmd":"pong"}""")
        h.clock.advance(30_000)
        assertEquals(2, c.sent.count { it == """{"cmd":"ping"}""" })
        h.clock.advance(9_999) // 第二次 ping 没有 pong
        assertEquals(ConnState.CONNECTED, h.im.state.value)
        h.clock.advance(1)
        assertEquals(ConnState.DISCONNECTED, h.im.state.value)
        assertNotNull(c.closedWith)
        h.clock.advance(1_000) // 第一次重连等 1 秒
        assertEquals(2, h.transport.connections.size)
    }

    @Test
    fun `退避：1、2、4、8、16、30、30 秒（AC-CONN-01-5）；连接成功后重置为 1 秒（C-22）`() {
        val h = setup()
        h.connect()
        val gaps = ArrayList<Long>()
        repeat(7) {
            h.transport.current.drop(1006)
            val before = h.transport.connections.size
            var waited = 0L
            while (h.transport.connections.size == before) {
                h.clock.advance(100)
                waited += 100
            }
            gaps.add(waited)
        }
        assertEquals(listOf(1000L, 2000, 4000, 8000, 16000, 30000, 30000), gaps)
        h.login()
        h.transport.current.drop(1006)
        h.clock.advance(999)
        assertEquals(8, h.transport.connections.size)
        h.clock.advance(1)
        assertEquals(9, h.transport.connections.size)
    }

    @Test
    fun `抖动在正负 20 个百分点以内，上限是加抖动之前的值`() {
        for ((r, want) in listOf(0.0 to 800L, 0.999999 to 1200L)) {
            val h = setup(random = { r })
            h.connect()
            h.transport.current.drop(1006)
            h.clock.advance(want - 1)
            assertEquals(1, h.transport.connections.size)
            h.clock.advance(1)
            assertEquals(2, h.transport.connections.size)
        }
    }

    @Test
    fun `网络恢复时立即重连，不等退避（F-CONN-01 规则 3）`() {
        val h = setup()
        h.connect()
        h.transport.current.drop(1006)
        h.clock.advance(1_000)
        h.transport.current.drop(1006) // 第二次失败，下一次要等 2 秒
        assertEquals(2, h.transport.connections.size)
        h.im.networkOnline()
        h.flush()
        assertEquals(3, h.transport.connections.size)
    }

    @Test
    fun `被踢不重连，通知界面（AC-AUTH-02-4、AC-AUTH-02-10、AC-AUTH-03-5）`() {
        for ((reason, code) in listOf("tokenInvalid" to 4010, "tokenExpired" to 4011, "banned" to 4012, "tooManyConnections" to 4429)) {
            val h = setup()
            h.connect()
            val c = h.login()
            c.receive(event("kicked", "reason" to reason))
            c.drop(code, reason)
            assertEquals(listOf<FatalReason>(FatalReason.Kicked(reason, code, null)), h.fatals())
            assertEquals(FatalReason.Kicked(reason, code, null), h.im.fatal.value)
            h.clock.advance(120_000)
            assertEquals(1, h.transport.connections.size)
        }
    }

    @Test
    fun `关闭码 1000、4009 不重连（没有 kicked 也一样）`() {
        for (code in listOf(1000, 4009)) {
            val h = setup()
            h.connect()
            h.transport.current.drop(code, "x")
            assertEquals(listOf<FatalReason>(FatalReason.Closed(code, "x")), h.fatals())
            h.clock.advance(60_000)
            assertEquals(1, h.transport.connections.size)
        }
    }

    @Test
    fun `serverShutdown（1001）、4400、4401、4408、1006 按退避重连（C-55、C-52）`() {
        for ((code, kicked) in listOf(1001 to "serverShutdown", 4400 to null, 4401 to null, 4408 to null, 1006 to null)) {
            val h = setup()
            h.connect()
            val c = h.login()
            if (kicked != null) c.receive(event("kicked", "reason" to kicked))
            c.drop(code)
            h.clock.advance(1_000)
            assertEquals(emptyList(), h.fatals(), "code $code")
            assertEquals(2, h.transport.connections.size, "code $code")
        }
    }

    @Test
    fun `退出登录以 1000 关闭，不再重连，清空数据（AC-AUTH-05-1）`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(AB, maxSeq = 1, unread = 1, updatedAt = 1)))
        assertEquals(1, h.im.conversations.value.size)
        h.call { disconnect() }
        assertEquals(1000 to "logout", c.closedWith)
        assertEquals(ConnState.DISCONNECTED, h.im.state.value)
        assertEquals(emptyList(), h.im.conversations.value)
        h.clock.advance(60_000)
        assertEquals(1, h.transport.connections.size)
    }

    @Test
    fun `日志和异常里不出现 token 和消息原文（C-52）`() {
        val h = setup()
        h.connect("super-secret-token")
        val c = h.login()
        c.receive("not json super-secret-token")
        c.receive(obj("cmd" to "msg", "data" to obj("seq" to "SECRET-TEXT-1"))) // 解码失败的异常信息里有 JSON 片段
        c.receive(event("convAdded", "conv" to obj("convId" to "SECRET-TEXT-1")))
        val d = h.start { send(AB, "SECRET-TEXT-1") }
        h.await(d)
        c.receive(obj("id" to c.last("send")["id"], "cmd" to "ack", "data" to obj("seq" to "SECRET-TEXT-1"))) // 坏的 ack
        c.drop(1006)
        h.clock.advance(1_000)
        h.transport.current.open()
        assertTrue(h.logs.isNotEmpty())
        val all = h.logs.joinToString("\n")
        assertFalse(all.contains("super-secret-token"), all)
        assertFalse(all.contains("SECRET-TEXT-1"), all)
    }

    @Test
    fun `断开 0点5 秒就恢复时不显示连接提示；超过 1 秒才显示，恢复后立即消失（AC-CONN-02-3、F-CONN-02）`() {
        val h = setup()
        h.connect()
        h.login()
        h.transport.current.drop(1006)
        h.clock.advance(500)
        h.im.networkOnline()
        h.flush()
        h.login()
        h.clock.advance(5_000)
        assertFalse(h.im.banner.value)

        h.transport.current.drop(1006)
        h.clock.advance(999)
        assertFalse(h.im.banner.value)
        h.clock.advance(1)
        assertTrue(h.im.banner.value)
        h.clock.advance(1_000) // 退避到期，重连
        h.login()
        assertFalse(h.im.banner.value)
    }

    @Test
    fun `握手后收不到 auth 的 ack：10 秒后按断线重连，不会一直停在连接中`() {
        val h = setup()
        h.connect()
        val c = h.transport.current
        c.open()
        h.clock.advance(9_999)
        assertEquals(ConnState.CONNECTING, h.im.state.value)
        h.clock.advance(1)
        assertEquals(ConnState.DISCONNECTED, h.im.state.value)
        assertNotNull(c.closedWith)
        h.clock.advance(1_000)
        assertEquals(2, h.transport.connections.size)
        assertEquals(emptyList(), h.fatals())
        h.login()
        h.clock.advance(20_000)
        assertEquals(ConnState.CONNECTED, h.im.state.value) // ack 到了就取消
    }

    @Test
    fun `旧连接迟到的回调被忽略`() {
        val h = setup()
        h.connect()
        val c1 = h.login()
        c1.drop(1006)
        c1.receive(msgFrame(msg(AB, 1)))
        assertEquals(emptyList(), h.im.messages(AB))
    }
}

/** 发送队列与状态机（FE-005、F-MSG-03）。 */
class SendTest : SdkTest() {
    private fun online(convs: List<kotlinx.serialization.json.JsonObject> = emptyList()): Pair<Harness, com.tianting.sdk.testing.FakeConnection> {
        val h = setup()
        h.connect()
        return h to h.login("alice", convs)
    }

    @Test
    fun `在线发送：发送中到已发送，按 seq 归位；发言即已读`() {
        val (h, c) = online(listOf(conv(AB, maxSeq = 4, unread = 4)))
        val r = h.call { send(AB, "  你好  ") } as SendResult.Ok
        assertEquals(MessageStatus.SENDING, r.message.status)
        assertEquals("你好", r.message.body.text)
        assertEquals(
            obj("convType" to "c2c", "to" to "bob", "clientMsgId" to "cid-0001", "type" to "text", "body" to obj("text" to "你好")),
            c.last("send").dataObj,
        )
        c.ack("send", obj("msgId" to "m5", "convId" to AB, "seq" to 5, "ts" to 99))
        assertEquals(listOf("cid-0001:sent#5"), h.status(AB))
        val v = h.convOf(AB)
        assertEquals(listOf(5L, 5L, 0L), listOf(v.maxSeq, v.readSeq, v.unread))
    }

    @Test
    fun `空白和超长不发送`() {
        val (h, c) = online()
        assertEquals(SendResult.Rejected(TextRejectReason.EMPTY, 0), h.call { send(AB, " \n ") })
        assertEquals(SendResult.Rejected(TextRejectReason.TOO_LONG, 1), h.call { send(AB, "字".repeat(5001)) })
        assertEquals(0, c.frames("send").size)
    }

    @Test
    fun `10 秒没有 ack 标记失败（AC-MSG-03-1）；ack 晚到时以 ack 为准（C-30）`() {
        val (h, c) = online()
        h.call { send(AB, "hi") }
        h.clock.advance(9_999)
        assertEquals(listOf("cid-0001:sending"), h.status(AB))
        h.clock.advance(1)
        assertEquals(listOf("cid-0001:failed(timeout)"), h.status(AB))
        c.ack("send", obj("msgId" to "m1", "convId" to AB, "seq" to 1, "ts" to 1))
        assertEquals(listOf("cid-0001:sent#1"), h.status(AB))
    }

    @Test
    fun `错误码透传：4042、4290、4033、4035、4036、4137、4292 标记失败、不自动重发（AC-MSG-01-9、AC-SOC-08-8、AC-SOC-07-1 的 SDK 部分）`() {
        for (code in listOf(4042, 4290, 4033, 4035, 4036, 4137, 4292)) {
            val (h, c) = online()
            h.call { send(AB, "x") }
            c.ackError("send", code)
            assertEquals(listOf("cid-0001:failed($code)"), h.status(AB))
            assertEquals(listOf(IMEvent.SendFailed(AB, "cid-0001", SendError.Code(code))), h.failed())
            c.drop(1006)
            val c2 = h.reconnectAuth()
            assertEquals(0, c2.frames("send").size, "code $code 不能自动重发")
        }
    }

    @Test
    fun `4137 文案里的 N 等于服务端配置；4036 文案（AC-SOC-08-8、AC-SOC-07-1）`() {
        val (h, c) = online()
        c.ack("socialSync", page(untilVer = 1, strangerMsgLimit = 3, mode = "friends"))
        val limit = h.im.socialSettings.value.strangerMsgLimit
        assertEquals("对方回复之前，最多只能发送 3 条消息", messageFor("send", 4137, limit))
        assertEquals("对方开启了好友验证，你还不是他的好友", messageFor("send", 4035))
        assertEquals("消息已发出，但被对方拒收了", messageFor("send", 4036))
    }

    @Test
    fun `离线发送先排队，重连后自动发一次（AC-MSG-03-2）`() {
        val (h, c1) = online()
        c1.drop(1006)
        h.call { send(AB, "offline") }
        assertEquals(listOf("cid-0001:queued"), h.status(AB))
        val c2 = h.reconnectAuth()
        val sends = c2.frames("send")
        assertEquals(1, sends.size)
        assertEquals("cid-0001", sends[0].dataObj["clientMsgId"]!!.str)
        c2.ack("send", obj("msgId" to "m1", "convId" to AB, "seq" to 1, "ts" to 1))
        assertEquals(listOf("cid-0001:sent#1"), h.status(AB))
    }

    @Test
    fun `自动补发只做一次：补发后又断线就标记失败`() {
        val (h, c1) = online()
        c1.drop(1006)
        h.call { send(AB, "x") }
        val c2 = h.reconnectAuth()
        c2.drop(1006)
        assertEquals(listOf("cid-0001:failed(timeout)"), h.status(AB))
    }

    @Test
    fun `发出后连接断了算一次自动补发：重连发一次（F-MSG-03 规则 2）`() {
        val (h, c1) = online()
        h.call { send(AB, "x") }
        c1.drop(1006)
        assertEquals(listOf("cid-0001:queued"), h.status(AB))
        val c2 = h.reconnectAuth()
        assertEquals(1, c2.frames("send").size)
        c2.drop(1006)
        assertEquals(listOf("cid-0001:failed(timeout)"), h.status(AB))
    }

    @Test
    fun `手动重发沿用原 clientMsgId，成功后只有一条（AC-MSG-03-3、AC-MSG-03-4）`() {
        val (h, c) = online()
        h.call { send(AB, "retry me") }
        h.clock.advance(10_000)
        h.call { retry(AB, "cid-0001") }
        assertEquals(listOf("cid-0001", "cid-0001"), c.frames("send").map { it.dataObj["clientMsgId"]!!.str })
        c.ack("send", obj("msgId" to "m1", "convId" to AB, "seq" to 1, "ts" to 1)) // 服务端去重，返回第一次的 msgId
        assertEquals(listOf("cid-0001:sent#1"), h.status(AB))
        assertEquals("m1", h.im.messages(AB)[0].msgId)
    }

    @Test
    fun `离线时手动重发：排队，重连后只发一次`() {
        val (h, c1) = online()
        h.call { send(AB, "x") }
        h.clock.advance(10_000)
        c1.drop(1006)
        h.call { retry(AB, "cid-0001") }
        assertEquals(listOf("cid-0001:queued"), h.status(AB))
        val c2 = h.reconnectAuth()
        assertEquals(1, c2.frames("send").size)
    }

    @Test
    fun `removeFailed：只移除失败的消息，pendingCount 减 1，不发帧，之后重发无效（AC-UI-09-5、G-UI-14）`() {
        val (h, c) = online()
        h.call { send(AB, "sent one") }
        c.ack("send", obj("msgId" to "m1", "convId" to AB, "seq" to 1, "ts" to 1))
        h.call { send(AB, "fails") }
        h.clock.advance(10_000)
        assertEquals(listOf("cid-0001:sent#1", "cid-0002:failed(timeout)"), h.status(AB))
        assertEquals(1, h.im.pendingCount.value)
        val frames = c.frames().size

        assertFalse(h.call { removeFailed(AB, "cid-0001") }) // sent 不动
        assertFalse(h.call { removeFailed(AB, "nope") })
        assertFalse(h.call { removeFailed("c2c:alice:nobody", "cid-0002") })
        assertEquals(listOf("cid-0001:sent#1", "cid-0002:failed(timeout)"), h.status(AB))

        assertTrue(h.call { removeFailed(AB, "cid-0002") })
        assertEquals(listOf("cid-0001:sent#1"), h.status(AB))
        assertEquals(0, h.im.pendingCount.value)
        assertFalse(h.call { removeFailed(AB, "cid-0002") })
        h.call { retry(AB, "cid-0002") }
        assertEquals(frames, c.frames().size)
        assertEquals(listOf("cid-0001:sent#1"), h.status(AB))
    }

    @Test
    fun `removeFailed：发送中和排队的消息不能移除`() {
        val (h, c) = online()
        h.call { send(AB, "sending") }
        assertFalse(h.call { removeFailed(AB, "cid-0001") })
        assertEquals(listOf("cid-0001:sending"), h.status(AB))
        c.drop(1006)
        h.call { send(AB, "queued") }
        assertFalse(h.call { removeFailed(AB, "cid-0002") })
        assertEquals(MessageStatus.QUEUED, h.im.messages(AB).first { it.clientMsgId == "cid-0002" }.status)
    }

    @Test
    fun `removeFailed 之后，晚到的 ack 和 sync 都不能让它再出现，内存里也没有原文（AC-UI-09-5）`() {
        val (h, c) = online()
        h.call { send(AB, "SECRET-REMOVED") }
        h.clock.advance(10_000)
        h.call { removeFailed(AB, "cid-0001") }
        c.ack("send", obj("msgId" to "m1", "convId" to AB, "seq" to 1, "ts" to 1)) // 超时后 ack 才到
        assertEquals(emptyList(), h.im.messages(AB))
        c.receive(msgFrame(obj(*msg(AB, 1, "alice", "SECRET-REMOVED").entries.map { it.key to it.value }.toTypedArray(), "clientMsgId" to "cid-0001")))
        assertEquals(emptyList(), h.im.messages(AB))
        assertEquals(0, h.im.pendingCount.value)
        assertFalse(holds(h.im, "SECRET-REMOVED"))
    }

    @Test
    fun `removeFailed 只拦自己的消息：别人用同一个 clientMsgId 发的照常显示`() {
        val (h, c) = online(listOf(conv(G)))
        h.call { send(G, "x") }
        h.clock.advance(10_000)
        h.call { removeFailed(G, "cid-0001") }
        c.receive(msgFrame(obj(*msg(G, 1, "bob", "hi").entries.map { it.key to it.value }.toTypedArray(), "clientMsgId" to "cid-0001")))
        assertEquals(listOf("hi"), h.im.messages(G).map { it.body.text })
    }

    @Test
    fun `被踢时发送中和排队的消息全部标记失败（AC-AUTH-03-6），包括还不在会话列表里的新会话`() {
        val (h, c) = online(listOf(conv(AB)))
        h.call { send(AB, "a") }
        h.call { send(AB, "b") }
        h.call { send(AC, "new peer") } // 第一次和 carol 聊，会话列表里还没有
        c.receive(event("kicked", "reason" to "banned"))
        c.drop(4012, "banned")
        assertEquals(listOf("cid-0001:failed(kicked)", "cid-0002:failed(kicked)"), h.status(AB))
        assertEquals(listOf("cid-0003:failed(kicked)"), h.status(AC))
        assertIs<FatalReason.Kicked>(h.fatals()[0])
        assertEquals(3, h.failed().size)
    }

    @Test
    fun `未确认的消息排在所有有 seq 的消息之后（F-MSG-03 规则 3）`() {
        val (h, c) = online(listOf(conv(AB)))
        h.call { send(AB, "mine") }
        c.receive(msgFrame(obj("msgId" to "x", "clientMsgId" to "b1", "convId" to AB, "seq" to 1, "from" to "bob", "type" to "text", "body" to obj("text" to "hi"), "ts" to 1)))
        assertEquals(listOf("b1", "cid-0001"), h.im.messages(AB).map { it.clientMsgId })
        c.ack("send", obj("msgId" to "m2", "convId" to AB, "seq" to 2, "ts" to 2))
        assertEquals(listOf(1L, 2L), h.im.messages(AB).map { it.seq })
    }

    @Test
    fun `还不知道自己是谁时发送抛 NotConnectedError`() {
        val h = setup()
        val d = h.start { runCatching { send(AB, "x") } }
        assertIs<NotConnectedError>(h.await(d).exceptionOrNull())
    }

    @Test
    fun `不合法的会话 ID：发送抛异常，不留下一条永远发送中的消息`() {
        val (h, c) = online()
        assertIs<IllegalArgumentException>(h.call { runCatching { send("abc", "x") } }.exceptionOrNull())
        assertEquals(emptyList(), h.im.messages("abc"))
        assertEquals(0, h.im.pendingCount.value)
        assertEquals(0, c.frames("send").size)
    }

    @Test
    fun `clientMsgId 不以 tip- 开头（AC-SOC-29-5 相关）`() {
        val (h, _) = online()
        val r = h.call { send(AB, "x") } as SendResult.Ok
        assertFalse(r.message.clientMsgId.startsWith("tip-"))
    }
}

/** 同步引擎（FE-004）。 */
class SyncTest : SdkTest() {
    private fun online(convs: List<kotlinx.serialization.json.JsonObject> = emptyList()): Pair<Harness, com.tianting.sdk.testing.FakeConnection> {
        val h = setup()
        h.connect()
        return h to h.login("alice", convs)
    }

    @Test
    fun `缺口：收到 1、3，500ms 内 2 没到就 sync（AC-MSG-04-3）`() {
        val (h, c) = online(listOf(conv(AB)))
        c.receive(msgFrame(msg(AB, 1)))
        c.receive(msgFrame(msg(AB, 3)))
        h.clock.advance(499)
        assertEquals(0, c.frames("sync").size)
        h.clock.advance(1)
        assertEquals(obj("convId" to AB, "afterSeq" to 1, "limit" to 200), c.last("sync").dataObj)
        c.ack("sync", obj("messages" to listOf(msg(AB, 2), msg(AB, 3)), "hasMore" to false))
        assertEquals(listOf(1L, 2L, 3L), h.im.messages(AB).map { it.seq })
    }

    @Test
    fun `缺口在 500ms 内补上就不 sync（AC-MSG-04-4）`() {
        val (h, c) = online(listOf(conv(AB)))
        c.receive(msgFrame(msg(AB, 1)))
        c.receive(msgFrame(msg(AB, 3)))
        h.clock.advance(200)
        c.receive(msgFrame(msg(AB, 2)))
        h.clock.advance(1_000)
        assertEquals(0, c.frames("sync").size)
        assertEquals(listOf(1L, 2L, 3L), h.im.messages(AB).map { it.seq })
    }

    @Test
    fun `按 msgId 去重（F-MSG-04 规则 1）`() {
        val (h, c) = online(listOf(conv(AB)))
        c.receive(msgFrame(msg(AB, 1)))
        c.receive(msgFrame(msg(AB, 1)))
        assertEquals(1, h.im.messages(AB).size)
        assertEquals(1, h.convOf(AB).unread)
    }

    @Test
    fun `重连：先 convs，再只补拉落后的、本地加载过的会话（AC-CONN-01-4）`() {
        val (h, c1) = online(listOf(conv(AB), conv(AC)))
        c1.receive(msgFrame(msg(AB, 1)))
        c1.receive(msgFrame(msg(AB, 2)))
        c1.drop(1006)
        val c2 = h.reconnectAuth()
        c2.ack("convs", obj("convs" to listOf(conv(AB, maxSeq = 5, unread = 3), conv(AC, maxSeq = 9, unread = 9)), "truncated" to false))
        assertEquals(listOf(obj("convId" to AB, "afterSeq" to 2, "limit" to 200)), c2.frames("sync").map { it.dataObj })
        c2.ack("sync", obj("messages" to listOf(msg(AB, 2), msg(AB, 3), msg(AB, 4), msg(AB, 5)), "hasMore" to false))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), h.im.messages(AB).map { it.seq })
    }

    @Test
    fun `群补拉起点为 max(本地 maxSeq, minSeq − 1)（C-24）`() {
        val (h, c1) = online(listOf(conv(G, maxSeq = 3)))
        c1.receive(msgFrame(msg(G, 3)))
        c1.drop(1006)
        val c2 = h.reconnectAuth()
        c2.ack("convs", obj("convs" to listOf(conv(G, maxSeq = 12, minSeq = 11, unread = 2)), "truncated" to false))
        assertEquals(obj("convId" to G, "afterSeq" to 10, "limit" to 200), c2.last("sync").dataObj)
    }

    @Test
    fun `缺口超过 200 条：只加载最新 20 条，向上翻页能加载到全部（AC-CONN-01-8）`() {
        val (h, c1) = online(listOf(conv(AB)))
        c1.receive(msgFrame(msg(AB, 1)))
        c1.drop(1006)
        val c2 = h.reconnectAuth()
        c2.ack("convs", obj("convs" to listOf(conv(AB, maxSeq = 251, unread = 250)), "truncated" to false))
        assertEquals(obj("convId" to AB, "beforeSeq" to 252, "limit" to 20), c2.last("sync").dataObj)
        c2.ack("sync", obj("messages" to (0L until 20).map { msg(AB, 232 + it) }, "hasMore" to true))
        assertEquals((232L until 252).toList(), h.im.messages(AB).map { it.seq })
        val more = h.start { loadHistory(AB) }
        assertEquals(obj("convId" to AB, "beforeSeq" to 232, "limit" to 20), c2.last("sync").dataObj)
        c2.ack("sync", obj("messages" to (0L until 20).map { msg(AB, 212 + it) }, "hasMore" to true))
        assertTrue(h.await(more))
        assertEquals(212, h.im.messages(AB)[0].seq)
    }

    @Test
    fun `打开会话 loadHistory：首次 beforeSeq = maxSeq + 1，hasMore=false 后不再请求（F-MSG-05）`() {
        val (h, c) = online(listOf(conv(AB, maxSeq = 3, unread = 3)))
        val p = h.start { loadHistory(AB) }
        assertEquals(obj("convId" to AB, "beforeSeq" to 4, "limit" to 20), c.last("sync").dataObj)
        c.ack("sync", obj("messages" to listOf(msg(AB, 1), msg(AB, 2), msg(AB, 3)), "hasMore" to false))
        assertFalse(h.await(p))
        val n = c.frames().size
        assertFalse(h.call { loadHistory(AB) })
        assertEquals(n, c.frames().size)
    }

    @Test
    fun `convAdded、convRemoved 更新会话列表（F-GRP-03、AC-CONN-01-7）`() {
        val (h, c) = online()
        c.receive(event("convAdded", "conv" to conv(G, name = "新群", updatedAt = 5)))
        assertEquals(listOf("新群"), h.im.conversations.value.map { it.name })
        c.receive(event("convRemoved", "convId" to G))
        assertEquals(emptyList(), h.im.conversations.value)
        assertEquals(listOf(IMEvent.ConvRemoved(G, null)), h.removed())
    }

    @Test
    fun `会话按 updatedAt 倒序、convId 升序；新消息把会话移到最前（AC-CONV-01-2、AC-CONV-01-4）`() {
        val (h, c) = online(listOf(conv("group:gB", updatedAt = 100), conv("c2c:alice:bob", updatedAt = 100), conv("group:ga", updatedAt = 50)))
        assertEquals(listOf("c2c:alice:bob", "group:gB", "group:ga"), h.im.conversations.value.map { it.convId })
        c.receive(msgFrame(msg("group:ga", 1, "carol", "最新", ts = 200)))
        val first = h.im.conversations.value[0]
        assertEquals("group:ga", first.convId)
        assertEquals("最新", first.lastMsg?.text)
        assertEquals("carol", first.lastMsg?.from)
    }

    @Test
    fun `摘要最多 100 个码点，不切开 emoji`() {
        val (h, c) = online(listOf(conv(AB)))
        c.receive(msgFrame(msg(AB, 1, text = "😀".repeat(150))))
        assertEquals("😀".repeat(100), h.convOf(AB).lastMsg?.text)
    }

    @Test
    fun `别人第一次发来单聊：本地补一个会话，200ms 后重新拉 convs 用服务端数据替换`() {
        val (h, c) = online()
        val before = c.frames("convs").size
        c.receive(msgFrame(msg("c2c:alice:zed", 1, "zed")))
        c.receive(msgFrame(msg("c2c:alice:zed", 2, "zed")))
        assertEquals("zed", h.im.conversations.value[0].name)
        assertEquals(2, h.im.conversations.value[0].unread)
        h.clock.advance(200)
        assertEquals(before + 1, c.frames("convs").size) // 合并为一次
        c.ack("convs", obj("convs" to listOf(conv("c2c:alice:zed", name = "小泽", maxSeq = 2, unread = 2)), "truncated" to false))
        assertEquals("小泽", h.im.conversations.value[0].name)
        assertEquals(2, h.im.conversations.value[0].unread)
        c.receive(msgFrame(msg("c2c:alice:zed", 3, "zed")))
        h.clock.advance(1_000)
        assertEquals(before + 1, c.frames("convs").size) // 已知会话不再刷新
    }

    @Test
    fun `自己在其他端发的消息不增加未读，并推进已读（C-34、AC-SYNC-01-2）`() {
        val (h, c) = online(listOf(conv(AB, maxSeq = 2, unread = 2)))
        c.receive(msgFrame(msg(AB, 3, "alice")))
        assertEquals(0, h.convOf(AB).unread)
        assertEquals(3, h.convOf(AB).readSeq)
    }

    @Test
    fun `sync 翻多页直到拿到 until`() {
        val (h, c) = online(listOf(conv(AB)))
        c.receive(msgFrame(msg(AB, 1)))
        c.receive(msgFrame(msg(AB, 500)))
        h.clock.advance(500)
        c.ack("sync", obj("messages" to (2L..201L).map { msg(AB, it) }, "hasMore" to true))
        assertEquals(201, c.last("sync").dataObj["afterSeq"]!!.jsonPrimitive.long)
        c.ack("sync", obj("messages" to (202L..500L).map { msg(AB, it) }, "hasMore" to false))
        assertEquals(500, h.im.messages(AB).size)
    }
}

/** 已读上报与未读数（FE-006、F-CONV-02）。 */
class ReadTest : SdkTest() {
    private fun online(convs: List<kotlinx.serialization.json.JsonObject>): Pair<Harness, com.tianting.sdk.testing.FakeConnection> {
        val h = setup()
        h.connect()
        return h to h.login("alice", convs)
    }

    private fun Harness.active(id: String?) {
        im.setActiveConversation(id)
        flush()
    }

    @Test
    fun `停在会话且页面可见时上报最新 seq，本地立即清零（AC-CONV-02-2）`() {
        val (h, c) = online(listOf(conv(AB, maxSeq = 3, unread = 3)))
        assertEquals(3, h.convOf(AB).unread)
        h.active(AB)
        assertEquals(obj("convId" to AB, "seq" to 3), c.last("read").dataObj)
        assertEquals(0, h.convOf(AB).unread)
        assertEquals(3, h.convOf(AB).readSeq)
    }

    @Test
    fun `页面在后台不上报，未读增加；切回前台后上报并清零（AC-CONV-02-7）`() {
        val (h, c) = online(listOf(conv(AB)))
        h.visible = false
        h.active(AB)
        c.receive(msgFrame(msg(AB, 1)))
        c.receive(msgFrame(msg(AB, 2)))
        assertEquals(0, c.frames("read").size)
        assertEquals(2, h.convOf(AB).unread)
        h.visible = true
        h.im.visibilityChanged()
        h.flush()
        assertEquals(obj("convId" to AB, "seq" to 2), c.last("read").dataObj)
        assertEquals(0, h.convOf(AB).unread)
    }

    @Test
    fun `节流：1 秒内最多上报一次，值为最新 seq`() {
        val (h, c) = online(listOf(conv(AB)))
        h.active(AB)
        for (s in 1L..5L) {
            c.receive(msgFrame(msg(AB, s)))
            h.clock.advance(100)
        }
        fun reads() = c.frames("read").map { it.dataObj["seq"]!!.jsonPrimitive.int }
        assertEquals(listOf(1), reads())
        h.clock.advance(600) // 距第一次上报满 1 秒
        assertEquals(listOf(1, 5), reads())
    }

    @Test
    fun `不在会话里不上报`() {
        val (h, c) = online(listOf(conv(AB)))
        h.active(null)
        c.receive(msgFrame(msg(AB, 1)))
        assertEquals(0, c.frames("read").size)
    }

    @Test
    fun `未读从入群时刻算起：已读低于 joinSeq 不会让未读变多（C-02）`() {
        val (h, c) = online(listOf(conv(G, maxSeq = 15, minSeq = 11, unread = 5)))
        c.receive(event("readUpdated", "convId" to G, "readSeq" to 3))
        assertEquals(5, h.convOf(G).unread)
        c.receive(event("readUpdated", "convId" to G, "readSeq" to 13))
        assertEquals(2, h.convOf(G).unread)
        c.receive(msgFrame(msg(G, 16)))
        assertEquals(3, h.convOf(G).unread)
    }

    @Test
    fun `readUpdated 只进不退（AC-SYNC-02-1 客户端部分）`() {
        val (h, c) = online(listOf(conv(AB, maxSeq = 10, unread = 10)))
        c.receive(event("readUpdated", "convId" to AB, "readSeq" to 8))
        c.receive(event("readUpdated", "convId" to AB, "readSeq" to 5))
        assertEquals(8, h.convOf(AB).readSeq)
        assertEquals(2, h.convOf(AB).unread)
    }
}

/** FE-380：PRD-ui 的 SDK 缺口。 */
class UiGapsTest : SdkTest() {
    private fun online(convs: List<kotlinx.serialization.json.JsonObject> = emptyList()): Pair<Harness, com.tianting.sdk.testing.FakeConnection> {
        val h = setup()
        h.connect()
        return h to h.login("alice", convs)
    }

    @Test
    fun `totalUnread：2 + 5 + 0 = 7；新消息 +1；打开会话后减少（AC-UI-23-1、AC-UI-23-3）`() {
        val (h, c) = online(listOf(conv(AB, maxSeq = 2, unread = 2), conv(AC, maxSeq = 5, unread = 5), conv(G)))
        assertEquals(7, h.im.totalUnread.value)
        c.receive(msgFrame(msg(AB, 3)))
        assertEquals(8, h.im.totalUnread.value)
        h.im.setActiveConversation(AC)
        h.flush()
        assertEquals(3, h.im.totalUnread.value)
    }

    @Test
    fun `pendingCount：发送中 1，超时失败仍为 1，再发一条 2，两条都成功后 0；退出登录清零（AC-UI-16-2）`() {
        val (h, c) = online(listOf(conv(AB)))
        h.call { send(AB, "one") }
        assertEquals(1, h.im.pendingCount.value)
        h.clock.advance(10_000)
        assertEquals(MessageStatus.FAILED, h.im.messages(AB)[0].status)
        assertEquals(1, h.im.pendingCount.value)
        h.call { send(AC, "two") }
        assertEquals(2, h.im.pendingCount.value)
        c.ack("send", obj("msgId" to "m2", "convId" to AC, "seq" to 1, "ts" to 1))
        assertEquals(1, h.im.pendingCount.value)
        h.call { retry(AB, "cid-0001") }
        assertEquals(1, h.im.pendingCount.value)
        c.ack("send", obj("msgId" to "m1", "convId" to AB, "seq" to 1, "ts" to 2))
        assertEquals(0, h.im.pendingCount.value)
        h.call { send(AB, "three") }
        h.call { disconnect() }
        assertEquals(0, h.im.pendingCount.value)
    }

    @Test
    fun `sendFailed 每次失败只发一次：4290、重发、再次 4290 共两次；超时一次；晚到的 ack 不再发（G-UI-22）`() {
        val (h, c) = online(listOf(conv(AB)))
        h.call { send(AB, "hi") }
        c.ackError("send", 4290)
        h.call { retry(AB, "cid-0001") }
        c.ackError("send", 4290)
        h.call { send(AB, "later") }
        h.clock.advance(10_000)
        c.ack("send", obj("msgId" to "m2", "convId" to AB, "seq" to 1, "ts" to 1))
        assertEquals(
            listOf(
                IMEvent.SendFailed(AB, "cid-0001", SendError.Code(4290)),
                IMEvent.SendFailed(AB, "cid-0001", SendError.Code(4290)),
                IMEvent.SendFailed(AB, "cid-0002", SendError.Timeout),
            ),
            h.failed(),
        )
    }

    @Test
    fun `快照稳定（G-UI-19）：500 个会话推 1 条消息，只有 1 个会话对象换了引用；没变化时列表引用不变`() {
        val (h, c) = online((0 until 500).map { conv("c2c:alice:u${it.toString().padStart(3, '0')}", updatedAt = it.toLong()) })
        val before = h.im.conversations.value
        assertSame(before, h.call { refreshConversations() })
        c.receive(msgFrame(msg("c2c:alice:u250", 1, "u250")))
        val after = h.im.conversations.value
        assertNotSame(before, after)
        val byId = before.associateBy { it.convId }
        assertEquals(listOf("c2c:alice:u250"), after.filter { byId[it.convId] !== it }.map { it.convId })
    }

    @Test
    fun `convs 刷新：内容没变的会话引用不变（G-UI-19）`() {
        val convs = listOf(conv(AB, maxSeq = 1, updatedAt = 1), conv(G, maxSeq = 1, updatedAt = 2, memberCount = 3))
        val (h, c) = online(convs)
        val before = h.im.conversations.value
        h.clock.advance(30_000)
        c.receive("""{"cmd":"pong"}""")
        val p = h.start { refreshConversations() }
        c.ack("convs", obj("convs" to listOf(convs[0], conv(G, maxSeq = 1, updatedAt = 2, memberCount = 4)), "truncated" to false))
        h.await(p)
        assertSame(before.first { it.convId == AB }, h.convOf(AB))
        assertNotSame(before.first { it.convId == G }, h.convOf(G))
    }

    @Test
    fun `Message 和 Conversation 是不可变的值：相同内容 equals 相等（G-UI-19）`() {
        val a = Message("c", null, null, AB, "alice", MsgType.TEXT, MsgBody("x"), 1, MessageStatus.SENDING)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
    }

    @Test
    fun `memberCount 透传，缺失时是 null；30 秒内只拉一次 convs；truncated 可读（G-UI-02、G-UI-24）`() {
        val (h, c) = online(listOf(conv(G, memberCount = 5), conv("group:g2")))
        assertEquals(5, h.convOf(G).memberCount)
        assertNull(h.convOf("group:g2").memberCount)
        assertFalse(h.im.truncated.value)
        fun sent() = c.frames("convs").size
        val n = sent()
        h.call { refreshConversations() }
        assertEquals(n, sent())
        h.clock.advance(29_999)
        h.call { refreshConversations() }
        assertEquals(n, sent())
        c.receive("""{"cmd":"pong"}""")
        h.clock.advance(1)
        val p = h.start { refreshConversations() }
        assertEquals(n + 1, sent())
        c.ack("convs", obj("convs" to listOf(conv(G, memberCount = 7), conv("group:g2")), "truncated" to true))
        h.await(p)
        assertEquals(7, h.convOf(G).memberCount)
        assertTrue(h.im.truncated.value)
    }

    @Test
    fun `在线收到 convRemoved：保留快照和 reason；convAdded 清除快照；退出登录清除（G-UI-21）`() {
        val (h, c) = online(listOf(conv(G, name = "周末球局")))
        c.receive(event("convRemoved", "convId" to G, "reason" to "dissolved"))
        assertEquals(emptyList(), h.im.conversations.value)
        assertEquals(ConversationSnapshot(G, ConvType.GROUP, "周末球局", "dissolved"), h.call { conversationSnapshot(G) })
        assertEquals(listOf(IMEvent.ConvRemoved(G, "dissolved")), h.removed())
        c.receive(event("convAdded", "conv" to conv(G, name = "周末球局")))
        assertNull(h.call { conversationSnapshot(G) })
        c.receive(event("convRemoved", "convId" to G))
        assertNull(h.call { conversationSnapshot(G) }?.reason)
        h.call { disconnect() }
        assertNull(h.call { conversationSnapshot(G) })
    }

    @Test
    fun `不认识的 reason 原样透传（AC-ADM 第一批：忽略未知）`() {
        val (h, c) = online(listOf(conv(G)))
        c.receive(event("convRemoved", "convId" to G, "reason" to "somethingNew"))
        assertEquals("somethingNew", h.call { conversationSnapshot(G) }?.reason)
    }

    @Test
    fun `离线期间被移出：重连后列表里消失的群生成快照并发事件；单聊不推断；截断时不推断（G-UI-21）`() {
        val (h, c1) = online(listOf(conv(G, name = "g1"), conv(AB), conv("group:g2")))
        c1.drop(1006)
        val c2 = h.reconnectAuth()
        c2.ack("convs", obj("convs" to listOf(conv("group:g2")), "truncated" to false))
        assertEquals(listOf(G), h.removed().map { it.convId })
        assertEquals("g1", h.call { conversationSnapshot(G) }?.name)
        assertNull(h.call { conversationSnapshot(AB) })
        c2.drop(1006)
        val c3 = h.reconnectAuth(waitMs = 2_000)
        c3.ack("convs", obj("convs" to emptyList<Any>(), "truncated" to true))
        assertEquals(listOf(G), h.removed().map { it.convId })
        assertNull(h.call { conversationSnapshot("group:g2") })
    }

    @Test
    fun `打开未知会话：sync beforeSeq = 2^53−1；4033、4043、4032 以 IMError 交给界面（G-UI-26）`() {
        for (code in listOf(4033, 4043, 4032)) {
            val (h, c) = online()
            val p = h.start { runCatching { loadHistory(G) } }
            assertEquals(obj("convId" to G, "beforeSeq" to 9007199254740991L, "limit" to 20), c.last("sync").dataObj)
            c.ackError("sync", code)
            assertEquals(code, (h.await(p).exceptionOrNull() as IMError).code)
        }
    }

    @Test
    fun `有历史时把消息和会话补进列表；单聊没有消息时不进列表（G-UI-26）`() {
        val (h, c) = online()
        val p = h.start { loadHistory(G) }
        c.ack("sync", obj("messages" to listOf(msg(G, 1), msg(G, 2)), "hasMore" to false))
        assertFalse(h.await(p))
        assertEquals(listOf(1L, 2L), h.im.messages(G).map { it.seq })
        assertEquals(listOf(G), h.im.conversations.value.map { it.convId })
        val q = h.start { loadHistory(AB) }
        c.ack("sync", obj("messages" to emptyList<Any>(), "hasMore" to false))
        assertFalse(h.await(q))
        assertEquals(listOf(G), h.im.conversations.value.map { it.convId })
    }

    @Test
    fun `AC-SOC-11-4：开放模式下有单聊的陌生人不在好友列表`() {
        val (h, c) = online(listOf(conv("c2c:alice:carol", maxSeq = 1)))
        c.ack("socialSync", page(untilVer = 1, friends = listOf(friend("bob", 1))))
        assertEquals(listOf("bob"), h.im.friends.value.map { it.userId })
        assertTrue(h.im.conversations.value.any { it.convId == "c2c:alice:carol" })
    }
}

/** 忽略未知事件 / 字段 / 枚举（协议 README 第 3 节规则 9）。 */
class ForwardCompatTest : SdkTest() {
    @Test
    fun `未知事件、未知 cmd、未知字段、未知枚举都不影响处理`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(G)))
        c.receive(event("someFutureEvent", "x" to 1))
        c.receive(obj("cmd" to "somethingNew", "data" to obj("a" to 1)))
        c.receive(obj("cmd" to "push", "data" to obj("pushId" to "p1", "type" to "goal", "data" to obj(), "ts" to 1)))
        // 未知字段
        c.receive(msgFrame(obj(*msg(G, 1).entries.map { it.key to it.value }.toTypedArray(), "futureField" to obj("a" to 1))))
        assertEquals(1, h.im.messages(G).size)
        // 未知的消息类型：保留，界面按未知处理
        c.receive(msgFrame(obj(*msg(G, 2).entries.map { it.key to it.value }.toTypedArray(), "type" to "sticker")))
        assertEquals(MsgType.UNKNOWN, h.im.messages(G)[1].type)
        // 未知的会话类型和角色
        c.receive(event("convAdded", "conv" to conv("group:g9", type = "channel", myRole = "superAdmin")))
        val v = h.convOf("group:g9")
        assertEquals(ConvType.UNKNOWN, v.type)
        assertEquals(GroupRole.UNKNOWN, v.myRole)
        // 未知的来源、申请状态、社交模式
        c.ack(
            "socialSync",
            page(
                untilVer = 1,
                mode = "invite-only",
                friends = listOf(obj(*friend("x", 1).entries.map { it.key to it.value }.toTypedArray(), "source" to "qrcode")),
                requests = listOf(obj(*req("y", "in", 1).entries.map { it.key to it.value }.toTypedArray(), "status" to "frozen")),
            ),
        )
        assertEquals(SocialMode.UNKNOWN, h.im.socialSettings.value.mode)
        assertEquals(ApplySource.UNKNOWN, h.im.friends.value[0].source)
        assertEquals(RequestStatus.UNKNOWN, h.im.friendRequests.value.items[0].status)
        // 未知的提示类型
        c.receive(msgFrame(obj("msgId" to "t", "clientMsgId" to "tip-9", "convId" to G, "seq" to 3, "from" to "", "type" to "tip", "body" to obj("tip" to obj("kind" to "pinned", "actor" to "", "targets" to emptyList<Any>(), "text" to "")), "ts" to 9)))
        assertEquals("[群通知]", tipText(h.im.messages(G)[2].body.tip!!, "alice") { it })
        assertEquals("[群通知]", h.convOf(G).lastMsg?.text)
        assertEquals(1, h.convOf(G).unread.coerceAtMost(1)) // tip 计入未读
        assertEquals(ConnState.CONNECTED, h.im.state.value)
        assertEquals(emptyList(), h.logs.filter { it.contains("unexpected") })
    }

    @Test
    fun `字段类型错的帧只丢这一帧，后面的照常处理`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(G)))
        c.receive(obj("cmd" to "msg", "data" to obj("seq" to "not-a-number")))
        c.receive(event("readUpdated", "convId" to G, "readSeq" to "x"))
        c.receive(event("muted", "scope" to "user")) // 缺 mutedUntil
        assertNull(h.call { muteState(G) })
        c.receive(msgFrame(msg(G, 1)))
        assertEquals(1, h.im.messages(G).size)
    }
}

class SignerTest {
    @Test
    fun `集成测试的管理接口签名与 admin-sign json 的 HMAC 用例一致`() {
        val spec = vector("admin-sign.json")
        val vectors = (spec as kotlinx.serialization.json.JsonObject)["vectors"] as kotlinx.serialization.json.JsonArray
        var n = 0
        for (v in vectors) {
            val o = v as kotlinx.serialization.json.JsonObject
            if (o["alg"]!!.str != "hmac-sha256") continue
            val secret = java.util.Base64.getDecoder().decode(o["secret"]!!.str)
            assertEquals(o["signature"]!!.str, AdminSigner.sign(secret, o["stringToSign"]!!.str), o["name"]!!.str)
            if (o["query"]!!.str.isEmpty()) {
                val built = AdminSigner.stringToSign(
                    o["method"]!!.str, o["path"]!!.str, o["timestamp"]!!.str, o["nonce"]!!.str, o["body"]!!.str,
                    o["operator"]!!.str, o["requestId"]!!.str, o["targetNode"]!!.str,
                )
                assertEquals(o["stringToSign"]!!.str, built, o["name"]!!.str)
            }
            n++
        }
        assertTrue(n >= 2)
    }
}
