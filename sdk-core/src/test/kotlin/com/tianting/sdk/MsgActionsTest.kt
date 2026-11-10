// FE-661、FE-662（C4）：撤回、引用、提及（PRD-chat 第 8 节 F-RCL、F-QUO、F-MEN，第 13.1 节）。语义同 sdk-ts 的 test/msg-actions.test.ts。
// 真实的 IMClient + FakeTransport + 假时钟（[SDK 钟]）；本地库、缓存目录、冷启动、升级的用例在 MsgActionsPersistenceTest。
// 共享用例在 ChatSyncVectorsTest，SDK 发出的帧都按 frame.schema.json 校验（SdkTest）。测试名带 TC 编号。本地单测结果，CI 不运行。
package com.tianting.sdk

import com.tianting.sdk.testing.FakeConnection
import kotlinx.coroutines.Deferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val C4_AB = "c2c:alice:bob"
internal const val C4_G = "group:g1"
internal const val RCL_SECRET = "ZXQ-RCL-2"
internal const val QUO_SECRET = "ZXQ-QUO-1"

/** 消息 ID 用协议允许的字符（Schema 的 msgId 不允许 ":" 和 "#"）。 */
internal fun idOf(convId: String, seq: Long) = "${convId.replace(Regex("[^A-Za-z0-9]"), "")}-$seq"

/** 下行文本消息；body 不为 null 时替换 body。 */
internal fun m(convId: String, seq: Long, from: String = "bob", text: String = "m$seq", ts: Long = 1000 + seq, body: JsonObject? = null): JsonObject {
    val base = msg(convId, seq, from, text, ts) + mapOf("msgId" to JsonPrimitive(idOf(convId, seq)))
    return JsonObject(if (body != null) base + mapOf("body" to body) else base)
}

/** 服务端下发的撤回状态（sync、迟到的 msg）：deleted + recalled，没有 body。 */
internal fun recalledMsg(convId: String, seq: Long, from: String = "bob", by: String = from, at: Long = 9): JsonObject = obj(
    "msgId" to idOf(convId, seq), "clientMsgId" to "$from-$convId-$seq", "convId" to convId, "seq" to seq, "from" to from, "type" to "text",
    "ts" to 1000 + seq, "deleted" to true, "recalled" to true, "recalledAt" to at, "recalledBy" to by,
)

internal fun quoteOf(convId: String, seq: Long, text: String, from: String = "bob") =
    obj("msgId" to idOf(convId, seq), "seq" to seq, "from" to from, "type" to "text", "state" to "normal", "text" to text)

/** 会话列表里的最后一条就是 seq 这条（convs 带的摘要）。 */
internal fun lastOf(convId: String, seq: Long, from: String, text: String) =
    obj("msgId" to idOf(convId, seq), "from" to from, "type" to "text", "text" to text, "ts" to 1000 + seq)

internal fun recalledEvent(convId: String, seq: Long, by: String, at: Long = 3) =
    event("msgDeleted", "convId" to convId, "msgIds" to listOf(idOf(convId, seq)), "seqs" to listOf(seq), "recalled" to obj("by" to by, "at" to at))

internal fun deletedEvent(convId: String, seq: Long) = event("msgDeleted", "convId" to convId, "msgIds" to listOf(idOf(convId, seq)), "seqs" to listOf(seq))

/** 打开会话加载最新一页，sync 用给定消息回复。 */
internal fun Harness.loaded(c: FakeConnection, convId: String, messages: List<JsonObject>) {
    val d = start { loadHistory(convId) }
    c.ack("sync", obj("messages" to messages, "hasMore" to false))
    await(d)
}

internal fun Harness.bySeq(convId: String, seq: Long): Message = im.messages(convId).first { it.seq == seq }

internal fun <T> Harness.failure(d: Deferred<T>): Throwable {
    flush()
    return d.getCompletionExceptionOrNull() ?: throw AssertionError("应该失败")
}

internal fun FakeConnection.ackErr(cmd: String, code: Int, details: JsonObject? = null, reason: String? = null) =
    receive(obj("id" to last(cmd)["id"]!!.str, "cmd" to "ack", "error" to objNoNull("code" to code, "msg" to "error", "details" to details, "reason" to reason)))

private val quiet = IMClientTimings(heartbeatMs = 10 * 365 * 24 * 3_600_000L) // 推进时钟时不因心跳断线

class MsgActionsTest : SdkTest() {
    private val AB = C4_AB
    private val G = C4_G

    // ---- 撤回（F-RCL-01～03） ----

    @Test
    fun `TC-RCL-01-01（AC-RCL-01-1）：recall 发出 convId、msgId，ack 后本端立即变撤回；对方和自己另一端收到带 recalled 的 msgDeleted`() {
        // 操作者：ack 之后本地清除（服务端不给发起的连接发 msgDeleted，问题 27）。
        val a = setup()
        a.connect()
        val ca = a.login("alice", listOf(conv(AB, maxSeq = 1, lastMsg = lastOf(AB, 1, "alice", "$RCL_SECRET 原文"))))
        a.loaded(ca, AB, listOf(m(AB, 1, "alice", "$RCL_SECRET 原文")))
        val p = a.start { recall(AB, idOf(AB, 1)) }
        assertEquals(obj("convId" to AB, "msgId" to idOf(AB, 1)), ca.last("recall").dataObj)
        ca.ack("recall", obj())
        a.await(p)
        val x = a.bySeq(AB, 1)
        assertTrue(x.deleted && x.recalled)
        assertEquals("alice", x.recalledBy)
        assertEquals(a.im.serverNow(), x.recalledAt) // 校正后的服务端时间估算，下次重拉以服务端为准
        assertEquals(MsgBody(), x.body)
        assertEquals("你撤回了一条消息", a.call { recallText(x) })
        val last = a.convOf(AB).lastMsg!!
        assertTrue(last.deleted && last.recalled)
        assertEquals("alice", last.recalledBy)
        assertNull(last.text)
        assertEquals("你撤回了一条消息", a.call { recallText(AB, last) })
        assertFalse(holds(storeOf(a.im), RCL_SECRET))

        // 对方（bob）和 alice 的另一端：msgDeleted 带 recalled。
        for ((self, want) in listOf("bob" to "对方撤回了一条消息", "alice" to "你撤回了一条消息")) {
            val h = setup()
            h.connect()
            val c = h.login(self, listOf(conv(AB, maxSeq = 1)))
            h.loaded(c, AB, listOf(m(AB, 1, "alice", "$RCL_SECRET 原文")))
            c.receive(recalledEvent(AB, 1, "alice", at = 5))
            val y = h.bySeq(AB, 1)
            assertTrue(y.deleted && y.recalled, self)
            assertEquals("alice", y.recalledBy)
            assertEquals(5L, y.recalledAt)
            assertEquals(want, h.call { recallText(y) }, self)
            assertFalse(holds(storeOf(h.im), RCL_SECRET), self)
        }
    }

    @Test
    fun `TC-RCL-01-08（AC-RCL-01-8）：canRecall 按 ts 和 auth ack 校正后的服务端时间（设备快 10 分钟），边界 120 秒；菜单打开期间过期点了提示 4097`() {
        val h = setup(timings = quiet)
        h.connect()
        val c = h.transport.current
        c.open()
        val server = h.clock.now() - 600_000 // 设备时钟比服务端快 10 分钟
        c.ack("auth", obj("userId" to "alice", "serverTime" to server))
        c.ack("convs", obj("convs" to listOf(conv(AB, maxSeq = 3)), "truncated" to false))
        h.loaded(
            c,
            AB,
            listOf(
                m(AB, 1, "alice", "60 秒前", ts = server - 60_000),
                m(AB, 2, "alice", "121 秒前", ts = server - 121_000),
                m(AB, 3, "alice", "恰好 120 秒", ts = server - 120_000),
            ),
        )
        assertEquals(listOf(true, false, true), (1L..3L).map { h.im.canRecall(h.bySeq(AB, it)) })
        assertEquals(listOf(MessageAction.COPY, MessageAction.QUOTE, MessageAction.RECALL), h.im.messageActions(h.bySeq(AB, 1)))
        assertEquals(listOf(MessageAction.COPY, MessageAction.QUOTE), h.im.messageActions(h.bySeq(AB, 2)))

        // 菜单开着过了 70 秒：点"撤回"由服务端判断，4097 带 windowSeconds，消息不变，不自动重试。
        h.clock.advance(70_000)
        assertFalse(h.im.canRecall(h.bySeq(AB, 1)))
        val p = h.start { recall(AB, idOf(AB, 1)) }
        c.ackErr("recall", 4097, obj("windowSeconds" to 120))
        val err = assertIs<IMError>(h.failure(p))
        assertEquals(ErrorCode.RECALL_WINDOW_EXPIRED, err.code)
        val ws = (err.details!!["windowSeconds"] as JsonPrimitive).long
        assertEquals("发送时间超过 2 分钟，无法撤回", messageFor("recall", err.code, windowSeconds = ws))
        assertEquals("发送时间超过 7 天，无法撤回", messageFor("recall", 4097, windowSeconds = 604_800))
        h.clock.advance(60_000)
        assertEquals(1, c.frames("recall").size)
        assertEquals("60 秒前", h.bySeq(AB, 1).body.text)
        assertFalse(h.bySeq(AB, 1).deleted)
    }

    @Test
    fun `TC-RCL-01-08（F-RCL-02 规则 1、1b）：群里的时限和角色：群主 7 天内任何人的；管理员 7 天内别人的、自己的也是 7 天；成员只有自己的 2 分钟`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(G, maxSeq = 5, myRole = "member")))
        val now = h.clock.now()
        val tip = JsonObject(
            m(G, 5, "", "", ts = now - 1_000, body = obj("tip" to obj("kind" to "created", "actor" to "bob", "targets" to emptyList<String>(), "text" to ""))) +
                mapOf("type" to JsonPrimitive("tip")),
        )
        h.loaded(
            c,
            G,
            listOf(
                m(G, 1, "bob", "别人 6 天前", ts = now - 6 * 86_400_000L),
                m(G, 2, "bob", "别人 8 天前", ts = now - 8 * 86_400_000L),
                m(G, 3, "alice", "自己 1 天前", ts = now - 86_400_000L),
                m(G, 4, "alice", "自己 60 秒前", ts = now - 60_000),
                tip,
            ),
        )
        fun can(role: GroupRole) = (1L..5L).map { h.im.canRecall(h.bySeq(G, it), role) }
        assertEquals(listOf(true, false, true, true, false), can(GroupRole.OWNER))
        assertEquals(listOf(true, false, true, true, false), can(GroupRole.ADMIN)) // 对方是管理员时服务端回 4030（规则 1b）
        assertEquals(listOf(false, false, false, true, false), can(GroupRole.MEMBER))
        // 不传角色时用会话列表里的 myRole。
        assertEquals(listOf(false, true), listOf(1L, 4L).map { h.im.canRecall(h.bySeq(G, it)) })
        assertEquals(emptyList(), h.im.messageActions(h.bySeq(G, 5))) // 提示行没有菜单
    }

    @Test
    fun `AC-RCL-02-5：管理员撤回其他管理员的消息，服务端 4030，提示"你没有权限撤回这条消息"，消息不变`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(G, maxSeq = 1, myRole = "admin")))
        h.loaded(c, G, listOf(m(G, 1, "bob", "管理员 bob 的消息", ts = h.clock.now() - 1_000)))
        assertTrue(MessageAction.RECALL in h.im.messageActions(h.bySeq(G, 1)))
        val p = h.start { recall(G, idOf(G, 1)) }
        c.ackErr("recall", ErrorCode.FORBIDDEN)
        val err = assertIs<IMError>(h.failure(p))
        assertEquals(4030, err.code)
        assertEquals("你没有权限撤回这条消息", messageFor("recall", err.code))
        assertEquals("管理员 bob 的消息", h.bySeq(G, 1).body.text)
    }

    @Test
    fun `TC-RCL-02-04（AC-RCL-02-4）：a 撤回 m 的最后一条：a、m、n 的聊天页和列表摘要按规则 1a 第 3 行一致`() {
        val names = mapOf("a" to "阿管", "m" to "小明", "n" to "小牛")
        val want = mapOf("a" to "你撤回了 小明 的一条消息", "m" to "阿管撤回了你的一条消息", "n" to "阿管撤回了一条成员消息")
        for (self in listOf("a", "m", "n")) {
            val h = setup()
            h.connect()
            val c = h.login(self, listOf(conv(G, maxSeq = 1, myRole = if (self == "a") "admin" else "member", lastMsg = lastOf(G, 1, "m", "$RCL_SECRET 成员的消息"))))
            // 显示名来自成员资料（备注只在本地显示，问题 19）。
            val pm = h.start { loadGroupMembers("g1") }
            c.ack(
                "groupMembers",
                obj("members" to names.map { (u, n) -> obj("userId" to u, "nickname" to n, "role" to "member", "joinedAt" to 1, "avatar" to "", "official" to false) }, "nextCursor" to ""),
            )
            h.await(pm)
            h.loaded(c, G, listOf(m(G, 1, "m", "$RCL_SECRET 成员的消息", ts = h.clock.now() - 1_000)))
            if (self == "a") {
                val p = h.start { recall(G, idOf(G, 1)) }
                c.ack("recall", obj())
                h.await(p)
            } else {
                c.receive(recalledEvent(G, 1, "a", at = 9))
            }
            val last = h.convOf(G).lastMsg!!
            assertTrue(last.deleted && last.recalled)
            assertEquals("a", last.recalledBy)
            assertNull(last.text)
            assertEquals(want[self], h.call { recallText(h.bySeq(G, 1)) }, self)
            assertEquals(want[self], h.call { recallText(G, last) }, "$self 列表")
            assertFalse(holds(storeOf(h.im), RCL_SECRET))
        }
    }

    @Test
    fun `TC-RCL-03-01（AC-RCL-03-1）：收到带 recalled 的 msgDeleted 后内存搜不到原文；图片撤回通过 FilesExpired 丢缓存，未读和排序不变`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(AB, maxSeq = 2)))
        val img = JsonObject(
            m(AB, 2, body = obj("image" to obj("fileId" to "f-zxq", "width" to 1, "height" to 1, "size" to 1, "mime" to "image/png"))) +
                mapOf("type" to JsonPrimitive("image")),
        )
        h.loaded(c, AB, listOf(m(AB, 1, "bob", "$RCL_SECRET 原文"), img))
        val before = h.events.size
        c.receive(
            event("msgDeleted", "convId" to AB, "msgIds" to listOf(idOf(AB, 1), idOf(AB, 2)), "seqs" to listOf(1, 2), "recalled" to obj("by" to "bob", "at" to 7)),
        )
        assertFalse(holds(storeOf(h.im), RCL_SECRET))
        assertFalse(holds(storeOf(h.im), "f-zxq"))
        assertEquals(listOf(Triple(1L, true, true), Triple(2L, true, true)), h.im.messages(AB).map { Triple(it.seq!!, it.deleted, it.recalled) })
        assertTrue(h.im.messages(AB).all { it.body == MsgBody() })
        assertEquals(listOf(listOf("f-zxq")), h.events.drop(before).filterIsInstance<IMEvent.FilesExpired>().map { it.fileIds })
        // 未读不变、会话不重新排序（F-RCL-01 规则 6）。
        assertEquals(0L, h.convOf(AB).updatedAt)
        assertEquals(0L, h.convOf(AB).unread)
    }

    @Test
    fun `TC-RCL-03-03、TC-RCL-03-11（AC-RCL-03-3、03-11）：断网期间被撤回，重连只对 modVer 变了的 c1 重拉，消息变撤回文案`() {
        val h = setup()
        val (c1, c2, c3) = listOf("c2c:alice:bob", "c2c:alice:carol", "c2c:alice:dave")
        h.connect()
        var c = h.login("alice", listOf(conv(c1, maxSeq = 1, modVer = 1), conv(c2, maxSeq = 1, modVer = 1), conv(c3, maxSeq = 1, modVer = 1)))
        for (id in listOf(c1, c2, c3)) h.loaded(c, id, listOf(m(id, 1, id.split(":")[2], "$RCL_SECRET 原文")))
        c.drop(1006)
        h.clock.advance(1_000)
        c = h.login("alice", listOf(conv(c1, maxSeq = 1, modVer = 2), conv(c2, maxSeq = 1, modVer = 1), conv(c3, maxSeq = 1, modVer = 1)))
        assertEquals(listOf(c1), c.frames("sync").map { it.dataObj["convId"]!!.str })
        c.ack("sync", obj("messages" to listOf(recalledMsg(c1, 1, "bob", at = 3)), "hasMore" to false))
        val x = h.bySeq(c1, 1)
        assertTrue(x.deleted && x.recalled)
        assertEquals("bob", x.recalledBy)
        assertEquals("对方撤回了一条消息", h.call { recallText(x) })
        assertFalse(h.im.messages(c1).toString().contains(RCL_SECRET))
        assertEquals(1, c.frames("sync").size)
    }

    @Test
    fun `TC-RCL-03-06（AC-RCL-03-6）：群里 u1 撤回自己的最后一条：u1 列表"你撤回了一条消息"，其他成员"{u1 显示名}撤回了一条消息"`() {
        for ((self, want) in listOf("u1" to "你撤回了一条消息", "n" to "u1撤回了一条消息")) {
            val h = setup()
            h.connect()
            // 列表来自 convs（还没打开过这个群）：lastMsg 带 recalled、recalledBy。
            h.login(self, listOf(conv(G, maxSeq = 4, lastMsg = obj("msgId" to "x4", "from" to "u1", "type" to "text", "ts" to 4, "deleted" to true, "recalled" to true, "recalledBy" to "u1"))))
            assertEquals(want, h.call { recallText(G, h.convOf(G).lastMsg!!) }, self)
        }
    }

    @Test
    fun `TC-RCL-03-10（AC-RCL-03-10）：单聊撤回，对方"对方撤回了一条消息"、自己"你撤回了一条消息"；提示行菜单为空`() {
        val line = Message(
            clientMsgId = "x", msgId = "x", seq = 1, convId = "c2c:u1:u2", from = "u1", type = MsgType.TEXT, ts = 0,
            status = MessageStatus.SENT, deleted = true, recalled = true, recalledBy = "u1",
        )
        assertEquals("对方撤回了一条消息", recallText(recallNoticeOf(line.convId, line, "u2")!!) { it })
        assertEquals("你撤回了一条消息", recallText(recallNoticeOf(line.convId, line, "u1")!!) { it })
        // 后台删除不是撤回：没有撤回文案（显示"该消息已被删除"）。
        assertNull(recallNoticeOf(line.convId, line.copy(recalled = false, recalledBy = null), "u2"))
        val h = setup()
        assertEquals(emptyList(), h.im.messageActions(line.copy(ts = h.clock.now())))
        assertFalse(h.im.canRecall(line.copy(ts = h.clock.now())))
        // 列表摘要用同一张表（老数据没有 recalledBy 时按发送者本人撤回）。
        val last = LastMsg("x", "u1", MsgType.TEXT, null, 0, deleted = true, recalled = true)
        assertEquals("对方撤回了一条消息", recallText(recallNoticeOf("c2c:u1:u2", last, "u2")!!) { it })
    }

    @Test
    fun `TC-RCL-03-13（AC-RCL-03-13）：m1 本地已撤回，迟到的旧应答带 m1 原文和（之前没加载过的）m2 的 quote state=normal：都不恢复`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(G, maxSeq = 2)))
        h.loaded(c, G, listOf(m(G, 1, "bob", "$RCL_SECRET 原文")))
        c.receive(recalledEvent(G, 1, "bob"))
        // 迟到的旧 sync（撤回之前读的库）。
        val late = listOf(
            m(G, 1, "bob", "$RCL_SECRET 原文"),
            m(G, 2, "carol", "回复", body = obj("text" to "回复", "quote" to quoteOf(G, 1, "$RCL_SECRET 原文"))),
        )
        h.start { loadHistory(G) }
        c.ack("sync", obj("messages" to late, "hasMore" to false))
        for (x in late) c.receive(msgFrame(x)) // 同样的内容也可能从实时通道迟到
        val m1 = h.bySeq(G, 1)
        assertTrue(m1.deleted && m1.recalled)
        assertEquals(MsgBody(), m1.body)
        assertEquals(QuoteView(idOf(G, 1), 1, "bob", MsgType.TEXT, QuoteState.RECALLED), h.bySeq(G, 2).body.quote)
        assertFalse(holds(storeOf(h.im), RCL_SECRET))
    }

    @Test
    fun `F-RCL-03 规则 2a：后台删除的消息之后才知道是撤回（recalled 从假变真）；已知撤回的不会被后台删除改回去`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(G, maxSeq = 2)))
        h.loaded(c, G, listOf(m(G, 1), m(G, 2)))
        c.receive(deletedEvent(G, 1))
        c.receive(recalledEvent(G, 2, "bob"))
        assertEquals(listOf(true to false, true to true), h.im.messages(G).map { it.deleted to it.recalled })
        c.receive(recalledEvent(G, 1, "bob", at = 4))
        c.receive(deletedEvent(G, 2))
        assertEquals(listOf(Triple(true, true, "bob"), Triple(true, true, "bob")), h.im.messages(G).map { Triple(it.deleted, it.recalled, it.recalledBy) })
    }

    @Test
    fun `F-RCL-03 规则 2a：sync 里才知道是撤回（本地按后台删除清过）：补上 recalled、recalledBy，正文不回来；之后的旧应答也不回写`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(G, maxSeq = 1, lastMsg = lastOf(G, 1, "bob", RCL_SECRET))))
        h.loaded(c, G, listOf(m(G, 1, "bob", RCL_SECRET)))
        c.receive(deletedEvent(G, 1))
        // 服务端的撤回状态（重拉或实时通道迟到的同一条），之后再来一份更旧的原文。
        c.receive(msgFrame(recalledMsg(G, 1, "bob", at = 9)))
        c.receive(msgFrame(m(G, 1, "bob", RCL_SECRET)))
        val x = h.bySeq(G, 1)
        assertTrue(x.deleted && x.recalled)
        assertEquals("bob", x.recalledBy)
        assertEquals(9L, x.recalledAt)
        assertEquals(MsgBody(), x.body)
        val last = h.convOf(G).lastMsg!!
        assertTrue(last.deleted && last.recalled)
        assertEquals("bob", last.recalledBy)
        assertFalse(holds(storeOf(h.im), RCL_SECRET))
    }

    @Test
    fun `F-RCL-03 规则 2a、4：会话摘要的撤回只能从假变真（后台删除后才知道是撤回会补上；之后的 convs 只带 deleted 不改回去）`() {
        val h = setup(timings = quiet)
        h.connect()
        val c = h.login("alice", listOf(conv(G, maxSeq = 1, lastMsg = lastOf(G, 1, "bob", RCL_SECRET))))
        h.loaded(c, G, listOf(m(G, 1, "bob", RCL_SECRET)))
        c.receive(deletedEvent(G, 1))
        assertTrue(h.convOf(G).lastMsg!!.deleted)
        assertFalse(h.convOf(G).lastMsg!!.recalled)
        c.receive(recalledEvent(G, 1, "bob"))
        assertTrue(h.convOf(G).lastMsg!!.recalled)
        assertEquals("bob", h.convOf(G).lastMsg!!.recalledBy)
        // 撤回之前生成的 convs（只有 deleted，或者还带原文）：摘要保持撤回。
        for (stale in listOf(
            obj("msgId" to idOf(G, 1), "from" to "bob", "type" to "text", "ts" to 1001, "deleted" to true),
            lastOf(G, 1, "bob", RCL_SECRET),
        )) {
            h.clock.advance(31_000)
            val p = h.start { refreshConversations() }
            c.ack("convs", obj("convs" to listOf(conv(G, maxSeq = 1, lastMsg = stale)), "truncated" to false))
            h.await(p)
            val last = h.convOf(G).lastMsg!!
            assertTrue(last.deleted && last.recalled, "$last")
            assertNull(last.text)
        }
        assertFalse(holds(storeOf(h.im), RCL_SECRET))
    }

    @Test
    fun `F-RCL-03 规则 4：错过了 msgDeleted，convs 的 lastMsg 带 recalled：已加载的那条按撤回清掉（不等重拉）`() {
        val h = setup(timings = quiet)
        h.connect()
        val c = h.login("alice", listOf(conv(G, maxSeq = 1, lastMsg = lastOf(G, 1, "bob", RCL_SECRET))))
        h.loaded(c, G, listOf(m(G, 1, "bob", RCL_SECRET)))
        h.clock.advance(31_000)
        val p = h.start { refreshConversations() }
        c.ack(
            "convs",
            obj("convs" to listOf(conv(G, maxSeq = 1, lastMsg = obj("msgId" to idOf(G, 1), "from" to "bob", "type" to "text", "ts" to 1001, "deleted" to true, "recalled" to true, "recalledBy" to "bob"))), "truncated" to false),
        )
        h.await(p)
        val x = h.bySeq(G, 1)
        assertTrue(x.deleted && x.recalled)
        assertEquals("bob", x.recalledBy)
        assertEquals("bob撤回了一条消息", h.call { recallText(x) })
        assertFalse(holds(storeOf(h.im), RCL_SECRET))
    }

    @Test
    fun `F-RCL-01 异常（问题 26）：recall 超时抛 TimeoutError，不自动重试；没连接过时抛 NotConnectedError`() {
        val n = setup()
        assertIs<NotConnectedError>(n.failure(n.start { recall(AB, "x") }))
        val h = setup(timings = quiet)
        h.connect()
        val c = h.login("alice", listOf(conv(AB, maxSeq = 1)))
        h.loaded(c, AB, listOf(m(AB, 1, "alice", "原文", ts = h.clock.now())))
        val p = h.start { recall(AB, idOf(AB, 1)) }
        h.clock.advance(60_000)
        assertIs<TimeoutError>(h.failure(p))
        h.clock.advance(60_000)
        assertEquals(1, c.frames("recall").size)
        assertFalse(h.bySeq(AB, 1).deleted)
    }

    // ---- 引用（F-QUO-01、02） ----

    @Test
    fun `TC-QUO-01-01（AC-QUO-01-1）：send 带 quote 上行只发 msgId；本地待发的气泡先有引用条；对方收到 state=normal 和原文摘要`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(AB, maxSeq = 1)))
        val long = "长".repeat(150)
        h.loaded(c, AB, listOf(m(AB, 1, "bob", long)))
        assertIs<SendResult.Ok>(h.call { send(AB, "回复你", quote = QuoteRef(idOf(AB, 1))) })
        assertEquals(obj("text" to "回复你", "quote" to obj("msgId" to idOf(AB, 1))), c.last("send").dataObj["body"])
        // 前 100 个码点（F-QUO-02 规则 2）
        val want = QuoteView(idOf(AB, 1), 1, "bob", MsgType.TEXT, QuoteState.NORMAL, "长".repeat(100))
        assertEquals(want, h.im.messages(AB).last().body.quote)
        c.ack("send", obj("msgId" to "mine-2", "convId" to AB, "seq" to 2, "ts" to 5))
        assertEquals(MessageStatus.SENT, h.bySeq(AB, 2).status)
        assertEquals(want, h.bySeq(AB, 2).body.quote)

        // 对方收到的 msg 带 quote（服务端组装），引用条显示摘要。
        val b = setup()
        b.connect()
        val cb = b.login("bob", listOf(conv(AB, maxSeq = 1)))
        b.loaded(cb, AB, listOf(m(AB, 1, "bob", long)))
        cb.receive(msgFrame(m(AB, 2, "alice", "回复你", body = obj("text" to "回复你", "quote" to quoteOf(AB, 1, "长".repeat(100))))))
        assertEquals(want, b.bySeq(AB, 2).body.quote)
        assertEquals("长".repeat(100), quoteLabel(b.bySeq(AB, 2).body.quote!!))
        // 引用的消息没加载过：待发气泡只有 msgId（state=normal，没有原文），服务端组装后以下行为准。
        assertIs<SendResult.Ok>(b.call { send(AB, "引用很早的", quote = QuoteRef("old-1")) })
        assertEquals(QuoteView("old-1", state = QuoteState.NORMAL), b.im.messages(AB).last().body.quote)
    }

    @Test
    fun `F-QUO-02 规则 2、3：摘要（图片、文件、带引用的只取自身文字）和四种状态的显示文字`() {
        fun msgOf(type: MsgType, body: MsgBody) = Message(clientMsgId = "x", convId = C4_G, from = "bob", type = type, body = body, ts = 0, status = MessageStatus.SENT)
        assertEquals("[图片]", quoteSummary(msgOf(MsgType.IMAGE, MsgBody(image = ImageBody("f")))))
        assertEquals("[文件] a.pdf", quoteSummary(msgOf(MsgType.FILE, MsgBody(file = FileBody("f", "a.pdf")))))
        assertEquals("自己的话", quoteSummary(msgOf(MsgType.TEXT, MsgBody(text = "自己的话", quote = QuoteView("x", state = QuoteState.NORMAL, text = "更早的")))))
        assertEquals("引用内容已被撤回", quoteLabel(QuoteView("x", state = QuoteState.RECALLED)))
        assertEquals("引用内容已被删除", quoteLabel(QuoteView("x", state = QuoteState.DELETED)))
        assertEquals("[图片] 已过期", quoteLabel(QuoteView("x", type = MsgType.IMAGE, state = QuoteState.EXPIRED)))
        assertEquals("[文件] 已过期", quoteLabel(QuoteView("x", type = MsgType.FILE, state = QuoteState.EXPIRED)))
        // 不认识的 state 解码成 UNKNOWN，"引用内容不可用"。
        val q = ImJson.decodeFromString(QuoteView.serializer(), """{"msgId":"x","state":"hidden"}""")
        assertEquals(QuoteState.UNKNOWN, q.state)
        assertEquals("引用内容不可用", quoteLabel(q))
        // 摘要含 HTML 时原样作为纯文本返回，由页面按文本渲染（AC-QUO-02-5）。
        assertEquals("<img src=x onerror=alert(1)>", quoteLabel(QuoteView("x", state = QuoteState.NORMAL, text = "<img src=x onerror=alert(1)>")))
        // toString 不带引用文字（第 13.1 节：引用文字不进日志）。
        assertFalse("ZXQ" in QuoteView("x", state = QuoteState.NORMAL, text = "ZXQ").toString())
    }

    @Test
    fun `TC-QUO-02-02（AC-QUO-02-2）：收到 m1 的 msgDeleted（带 recalled）：已收到的 m2 和待发的 m3 的引用都清除原文，显示"引用内容已被撤回"`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(G, maxSeq = 2)))
        h.loaded(c, G, listOf(m(G, 1, "bob", "$QUO_SECRET 原文"), m(G, 2, "carol", "回复", body = obj("text" to "回复", "quote" to quoteOf(G, 1, "$QUO_SECRET 原文")))))
        c.drop(1006) // 待发：排队中的 m3 也引用 m1
        h.call { send(G, "我也回复", quote = QuoteRef(idOf(G, 1))) }
        assertEquals("$QUO_SECRET 原文", h.im.messages(G).last().body.quote?.text)
        val c2 = h.reconnectAuth()
        c2.receive(recalledEvent(G, 1, "bob"))
        val q2 = h.bySeq(G, 2).body.quote!!
        assertEquals(QuoteView(idOf(G, 1), 1, "bob", MsgType.TEXT, QuoteState.RECALLED), q2)
        assertEquals("引用内容已被撤回", quoteLabel(q2))
        val q3 = h.im.messages(G).last().body.quote!!
        assertEquals(QuoteState.RECALLED, q3.state)
        assertNull(q3.text)
        assertFalse(holds(storeOf(h.im), QUO_SECRET))
    }

    @Test
    fun `F-QUO-02 规则 4：被引用的消息被后台删除（msgDeleted 不带 recalled）：引用 state=deleted，"引用内容已被删除"；之后引用它的待发气泡也是`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(G, maxSeq = 2)))
        h.loaded(c, G, listOf(m(G, 1, "bob", QUO_SECRET), m(G, 2, "carol", "回复", body = obj("text" to "回复", "quote" to quoteOf(G, 1, QUO_SECRET)))))
        c.receive(deletedEvent(G, 1))
        assertEquals("引用内容已被删除", quoteLabel(h.bySeq(G, 2).body.quote!!))
        assertFalse(h.bySeq(G, 1).recalled)
        h.call { send(G, "再引用", quote = QuoteRef(idOf(G, 1))) }
        assertEquals(QuoteState.DELETED, h.im.messages(G).last().body.quote!!.state)
        assertFalse(holds(storeOf(h.im), QUO_SECRET))
    }

    @Test
    fun `发送带引用失败 4040（reason=quoteUnavailable）：标记失败，带 errorReason，不自动重发；日志里没有引用文字和 @ 的内容`() {
        val h = setup(timings = quiet)
        h.connect()
        val c = h.login("alice", listOf(conv(G, maxSeq = 1)))
        h.loaded(c, G, listOf(m(G, 1, "bob", "$QUO_SECRET 原文")))
        h.call { send(G, "@小王 ZXQ-MEN-NAME", quote = QuoteRef(idOf(G, 1)), mentions = Mentions(listOf("wang"))) }
        c.ackErr("send", 4040, reason = "quoteUnavailable")
        val x = h.im.messages(G).last()
        assertEquals(MessageStatus.FAILED, x.status)
        assertEquals(SendError.Code(4040), x.error)
        assertEquals("quoteUnavailable", x.errorReason)
        h.clock.advance(30_000)
        assertEquals(1, c.frames("send").size)
        val logs = h.logs.joinToString("\n")
        assertFalse(QUO_SECRET in logs)
        assertFalse("ZXQ-MEN-NAME" in logs)
        assertFalse("小王" in logs)
    }

    // ---- 提及（F-MEN-01～03，FE-662） ----

    @Test
    fun `TC-MEN-01-01（AC-MEN-01-1）：u1 @ u2：上行带 mentions（去重）；u2 收到后会话 mentionUnread 加 1、firstMentionSeq；u3 不变`() {
        val u1 = setup()
        u1.connect()
        val c1 = u1.login("u1", listOf(conv(G, maxSeq = 4, mentionUnread = 0)))
        u1.call { send(G, "@小王 看一下", mentions = Mentions(listOf("u2", "u2", ""))) }
        assertEquals(obj("text" to "@小王 看一下", "mentions" to obj("userIds" to listOf("u2"))), c1.last("send").dataObj["body"])
        c1.ack("send", obj("msgId" to "g1-5", "convId" to G, "seq" to 5, "ts" to 5))
        assertEquals(0L, u1.convOf(G).mentionUnread) // 自己发的不算
        // 自己另一端发的 @ 所有人也不算（F-MEN-03 规则 6）。
        c1.receive(msgFrame(m(G, 6, "u1", "@所有人", body = obj("text" to "@所有人", "mentions" to obj("all" to true)))))
        assertEquals(0L, u1.convOf(G).mentionUnread)

        val incoming = m(G, 5, "u1", "@小王 看一下", body = obj("text" to "@小王 看一下", "mentions" to obj("userIds" to listOf("u2"))))
        for ((self, n, first) in listOf(Triple("u2", 1L, 5L), Triple("u3", 0L, null))) {
            val h = setup()
            h.connect()
            val c = h.login(self, listOf(conv(G, maxSeq = 4, readSeq = 4, mentionUnread = 0)))
            c.receive(msgFrame(incoming))
            assertEquals(Mentions(listOf("u2")), h.im.messages(G).last().body.mentions)
            assertEquals(n, h.convOf(G).mentionUnread, self)
            assertEquals(first, h.convOf(G).firstMentionSeq, self)
        }

        // @ 所有人也算；在这个会话里读到最新后清零（跳转按钮消失）。
        val h = setup()
        h.connect()
        val c = h.login("u3", listOf(conv(G, maxSeq = 4, readSeq = 4, mentionUnread = 0)))
        c.receive(msgFrame(m(G, 6, "o", "@所有人", body = obj("text" to "@所有人", "mentions" to obj("all" to true)))))
        assertEquals(1L, h.convOf(G).mentionUnread)
        assertEquals(6L, h.convOf(G).firstMentionSeq)
        h.im.setActiveConversation(G)
        h.flush()
        assertEquals(obj("convId" to G, "seq" to 6), c.last("read").dataObj)
        assertEquals(0L, h.convOf(G).mentionUnread)
        assertNull(h.convOf(G).firstMentionSeq)
        // 服务端没给过计数的会话（开关关闭或老服务端）不推算。
        val old = setup()
        old.connect()
        val co = old.login("u2", listOf(conv(G, maxSeq = 4, readSeq = 4)))
        co.receive(msgFrame(incoming))
        assertNull(old.convOf(G).mentionUnread)
    }

    @Test
    fun `F-MEN-03 规则 1：已读位置之前的 @（迟到、补拉到的旧消息）不计数`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(G, maxSeq = 10, readSeq = 10, mentionUnread = 0)))
        h.loaded(c, G, listOf(m(G, 10)))
        c.receive(msgFrame(m(G, 8, "bob", "@alice 早的", body = obj("text" to "@alice 早的", "mentions" to obj("userIds" to listOf("alice"))))))
        assertEquals(0L, h.convOf(G).mentionUnread)
        c.receive(msgFrame(m(G, 11, "bob", "@alice 新的", body = obj("text" to "@alice 新的", "mentions" to obj("userIds" to listOf("alice"))))))
        assertEquals(1L, h.convOf(G).mentionUnread)
        assertEquals(11L, h.convOf(G).firstMentionSeq)
    }

    @Test
    fun `F-MEN-01 规则 1：单聊带 mentions 在入队前抛错；mentions 全空时不带`() {
        val h = setup()
        h.connect()
        val c = h.login("alice")
        val e = h.failure(h.start { send(AB, "@bob", mentions = Mentions(listOf("bob"))) })
        assertIs<IllegalArgumentException>(e)
        assertTrue("group" in e.message!!)
        assertEquals(emptyList(), h.im.messages(AB))
        h.call { send(G, "纯文本", mentions = Mentions(emptyList(), all = false)) }
        assertEquals(obj("text" to "纯文本"), c.last("send").dataObj["body"])
    }

    @Test
    fun `TC-MEN-02-05（AC-MEN-02-5）：@ 所有人返回 4293：消息失败、提示"@所有人次数已用完"，断线重连后不自动重发；4131 同样`() {
        val h = setup(timings = quiet)
        h.connect()
        val c = h.login("o", listOf(conv(G, maxSeq = 1, myRole = "owner")))
        h.call { send(G, "@所有人 开会", mentions = Mentions(all = true)) }
        assertEquals(obj("text" to "@所有人 开会", "mentions" to obj("all" to true)), c.last("send").dataObj["body"])
        c.ackErr("send", 4293, obj("retryAfterSeconds" to 600))
        val mine = h.im.messages(G).last()
        assertEquals(MessageStatus.FAILED, mine.status)
        assertEquals(SendError.Code(ErrorCode.MENTION_ALL_LIMITED), mine.error)
        assertEquals(listOf<SendError>(SendError.Code(4293)), h.failed().map { it.error })
        assertEquals("@所有人次数已用完", messageFor("send", (mine.error as SendError.Code).code))
        // 4131（@ 的人数超过上限，开发期缺陷）同样标记失败、不自动重发。
        h.call { send(G, "@很多人", mentions = Mentions((1..21).map { "u$it" })) }
        c.ackErr("send", ErrorCode.BATCH_LIMIT_EXCEEDED)
        assertEquals(SendError.Code(4131), h.im.messages(G).last().error)
        c.drop(1006)
        val c2 = h.reconnectAuth("o")
        c2.ack("convs", obj("convs" to listOf(conv(G, maxSeq = 1, myRole = "owner")), "truncated" to false))
        h.clock.advance(60_000)
        assertEquals(0, c2.frames("send").size)
        assertEquals(listOf("cid-0001:failed(4293)", "cid-0002:failed(4131)"), h.status(G))
    }

    @Test
    fun `TC-MEN-03-07（AC-MEN-03-7）补充：没有提及（mentionUnread=0 或服务端没给）的会话收到 readUpdated、msgDeleted 不拉 convs；断线时不拉`() {
        val h = setup(timings = quiet)
        h.connect()
        val c = h.login("alice", listOf(conv(G, maxSeq = 9, mentionUnread = 0), conv("group:g2", maxSeq = 9), conv("group:g3", maxSeq = 9, mentionUnread = 1, firstMentionSeq = 9)))
        fun convs() = c.frames("convs").size
        val before = convs()
        c.receive(event("readUpdated", "convId" to G, "readSeq" to 9))
        c.receive(event("msgDeleted", "convId" to "group:g2", "msgIds" to listOf("x"), "seqs" to listOf(3)))
        h.clock.advance(10_000)
        assertEquals(before, convs())
        // 有提及：窗口内的第二个事件推迟到窗口结束；那时断线了就不拉（重连的 convs 带最新计数）。
        c.receive(event("readUpdated", "convId" to "group:g3", "readSeq" to 9))
        assertEquals(before + 1, convs())
        c.receive(event("readUpdated", "convId" to "group:g3", "readSeq" to 9))
        c.drop(1006)
        h.clock.advance(5_000)
        assertEquals(before + 1, convs())
    }

    @Test
    fun `TC-MEN-03-07（AC-MEN-03-7）：重拉 convs 用整份结果更新会话，提及数以服务端为准（问题 23）；退出登录清掉节流定时器`() {
        val h = setup(timings = quiet)
        h.connect()
        val c = h.login("alice", listOf(conv(G, maxSeq = 9, mentionUnread = 1, firstMentionSeq = 5)))
        c.receive(recalledEvent(G, 5, "bob"))
        assertEquals(2, c.frames("convs").size)
        c.ack("convs", obj("convs" to listOf(conv(G, name = "新群名", maxSeq = 9, mentionUnread = 0)), "truncated" to false))
        assertEquals(0L, h.convOf(G).mentionUnread)
        assertNull(h.convOf(G).firstMentionSeq)
        assertEquals("新群名", h.convOf(G).name)
        // 新的 @ 我（本地 +1），窗口内的 readUpdated 推迟到窗口结束；这时退出登录再登录，旧的定时器不能在新会话里拉 convs。
        c.receive(msgFrame(m(G, 10, "bob", "@alice", body = obj("text" to "@alice", "mentions" to obj("userIds" to listOf("alice"))))))
        assertEquals(1L, h.convOf(G).mentionUnread)
        assertEquals(10L, h.convOf(G).firstMentionSeq)
        c.receive(event("readUpdated", "convId" to G, "readSeq" to 10))
        assertEquals(2, c.frames("convs").size)
        h.call { disconnect() }
        h.connect()
        val c2 = h.login("alice", listOf(conv(G, maxSeq = 10, mentionUnread = 1, firstMentionSeq = 10)))
        h.clock.advance(10_000)
        assertEquals(1, c2.frames("convs").size)
        // 新会话里第一次事件立即拉（节流窗口也重置了）。
        c2.receive(event("readUpdated", "convId" to G, "readSeq" to 10))
        assertEquals(2, c2.frames("convs").size)
    }

    @Test
    fun `F-MEN-01 规则 6：searchGroupMembers 带 keyword（空串不带），资料进缓存；4290 抛给页面、不自动重试`() {
        val h = setup(timings = quiet)
        h.connect()
        val c = h.login("alice")
        val p = h.start { searchGroupMembers("g1", "zh") }
        assertEquals(obj("groupId" to "g1", "keyword" to "zh"), c.last("groupMembers").dataObj)
        c.ack("groupMembers", obj("members" to listOf(obj("userId" to "zhang", "nickname" to "张三", "role" to "member", "joinedAt" to 1, "avatar" to "", "official" to false)), "nextCursor" to ""))
        assertEquals(listOf("zhang"), h.await(p).members.map { it.userId })
        assertEquals("张三", h.call { displayName("zhang") })
        val p2 = h.start { searchGroupMembers("g1", "", "cur") }
        assertEquals(obj("groupId" to "g1", "cursor" to "cur"), c.last("groupMembers").dataObj)
        c.ack("groupMembers", obj("members" to emptyList<JsonObject>(), "nextCursor" to ""))
        h.await(p2)
        val p3 = h.start { searchGroupMembers("g1", "z") }
        c.ackErr("groupMembers", ErrorCode.RATE_LIMITED)
        assertEquals(4290, assertIs<IMError>(h.failure(p3)).code)
        h.clock.advance(5_000)
        assertEquals(3, c.frames("groupMembers").size)
    }

    // ---- 帧（AC-CHAT-90-10） ----

    @Test
    fun `TC-CHAT-90-32 SDK 发出的 C4 帧符合 Schema：recall、send 带 quote 和 mentions、groupMembers 带 keyword`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv(G, maxSeq = 3, myRole = "owner")))
        h.loaded(c, G, listOf(m(G, 3, "bob", "被引用的原文")))
        h.call { send(G, "@bob 看这条", quote = QuoteRef(idOf(G, 3)), mentions = Mentions(listOf("bob", "bob"))) }
        h.call { send(G, "@所有人 开会", mentions = Mentions(all = true)) }
        h.call { send(AB, "单聊引用", quote = QuoteRef("m1")) }
        h.start { recall(G, idOf(G, 3)) }
        h.start { searchGroupMembers("g1", "zh") }
        assertTrue(c.frames().map { it["cmd"]!!.str }.containsAll(listOf("recall", "send", "groupMembers")))
        for (f in c.frames()) assertEquals(emptyList(), FrameSchema.errors(f.toString()), f["cmd"]!!.str)
        assertEquals(obj("groupId" to "g1", "keyword" to "zh"), c.last("groupMembers").dataObj)
        assertEquals(obj("text" to "@bob 看这条", "quote" to obj("msgId" to idOf(G, 3)), "mentions" to obj("userIds" to listOf("bob"))), c.frames("send")[0].dataObj["body"])
    }

    @Test
    fun `TC-CHAT-90-32 frames json 的 C4 下行帧能用 SDK 的模型解码出新字段`() {
        val valid = vector("frames.json").jsonObjectOf("valid")
        fun frame(name: String) = valid.first { it["name"]!!.str == name }["frame"]!! as JsonObject
        val q = ImJson.decodeFromJsonElement(ServerMessage.serializer(), frame("msg quote state=normal（C4）").dataObj).body!!.quote!!
        assertEquals(QuoteState.NORMAL, q.state)
        val gone = ImJson.decodeFromJsonElement(ServerMessage.serializer(), frame("msg quote 原消息已不存在（C4）").dataObj).body!!.quote!!
        assertEquals(QuoteView("m3", state = QuoteState.DELETED), gone)
        val men = ImJson.decodeFromJsonElement(ServerMessage.serializer(), frame("msg 带 mentions.all（C4）").dataObj).body!!.mentions!!
        assertTrue(men.all)
        val sync = ImJson.decodeFromJsonElement(SyncAck.serializer(), frame("ack sync 含撤回的消息（C4）").dataObj)
        assertEquals(listOf(false, true, true), sync.messages.map { it.recalled })
        assertEquals("u1", sync.messages[1].recalledBy)
        val convs = ImJson.decodeFromJsonElement(ConvsAck.serializer(), frame("ack convs lastMsg 撤回、mentionUnread（C4）").dataObj)
        assertEquals(listOf(2L, 0L), convs.convs.map { it.mentionUnread })
        assertEquals(listOf(5L, null), convs.convs.map { it.firstMentionSeq })
        assertTrue(convs.convs[0].lastMsg!!.recalled)
        assertEquals("o", convs.convs[0].lastMsg!!.recalledBy)
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOf(key: String): List<JsonObject> =
    (this as JsonObject)[key]!!.let { it as kotlinx.serialization.json.JsonArray }.map { it as JsonObject }
