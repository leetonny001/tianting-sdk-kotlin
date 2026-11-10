// FE-681（C5）：会话置顶、免打扰、删除、多端同步、总未读（PRD-chat 第 9 节 F-CVS，第 13.1 节）。语义同 sdk-ts 的 test/conv-settings.test.ts。
// 真实的 IMClient + FakeTransport + 假时钟。测试下发的帧按 frame.schema.json 校验（down），SDK 发出的帧由 SdkTest 校验。
// 本地库、缓存目录、冷启动、升级、"待清除"的用例在 ConvSettingsPersistenceTest。共享用例 chat-sync.json 的 C5 部分等 BE-487。本地单测结果，CI 不运行。
package com.tianting.sdk

import com.tianting.sdk.testing.FakeConnection
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal const val CVS_SECRET = "ZXQ-CVS-1"

/** 符合 Schema 的会话（群带 memberCount、myRole；都带 modVer）。pinnedAt 不为 null 时 pinned=true。 */
internal fun sc(
    convId: String,
    maxSeq: Long = 0,
    readSeq: Long = 0,
    minSeq: Long = 1,
    unread: Long = 0,
    updatedAt: Long = 0,
    name: String = convId,
    lastMsg: JsonObject? = null,
    pinnedAt: Long? = null,
    notifyOff: Boolean = false,
    mentionUnread: Long? = null,
    firstMentionSeq: Long? = null,
): JsonObject {
    val group = convId.startsWith("group:")
    val base = conv(
        convId, name = name, maxSeq = maxSeq, readSeq = readSeq, minSeq = minSeq, unread = unread, updatedAt = updatedAt, lastMsg = lastMsg,
        memberCount = if (group) 3 else null, myRole = if (group) "member" else null, modVer = 0,
        mentionUnread = mentionUnread, firstMentionSeq = firstMentionSeq,
    )
    val extra = LinkedHashMap<String, JsonPrimitive>()
    if (pinnedAt != null) {
        extra["pinned"] = JsonPrimitive(true)
        extra["pinnedAt"] = JsonPrimitive(pinnedAt)
    }
    if (notifyOff) extra["notifyOff"] = JsonPrimitive(true)
    return JsonObject(base + extra)
}

/** 服务端下发：先按 Schema 校验。 */
internal fun down(c: FakeConnection, frame: JsonObject) {
    assertEquals(emptyList(), FrameSchema.errors(frame.toString()), frame.toString())
    c.receive(frame)
}

internal fun ackOf(c: FakeConnection, cmd: String, data: JsonObject) = obj("id" to c.last(cmd)["id"]!!.str, "cmd" to "ack", "data" to data)

internal fun ackErrOf(c: FakeConnection, cmd: String, code: Int) = obj("id" to c.last(cmd)["id"]!!.str, "cmd" to "ack", "error" to obj("code" to code, "msg" to "x"))

internal fun downEvent(c: FakeConnection, type: String, vararg pairs: Pair<String, Any?>) = down(c, event(type, *pairs))

internal fun Harness.ids(): List<String> = im.conversations.value.map { it.convId }

internal fun Harness.find(convId: String): Conversation? = im.conversations.value.firstOrNull { it.convId == convId }

internal fun Harness.convDeleted(): List<String> = events.filterIsInstance<IMEvent.ConvDeleted>().map { it.convId }

private val quiet = IMClientTimings(heartbeatMs = 10 * 365 * 24 * 3_600_000L) // 推进时钟时不因心跳断线

class ConvSettingsTest : SdkTest() {
    private val AB = "c2c:alice:bob"
    private val AC = "c2c:alice:carol"
    private val G1 = "group:g1"
    private val G2 = "group:g2"

    private fun online(vararg convs: JsonObject): Pair<Harness, FakeConnection> {
        val h = setup(timings = quiet)
        h.connect()
        val c = h.transport.current
        c.open()
        c.ack("auth", obj("userId" to "alice", "serverTime" to h.clock.now()))
        down(c, ackOf(c, "convs", obj("convs" to convs.toList(), "truncated" to false)))
        return h to c
    }

    /** 断线后重连，convs 用给定的应答。 */
    private fun reconnect(h: Harness, c: FakeConnection, convsAck: JsonObject): FakeConnection {
        c.drop()
        h.clock.advance(60_000)
        val n = h.transport.current
        n.open()
        n.ack("auth", obj("userId" to "alice", "serverTime" to h.clock.now()))
        down(n, ackOf(n, "convs", convsAck))
        return n
    }

    private fun clean(h: Harness, needle: String) {
        assertFalse(holds(storeOf(h.im), needle), "内存里还有 $needle")
        assertFalse(h.call { debugExport() }.toString().contains(needle))
    }

    // ---- F-CVS-01 置顶 ----

    @Test
    fun `TC-CVS-01-01（F-CVS-01 规则 3）：置顶的在最前按 pinnedAt 倒序，其余 updatedAt 倒序、同时间 convId 升序；引用稳定`() {
        val (h, c) = online(
            sc(AB, updatedAt = 300),
            sc(AC, updatedAt = 100),
            sc(G1, updatedAt = 50, pinnedAt = 10),
            sc(G2, updatedAt = 1, pinnedAt = 20),
            sc("c2c:alice:dave", updatedAt = 100),
        )
        assertEquals(listOf(G2, G1, AB, AC, "c2c:alice:dave"), h.ids())
        val a = h.im.conversations.value
        down(c, msgFrame(m(AB, 1, ts = 200))) // 不改变顺序的更新（updatedAt 不变大）
        assertSame(a[0], h.im.conversations.value[0]) // 没有变化的会话是同一个对象（G-UI-19）
    }

    @Test
    fun `TC-CVS-01-01（第 13-1 节）：setConvPinned 发 convSet {convId, pinned}，ack 前本地已移到最前；ack 的 pinnedAt 覆盖本地`() {
        val (h, c) = online(sc(AB, updatedAt = 300), sc(G1, updatedAt = 100))
        val d = h.start { setConvPinned(G1, true) }
        assertEquals(obj("convId" to G1, "pinned" to true), c.last("convSet").dataObj)
        assertEquals(listOf(G1, AB), h.ids()) // 乐观更新
        assertEquals(true, h.find(G1)!!.pinned)
        assertEquals(h.im.serverNow(), h.find(G1)!!.pinnedAt)
        down(c, ackOf(c, "convSet", obj("conv" to sc(G1, updatedAt = 100, pinnedAt = 777))))
        h.await(d)
        assertEquals(777L, h.find(G1)!!.pinnedAt)

        val q = h.start { setConvPinned(G1, false) }
        assertEquals(obj("convId" to G1, "pinned" to false), c.last("convSet").dataObj)
        assertEquals(listOf(AB, G1), h.ids())
        assertFalse(h.find(G1)!!.pinned)
        assertNull(h.find(G1)!!.pinnedAt)
        down(c, ackOf(c, "convSet", obj("conv" to sc(G1, updatedAt = 100))))
        h.await(q)
        assertEquals(listOf(AB, G1), h.ids())
    }

    @Test
    fun `置顶时间相同：再按 updatedAt 倒序、convId 升序（同 Go 参考实现 chatmodel-Conversations）`() {
        val (h, _) = online(sc(G1, updatedAt = 1, pinnedAt = 5), sc(G2, updatedAt = 9, pinnedAt = 5), sc("group:g3", updatedAt = 9, pinnedAt = 5), sc(AB, updatedAt = 99))
        assertEquals(listOf(G2, "group:g3", G1, AB), h.ids())
    }

    @Test
    fun `已置顶的再置顶：本地 pinnedAt 不变（服务端同样不变，AC-CVS-01-2）`() {
        val (h, c) = online(sc(G1, pinnedAt = 55))
        h.clock.advance(10_000)
        val d = h.start { setConvPinned(G1, true) }
        assertEquals(55L, h.find(G1)!!.pinnedAt)
        down(c, ackOf(c, "convSet", obj("conv" to sc(G1, pinnedAt = 55))))
        h.await(d)
        assertEquals(55L, h.find(G1)!!.pinnedAt)
    }

    @Test
    fun `第 56 条：convSet 失败 5000、4220、4290、4030：回滚乐观显示，IMError 交给页面，不自动重试`() {
        for (code in listOf(5000, ErrorCode.PIN_LIMIT_EXCEEDED, ErrorCode.RATE_LIMITED, 4030)) {
            val (h, c) = online(sc(AB, updatedAt = 300), sc(G1, updatedAt = 100))
            val before = h.im.conversations.value
            val d = h.start { setConvPinned(G1, true) }
            assertEquals(listOf(G1, AB), h.ids())
            down(c, ackErrOf(c, "convSet", code))
            assertEquals(code, assertIs<IMError>(h.failure(d)).code)
            assertEquals(listOf(AB, G1), h.ids(), "$code")
            assertFalse(h.find(G1)!!.pinned)
            assertEquals(before, h.im.conversations.value)
            h.clock.advance(60_000) // 4290 在 errors.json 是可重试：也不自动重试
            assertEquals(1, c.frames("convSet").size)
        }
    }

    @Test
    fun `第 56 条：超时、未连接也回滚；免打扰同样`() {
        val (h, c) = online(sc(AB, maxSeq = 3, unread = 3))
        val d = h.start { setConvNotifyOff(AB, true) }
        assertTrue(h.find(AB)!!.notifyOff)
        assertEquals(0L, h.im.totalUnread.value)
        h.clock.advance(11_000)
        assertIs<TimeoutError>(h.failure(d))
        assertFalse(h.find(AB)!!.notifyOff)
        assertEquals(3L, h.im.totalUnread.value)
        c.drop()
        val e = h.start { setConvNotifyOff(AB, true) }
        assertIs<NotConnectedError>(h.failure(e))
        assertFalse(h.find(AB)!!.notifyOff)
    }

    @Test
    fun `等 ack 期间其他端的 convSettingsChanged 改了同一会话：失败时不回滚成旧值（后到的覆盖，F-CVS-04 异常）`() {
        val (h, c) = online(sc(G1))
        val d = h.start { setConvPinned(G1, true) }
        downEvent(c, "convSettingsChanged", "convId" to G1, "pinned" to true, "pinnedAt" to 900, "notifyOff" to true)
        down(c, ackErrOf(c, "convSet", 5000))
        assertIs<IMError>(h.failure(d))
        val g = h.find(G1)!!
        assertEquals(Triple(true, 900L, true), Triple(g.pinned, g.pinnedAt, g.notifyOff))
    }

    // ---- F-CVS-02 免打扰和总未读（规则 4） ----

    @Test
    fun `TC-CVS-02-02（AC-CVS-02-2）：普通未读 2、3，免打扰未读 5：totalUnread=5；取消免打扰后 10；免打扰会话来了新消息总未读不变`() {
        val (h, c) = online(
            sc(AB, maxSeq = 2, unread = 2),
            sc(AC, maxSeq = 3, unread = 3),
            sc(G1, maxSeq = 5, unread = 5, notifyOff = true, mentionUnread = 0),
        )
        assertEquals(5L, h.im.totalUnread.value)
        assertEquals(true to 5L, h.find(G1)!!.let { it.notifyOff to it.unread }) // 未读照常计算（规则 3），列表显示小红点
        val d = h.start { setConvNotifyOff(G1, false) }
        assertEquals(obj("convId" to G1, "notifyOff" to false), c.last("convSet").dataObj)
        assertEquals(10L, h.im.totalUnread.value)
        down(c, ackOf(c, "convSet", obj("conv" to sc(G1, maxSeq = 5, unread = 5, mentionUnread = 0))))
        h.await(d)
        val q = h.start { setConvNotifyOff(G1, true) }
        down(c, ackOf(c, "convSet", obj("conv" to sc(G1, maxSeq = 5, unread = 5, mentionUnread = 0, notifyOff = true))))
        h.await(q)
        assertEquals(5L, h.im.totalUnread.value)
        down(c, msgFrame(m(G1, 6)))
        assertEquals(6L, h.find(G1)!!.unread)
        assertEquals(5L, h.im.totalUnread.value) // 规则 6：列表更新，不计入
    }

    @Test
    fun `TC-CVS-02-05（AC-CVS-02-5）：免打扰未读 5 其中 1 条 @ 我、普通未读 2：totalUnread=3；读到最新之后 2`() {
        val (h, c) = online(
            sc(AB, maxSeq = 2, unread = 2),
            sc(G1, maxSeq = 5, unread = 5, notifyOff = true, mentionUnread = 1, firstMentionSeq = 3),
        )
        assertEquals(3L, h.im.totalUnread.value)
        h.loaded(c, G1, (1L..5L).map { m(G1, it) })
        h.im.setActiveConversation(G1) // 本端读到最新
        h.flush()
        assertEquals(obj("convId" to G1, "seq" to 5), c.last("read").dataObj)
        assertEquals(2L, h.im.totalUnread.value)
    }

    @Test
    fun `免打扰会话实时收到 @ 我：总未读 +1（@ 我计入，R-CF-20）`() {
        val (h, c) = online(sc(G1, maxSeq = 1, readSeq = 1, notifyOff = true, mentionUnread = 0))
        down(c, msgFrame(m(G1, 2, text = "@alice", body = obj("text" to "@alice", "mentions" to obj("userIds" to listOf("alice"))))))
        down(c, msgFrame(m(G1, 3)))
        assertEquals(2L to 1L, h.find(G1)!!.let { it.unread to it.mentionUnread })
        assertEquals(1L, h.im.totalUnread.value)
    }

    // ---- F-CVS-03 删除会话 ----

    @Test
    fun `第 13-1 节：deleteConv 发 convDelete {convId, upToSeq = 本地看到的最大 seq}；ack 前先从列表消失；成功后清除并发 ConvDeleted`() {
        val (h, c) = online(sc(AB, maxSeq = 8, readSeq = 8, updatedAt = 9, pinnedAt = 1), sc(AC, updatedAt = 1))
        h.loaded(c, AB, listOf(m(AB, 7, text = "$CVS_SECRET 第 7 条"), m(AB, 8, text = "$CVS_SECRET 第 8 条")))
        down(c, msgFrame(m(AB, 10, text = "$CVS_SECRET 第 10 条"))) // 本地看到的比 convs 的 maxSeq 新
        val d = h.start { deleteConv(AB) }
        assertEquals(obj("convId" to AB, "upToSeq" to 10), c.last("convDelete").dataObj)
        assertEquals(listOf(AC), h.ids())
        assertEquals(emptyList(), h.convDeleted()) // 成功之前不让页面清草稿
        down(c, ackOf(c, "convDelete", obj("clearedSeq" to 10)))
        h.await(d)
        assertEquals(listOf(AC), h.ids())
        assertEquals(listOf(AB), h.convDeleted())
        assertEquals(emptyList(), h.im.messages(AB))
        assertNull(h.call { conversationSnapshot(AB) }) // 不是"你已不在该群"
        assertEquals(emptyList(), h.removed())
        clean(h, CVS_SECRET)
    }

    @Test
    fun `F-CVS-03 异常、第 56 条：convDelete 失败 5000、4290、4030：会话恢复显示、消息不动，IMError 交给页面，不自动重试`() {
        for (code in listOf(5000, ErrorCode.RATE_LIMITED, 4030)) {
            val (h, c) = online(sc(AB, maxSeq = 2, updatedAt = 9), sc(AC, updatedAt = 1))
            h.loaded(c, AB, listOf(m(AB, 1), m(AB, 2)))
            h.call { send(AB, "失败的") }
            h.clock.advance(11_000)
            val d = h.start { deleteConv(AB) }
            assertEquals(listOf(AC), h.ids())
            down(c, ackErrOf(c, "convDelete", code))
            assertEquals(code, assertIs<IMError>(h.failure(d)).code)
            assertEquals(listOf(AB, AC), h.ids())
            assertEquals(listOf("bob-$AB-1:sent#1", "bob-$AB-2:sent#2", "cid-0001:failed(timeout)"), h.status(AB))
            assertEquals(emptyList(), h.convDeleted())
            h.clock.advance(60_000)
            assertEquals(1, c.frames("convDelete").size)
        }
    }

    @Test
    fun `upToSeq 取本地看到的最大 seq：之后的 convs 是旧的（maxSeq 比本地小）也按本地的`() {
        val (h, c) = online(sc(AB, maxSeq = 5, readSeq = 5, updatedAt = 1))
        h.loaded(c, AB, listOf(m(AB, 4), m(AB, 5)))
        h.clock.advance(31_000)
        val r = h.start { refreshConversations() }
        down(c, ackOf(c, "convs", obj("convs" to listOf(sc(AB, maxSeq = 3, readSeq = 3, updatedAt = 1)), "truncated" to false)))
        h.await(r)
        assertEquals(3L, h.find(AB)!!.maxSeq)
        h.start { deleteConv(AB) }
        assertEquals(obj("convId" to AB, "upToSeq" to 5), c.last("convDelete").dataObj)
    }

    @Test
    fun `TC-CVS-03-02（AC-CVS-03-2）：删除后对方再发 1 条：重新出现，未读 1，只有这 1 条，minSeq=11，翻页不发请求`() {
        val (h, c) = online(sc(AB, name = "Bob", maxSeq = 10, readSeq = 10, updatedAt = 10, notifyOff = true))
        h.loaded(c, AB, listOf(m(AB, 9, text = "$CVS_SECRET 9"), m(AB, 10, text = "$CVS_SECRET 10")))
        val d = h.start { deleteConv(AB) }
        down(c, ackOf(c, "convDelete", obj("clearedSeq" to 10)))
        h.await(d)
        assertEquals(emptyList(), h.ids())
        down(c, msgFrame(m(AB, 11, text = "新的一条")))
        val x = h.find(AB)!!
        assertEquals(listOf("Bob", 1L, 11L, 10L, 11L, true, false), listOf(x.name, x.unread, x.minSeq, x.readSeq, x.maxSeq, x.notifyOff, x.pinned))
        assertEquals(listOf(11L), h.im.messages(AB).map { it.seq })
        // 翻页：minSeq 之前没有了，不发请求。
        val n = c.frames().size
        assertFalse(h.call { loadHistory(AB) })
        assertEquals(n, c.frames().size)
        // 重新出现的是本地临时恢复的条目：稍后拉一次 convs，以服务端为准。
        h.clock.advance(300)
        down(c, ackOf(c, "convs", obj("convs" to listOf(sc(AB, name = "Bob", maxSeq = 11, readSeq = 10, unread = 1, minSeq = 11, notifyOff = true)), "truncated" to false)))
        assertEquals(1L to 11L, h.find(AB)!!.let { it.unread to it.minSeq })
        clean(h, CVS_SECRET)
    }

    @Test
    fun `清除之后迟到的 sync 应答（删除前发出的）带着 seq ≤ clearedSeq 的消息：不写进来`() {
        val (h, c) = online(sc(AB, maxSeq = 30, readSeq = 30, updatedAt = 1))
        h.start { loadHistory(AB) } // 请求发出，应答迟到
        val d = h.start { deleteConv(AB) }
        c.ack("convDelete", obj("clearedSeq" to 30))
        h.await(d)
        c.ack("sync", obj("messages" to listOf(m(AB, 29, text = "ZXQ-CVS-3")), "hasMore" to true))
        assertEquals(emptyList(), h.im.messages(AB))
        assertEquals(emptyList(), h.ids())
        clean(h, "ZXQ-CVS-3")
    }

    @Test
    fun `F-CVS-03 规则 3、6 ②：被清除的图片、文件经 FilesExpired 让页面丢掉缓存；不是撤回`() {
        val (h, c) = online(sc(AB, maxSeq = 2, readSeq = 2, updatedAt = 1))
        val pic = JsonObject(m(AB, 1, body = obj("image" to obj("fileId" to "F9", "width" to 1, "height" to 1, "size" to 1, "mime" to "image/png"))) + mapOf("type" to JsonPrimitive("image")))
        h.loaded(c, AB, listOf(pic, m(AB, 2)))
        val d = h.start { deleteConv(AB) }
        down(c, ackOf(c, "convDelete", obj("clearedSeq" to 2)))
        h.await(d)
        assertEquals(listOf(listOf("F9")), h.events.filterIsInstance<IMEvent.FilesExpired>().map { it.fileIds })
    }

    @Test
    fun `删除到 clearedSeq 小于本地最新（upToSeq 之后到了新消息，规则 5）：会话不消失，只清除 ≤ clearedSeq，未读只算之后的，不再置顶`() {
        val (h, c) = online(sc(AB, maxSeq = 3, readSeq = 3, updatedAt = 1, pinnedAt = 5))
        h.loaded(c, AB, listOf(m(AB, 1), m(AB, 2), m(AB, 3)))
        val d = h.start { deleteConv(AB) }
        down(c, msgFrame(m(AB, 4)))
        down(c, ackOf(c, "convDelete", obj("clearedSeq" to 3)))
        h.await(d)
        assertEquals(listOf(AB), h.ids())
        assertEquals(listOf(4L, 1L, false), h.find(AB)!!.let { listOf(it.minSeq, it.unread, it.pinned) })
        assertEquals(listOf(4L), h.im.messages(AB).map { it.seq })
    }

    @Test
    fun `TC-CVS-03-05（AC-CVS-03-5）：D2 离线时 D1 删除 g1：重连 convs 里 g1 在 deleted，从列表消失，没有 ConvRemoved、没有快照，本地消息清除；进入 g1 能发言`() {
        val (h, c) = online(sc(G1, maxSeq = 12, readSeq = 12, updatedAt = 12), sc(G2, maxSeq = 1, readSeq = 1, updatedAt = 1))
        h.loaded(c, G1, listOf(m(G1, 11, text = CVS_SECRET), m(G1, 12)))
        val n = reconnect(h, c, obj("convs" to listOf(sc(G2, maxSeq = 1, readSeq = 1, updatedAt = 1)), "truncated" to false, "deleted" to listOf(obj("convId" to G1, "clearedSeq" to 12))))
        assertEquals(listOf(G2), h.ids())
        assertEquals(emptyList(), h.removed())
        assertNull(h.call { conversationSnapshot(G1) })
        assertEquals(emptyList(), h.im.messages(G1))
        clean(h, CVS_SECRET)
        // 从通讯录进入：空的聊天页，可以发送；发出去的这条让会话重新出现。
        assertIs<SendResult.Ok>(h.call { send(G1, "我还在群里") })
        n.ack("send", obj("msgId" to "g1-13", "convId" to G1, "seq" to 13, "ts" to 5000))
        assertEquals(listOf("cid-0001:sent#13"), h.status(G1))
        assertEquals(listOf(G1, G2), h.ids())
    }

    @Test
    fun `TC-CVS-03-05 对照：g2 既不在 convs 也不在 deleted，照常推断「你已不在该群」；truncated 或 deletedTruncated 时都不推断（第 54 条）`() {
        for ((flags, want) in listOf(
            mapOf("truncated" to false) to listOf(G2),
            mapOf("truncated" to true) to emptyList(),
            mapOf("truncated" to false, "deletedTruncated" to true) to emptyList(),
        )) {
            val (h, c) = online(sc(G1, updatedAt = 2), sc(G2, updatedAt = 1))
            reconnect(h, c, js(mapOf("convs" to emptyList<Any>(), "deleted" to listOf(obj("convId" to G1, "clearedSeq" to 0))) + flags).jsonObject)
            assertEquals(want, h.removed().map { it.convId }, "$flags")
            assertNull(h.call { conversationSnapshot(G1) })
            assertEquals(want.isNotEmpty(), h.call { conversationSnapshot(G2) } != null)
        }
    }

    @Test
    fun `F-CVS-03 规则 6 ②：convs 列表里的会话也按 minSeq 物理清除；还没发出的引用了被清除消息的，引用原文清除；引用留下的照常`() {
        val (h, c) = online(sc(AB, maxSeq = 5, readSeq = 5, updatedAt = 5))
        h.loaded(c, AB, (1L..4L).map { m(AB, it, text = "ZXQ-CVS-4 $it") } + m(AB, 5, body = obj("text" to "引用 2", "quote" to quoteOf(AB, 2, "引用摘要 2"))))
        c.drop()
        val r = assertIs<SendResult.Ok>(h.call { send(AB, "回复", quote = QuoteRef(idOf(AB, 2))) })
        assertEquals(QuoteState.NORMAL to "ZXQ-CVS-4 2", r.message.body.quote!!.let { it.state to it.text })
        val keep = assertIs<SendResult.Ok>(h.call { send(AB, "回复留下的", quote = QuoteRef(idOf(AB, 4))) })
        h.clock.advance(60_000)
        val n = h.transport.current
        n.open()
        n.ack("auth", obj("userId" to "alice", "serverTime" to h.clock.now()))
        down(n, ackOf(n, "convs", obj("convs" to listOf(sc(AB, maxSeq = 5, readSeq = 5, minSeq = 4, updatedAt = 5)), "truncated" to false)))
        assertEquals(listOf(4L, 5L), h.im.messages(AB).mapNotNull { it.seq })
        val pending = h.im.messages(AB).first { it.clientMsgId == r.message.clientMsgId }
        assertEquals(QuoteView(idOf(AB, 2), 2, "bob", MsgType.TEXT, QuoteState.DELETED), pending.body.quote)
        val kept = h.im.messages(AB).first { it.clientMsgId == keep.message.clientMsgId }
        assertEquals(QuoteState.NORMAL to "ZXQ-CVS-4 4", kept.body.quote!!.let { it.state to it.text })
        for (s in 1..3) clean(h, "ZXQ-CVS-4 $s")
        // 问题 62：保留下来的消息（seq ≥ minSeq）里引用被清除消息的摘要照常显示。
        assertEquals(QuoteState.NORMAL to "引用摘要 2", h.bySeq(AB, 5).body.quote!!.let { it.state to it.text })
    }

    @Test
    fun `删除隐藏、本地没有会话的：翻页不发请求（minSeq = clearedSeq + 1）`() {
        val (h, c) = online(sc(AB, maxSeq = 10, readSeq = 10, updatedAt = 10))
        h.loaded(c, AB, listOf(m(AB, 9), m(AB, 10)))
        downEvent(c, "convDeleted", "convId" to AB, "clearedSeq" to 10)
        val n = c.frames().size
        assertFalse(h.call { loadHistory(AB) })
        assertEquals(n, c.frames().size)
        assertEquals(emptyList(), h.im.messages(AB))
    }

    @Test
    fun `P-46 ④、问题 62：删除之后对方的新消息引用了被清除的旧消息，引用摘要照常显示（服务端组装）`() {
        val (h, c) = online(sc(AB, maxSeq = 2, readSeq = 2, updatedAt = 2))
        h.loaded(c, AB, listOf(m(AB, 1), m(AB, 2)))
        val d = h.start { deleteConv(AB) }
        down(c, ackOf(c, "convDelete", obj("clearedSeq" to 2)))
        h.await(d)
        down(c, msgFrame(m(AB, 3, body = obj("text" to "你看这条", "quote" to quoteOf(AB, 1, "m1")))))
        assertEquals(listOf("m1"), h.im.messages(AB).map { it.body.quote?.text })
    }

    @Test
    fun `backend-chat 第 85 条：对被删除隐藏的会话 convSet，ack 的 conv 不让它重新出现；有新消息时带着新设置出现`() {
        val (h, c) = online(sc(AB, maxSeq = 2, readSeq = 2, updatedAt = 2))
        val d = h.start { deleteConv(AB) }
        down(c, ackOf(c, "convDelete", obj("clearedSeq" to 2)))
        h.await(d)
        val q = h.start { setConvNotifyOff(AB, true) }
        down(c, ackOf(c, "convSet", obj("conv" to sc(AB, maxSeq = 2, readSeq = 2, minSeq = 3, updatedAt = 2, notifyOff = true))))
        h.await(q)
        assertEquals(emptyList(), h.ids())
        down(c, msgFrame(m(AB, 3)))
        assertEquals(true to 1L, h.find(AB)!!.let { it.notifyOff to it.unread })
    }

    // ---- F-CVS-04 多端同步 ----

    @Test
    fun `TC-CVS-04-01（AC-CVS-04-1）：另一端置顶、免打扰：convSettingsChanged 覆盖本地，g1 移到最前；取消置顶不留 pinnedAt；不认识的会话忽略`() {
        val (h, c) = online(sc(AB, updatedAt = 300, maxSeq = 2, unread = 2), sc(G1, updatedAt = 100, maxSeq = 4, unread = 4))
        val lists = ArrayList<List<Conversation>>()
        downEvent(c, "convSettingsChanged", "convId" to G1, "pinned" to true, "pinnedAt" to 1234, "notifyOff" to false)
        lists.add(h.im.conversations.value)
        assertEquals(listOf(G1, AB), h.ids())
        assertEquals(1234L, h.find(G1)!!.pinnedAt)
        downEvent(c, "convSettingsChanged", "convId" to G1, "pinned" to true, "pinnedAt" to 1234, "notifyOff" to true)
        assertTrue(h.find(G1)!!.notifyOff)
        assertEquals(2L, h.im.totalUnread.value)
        val same = h.im.conversations.value
        downEvent(c, "convSettingsChanged", "convId" to G1, "pinned" to true, "pinnedAt" to 1234, "notifyOff" to true) // 没有变化
        assertSame(same, h.im.conversations.value)
        downEvent(c, "convSettingsChanged", "convId" to G1, "pinned" to false, "notifyOff" to false)
        assertEquals(listOf(AB, G1), h.ids())
        assertNull(h.find(G1)!!.pinnedAt)
        assertFalse(h.find(G1)!!.notifyOff)
        assertEquals(6L, h.im.totalUnread.value)
        downEvent(c, "convSettingsChanged", "convId" to "group:zz", "pinned" to true, "pinnedAt" to 1, "notifyOff" to false)
        assertEquals(listOf(AB, G1), h.ids())
    }

    @Test
    fun `TC-CVS-04-05（AC-CVS-04-5，第 55 条）：另一端删除：convDeleted 后会话消失，本地消息清除，发 ConvDeleted；之后新消息只算新的未读、保留免打扰、不再置顶`() {
        val (h, c) = online(sc(AB, maxSeq = 10, readSeq = 6, unread = 4, updatedAt = 10, pinnedAt = 3, notifyOff = true), sc(AC, updatedAt = 1))
        h.loaded(c, AB, listOf(m(AB, 9, text = "$CVS_SECRET 9"), m(AB, 10, text = "$CVS_SECRET 10")))
        downEvent(c, "convDeleted", "convId" to AB, "clearedSeq" to 10)
        assertEquals(listOf(AC), h.ids())
        assertEquals(listOf(AB), h.convDeleted())
        assertEquals(emptyList(), h.removed())
        assertEquals(emptyList(), h.im.messages(AB))
        clean(h, CVS_SECRET)
        down(c, msgFrame(m(AB, 11)))
        val x = h.find(AB)!!
        assertEquals(listOf(11L, 10L, 1L, true, false, null), listOf(x.minSeq, x.readSeq, x.unread, x.notifyOff, x.pinned, x.pinnedAt))
    }

    @Test
    fun `问题 63：其他设备删除时本机没发出的消息不丢；发出后 seq 大于 clearedSeq，会话重新出现`() {
        val (h, c) = online(sc(AB, maxSeq = 2, readSeq = 2, updatedAt = 2))
        h.loaded(c, AB, listOf(m(AB, 1), m(AB, 2)))
        c.drop()
        h.call { send(AB, "排队的") }
        val n = h.reconnectAuth(waitMs = 60_000)
        // 重连后 convs 还没回，排队的已经发出（发送中）；这时其他设备删除了会话。
        downEvent(n, "convDeleted", "convId" to AB, "clearedSeq" to 2)
        assertEquals(listOf("cid-0001:sending"), h.status(AB))
        n.ack("send", obj("msgId" to "ab-3", "convId" to AB, "seq" to 3, "ts" to 9000))
        assertEquals(listOf("cid-0001:sent#3"), h.status(AB))
        assertEquals(listOf(AB), h.ids())
        assertEquals(0L, h.find(AB)!!.unread)
    }

    @Test
    fun `convDeleted 空群（clearedSeq=0）：从列表移除；之后有消息重新出现（AC-CVS-03-11 的客户端部分）`() {
        val (h, c) = online(sc(G1, updatedAt = 5))
        downEvent(c, "convDeleted", "convId" to G1, "clearedSeq" to 0)
        assertEquals(emptyList(), h.ids())
        down(c, msgFrame(m(G1, 1)))
        assertEquals(1L to 1L, h.find(G1)!!.let { it.unread to it.minSeq })
    }

    @Test
    fun `TC-CVS-04-02（AC-CVS-04-2）：D2 离线时 D1 置顶 c1 并删除 c2：D2 重连后顺序、置顶、免打扰和服务端一致，c2 不在列表、本地消息清除`() {
        val (h, c) = online(sc(AB, maxSeq = 2, readSeq = 2, updatedAt = 1), sc(AC, maxSeq = 3, readSeq = 3, updatedAt = 9), sc(G1, maxSeq = 1, readSeq = 1, updatedAt = 5, notifyOff = true))
        h.loaded(c, AB, listOf(m(AB, 1), m(AB, 2)))
        h.loaded(c, AC, listOf(m(AC, 1, "carol", "ZXQ-CVS-5"), m(AC, 2, "carol"), m(AC, 3, "carol")))
        val server = listOf(sc(AB, maxSeq = 2, readSeq = 2, updatedAt = 1, pinnedAt = 99), sc(G1, maxSeq = 1, readSeq = 1, updatedAt = 5, notifyOff = true))
        reconnect(h, c, obj("convs" to server, "truncated" to false, "deleted" to listOf(obj("convId" to AC, "clearedSeq" to 3))))
        assertEquals(
            listOf(listOf(AB, true, 99L, false), listOf(G1, false, null, true)),
            h.im.conversations.value.map { listOf(it.convId, it.pinned, it.pinnedAt, it.notifyOff) },
        )
        assertEquals(emptyList(), h.im.messages(AC))
        assertEquals(listOf(1L, 2L), h.im.messages(AB).map { it.seq })
        clean(h, "ZXQ-CVS-5")
    }

    @Test
    fun `老服务端（convs 没有 pinned、notifyOff、deleted）：口径和 C4 相同`() {
        val (h, _) = online(sc(AB, updatedAt = 1, maxSeq = 2, unread = 2), sc(AC, updatedAt = 2, maxSeq = 1, unread = 1))
        assertEquals(listOf(AC, AB), h.ids())
        assertEquals(3L, h.im.totalUnread.value)
    }

    @Test
    fun `features-convSettings 关闭（P-46 ②）：服务端照样下发已有设置，SDK 不看开关照常应用`() {
        val (h, _) = online(sc(AB, updatedAt = 9, maxSeq = 3, unread = 3, notifyOff = true), sc(AC, updatedAt = 1, pinnedAt = 1))
        assertEquals(listOf(AC, AB), h.ids())
        assertEquals(0L, h.im.totalUnread.value)
    }

    // ---- 问题 65：服务端的 minSeq 变小时放低本地下限（F-CVS-03 规则 6a） ----

    @Test
    fun `问题 65：删除后离线期间被移出又加回（本地没有快照）：重连 convs 的 minSeq 变小，旧历史可以再加载`() {
        val (h, c) = online(sc(G1, maxSeq = 5, readSeq = 5, updatedAt = 5))
        h.loaded(c, G1, (1L..5L).map { m(G1, it) })
        downEvent(c, "convDeleted", "convId" to G1, "clearedSeq" to 5)
        down(c, msgFrame(m(G1, 6)))
        assertEquals(6L, h.find(G1)!!.minSeq)
        assertNull(h.call { conversationSnapshot(G1) })
        // 离线期间被移出又加回（historyVisible=true）：没有收到 convRemoved，本地没有快照。
        val n = reconnect(h, c, obj("convs" to listOf(sc(G1, maxSeq = 6, readSeq = 6, minSeq = 1, updatedAt = 6)), "truncated" to false))
        val d = h.start { loadHistory(G1) }
        assertEquals(6L, n.last("sync").dataObj["beforeSeq"]!!.str.toLong())
        n.ack("sync", obj("messages" to (1L..5L).map { m(G1, it) }, "hasMore" to false))
        h.await(d)
        assertEquals((1L..6L).toList(), h.im.messages(G1).map { it.seq })
    }

    @Test
    fun `问题 65：convAdded 的 minSeq 比本地下限小时放低、可以再加载；比本地大时按它清除本地消息（文件经 FilesExpired）`() {
        val (h, c) = online(sc(G1, maxSeq = 5, readSeq = 5, updatedAt = 5))
        val pic = JsonObject(m(G1, 2, body = obj("image" to obj("fileId" to "F2", "width" to 1, "height" to 1, "size" to 1, "mime" to "image/png"))) + mapOf("type" to JsonPrimitive("image")))
        h.loaded(c, G1, listOf(m(G1, 1, text = CVS_SECRET), pic) + (3L..5L).map { m(G1, it) })
        downEvent(c, "convAdded", "conv" to sc(G1, maxSeq = 5, readSeq = 5, minSeq = 4, updatedAt = 5))
        assertEquals(listOf(4L, 5L), h.im.messages(G1).map { it.seq })
        assertEquals(listOf(listOf("F2")), h.events.filterIsInstance<IMEvent.FilesExpired>().map { it.fileIds })
        clean(h, CVS_SECRET)
        downEvent(c, "convAdded", "conv" to sc(G1, maxSeq = 5, readSeq = 5, minSeq = 1, updatedAt = 5))
        val d = h.start { loadHistory(G1) }
        c.ack("sync", obj("messages" to listOf(m(G1, 3)), "hasMore" to false))
        h.await(d)
        assertEquals(listOf(3L, 4L, 5L), h.im.messages(G1).map { it.seq })
    }

    // ---- 问题 68：本机待发的消息，迟到的 ack 带回的 seq 落在清除范围里 ----

    @Test
    fun `问题 68：发送中的消息 ack 晚到、seq 落在清除范围里（upToSeq 之前已看到别人更新的消息）：这条一起去掉，不停在发送中`() {
        val (h, c) = online(sc(AB, maxSeq = 2, readSeq = 2, updatedAt = 2))
        h.loaded(c, AB, listOf(m(AB, 1), m(AB, 2)))
        h.call { send(AB, "我的 $CVS_SECRET") } // 服务端给了 seq 3，ack 还没到
        down(c, msgFrame(m(AB, 4))) // 别人更新的消息先到
        val d = h.start { deleteConv(AB) }
        assertEquals(4L, c.last("convDelete").dataObj["upToSeq"]!!.str.toLong())
        down(c, ackOf(c, "convDelete", obj("clearedSeq" to 4)))
        h.await(d)
        assertEquals(listOf("cid-0001:sending"), h.status(AB)) // 发送中的不丢（问题 59）
        down(c, ackOf(c, "send", obj("msgId" to "ab-3", "convId" to AB, "seq" to 3, "ts" to 9000)))
        assertEquals(emptyList(), h.status(AB))
        assertEquals(0, h.im.pendingCount.value)
        assertEquals(emptyList(), h.ids())
        clean(h, CVS_SECRET)
    }

    @Test
    fun `问题 68：超时后已标记失败的，晚到的 ack 落在清除范围里同样去掉；别人用同一个 clientMsgId 的消息不算`() {
        val (h, c) = online(sc(AB, maxSeq = 2, readSeq = 2, updatedAt = 2))
        h.loaded(c, AB, listOf(m(AB, 1), m(AB, 2)))
        h.call { send(AB, "我的") }
        val sendId = c.last("send")["id"]!!.str
        h.clock.advance(11_000)
        assertEquals(listOf("bob-$AB-1:sent#1", "bob-$AB-2:sent#2", "cid-0001:failed(timeout)"), h.status(AB))
        down(c, msgFrame(m(AB, 4)))
        downEvent(c, "convDeleted", "convId" to AB, "clearedSeq" to 4) // 其他设备删除：失败的不丢（问题 63）
        assertEquals(listOf("cid-0001:failed(timeout)"), h.status(AB))
        // 别人的消息碰巧用了同一个 clientMsgId，seq 落在清除范围里：不动我的。
        val other = JsonObject(m(AB, 3) + mapOf("clientMsgId" to JsonPrimitive("cid-0001")))
        down(c, msgFrame(other))
        assertEquals(listOf("alice"), h.call { debugExport() }.messages[AB]!!.filter { it.clientMsgId == "cid-0001" }.map { it.from })
        down(c, obj("id" to sendId, "cmd" to "ack", "data" to obj("msgId" to "ab-3", "convId" to AB, "seq" to 3, "ts" to 9000)))
        assertEquals(emptyList(), h.status(AB))
        assertEquals(0, h.im.pendingCount.value)
    }

    @Test
    fun `convSettingsChanged、convDeleted 缺字段或 clearedSeq 为负：忽略，不改本地`() {
        val (h, c) = online(sc(G1, maxSeq = 2, updatedAt = 1))
        c.receive(event("convSettingsChanged", "convId" to G1, "pinned" to true, "pinnedAt" to 5))
        c.receive(event("convSettingsChanged", "convId" to G1, "notifyOff" to true))
        c.receive(event("convDeleted", "convId" to G1, "clearedSeq" to -1))
        c.receive(event("convDeleted", "convId" to G1))
        assertEquals(listOf(G1), h.ids())
        assertEquals(false to false, h.find(G1)!!.let { it.pinned to it.notifyOff })
        assertEquals(emptyList(), h.convDeleted())
    }
}

/** 第 59 条、AC-CVS-03-8 的 SDK 部分：要真的上传流程（假 HTTP、假对象存储），用账号的测试底座。 */
class ConvDeleteUploadsTest : AccountTest() {
    @org.junit.jupiter.api.io.TempDir
    lateinit var tmp: File

    @Test
    fun `第 59 条、AC-CVS-03-8（SDK 部分）：确认删除时取消这个会话正在上传的附件（DELETE 临时对象）、丢弃排队和失败的消息；别的会话不动`() {
        val ab = "c2c:$USER:bob"
        val ac = "c2c:$USER:carol"
        val a = setupAccount()
        a.session(1, DAY)
        val c = a.online()
        val img = ProcessedImage(
            LocalFile(File(tmp, "o").apply { writeBytes(ByteArray(1000)) }, "image/jpeg"),
            LocalFile(File(tmp, "t").apply { writeBytes(ByteArray(100)) }, "image/jpeg"),
            10,
            10,
        )
        a.h.call { sendImage(ab, img) }
        a.http.last("/v1/files/uploads").respond(200, obj("fileId" to "F1", "put" to obj("url" to "https://s3.test/x?X-Amz-Signature=1", "headers" to obj(), "expiresAt" to 1)))
        val failed = assertIs<SendResult.Ok>(a.h.call { send(ab, "ZXQ-CVS-2 失败的") })
        a.clock.advance(11_000) // 10 秒没有 ack：失败
        val other = assertIs<SendResult.Ok>(a.h.call { send(ac, "别的会话") })
        assertEquals(MessageStatus.FAILED, a.im.messages(ab).first { it.clientMsgId == failed.message.clientMsgId }.status)
        val d = a.h.start { deleteConv(ab) }
        assertEquals(obj("convId" to ab, "upToSeq" to 0), c.last("convDelete").dataObj)
        assertEquals(0, a.http.calls.count { it.req.method == "DELETE" }) // 取消要等成功
        c.ack("convDelete", obj("clearedSeq" to 0))
        a.h.await(d)
        assertEquals(emptyList(), a.im.messages(ab))
        assertEquals(listOf("/v1/files/uploads/F1"), a.http.calls.filter { it.req.method == "DELETE" }.map { it.path })
        assertEquals(1, a.im.pendingCount.value)
        assertEquals(listOf(other.message.clientMsgId), a.im.messages(ac).map { it.clientMsgId })
        assertFalse(holds(storeOf(a.im), "ZXQ-CVS-2"))
        assertEquals(emptyList(), a.h.attachmentDir.listFiles()?.toList() ?: emptyList()) // 沙盒副本删除
    }

    @Test
    fun `问题 68（附件）：图片已发出、ack 晚到且 seq 落在清除范围里：消息去掉，fileId 经 FilesExpired 通知页面，沙盒副本删除`() {
        val ab = "c2c:$USER:bob"
        val a = setupAccount()
        a.session(1, DAY)
        val c = a.online()
        val img = ProcessedImage(
            LocalFile(File(tmp, "o").apply { writeBytes(ByteArray(1000)) }, "image/jpeg"),
            LocalFile(File(tmp, "t").apply { writeBytes(ByteArray(100)) }, "image/jpeg"),
            10,
            10,
        )
        a.h.call { sendImage(ab, img) }
        val put = obj("url" to "https://s3.test/x?X-Amz-Signature=1", "headers" to obj(), "expiresAt" to 1)
        a.http.last("/v1/files/uploads").respond(200, obj("fileId" to "F7", "put" to put, "thumbPut" to put))
        a.h.objects.pending[0].respond(200)
        a.h.objects.pending[0].respond(200)
        a.http.last("/v1/files/F7/complete").respond(200, obj("fileId" to "F7", "size" to 1000, "mime" to "image/jpeg", "width" to 10, "height" to 10))
        val sendId = c.last("send")["id"]!!.str
        assertEquals(listOf(MessageStatus.SENDING), a.im.messages(ab).map { it.status })
        c.receive(msgFrame(m(ab, 2)))
        val d = a.h.start { deleteConv(ab) }
        c.ack("convDelete", obj("clearedSeq" to 2))
        a.h.await(d)
        c.receive(obj("id" to sendId, "cmd" to "ack", "data" to obj("msgId" to "ab-1", "convId" to ab, "seq" to 1, "ts" to 9000)))
        assertEquals(emptyList(), a.im.messages(ab))
        assertTrue(a.h.events.filterIsInstance<IMEvent.FilesExpired>().any { "F7" in it.fileIds })
        assertEquals(0, a.im.pendingCount.value)
        a.h.flush()
        assertEquals(emptyList(), a.h.attachmentDir.listFiles()?.toList() ?: emptyList())
    }
}
