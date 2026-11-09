// FE-631：图片和文件（PRD-chat F-FILE-01、03、04、06、07，F-RCL-03 规则 2、2a）。语义同 sdk-ts 的 test/files.test.ts。
// 假 HTTP + 假对象存储（FakeObjects）+ FakeTransport + 假时钟 + 真 SQLite（冷启动、升级）。测试名带 TC-FILE 编号。本地单测结果，CI 不运行。
package com.tianting.sdk

import com.tianting.sdk.jdbc.JdbcLocalDatabase
import com.tianting.sdk.testing.FakeConnection
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.async
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val AB = "c2c:$USER:bob"
private const val G1 = "group:g1"
private const val UPLOADS = "/v1/files/uploads"
private const val URLS = "/v1/files/urls"
private const val SIG = "X-Amz-Signature=deadbeef"
private const val SECRET_NAME = "ZXQ-FILE-NAME.pdf"

class FilesTest : AccountTest() {
    @TempDir
    lateinit var tmp: File

    private val made = ArrayList<AccountHarness>()

    @AfterEach
    fun noSecretsInLogs() {
        // AC-FILE-04-11：日志里没有预签名地址、签名、文件名。
        for (a in made) {
            val logs = a.logs.joinToString("\n")
            assertFalse("X-Amz" in logs, logs)
            assertFalse("s3.test" in logs, logs)
            assertFalse("ZXQ-FILE" in logs, logs)
        }
    }

    private fun online(database: LocalDatabase? = null): Pair<AccountHarness, FakeConnection> {
        val a = setupAccount(database).also { made.add(it) }
        a.session(1, DAY)
        return a to a.online()
    }

    private var n = 0
    private fun tmpFile(bytes: Int, fill: Int = 1): File = File(tmp, "src-${++n}").apply { writeBytes(ByteArray(bytes) { fill.toByte() }) }

    private fun image(size: Int = 1000): ProcessedImage = ProcessedImage(
        LocalFile(tmpFile(size), "image/jpeg"),
        LocalFile(tmpFile(100, 2), "image/jpeg"),
        4000,
        3000,
    )

    private fun put(key: String) = obj("url" to "https://s3.test/chat/tmp/$key?$SIG", "headers" to obj("Content-Type" to "image/jpeg", "Content-Length" to "1000"), "expiresAt" to 1)

    private fun AccountHarness.grant(fileId: String, thumb: Boolean = true) {
        http.last(UPLOADS).respond(200, objNoNull("fileId" to fileId, "put" to put("$fileId-o"), "thumbPut" to if (thumb) put("$fileId-t") else null))
    }

    private fun AccountHarness.completeOk(fileId: String) =
        http.last("/v1/files/$fileId/complete").respond(200, obj("fileId" to fileId, "size" to 1000, "mime" to "image/jpeg", "width" to 4000, "height" to 3000))

    /** 走完一个文件的上传：申请 → PUT 原图（缩略图）→ complete。 */
    private fun AccountHarness.uploadOk(fileId: String, thumb: Boolean = true) {
        grant(fileId, thumb)
        h.objects.pending[0].respond(200)
        if (thumb) h.objects.pending[0].respond(200)
        completeOk(fileId)
    }

    private fun AccountHarness.sendImage(convId: String = AB, p: ProcessedImage = image(), progress: UploadProgress? = null): Message =
        h.call { sendImage(convId, p, progress) }

    private fun sends(c: FakeConnection) = c.frames("send")
    private fun AccountHarness.deletes() = http.calls.filter { it.req.method == "DELETE" }.map { it.path }
    private fun AccountHarness.status(convId: String = AB) = h.status(convId)
    private fun AccountHarness.sandbox(): List<File> = h.attachmentDir.listFiles()?.toList() ?: emptyList()

    // ---- 状态机（F-FILE-07 规则 1） ----

    @Test
    fun `发图片：复制进沙盒，申请、PUT 原图和缩略图（签入的头原样带上）、complete，只发 fileId；ack 后已发送，沙盒副本删除`() {
        val (a, c) = online()
        val progress = ArrayList<Pair<Long, Long>>()
        val p = image()
        val m = a.sendImage(p = p) { s, t -> progress.add(s to t) }
        assertEquals(MessageStatus.UPLOADING, m.status)
        assertEquals(ImageBody("", 4000, 3000, 1000, "image/jpeg"), a.im.messages(AB)[0].body.image)
        assertEquals(1, a.sandbox().size) // 规则 2：发送前复制进沙盒
        assertEquals(obj("kind" to "image", "name" to "image", "size" to 1000, "mime" to "image/jpeg", "thumb" to obj("size" to 100, "mime" to "image/jpeg")), a.http.last(UPLOADS).json)
        assertEquals(jwt("sid1", 1), a.http.last(UPLOADS).bearer)
        a.grant("F1")
        assertEquals(1, a.h.objects.calls.size) // 并发 1：原图完了才传缩略图
        val o = a.h.objects.calls[0]
        assertTrue("F1-o" in o.url)
        assertEquals(mapOf("Content-Type" to "image/jpeg", "Content-Length" to "1000"), o.headers)
        o.sent(500)
        o.respond(200)
        assertTrue("F1-t" in a.h.objects.calls[1].url)
        a.h.objects.calls[1].respond(200)
        assertEquals(listOf(500L to 1100L, 1000L to 1100L, 1100L to 1100L), progress)
        assertEquals(0, sends(c).size)
        a.http.last("/v1/files/F1/complete").respond(200, obj("fileId" to "F1", "size" to 990, "mime" to "image/jpeg", "width" to 4000, "height" to 3000))
        assertEquals(listOf("cid-0001:sending"), a.status())
        assertEquals(
            obj("convType" to "c2c", "to" to "bob", "clientMsgId" to "cid-0001", "type" to "image", "body" to obj("image" to obj("fileId" to "F1"))),
            c.last("send").dataObj,
        )
        c.ack("send", obj("msgId" to "m1", "convId" to AB, "seq" to 1, "ts" to 9))
        assertEquals(listOf("cid-0001:sent#1"), a.status())
        assertEquals(ImageBody("F1", 4000, 3000, 990, "image/jpeg"), a.im.messages(AB)[0].body.image)
        assertEquals(MsgType.IMAGE, a.h.convOf(AB).lastMsg?.type)
        assertEquals(emptyList(), a.sandbox()) // 发出后删除
        assertEquals(0, a.im.pendingCount.value)
    }

    @Test
    fun `处理中：轮到它时才调用处理函数；处理失败 error=Process，不申请上传`() {
        val (a, _) = online()
        var called = 0
        a.h.call { sendImage(AB, 10, 10, { called++; error("decode") }) }
        assertEquals(listOf("cid-0001:failed(process)"), a.status())
        assertEquals(1, called)
        assertEquals(0, a.http.to(UPLOADS).size)
        assertIs<SendError.Process>(a.h.failed().single().error)
    }

    @Test
    fun `处理函数排在上一个上传后面；处理好之后复制进沙盒再上传`() {
        val (a, _) = online()
        a.sendImage()
        var called = false
        val p = image(2000)
        a.h.call { sendImage(AB, null, null, { called = true; p }) }
        assertEquals(listOf("cid-0001:uploading", "cid-0002:processing"), a.status())
        assertFalse(called)
        a.uploadOk("F1")
        assertTrue(called)
        assertEquals(2000L, a.http.last(UPLOADS).json["size"]!!.jsonPrimitive.content.toLong())
        assertEquals(listOf("cid-0001:sending", "cid-0002:uploading"), a.status())
    }

    @Test
    fun `上传中的时间不算 10 秒发送超时（规则 8）`() {
        val (a, c) = online()
        a.sendImage()
        a.grant("F1")
        a.clock.advance(60_000) // PUT 慢：一分钟还在传
        assertEquals(listOf("cid-0001:uploading"), a.status())
        a.h.objects.pending[0].respond(200)
        a.h.objects.pending[0].respond(200)
        a.completeOk("F1")
        a.clock.advance(9_999)
        assertEquals(listOf("cid-0001:sending"), a.status())
        a.clock.advance(1)
        assertEquals(listOf("cid-0001:failed(timeout)"), a.status())
        assertEquals(1, sends(c).size)
    }

    @Test
    fun `发文件：kind=file，没有缩略图，文件名只在请求体里`() {
        val (a, c) = online()
        a.h.call { sendFile(G1, FileInput(tmpFile(5000), SECRET_NAME, "application/pdf")) }
        assertEquals(obj("kind" to "file", "name" to SECRET_NAME, "size" to 5000, "mime" to "application/pdf"), a.http.last(UPLOADS).json)
        a.grant("F9", thumb = false)
        a.h.objects.pending[0].respond(200)
        assertEquals(1, a.h.objects.calls.size)
        a.http.last("/v1/files/F9/complete").respond(200, obj("fileId" to "F9", "size" to 5000, "mime" to "application/pdf"))
        assertEquals(obj("file" to obj("fileId" to "F9")), c.last("send").dataObj["body"])
        c.ack("send", obj("msgId" to "m1", "convId" to G1, "seq" to 1, "ts" to 9))
        assertEquals(FileBody("F9", SECRET_NAME, 5000, "application/pdf"), a.im.messages(G1)[0].body.file)
    }

    @Test
    fun `复制进沙盒失败：失败，error=Storage（存储空间不足），不申请上传`() {
        val (a, _) = online()
        a.h.call { sendFile(AB, FileInput(File(tmp, "missing"), "a.txt", "text/plain")) }
        assertEquals(listOf("cid-0001:failed(storage)"), a.status())
        assertEquals(0, a.http.to(UPLOADS).size)
    }

    @Test
    fun `complete 之后断线：待发送（queued），连上后自动发一次（规则 3）`() {
        val (a, c) = online()
        a.sendImage()
        a.grant("F1")
        a.h.objects.pending[0].respond(200)
        a.h.objects.pending[0].respond(200)
        c.drop(1006)
        a.completeOk("F1")
        assertEquals(listOf("cid-0001:queued"), a.status())
        a.clock.advance(1_000)
        val c2 = a.authNew()
        assertEquals(obj("image" to obj("fileId" to "F1")), c2.last("send").dataObj["body"])
        assertEquals(1, a.http.to(UPLOADS).size)
    }

    // ---- TC-FILE-01-12、01-17 ----

    @Test
    fun `TC-FILE-01-12 4292：标记失败，只有 1 个申请请求，不自动重试（AC-FILE-01-12）`() {
        val (a, _) = online()
        a.sendImage()
        a.http.last(UPLOADS).respondError(429, 4292)
        a.clock.advance(5_000)
        assertEquals(listOf("cid-0001:failed(4292)"), a.status())
        assertEquals(SendError.Code(4292), a.h.failed().single().error)
        assertEquals(1, a.http.to(UPLOADS).size)
        assertEquals(1, a.sandbox().size) // 失败的保留沙盒副本，供手动重发
    }

    @Test
    fun `TC-FILE-01-17 4290 uploadPendingLimit：失败带 reason，只有 1 个请求；用户取消上传时发 DELETE（AC-FILE-01-17）`() {
        val (a, c) = online()
        a.h.call { sendFile(AB, FileInput(tmpFile(10), "a.txt", "text/plain")) }
        a.http.last(UPLOADS).respondError(429, 4290, reason = "uploadPendingLimit")
        a.clock.advance(5_000)
        val m = a.im.messages(AB)[0]
        assertEquals(SendError.Code(4290), m.error)
        assertEquals("uploadPendingLimit", m.errorReason)
        assertEquals(1, a.http.to(UPLOADS).size)
        val m2 = a.h.call { sendFile(AB, FileInput(tmpFile(10), "b.txt", "text/plain")) }
        a.grant("F2", thumb = false)
        assertEquals(1, a.h.objects.pending.size)
        assertTrue(a.h.call { cancelUpload(AB, m2.clientMsgId) })
        assertTrue(a.h.objects.calls.last().cancelled)
        assertEquals(listOf("/v1/files/uploads/F2"), a.deletes())
        assertEquals(listOf("cid-0001"), a.im.messages(AB).map { it.clientMsgId })
        assertEquals(0, sends(c).size)
        assertEquals(1, a.sandbox().size) // 取消的副本删了，失败的还在
    }

    // ---- TC-FILE-02-05 ----

    @Test
    fun `TC-FILE-02-05 PUT 到一半取消：消息消失，不发 send，发 DELETE；迟到的 PUT 结果不会让它回来（AC-FILE-02-5）`() {
        val (a, c) = online()
        val m = a.sendImage()
        a.grant("F1")
        val o = a.h.objects.pending[0]
        o.sent(400)
        assertTrue(a.h.call { cancelUpload(AB, m.clientMsgId) })
        assertEquals(emptyList(), a.im.messages(AB))
        assertEquals(listOf("/v1/files/uploads/F1"), a.deletes())
        o.respond(200)
        a.clock.advance(60_000)
        assertEquals(0, sends(c).size)
        assertEquals(0, a.http.to("/v1/files/F1/complete").size)
        assertEquals(0, a.im.pendingCount.value)
        assertEquals(emptyList(), a.sandbox())
    }

    // ---- TC-FILE-07-02 ----

    @Test
    fun `TC-FILE-07-02 complete 成功但 send 超时：手动重发复用同一 fileId、clientMsgId，没有新的申请和 PUT（AC-FILE-07-2）`() {
        val (a, c) = online()
        a.sendImage()
        a.uploadOk("F1")
        a.clock.advance(10_000)
        assertEquals(listOf("cid-0001:failed(timeout)"), a.status())
        a.h.call { retry(AB, "cid-0001") }
        val s = sends(c)
        assertEquals(2, s.size)
        assertEquals(s[0]["data"], s[1]["data"])
        assertEquals(1, a.http.to(UPLOADS).size)
        assertEquals(2, a.h.objects.calls.size)
        c.ack("send", obj("msgId" to "m1", "convId" to AB, "seq" to 1, "ts" to 9))
        assertEquals(listOf("cid-0001:sent#1"), a.status())
    }

    // ---- TC-FILE-07-04 ----

    @Test
    fun `TC-FILE-07-04 文字越过上传中的图片；3 张图依次上传（同时只有 1 个），send 顺序和选择顺序相同（AC-FILE-07-4）`() {
        val (a, c) = online()
        a.sendImage(p = image(1000))
        a.sendImage(p = image(2000))
        a.sendImage(p = image(3000))
        assertEquals(1, a.http.to(UPLOADS).size)
        a.h.call { send(AB, "先到") }
        assertEquals("text", c.last("send").dataObj["type"]!!.str)
        c.ack("send", obj("msgId" to "t", "convId" to AB, "seq" to 1, "ts" to 1))
        assertEquals("cid-0004:sent#1", a.status()[0])
        listOf("F1", "F2", "F3").forEachIndexed { i, id ->
            assertEquals(i + 1, a.http.to(UPLOADS).size)
            assertEquals((1000 * (i + 1)).toString(), a.http.last(UPLOADS).json["size"]!!.str)
            assertTrue(a.h.objects.pending.size <= 1)
            a.uploadOk(id)
            assertEquals("cid-000${i + 1}", c.last("send").dataObj["clientMsgId"]!!.str)
            c.ack("send", obj("msgId" to "m$id", "convId" to AB, "seq" to i + 2, "ts" to 2 + i))
        }
        assertEquals(listOf("cid-0004:sent#1", "cid-0001:sent#2", "cid-0002:sent#3", "cid-0003:sent#4"), a.status())
    }

    // ---- TC-FILE-07-07 ----

    @Test
    fun `TC-FILE-07-07 PUT 连续失败 5 次：每次失败，不调 complete（不扣额度），每次从头申请，放弃的上传发 DELETE（AC-FILE-07-7）`() {
        val (a, c) = online()
        a.sendImage()
        for (i in 1..5) {
            a.grant("F$i")
            a.h.objects.pending[0].fail()
            assertEquals(listOf("cid-0001:failed(network)"), a.status())
            if (i < 5) a.h.call { retry(AB, "cid-0001") }
        }
        assertEquals(5, a.http.to(UPLOADS).size)
        assertEquals(0, a.http.calls.count { it.path.endsWith("/complete") })
        assertEquals(5, a.deletes().size)
        assertEquals(0, sends(c).size)
    }

    @Test
    fun `对象存储拒绝 PUT（403，地址过期）同样按失败`() {
        val (a, _) = online()
        a.sendImage()
        a.grant("F1")
        a.h.objects.pending[0].respond(403)
        assertEquals(listOf("cid-0001:failed(network)"), a.status())
    }

    // ---- complete 5032 ----

    private fun AccountHarness.toComplete() {
        sendImage()
        grant("F1")
        h.objects.pending[0].respond(200)
        h.objects.pending[0].respond(200)
    }

    @Test
    fun `complete 5032：1、2、4 秒后重试，第 4 次还是 5032 就失败`() {
        val (a, _) = online()
        a.toComplete()
        fun complete() = a.http.to("/v1/files/F1/complete")
        listOf(1_000L, 2_000L, 4_000L).forEachIndexed { i, wait ->
            complete().last().respondError(503, 5032, reason = "completeInProgress")
            a.clock.advance(wait - 1)
            assertEquals(i + 1, complete().size)
            a.clock.advance(1)
            assertEquals(i + 2, complete().size)
        }
        complete().last().respondError(503, 5032)
        assertEquals(listOf("cid-0001:failed(5032)"), a.status())
        a.clock.advance(60_000)
        assertEquals(4, complete().size)
    }

    @Test
    fun `complete 4096（缩略图没传上）：失败，不重试`() {
        val (a, _) = online()
        a.toComplete()
        a.http.last("/v1/files/F1/complete").respondError(409, 4096)
        assertEquals(listOf("cid-0001:failed(4096)"), a.status())
    }

    // ---- TC-FILE-07-10 ----

    @Test
    fun `TC-FILE-07-10 send 返回 4096：用沙盒副本重新上传（新 fileId），clientMsgId 不变，不占自动重发次数（AC-FILE-07-10）`() {
        val (a, c) = online()
        a.sendImage()
        a.uploadOk("F1")
        c.ackError("send", 4096)
        assertEquals(listOf("cid-0001:uploading"), a.status())
        assertEquals("", a.im.messages(AB)[0].body.image!!.fileId)
        a.uploadOk("F2")
        assertEquals("cid-0001", c.last("send").dataObj["clientMsgId"]!!.str)
        assertEquals(obj("image" to obj("fileId" to "F2")), c.last("send").dataObj["body"])
        c.drop(1006)
        assertEquals(listOf("cid-0001:queued"), a.status())
        a.clock.advance(1_000)
        val c2 = a.authNew()
        assertEquals("cid-0001", c2.last("send").dataObj["clientMsgId"]!!.str)
    }

    // ---- 被踢、退出 ----

    @Test
    fun `被踢：上传中的中止，标记失败（kicked），沙盒副本一起清除`() {
        val (a, c) = online()
        a.sendImage()
        a.grant("F1")
        a.kicked(c, "banned")
        assertEquals(listOf("cid-0001:failed(kicked)"), a.status())
        assertTrue(a.h.objects.calls[0].cancelled)
        assertEquals(emptyList(), a.sandbox())
    }

    @Test
    fun `TC-FILE-07-09 9 个失败的文件，退出登录：沙盒副本和下载缓存都清除（AC-FILE-07-9）`() {
        val (a, _) = online()
        repeat(9) {
            a.h.call { sendFile(AB, FileInput(tmpFile(10), "f$it.txt", "text/plain")) }
            a.http.pending.first { it.path == UPLOADS }.respondError(429, 4292)
        }
        assertEquals(9, a.im.messages(AB).count { it.status == MessageStatus.FAILED })
        assertEquals(9, a.sandbox().size)
        a.h.cacheDir.mkdirs()
        File(a.h.cacheDir, "f-x-thumb").writeText("cached")
        a.h.call { disconnect() }
        assertEquals(emptyList(), a.sandbox())
        assertEquals(emptyList(), a.h.cacheDir.listFiles()?.toList() ?: emptyList())
    }

    // ---- 冷启动（AC-FILE-07-1、07-5）和本地库升级 ----

    @Test
    fun `TC-FILE-07-01、07-05 上传中杀 App：重启后失败、不自动上传；原图删了也能用沙盒副本重发，成功后副本删除`() {
        val dbDir = File(tmp, "db").apply { mkdirs() }
        val root = File(tmp, "root")
        val a = AccountHarness(setup(database = JdbcLocalDatabase(File(dbDir, "im.db")), clock = com.tianting.sdk.testing.FakeClock(START_MS), timings = IMClientTimings(heartbeatMs = 10 * 365 * DAY), filesRoot = root)).also { made.add(it) }
        a.session(1, DAY)
        a.online()
        val src = image()
        a.sendImage(p = src)
        a.grant("F1")
        a.h.objects.pending[0].sent(300)
        a.h.call { close() } // 杀进程：没有机会收尾
        src.original.file.delete() // 系统相册里删了原图（AC-FILE-07-5）
        src.thumb.file.delete()

        val b = AccountHarness(setup(database = JdbcLocalDatabase(File(dbDir, "im.db")), clock = com.tianting.sdk.testing.FakeClock(START_MS + 60_000), timings = IMClientTimings(heartbeatMs = 10 * 365 * DAY), filesRoot = root)).also { made.add(it) }
        assertEquals(listOf("cid-0001:failed(network)"), b.status())
        b.session(1, DAY)
        val c = b.online()
        b.clock.advance(10_000)
        assertEquals(0, b.http.to(UPLOADS).size) // 没有自动上传（规则 3）
        assertEquals(listOf("cid-0001:failed(network)"), b.status())
        b.h.call { retry(AB, "cid-0001") }
        assertEquals(1000L, b.http.last(UPLOADS).json["size"]!!.str.toLong())
        b.uploadOk("F2") // 重发从头上传：新的 fileId
        assertContentEquals(ByteArray(1000) { 1 }, b.h.objects.puts[0].bytes) // 沙盒副本的内容
        assertEquals("cid-0001", c.last("send").dataObj["clientMsgId"]!!.str)
        c.ack("send", obj("msgId" to "m1", "convId" to AB, "seq" to 1, "ts" to 9))
        assertEquals(listOf("cid-0001:sent#1"), b.status())
        assertEquals(emptyList(), b.sandbox())
    }

    private fun restartable(dbDir: File, root: File, at: Long) =
        AccountHarness(setup(database = JdbcLocalDatabase(File(dbDir, "im.db")), clock = com.tianting.sdk.testing.FakeClock(at), timings = IMClientTimings(heartbeatMs = 10 * 365 * DAY), filesRoot = root)).also { made.add(it) }

    @Test
    fun `D3 冷启动：待发送的附件按排队，连上后自动发 1 次，同一 clientMsgId，不重新上传；排队的文字照旧转失败（F-FILE-07 规则 3）`() {
        val dbDir = File(tmp, "db").apply { mkdirs() }
        val root = File(tmp, "root")
        val a = restartable(dbDir, root, START_MS)
        a.session(1, DAY)
        val c = a.online()
        a.sendImage()
        a.grant("F1")
        a.h.objects.pending[0].respond(200)
        a.h.objects.pending[0].respond(200)
        c.drop(1006)
        a.completeOk("F1")
        a.h.call { send(AB, "离线的文字") }
        assertEquals(listOf("cid-0001:queued", "cid-0002:queued"), a.status())
        a.h.call { close() } // 杀进程

        val b = restartable(dbDir, root, START_MS + 60_000)
        assertEquals(listOf("cid-0001:queued", "cid-0002:failed(timeout)"), b.status())
        b.session(1, DAY)
        val c2 = b.online()
        val sent = c2.frames("send")
        assertEquals(1, sent.size, "只自动发待发送的附件")
        assertEquals("cid-0001", sent[0].dataObj["clientMsgId"]!!.str)
        assertEquals(obj("image" to obj("fileId" to "F1")), sent[0].dataObj["body"])
        assertEquals(0, b.http.to(UPLOADS).size, "不重新上传")
        c2.ack("send", obj("msgId" to "m1", "convId" to AB, "seq" to 1, "ts" to 9))
        assertEquals(listOf("cid-0001:sent#1", "cid-0002:failed(timeout)"), b.status())
        assertEquals(emptyList(), b.sandbox())
    }

    @Test
    fun `D3 冷启动：有 fileId 的发送中（send 没有 ack）同样按排队自动发 1 次；再断线不再自动发`() {
        val dbDir = File(tmp, "db").apply { mkdirs() }
        val root = File(tmp, "root")
        val a = restartable(dbDir, root, START_MS)
        a.session(1, DAY)
        val c = a.online()
        a.sendImage()
        a.uploadOk("F1")
        assertEquals(1, c.frames("send").size)
        assertEquals(listOf("cid-0001:sending"), a.status())
        a.h.call { close() }

        val b = restartable(dbDir, root, START_MS + 60_000)
        assertEquals(listOf("cid-0001:queued"), b.status())
        b.session(1, DAY)
        val c2 = b.online()
        assertEquals(listOf("cid-0001"), c2.frames("send").map { it.dataObj["clientMsgId"]!!.str })
        c2.drop(1006) // 发出后又断了：已经自动发过 1 次（规则 3 只发 1 次），转失败由用户重发
        b.clock.advance(1_000)
        val c3 = b.authNew()
        assertEquals(0, c3.frames("send").size)
        assertEquals(listOf("cid-0001:failed(timeout)"), b.status())
        assertEquals(0, b.http.to(UPLOADS).size)
    }

    @Test
    fun `沙盒副本也没了（文件被删）：重发不上传，error=SourceMissing（原文件已不可用）`() {
        val (a, _) = online()
        a.sendImage()
        a.http.last(UPLOADS).respondError(500, 5000)
        a.h.attachmentDir.listFiles()!!.forEach { it.deleteRecursively() }
        a.h.call { retry(AB, "cid-0001") }
        assertEquals(listOf("cid-0001:failed(sourceMissing)"), a.status())
        assertEquals(1, a.http.to(UPLOADS).size)
    }

    @Test
    fun `本地库第 1 版升级到第 2 版：已有的会话和消息（含失败的）保留`() {
        val file = File(tmp, "v1.db")
        JdbcLocalDatabase(file).use { db ->
            db.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execute("CREATE TABLE conversations(conv_id TEXT PRIMARY KEY, read_base INTEGER NOT NULL, placeholder INTEGER NOT NULL, json TEXT NOT NULL)")
            db.execute("CREATE TABLE messages(conv_id TEXT NOT NULL, row_key TEXT NOT NULL, seq INTEGER, json TEXT NOT NULL, PRIMARY KEY(conv_id, row_key))")
            db.execute("CREATE TABLE deleted_refs(conv_id TEXT NOT NULL, kind TEXT NOT NULL, ref TEXT NOT NULL, PRIMARY KEY(conv_id, kind, ref))")
            db.execute("CREATE TABLE social_meta(id INTEGER PRIMARY KEY CHECK(id = 1), social_ver INTEGER NOT NULL, request_seen_ver INTEGER NOT NULL, settings TEXT NOT NULL, official INTEGER NOT NULL, loaded INTEGER NOT NULL)")
            db.execute("CREATE TABLE friends(user_id TEXT PRIMARY KEY, ver INTEGER NOT NULL, json TEXT NOT NULL)")
            db.execute("CREATE TABLE friend_requests(user_id TEXT NOT NULL, direction TEXT NOT NULL, ver INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(user_id, direction))")
            db.execute("CREATE TABLE blocks(user_id TEXT PRIMARY KEY, ver INTEGER NOT NULL, json TEXT NOT NULL)")
            db.execute("PRAGMA user_version = 1")
            db.execute("INSERT INTO meta(key, value) VALUES('owner', 'alice')")
            db.execute(
                "INSERT INTO conversations(conv_id, read_base, placeholder, json) VALUES(?, 0, 0, ?)",
                listOf("c2c:alice:bob", """{"convId":"c2c:alice:bob","type":"c2c","name":"bob","maxSeq":1,"readSeq":1,"minSeq":1,"unread":0,"updatedAt":5}"""),
            )
            db.execute(
                "INSERT INTO messages(conv_id, row_key, seq, json) VALUES(?, 's1', 1, ?)",
                listOf("c2c:alice:bob", """{"clientMsgId":"x1","msgId":"m1","seq":1,"convId":"c2c:alice:bob","from":"bob","type":"text","body":{"text":"old"},"ts":5,"status":"SENT"}"""),
            )
            db.execute(
                "INSERT INTO messages(conv_id, row_key, seq, json) VALUES(?, 'cx2', NULL, ?)",
                listOf("c2c:alice:bob", """{"clientMsgId":"x2","convId":"c2c:alice:bob","from":"alice","type":"text","body":{"text":"失败的"},"ts":6,"status":"FAILED","error":"timeout"}"""),
            )
        }
        val h = setup(database = JdbcLocalDatabase(file))
        assertEquals(listOf("x1:sent#1", "x2:failed(timeout)"), h.status("c2c:alice:bob"))
        assertEquals("bob", h.convOf("c2c:alice:bob").name)
        h.call { close() }
        JdbcLocalDatabase(file).use { db ->
            assertEquals(2L, db.query("PRAGMA user_version").single().values.single())
            assertEquals(2, db.query("SELECT conv_id FROM messages").size)
            assertEquals(0, db.query("SELECT * FROM attachments").size)
        }
    }

    // ---- 取地址（F-FILE-04 规则 1、5、6，AC-FILE-04-10） ----

    private fun urlItem(fileId: String, variant: String, now: Long) =
        obj("fileId" to fileId, "variant" to variant, "url" to "https://s3.test/chat/final/$fileId/$variant?$SIG", "expiresAt" to now + 120_000)

    @Test
    fun `60 项分成 50 + 10 两批；同一轮的两次调用合并；第二次（没到期）不再请求；剩余不到 15 秒重新申请`() {
        val (a, _) = online()
        val items = (0 until 60).map { FileUrlItem("F$it", FileVariant.THUMB) }
        // 一屏的两次调用在 SDK 线程的同一轮里：合并
        val p = a.h.callScope.async { a.im.getFileUrls(items.take(30)) }
        val q = a.h.callScope.async { a.im.getFileUrls(items.drop(30)) }
        a.h.flush()
        val calls = a.http.to(URLS)
        assertEquals(listOf(50, 10), calls.map { it.json["items"]!!.jsonArray.size })
        val now = a.clock.now()
        calls[0].respond(200, obj("items" to items.take(50).map { urlItem(it.fileId, "thumb", now) }))
        calls[1].respond(200, obj("items" to items.drop(50).mapIndexed { k, it -> if (k == 0) obj("fileId" to it.fileId, "variant" to "thumb", "code" to 4048) else urlItem(it.fileId, "thumb", now) }))
        val all = a.h.await(p) + a.h.await(q)
        assertTrue("F0/" in all[0].url!!)
        assertEquals(4048, all[50].code)
        assertNull(all[50].url)
        assertTrue("F1/" in a.h.call { getFileUrls(listOf(items[1])) }[0].url!!)
        assertEquals(2, a.http.to(URLS).size)
        a.clock.advance(106_000)
        a.h.start { getFileUrls(listOf(items[1])) }
        assertEquals(3, a.http.to(URLS).size)
    }

    @Test
    fun `TC-ACC-03-42 用原接口：在途的 files urls 收到 4011，本地已有新 token 就用它重试一次（AC-ACC-03-42）`() {
        val (a, _) = online()
        val p = a.h.start { getFileUrls(listOf(FileUrlItem("F1", FileVariant.THUMB))) }
        val first = a.http.last(URLS)
        assertEquals(jwt("sid1", 1), first.bearer)
        a.session(2, DAY) // 等响应期间刷新换了 token
        first.respondError(401, 4011, reason = "tokenSuperseded")
        val calls = a.http.to(URLS)
        assertEquals(2, calls.size)
        assertEquals(jwt("sid1", 2), calls[1].bearer)
        assertEquals(0, a.http.to(REFRESH).size)
        calls[1].respond(200, obj("items" to listOf(urlItem("F1", "thumb", a.clock.now()))))
        assertTrue("F1/" in a.h.await(p)[0].url!!)
    }

    // ---- 下载（第 13.2 节，AC-FILE-03-5、04-10） ----

    @Test
    fun `TC-FILE-03-05 下载到一半断网：失败，部分文件删除；重试重新申请地址、从 0 开始；第二次打开命中缓存（AC-FILE-03-5、04-10）`() {
        val (a, _) = online()
        val content = ByteArray(5000) { (it % 251).toByte() }
        val d1 = a.h.start { download("F1", FileVariant.ORIGINAL) }
        a.http.last(URLS).respond(200, obj("items" to listOf(urlItem("F1", "original", a.clock.now()))))
        a.h.objects.gets[0].failAfter(content.copyOf(2000))
        a.h.flush()
        assertTrue(d1.isCompleted && d1.getCompletionExceptionOrNull() is ObjectTransferError)
        assertEquals(emptyList(), a.h.cacheDir.listFiles()!!.toList()) // 不留部分文件
        val d2 = a.h.start { download("F1", FileVariant.ORIGINAL) }
        assertEquals(2, a.http.to(URLS).size) // 重新申请地址
        a.http.last(URLS).respond(200, obj("items" to listOf(urlItem("F1", "original", a.clock.now()))))
        a.h.objects.gets[1].respond(200, content)
        val f = a.h.await(d2)
        assertContentEquals(content, f.readBytes())
        assertEquals(f, a.h.call { download("F1", FileVariant.ORIGINAL) })
        assertEquals(2, a.h.objects.gets.size) // 命中缓存，不再下载
        assertEquals(2, a.http.to(URLS).size)
        assertFalse(holds(storeOf(a.im), "s3.test"))
    }

    @Test
    fun `下载地址过期（403）：重新申请一次再下`() {
        val (a, _) = online()
        val d = a.h.start { download("F1", FileVariant.THUMB) }
        a.http.last(URLS).respond(200, obj("items" to listOf(urlItem("F1", "thumb", a.clock.now()))))
        a.h.objects.gets[0].respond(403)
        a.h.flush()
        assertEquals(2, a.http.to(URLS).size)
        a.http.last(URLS).respond(200, obj("items" to listOf(urlItem("F1", "thumb", a.clock.now()))))
        a.h.objects.gets[1].respond(200, byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), a.h.await(d).readBytes())
    }

    @Test
    fun `不可访问：download 抛 IMError 4048`() {
        val (a, _) = online()
        val d = a.h.start { download("F1", FileVariant.THUMB) }
        a.http.last(URLS).respond(200, obj("items" to listOf(obj("fileId" to "F1", "variant" to "thumb", "code" to 4048))))
        a.h.flush()
        assertEquals(4048, (d.getCompletionExceptionOrNull() as IMError).code)
    }

    // ---- TC-FILE-06-11 modVer 和单调合并（AC-FILE-06-12，F-RCL-03 规则 2、2a） ----

    private fun img(convId: String, seq: Long, fileId: String, expired: Boolean = false): JsonObject = obj(
        "msgId" to "$convId#$seq", "clientMsgId" to "bob-$convId-$seq", "convId" to convId, "seq" to seq, "from" to "bob", "type" to "image",
        "body" to obj("image" to objNoNull("fileId" to fileId, "width" to 10, "height" to 10, "size" to 100, "mime" to "image/png", "expired" to if (expired) true else null)),
        "ts" to 1000 + seq,
    )

    private fun fileMsg(convId: String, seq: Long, fileId: String, expired: Boolean = false): JsonObject = obj(
        "msgId" to "$convId#$seq", "clientMsgId" to "bob-$convId-$seq", "convId" to convId, "seq" to seq, "from" to "bob", "type" to "file",
        "body" to obj("file" to objNoNull("fileId" to fileId, "name" to if (expired) "" else SECRET_NAME, "size" to 100, "mime" to "application/pdf", "expired" to if (expired) true else null)),
        "ts" to 1000 + seq,
    )

    private fun loaded(database: LocalDatabase? = null, root: File? = null): Pair<AccountHarness, FakeConnection> {
        val a = AccountHarness(setup(database = database, clock = com.tianting.sdk.testing.FakeClock(START_MS), timings = IMClientTimings(heartbeatMs = 10 * 365 * DAY), filesRoot = root)).also { made.add(it) }
        a.session(1, DAY)
        a.connect()
        val c1 = a.h.login(USER, listOf(conv(AB, maxSeq = 2, modVer = 3), conv(G1, maxSeq = 1, modVer = 7)))
        a.h.start { loadHistory(AB) }
        c1.ack("sync", obj("messages" to listOf(img(AB, 1, "F1"), fileMsg(AB, 2, "F2")), "hasMore" to false))
        a.h.start { loadHistory(G1) }
        c1.ack("sync", obj("messages" to listOf(img(G1, 1, "G1F")), "hasMore" to false))
        // F1 的缩略图已缓存（地址、下载缓存）
        val d = a.h.start { download("F1", FileVariant.THUMB) }
        a.http.last(URLS).respond(200, obj("items" to listOf(urlItem("F1", "thumb", a.clock.now()))))
        a.h.objects.gets.last().respond(200, byteArrayOf(9))
        a.h.await(d)
        return a to c1
    }

    @Test
    fun `TC-FILE-06-11 重连时 AB 的 modVer +1、G1 不变：只对 AB 发 sync；变成已过期，地址和下载缓存清除，FilesExpired 通知 App`() {
        val (a, c1) = loaded()
        assertEquals(1, a.h.cacheDir.listFiles()!!.size)
        c1.drop(1006)
        a.clock.advance(1_000)
        val c2 = a.h.login(USER, listOf(conv(AB, maxSeq = 2, modVer = 4), conv(G1, maxSeq = 1, modVer = 7)))
        assertEquals(listOf(AB), c2.frames("sync").map { it.dataObj["convId"]!!.str })
        c2.ack("sync", obj("messages" to listOf(img(AB, 1, "F1", true), fileMsg(AB, 2, "F2", true)), "hasMore" to false))
        val (m1, m2) = a.im.messages(AB)
        assertEquals(ImageBody("F1", 10, 10, 100, "image/png", expired = true), m1.body.image)
        assertEquals(FileBody("F2", "", 100, "application/pdf", expired = true), m2.body.file)
        assertEquals(listOf("F1", "F2"), a.h.events.filterIsInstance<IMEvent.FilesExpired>().flatMap { it.fileIds }.sorted())
        assertEquals(emptyList(), a.h.cacheDir.listFiles()!!.toList())
        // 已知过期：不发申请地址的请求（AC-FILE-06-6 的 SDK 部分）
        val n = a.http.to(URLS).size
        assertEquals(4048, a.h.call { getFileUrls(listOf(FileUrlItem("F1", FileVariant.THUMB))) }.single().code)
        assertEquals(n, a.http.to(URLS).size)
        // 再重连，modVer 没变：不再重拉
        c2.drop(1006)
        a.clock.advance(1_000)
        val c3 = a.h.login(USER, listOf(conv(AB, maxSeq = 2, modVer = 4), conv(G1, maxSeq = 1, modVer = 7)))
        assertEquals(emptyList(), c3.frames("sync"))
        assertFalse(holds(storeOf(a.im), SECRET_NAME))
    }

    @Test
    fun `迟到的旧应答带 expired=false 和文件名：不改回去（单调合并）；删除同样单调`() {
        val (a, c1) = loaded()
        c1.receive(msgFrame(img(AB, 1, "F1", true)))
        assertTrue(a.im.messages(AB)[0].body.image!!.expired)
        c1.receive(msgFrame(img(AB, 1, "F1", false)))
        assertTrue(a.im.messages(AB)[0].body.image!!.expired)
        c1.receive(msgFrame(fileMsg(AB, 2, "F2", true)))
        c1.receive(msgFrame(fileMsg(AB, 2, "F2", false)))
        assertEquals(FileBody("F2", "", 100, "application/pdf", expired = true), a.im.messages(AB)[1].body.file)
        c1.receive(event("msgDeleted", "convId" to G1, "msgIds" to listOf("$G1#1"), "seqs" to listOf(1)))
        c1.receive(msgFrame(img(G1, 1, "G1F")))
        assertTrue(a.im.messages(G1)[0].deleted)
        assertEquals(MsgBody(), a.im.messages(G1)[0].body)
    }

    @Test
    fun `缺口太大只留最新一页之后，向前翻页拿到的旧应答也不把 expired 改回去`() {
        val (a, c1) = loaded()
        c1.receive(msgFrame(img(AB, 1, "F1", true)))
        c1.drop(1006)
        a.clock.advance(1_000)
        val c2 = a.h.login(USER, listOf(conv(AB, maxSeq = 500, modVer = 3), conv(G1, maxSeq = 1, modVer = 7)))
        assertEquals(501L, c2.last("sync").dataObj["beforeSeq"]!!.str.toLong())
        c2.ack("sync", obj("messages" to listOf(msg(AB, 500)), "hasMore" to true))
        assertEquals(listOf(500L), a.im.messages(AB).map { it.seq })
        a.h.start { loadHistory(AB, 200) }
        c2.ack("sync", obj("messages" to listOf(img(AB, 1, "F1", false), msg(AB, 499)), "hasMore" to false))
        assertTrue(a.im.messages(AB)[0].body.image!!.expired)
    }

    @Test
    fun `老服务端（convs 没有 modVer）：仍然全部重拉`() {
        val (a, c1) = loaded()
        c1.drop(1006)
        a.clock.advance(1_000)
        val c2 = a.h.login(USER, listOf(conv(AB, maxSeq = 2), conv(G1, maxSeq = 1)))
        c2.ack("sync", obj("messages" to emptyList<Any>(), "hasMore" to false))
        assertEquals(setOf(AB, G1), c2.frames("sync").map { it.dataObj["convId"]!!.str }.toSet())
    }

    @Test
    fun `modVer 基线存在本地库：冷启动后 modVer 没变的不重拉，变了的重拉；过期标记跨冷启动保留`() {
        val dbDir = File(tmp, "db2").apply { mkdirs() }
        val root = File(tmp, "root2")
        val (a, c1) = loaded(JdbcLocalDatabase(File(dbDir, "im.db")), root)
        c1.receive(msgFrame(img(AB, 1, "F1", true)))
        a.h.call { close() }
        val b = AccountHarness(setup(database = JdbcLocalDatabase(File(dbDir, "im.db")), clock = com.tianting.sdk.testing.FakeClock(START_MS + 1000), timings = IMClientTimings(heartbeatMs = 10 * 365 * DAY), filesRoot = root)).also { made.add(it) }
        b.session(1, DAY)
        b.connect()
        val c = b.h.login(USER, listOf(conv(AB, maxSeq = 2, modVer = 3), conv(G1, maxSeq = 1, modVer = 8)))
        assertEquals(listOf(G1), c.frames("sync").map { it.dataObj["convId"]!!.str })
        // 冷启动后迟到的旧值同样不改回
        c.receive(msgFrame(img(AB, 1, "F1", false)))
        assertTrue(b.im.messages(AB)[0].body.image!!.expired)
        // 缺口太大清掉已加载的范围之后，翻页拿到的旧应答也不改回（过期记录跨冷启动保留在本地库）
        c.drop(1006)
        b.clock.advance(1_000)
        val c2 = b.h.login(USER, listOf(conv(AB, maxSeq = 500, modVer = 3), conv(G1, maxSeq = 1, modVer = 8)))
        c2.ack("sync", obj("messages" to listOf(msg(AB, 500)), "hasMore" to true))
        b.h.start { loadHistory(AB, 200) }
        c2.ack("sync", obj("messages" to listOf(img(AB, 1, "F1", false), msg(AB, 499)), "hasMore" to false))
        assertTrue(b.im.messages(AB)[0].body.image!!.expired)
    }
}
