// FE-691（C6）：举报、4510、features.moderation、canReport（PRD-chat 第 10 节 F-MOD-02、03，第 13.1 节）。语义同 sdk-ts 的 test/report.test.ts。
// 真实的 IMClient + FakeTransport + 假时钟（[SDK 钟]）+ 假 HTTP；冷启动用真 SQLite 文件。图片、文件的 4510 在 FilesTest。
// 共享用例（frames.json 的 C6）在 VectorsTest；SDK 发出的帧都按 frame.schema.json 校验（SdkTest）。只用特征串，没有真实敏感词。本地单测结果，CI 不运行。
package com.tianting.sdk

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val G = "group:g1"
private const val AB = "c2c:alice:bob"
private const val NOTE_SECRET = "ZXQ-RPT-NOTE"
private val QUIET = IMClientTimings(heartbeatMs = 10 * 365 * 24 * 3_600_000L) // 推进时钟时不因心跳断线

class ReportTest : AccountTest() {
    @TempDir
    lateinit var dir: File

    private fun online(convs: List<JsonObject> = listOf(conv(G, maxSeq = 3), conv(AB, maxSeq = 2))): Pair<Harness, com.tianting.sdk.testing.FakeConnection> {
        val h = setup(timings = QUIET)
        h.connect()
        return h to h.login("alice", convs)
    }

    private fun data(c: com.tianting.sdk.testing.FakeConnection) = c.last("report").dataObj

    private fun field(e: Throwable): String {
        assertIs<IMError>(e)
        assertEquals(ErrorCode.BAD_REQUEST, e.code)
        return e.details!!["field"]!!.str
    }

    // ---- report（F-MOD-03，AC-MOD-03-1、AC-MOD-03-9 的 SDK 部分） ----

    @Test
    fun `TC-MOD-03-01 举报群里的一条消息：发 report 帧，ack 的 reportId 原样返回；不发别的帧、不改本地数据`() {
        val (h, c) = online()
        h.loaded(c, G, listOf(m(G, 1), m(G, 2)))
        val before = c.frames().size
        val msgs = h.im.messages(G)
        val convs = h.im.conversations.value
        val p = h.start { report(ReportTarget.Messages(G, listOf(idOf(G, 2))), ReportReason.ABUSE) }
        assertEquals(obj("targetType" to "message", "convId" to G, "msgIds" to listOf(idOf(G, 2)), "reason" to "abuse"), data(c))
        c.ack("report", obj("reportId" to "rp_01J9ZXQ"))
        assertEquals("rp_01J9ZXQ", h.await(p))
        assertEquals(before + 1, c.frames().size)
        assertEquals(msgs, h.im.messages(G))
        assertEquals(convs, h.im.conversations.value)
    }

    @Test
    fun `三种对象：单聊消息（带 note）、用户、群；note 原样（空串也发），null 不带`() {
        val (h, c) = online()
        val cases = listOf(
            Triple(ReportTarget.Messages(AB, listOf("m1", "m2")), ReportReason.SPAM, NOTE_SECRET) to
                obj("targetType" to "message", "convId" to AB, "msgIds" to listOf("m1", "m2"), "reason" to "spam", "note" to NOTE_SECRET),
            Triple(ReportTarget.User("u9"), ReportReason.FRAUD, "") to obj("targetType" to "user", "userId" to "u9", "reason" to "fraud", "note" to ""),
            Triple(ReportTarget.Group("g1"), ReportReason.ILLEGAL, null) to obj("targetType" to "group", "groupId" to "g1", "reason" to "illegal"),
            Triple(ReportTarget.User("u9"), ReportReason.PORN, null) to obj("targetType" to "user", "userId" to "u9", "reason" to "porn"),
            Triple(ReportTarget.User("u9"), ReportReason.OTHER, null) to obj("targetType" to "user", "userId" to "u9", "reason" to "other"),
        )
        for ((i, x) in cases.withIndex()) {
            val (args, want) = x
            val p = h.start { report(args.first, args.second, args.third) }
            assertEquals(want, data(c))
            c.ack("report", obj("reportId" to "rp$i"))
            assertEquals("rp$i", h.await(p))
        }
        assertEquals(listOf("spam", "abuse", "porn", "fraud", "illegal", "other"), ReportReason.entries.map { it.wire })
        assertFalse(h.logs.any { NOTE_SECRET in it }, h.logs.toString())
    }

    @Test
    fun `note 按码点数（frontend-chat 第 84 条）：200 个 emoji（400 个 UTF-16 单元）可以，201 个码点 4000 不发帧`() {
        val (h, c) = online()
        val ok = "😀".repeat(200)
        assertEquals(400, ok.length)
        val p = h.start { report(ReportTarget.Group("g1"), ReportReason.OTHER, ok) }
        assertEquals(ok, data(c)["note"]!!.str)
        c.ack("report", obj("reportId" to "rp1"))
        h.await(p)
        val n = c.frames("report").size
        assertEquals("note", field(h.failure(h.start { report(ReportTarget.Group("g1"), ReportReason.OTHER, "举".repeat(201)) })))
        assertEquals(n, c.frames("report").size)
        assertEquals(REPORT_NOTE_MAX, 200)
    }

    @Test
    fun `msgIds 20 条可以发（协议上限）；21 条、0 条本地 4000；第一批页面只传 1 条，接口保留列表`() {
        val (h, c) = online()
        val ids = (1..20).map { "m$it" }
        val p = h.start { report(ReportTarget.Messages(G, ids), ReportReason.PORN) }
        assertEquals(js(ids), data(c)["msgIds"])
        c.ack("report", obj("reportId" to "rp1"))
        h.await(p)
        for (bad in listOf((1..21).map { "m$it" }, emptyList())) {
            assertEquals("msgIds", field(h.failure(h.start { report(ReportTarget.Messages(G, bad), ReportReason.PORN) })))
        }
        assertEquals(1, c.frames("report").size)
        assertEquals(REPORT_MSG_MAX, 20)
    }

    @Test
    fun `参数不合法：本地直接抛 4000（details field），不发帧；错误信息里没有 note 和参数值`() {
        val (h, c) = online()
        val cases = listOf(
            ReportTarget.Messages("c2c:bob:carol", listOf("m1")) to "convId", // 单聊自己不是参与者
            ReportTarget.Messages("c2c:bob:alice", listOf("m1")) to "convId", // 顺序不对
            ReportTarget.Messages("room:r1", listOf("m1")) to "convId", // 聊天室不能举报
            ReportTarget.Messages("g1", listOf("m1")) to "convId",
            ReportTarget.Messages("group:g/1", listOf("m1")) to "convId",
            ReportTarget.Messages(G, listOf("m 1")) to "msgIds",
            ReportTarget.Messages(G, listOf("m1", "")) to "msgIds",
            ReportTarget.Messages(G, listOf("x".repeat(65))) to "msgIds",
            ReportTarget.User("u 9") to "userId",
            ReportTarget.User("") to "userId",
            ReportTarget.Group("g/1") to "groupId",
        )
        for ((target, want) in cases) {
            val e = h.failure(h.start { report(target, ReportReason.ABUSE, NOTE_SECRET) })
            assertEquals(want, field(e), target.toString())
            assertFalse(NOTE_SECRET in e.message!!, e.message)
            assertFalse(NOTE_SECRET in e.toString())
        }
        // note 超长时错误信息里也没有 note。
        val long = NOTE_SECRET + "举".repeat(200)
        val e = h.failure(h.start { report(ReportTarget.User("u9"), ReportReason.ABUSE, long) })
        assertEquals("note", field(e))
        assertFalse(NOTE_SECRET in e.message!!)
        assertEquals(emptyList(), c.frames("report"))
        assertFalse(h.logs.any { NOTE_SECRET in it }, h.logs.toString())
        // 单聊自己是参与者（包括给自己发的会话）可以。
        val p = h.start { report(ReportTarget.Messages("c2c:alice:alice", listOf("m1")), ReportReason.SPAM) }
        c.ack("report", obj("reportId" to "rp1"))
        assertEquals("rp1", h.await(p))
    }

    @Test
    fun `4292、4040 reportTargetInvalid、4030 featureDisabled、4290、5000 原样抛出，不自动重试（推进时间、重连后也只有 1 帧）`() {
        val (h, c) = online()
        val errs = listOf(
            Triple(ErrorCode.QUOTA_EXCEEDED, null, "今天的举报次数已用完"),
            Triple(ErrorCode.NOT_FOUND, "reportTargetInvalid", "无法举报该内容"),
            Triple(ErrorCode.FORBIDDEN, "featureDisabled", "举报功能暂不可用"),
            Triple(ErrorCode.RATE_LIMITED, null, "操作太频繁，请稍后再试"),
            Triple(ErrorCode.INTERNAL, null, null),
        )
        for ((code, reason, copy) in errs) {
            val p = h.start { report(ReportTarget.User("u9"), ReportReason.SPAM, NOTE_SECRET) }
            c.ackErr("report", code, reason = reason)
            val e = h.failure(p)
            assertIs<IMError>(e)
            assertEquals(code, e.code)
            assertEquals(reason, e.reason)
            assertEquals(copy, messageFor("report", code))
        }
        assertEquals(errs.size, c.frames("report").size)
        h.clock.advance(60_000)
        c.drop()
        h.reconnectAuth("alice", waitMs = 60_000)
        assertEquals(errs.size, h.transport.connections.sumOf { it.frames("report").size })
        assertFalse(h.logs.any { NOTE_SECRET in it }, h.logs.toString())
    }

    @Test
    fun `超时抛 TimeoutError，不重试，迟到的 ack 不出错；未连接抛 NotConnectedError，不发帧；未连接时参数不合法仍是 4000`() {
        val (h, c) = online()
        val p = h.start { report(ReportTarget.User("u9"), ReportReason.SPAM) }
        h.clock.advance(IMClientTimings().requestTimeoutMs)
        assertIs<TimeoutError>(h.failure(p))
        c.ack("report", obj("reportId" to "late"))
        h.clock.advance(60_000)
        assertEquals(1, c.frames("report").size)
        assertEquals(ConnState.CONNECTED, h.im.state.value)

        val off = setup()
        assertIs<NotConnectedError>(off.failure(off.start { report(ReportTarget.User("u9"), ReportReason.SPAM) }))
        assertEquals("userId", field(off.failure(off.start { report(ReportTarget.User("u 9"), ReportReason.SPAM) })))
        // 还不知道自己是谁时，单聊 convId 只校验格式。
        assertIs<NotConnectedError>(off.failure(off.start { report(ReportTarget.Messages("c2c:bob:carol", listOf("m1")), ReportReason.SPAM) }))
        assertTrue(off.transport.connections.isEmpty())
    }

    // ---- canReport（frontend-chat 第 77、88 条） ----

    @Test
    fun `canReport：别人发的已发送 text、image、file 有；自己的、tip、custom、发送中、失败、后台删除的没有；撤回提示行只在别人撤回的上有`() {
        val (h, c) = online(listOf(conv(G, maxSeq = 20, readSeq = 20, myRole = "admin"), conv(AB, maxSeq = 2)))
        fun typed(seq: Long, type: String, body: JsonObject) = JsonObject(m(G, seq, body = body) + mapOf("type" to js(type)))
        h.loaded(
            c, G,
            listOf(
                m(G, 1), // 别人的文本
                typed(2, "image", obj("image" to obj("fileId" to "f2", "width" to 1, "height" to 1, "size" to 1, "mime" to "image/png"))),
                typed(3, "file", obj("file" to obj("fileId" to "f3", "name" to "a.pdf", "size" to 1, "mime" to "application/pdf"))),
                m(G, 4, from = "alice"), // 自己的
                typed(5, "tip", obj("tip" to obj("kind" to "memberJoined", "targets" to listOf("bob")))),
                typed(6, "custom", obj("data" to obj("k" to 1))),
                recalledMsg(G, 7, from = "bob", by = "bob"), // 别人自己撤回
                recalledMsg(G, 8, from = "carol", by = "bob"), // 别人（管理员）撤回第三个人的
                recalledMsg(G, 9, from = "bob", by = "alice"), // 我（管理员）撤回的别人的
                recalledMsg(G, 10, from = "alice", by = "bob"), // 别人撤回的我的
                deletedMsg(G, 11, msgId = idOf(G, 11)), // 后台删除
                typed(12, "unknown-kind", obj()),
            ),
        )
        val want = mapOf(1L to true, 2L to true, 3L to true, 4L to false, 5L to false, 6L to false, 7L to true, 8L to true, 9L to false, 10L to false, 11L to false, 12L to false)
        assertEquals(want, h.im.messages(G).associate { it.seq!! to h.im.canReport(it) })
        // 撤回者不知道（老服务端）时：别人的消息按别人撤回的。
        assertTrue(h.im.canReport(h.bySeq(G, 7).copy(recalledBy = null)))

        // 自己发送中、失败的没有；发送成功后也没有（自己的）。
        val sending = (h.call { send(AB, "hi") } as SendResult.Ok).message
        assertFalse(h.im.canReport(sending))
        c.ackErr("send", ErrorCode.INTERNAL)
        assertFalse(h.im.canReport(h.im.messages(AB).single()))
        // 别人的消息只要不是 SENT 就没有（本地构造，防御）。
        val other = h.bySeq(G, 1)
        for (st in MessageStatus.entries.filter { it != MessageStatus.SENT }) assertFalse(h.im.canReport(other.copy(status = st)), st.name)
        assertFalse(h.im.canReport(other.copy(msgId = null)))

        // 还不知道自己是谁时一律没有。
        assertFalse(setup().im.canReport(other))
    }

    @Test
    fun `canReport（问题 92）：只有 seq ≤ 会话 readSeq 的才有；已读推进后（本端看过、其他端已读）出现；会话不在本地列表里的没有`() {
        val (h, c) = online(listOf(conv(G, maxSeq = 5, readSeq = 2)))
        h.loaded(c, G, (1L..5L).map { m(G, it) } + listOf(recalledMsg(G, 6, from = "bob")))
        fun can() = h.im.messages(G).associate { it.seq!! to h.im.canReport(it) }
        // 边界：seq = readSeq 有，readSeq + 1 没有；撤回提示行同样按 readSeq（服务端 readSeq ≥ seq）。
        assertEquals(mapOf(1L to true, 2L to true, 3L to false, 4L to false, 5L to false, 6L to false), can())
        // 其他端已读到 4。
        c.receive(event("readUpdated", "convId" to G, "readSeq" to 4))
        assertEquals(mapOf(1L to true, 2L to true, 3L to true, 4L to true, 5L to false, 6L to false), can())
        // 本端打开会话、页面可见：上报已读到最新，本地立即推进。
        h.im.setActiveConversation(G)
        h.flush()
        assertEquals(6L, c.last("read").dataObj["seq"]!!.str.toLong())
        assertTrue(can().values.all { it }, can().toString())

        // 会话不在本地列表里（例如已删除、已退群，或者构造出来的消息）：不显示。
        val stranger = h.bySeq(G, 1).copy(convId = "group:g9")
        assertFalse(h.im.canReport(stranger))
        // 没有 seq 的（防御）也没有。
        assertFalse(h.im.canReport(h.bySeq(G, 1).copy(seq = null)))
    }

    @Test
    fun `canReport：自己的消息被别人（群主、管理员）撤回后，提示行不显示"举报"（问题 92）`() {
        val (h, c) = online(listOf(conv(G, maxSeq = 3, readSeq = 3)))
        h.loaded(c, G, listOf(m(G, 1, from = "alice"), m(G, 2)))
        c.receive(recalledEvent(G, 1, by = "bob"))
        c.receive(recalledEvent(G, 2, by = "bob"))
        val mine = h.bySeq(G, 1)
        assertTrue(mine.recalled && mine.recalledBy == "bob" && mine.from == "alice")
        assertFalse(h.im.canReport(mine))
        assertTrue(h.im.canReport(h.bySeq(G, 2))) // 对照：别人自己撤回的有
    }

    // ---- 4510（F-MOD-02 规则 6，AC-MOD-02-9 的 SDK 部分，frontend-chat 第 81 条） ----

    @Test
    fun `TC-MOD-02-09 send 收到 4510：标记失败、error=4510，SendFailed 只带码；错误里没有内容；不自动重发，也不能手动重发`() {
        val (h, c) = online()
        h.call { send(AB, "含 ZXQWORD 的消息") }
        c.receive(obj("id" to c.last("send")["id"]!!.str, "cmd" to "ack", "error" to obj("code" to 4510, "msg" to "content blocked")))
        val m = h.im.messages(AB).single()
        assertEquals(MessageStatus.FAILED, m.status)
        assertEquals(SendError.Code(4510), m.error)
        assertNull(m.errorReason)
        assertFalse(h.im.canRetry(m))
        assertEquals(listOf(IMEvent.SendFailed(AB, m.clientMsgId, SendError.Code(4510))), h.failed())
        assertEquals("消息包含不当内容，无法发送", messageFor("send", 4510))
        assertFalse(h.logs.any { "ZXQWORD" in it }, h.logs.toString())
        h.call { retry(AB, m.clientMsgId) }
        assertEquals(1, c.frames("send").size)
        assertEquals(listOf("${m.clientMsgId}:failed(4510)"), h.status(AB))
        // 断线重连后也不自动重发。
        c.drop()
        h.reconnectAuth("alice", waitMs = 60_000)
        h.transport.current.ack("convs", obj("convs" to listOf(conv(AB, maxSeq = 2)), "truncated" to false))
        h.clock.advance(60_000)
        assertEquals(1, h.transport.connections.sumOf { it.frames("send").size })
        assertEquals(listOf("${m.clientMsgId}:failed(4510)"), h.status(AB))
        // 只能删除。
        assertTrue(h.call { removeFailed(AB, m.clientMsgId) })
    }

    @Test
    fun `对照：5000、超时失败的可以重发（canRetry 为 true，retry 再发一次）`() {
        val (h, c) = online()
        h.call { send(AB, "a") }
        c.ackErr("send", ErrorCode.INTERNAL)
        val m = h.im.messages(AB).single()
        assertTrue(h.im.canRetry(m))
        h.call { retry(AB, m.clientMsgId) }
        assertEquals(2, c.frames("send").size)
        h.clock.advance(10_000)
        assertEquals(SendError.Timeout, h.im.messages(AB).single().error)
        assertTrue(h.im.canRetry(h.im.messages(AB).single()))
        // 不是失败的都不能"重发"。
        for (st in MessageStatus.entries.filter { it != MessageStatus.FAILED }) assertFalse(h.im.canRetry(m.copy(status = st)), st.name)
    }

    @Test
    fun `超时标记失败之后才到的 4510：失败原因改成 4510，不再能重发，不重复发 SendFailed；已经手动重发的以那次为准`() {
        val (h, c) = online()
        h.call { send(AB, "a") }
        val first = c.last("send")["id"]!!.str
        h.clock.advance(10_000)
        val m = h.im.messages(AB).single()
        assertEquals(SendError.Timeout, m.error)
        assertEquals(1, h.failed().size)
        c.receive(obj("id" to first, "cmd" to "ack", "error" to obj("code" to 4510, "msg" to "content blocked")))
        val x = h.im.messages(AB).single()
        assertEquals(MessageStatus.FAILED, x.status)
        assertEquals(SendError.Code(4510), x.error)
        assertFalse(h.im.canRetry(x))
        assertEquals(1, h.failed().size)

        // 另一条：超时后用户手动重发（又在发送中），第一次的 4510 迟到：不改，以第二次的结果为准。
        val b = (h.call { send(AB, "b") } as SendResult.Ok).message
        val id1 = c.last("send")["id"]!!.str
        h.clock.advance(10_000)
        assertEquals(SendError.Timeout, h.im.messages(AB).single { it.clientMsgId == b.clientMsgId }.error)
        h.call { retry(AB, b.clientMsgId) }
        c.receive(obj("id" to id1, "cmd" to "ack", "error" to obj("code" to 4510, "msg" to "content blocked")))
        fun bNow() = h.im.messages(AB).single { it.clientMsgId == b.clientMsgId }
        assertEquals(MessageStatus.SENDING, bNow().status)
        c.ack("send", obj("msgId" to "m9", "convId" to AB, "seq" to 9, "ts" to 9))
        assertEquals(MessageStatus.SENT, bNow().status)
        assertEquals(SendError.Code(4510), h.im.messages(AB).single { it.clientMsgId == x.clientMsgId }.error)
    }

    @Test
    fun `SourceMissing 的也不能重发（只能删除）`() {
        val (h, _) = online()
        val m = Message(clientMsgId = "x", convId = AB, from = "alice", type = MsgType.IMAGE, ts = 1, status = MessageStatus.FAILED, error = SendError.SourceMissing)
        assertFalse(h.im.canRetry(m))
        assertTrue(h.im.canRetry(m.copy(error = SendError.Network)))
        assertTrue(h.im.canRetry(m.copy(error = SendError.Code(4040))))
    }

    @Test
    fun `冷启动：4510 的失败消息带失败原因进本地库，重启后仍是 4510、不能重发，连上后也不自动发`() {
        val db = File(dir, "db").apply { mkdirs() }
        val h = setup(database = tempDb(db), timings = QUIET)
        h.connect()
        val c = h.login("alice", listOf(conv(AB, maxSeq = 2)))
        h.call { send(AB, "ZXQWORD 一条") }
        c.ackErr("send", 4510)
        val id = h.im.messages(AB).single().clientMsgId
        h.call { close() }

        val h2 = setup(database = tempDb(db), timings = QUIET)
        val m = h2.im.messages(AB).single { it.clientMsgId == id }
        assertEquals(MessageStatus.FAILED, m.status)
        assertEquals(SendError.Code(4510), m.error)
        assertFalse(h2.im.canRetry(m))
        h2.connect()
        val c2 = h2.login("alice", listOf(conv(AB, maxSeq = 2)))
        h2.call { retry(AB, id) }
        h2.clock.advance(60_000)
        assertEquals(emptyList(), c2.frames("send"))
        assertEquals(listOf("$id:failed(4510)"), h2.status(AB))
    }

    @Test
    fun `超时之后才到的 4510 也写进本地库：冷启动后仍是 4510`() {
        val db = File(dir, "db2").apply { mkdirs() }
        val h = setup(database = tempDb(db), timings = QUIET)
        h.connect()
        val c = h.login("alice", listOf(conv(AB, maxSeq = 2)))
        h.call { send(AB, "a") }
        val reqId = c.last("send")["id"]!!.str
        h.clock.advance(10_000)
        c.receive(obj("id" to reqId, "cmd" to "ack", "error" to obj("code" to 4510, "msg" to "content blocked")))
        h.call { close() }
        val h2 = setup(database = tempDb(db), timings = QUIET)
        assertEquals(SendError.Code(4510), h2.im.messages(AB).single().error)
    }

    @Test
    fun `其他 WS 命令（建群）的 4510 原样抛出，不带 details；文案用非消息那句（P-48 ⑥）`() {
        val (h, c) = online()
        val p = h.start { createGroup(listOf("bob"), "群 ZXQWORD") }
        c.ackBlocked("groupCreate")
        val e = h.failure(p)
        assertIs<IMError>(e)
        assertEquals(4510, e.code)
        assertNull(e.details)
        for (x in listOf("groupCreate", "groupUpdate", "friendApply", "register", "profile")) assertEquals("内容包含不当信息，请修改后重试", messageFor(x, 4510), x)
        assertEquals("消息包含不当内容，无法发送", messageFor("send", 4510))
        assertFalse(describeError(4510).retryable)
    }

    // ---- 账号接口的 4510（注册、改昵称，HTTP 451） ----

    @Test
    fun `register、updateProfile：AccountError {status 451, code 4510}，没有 details；会话不受影响`() {
        val a = setupAccount()
        val p = a.start { register("mod_user", "Password-1", nickname = "昵称 ZXQWORD", inviteCode = "INV1") }
        a.http.last("/v1/account/register").respondError(451, 4510, msg = "content blocked")
        val e = a.failure<AccountError>(p)
        assertEquals(451, e.status)
        assertEquals(4510, e.code)
        assertNull(e.details)
        assertNull(a.store.saved)

        val b = setupAccount()
        val s = b.session(1, DAY)
        val q = b.start { updateProfile(nickname = "新 ZXQWORD") }
        b.http.last("/v1/account/profile").respondError(451, 4510, msg = "content blocked")
        val e2 = b.failure<AccountError>(q)
        assertEquals(451 to 4510, e2.status to e2.code)
        assertNull(e2.details)
        assertEquals(s, b.store.saved)
        for (x in listOf(a, b)) assertFalse(x.h.logs.any { "ZXQWORD" in it }, x.h.logs.toString())
    }

    // ---- features.moderation（backend-chat 第 96 条，frontend-chat 第 76 条） ----

    @Test
    fun `features 解析出 moderation；老服务端没有这个字段按关闭`() {
        for ((features, want) in listOf(
            obj("files" to true, "moderation" to true) to true,
            obj("files" to true, "moderation" to false) to false,
            obj("files" to true) to false,
        )) {
            val a = setupAccount()
            val p = a.start { getConfig() }
            val limits = obj(
                "usernameMinLen" to 4, "usernameMaxLen" to 32, "passwordMinLen" to 8,
                "passwordMaxLen" to 128, "nicknameMaxLen" to 32, "deviceNameMaxLen" to 64,
            )
            a.http.last("/v1/config").respond(200, obj("registerMode" to "open", "demoLogin" to false, "features" to features, "limits" to limits))
            assertEquals(want, a.await(p).features.moderation, features.toString())
        }
    }
}

/** 4510 的 ack（不带 details），给其他测试文件用。 */
internal fun com.tianting.sdk.testing.FakeConnection.ackBlocked(cmd: String) =
    receive(obj("id" to last(cmd)["id"]!!.str, "cmd" to "ack", "error" to obj("code" to ErrorCode.CONTENT_BLOCKED, "msg" to "content blocked")))
