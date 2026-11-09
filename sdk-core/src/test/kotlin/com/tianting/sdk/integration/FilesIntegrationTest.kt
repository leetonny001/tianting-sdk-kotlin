// FE-631 对真实 im-server（IM_FILES_ENABLED=1）+ 假 S3（fake_s3.py）的集成测试：真 OkHttp（上传、下载）、真协程线程。
// AC-FILE-02-1、03-1 的 sdk-kotlin 部分（发、收、下载内容一致），4292 和取消的真实路径，日志里没有签名和文件名。
// 本地结果，CI 不运行。假 S3 只验调度逻辑，不证明厂商行为（TC-FILE 通用约定）。
package com.tianting.sdk.integration

import com.tianting.sdk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FilesIntegrationTest {
    private lateinit var s3: FakeS3
    private lateinit var srv: TestServer
    private val clients = ArrayList<IMClient>()
    private val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val tmp: File = Files.createTempDirectory("tt-files-it-").toFile()
    private var n = 0
    private val logger = object : Logger {
        override fun debug(message: String) { logs.add(message) }
        override fun warn(message: String) { logs.add(message) }
        override fun error(message: String) { logs.add(message) }
    }

    private fun uid(base: String) = "$base${System.currentTimeMillis().toString(36)}${n++}"

    @BeforeAll
    fun up() = runBlocking {
        s3 = FakeS3.start()
        srv = TestServer.start(s3.env + mapOf("IM_UPLOAD_DAILY_BYTES" to (40L shl 20).toString()))
    }

    @AfterEach
    fun closeClients() = runBlocking {
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    @AfterAll
    fun down() {
        srv.stop()
        s3.stop()
        tmp.deleteRecursively()
        // AC-FILE-04-11：SDK 的日志里没有预签名地址、签名和文件名特征串。
        val all = logs.joinToString("\n")
        assertFalse("X-Amz" in all)
        assertFalse("127.0.0.1" in all)
        assertFalse("ZXQ-FILE" in all)
    }

    private suspend fun login(userId: String): IMClient {
        val token = srv.token(userId)
        val root = File(tmp, "c${n++}")
        val im = IMClient(
            IMClientOptions(
                url = srv.wsURL,
                logger = logger,
                files = FileClient(StaticTokenAuth(srv.httpURL, token)),
                attachmentDir = File(root, "attachments"),
                fileCacheDir = File(root, "cache"),
            ),
        )
        clients.add(im)
        im.connect(token)
        until("$userId connected") { im.state.value == ConnState.CONNECTED && im.userId.value == userId }
        return im
    }

    private suspend fun <T> until(what: String, timeoutMs: Long = 15_000, fn: () -> T?): T = withTimeout(timeoutMs) {
        while (true) {
            val v = fn()
            if (v != null && v != false) return@withTimeout v
            delay(20)
        }
        @Suppress("UNREACHABLE_CODE")
        error(what)
    }

    /** 只有 SOF0 段的 JPEG：服务端嗅探能读到宽高（不解码像素）。 */
    private fun jpeg(width: Int, height: Int, size: Int): File {
        val head = intArrayOf(0xff, 0xd8, 0xff, 0xc0, 0x00, 0x11, 0x08, height shr 8, height and 255, width shr 8, width and 255, 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1)
        val b = ByteArray(size) { ((it * 31) and 0xff).toByte() }
        head.forEachIndexed { i, v -> b[i] = v.toByte() }
        b[size - 2] = 0xff.toByte()
        b[size - 1] = 0xd9.toByte()
        return File(tmp, "img-${n++}.jpg").apply { writeBytes(b) }
    }

    private fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun `TC-FILE-02-01（sdk-kotlin）u1 发 2 MiB jpeg，u2 收到 type=image，先下缩略图再下原图，两边 size、mime、宽高一致`() = runBlocking {
        val a = uid("fa")
        val b = uid("fb")
        srv.importUsers(b to null)
        val u1 = login(a)
        val u2 = login(b)
        val conv = c2cConvId(a, b)
        val original = jpeg(1920, 1080, 2 shl 20)
        val thumb = jpeg(480, 270, 30_000)
        val seen = Collections.synchronizedList(ArrayList<MessageStatus>())
        val m = u1.sendImage(conv, ProcessedImage(LocalFile(original, "image/jpeg"), LocalFile(thumb, "image/jpeg"), 1920, 1080))
        seen.add(m.status)
        val sent = until("u1 sent") { u1.messages(conv).firstOrNull { it.status == MessageStatus.SENT } }
        val got = until("u2 receives") { u2.messages(conv).firstOrNull() }
        assertEquals(MsgType.IMAGE, got.type)
        assertEquals(sent.body.image, got.body.image)
        assertEquals(ImageBody(got.body.image!!.fileId, 1920, 1080, original.length(), "image/jpeg"), got.body.image)
        val t = u2.download(got.body.image!!.fileId, FileVariant.THUMB)
        assertEquals(sha256(thumb), sha256(t))
        val o = u2.download(got.body.image!!.fileId, FileVariant.ORIGINAL)
        assertEquals(sha256(original), sha256(o))
        assertEquals(o, u2.cachedFile(got.body.image!!.fileId, FileVariant.ORIGINAL))
        assertEquals(MsgType.IMAGE, u2.conversations.value.first { it.convId == conv }.lastMsg?.type)
    }

    @Test
    fun `TC-FILE-03-01（sdk-kotlin）群里发 5 MiB pdf，成员下载 SHA-256 相同`() = runBlocking {
        val a = uid("ga")
        val b = uid("gb")
        val c = uid("gc")
        srv.importUsers(b to null, c to null)
        val u1 = login(a)
        val u2 = login(b)
        val groupId = uid("g")
        srv.admin("POST", "/admin/groups", obj("groupId" to groupId, "name" to groupId, "members" to listOf(a, b, c)))
        val conv = groupConvId(groupId)
        until("u1 sees group") { u1.conversations.value.any { it.convId == conv } }
        val pdf = File(tmp, "doc-${n++}.pdf").apply {
            val bytes = ByteArray(5 shl 20) { ((it * 7) and 0xff).toByte() }
            "%PDF-1.7\n".toByteArray().copyInto(bytes)
            writeBytes(bytes)
        }
        val name = "ZXQ-FILE-报告.pdf"
        u1.sendFile(conv, FileInput(pdf, name, "application/pdf"))
        until("u1 sent") { u1.messages(conv).firstOrNull { it.type == MsgType.FILE && it.status == MessageStatus.SENT } }
        val got = until("u2 receives") { u2.messages(conv).firstOrNull { it.type == MsgType.FILE } }
        assertEquals(FileBody(got.body.file!!.fileId, name, pdf.length(), "application/pdf"), got.body.file)
        val d = u2.download(got.body.file!!.fileId, FileVariant.ORIGINAL)
        assertEquals(sha256(pdf), sha256(d))
    }

    @Test
    fun `不是参与者：这一项 code=4048，和不存在的 fileId 相同；download 抛 4048`() = runBlocking {
        val a = uid("xa")
        val b = uid("xb")
        val x = uid("xx")
        srv.importUsers(b to null)
        val u1 = login(a)
        val u3 = login(x)
        val conv = c2cConvId(a, b)
        u1.sendImage(conv, ProcessedImage(LocalFile(jpeg(100, 100, 2000), "image/jpeg"), LocalFile(jpeg(50, 50, 1000), "image/jpeg"), 100, 100))
        val sent = until("sent") { u1.messages(conv).firstOrNull { it.status == MessageStatus.SENT } }
        val res = u3.getFileUrls(listOf(FileUrlItem(sent.body.image!!.fileId, FileVariant.ORIGINAL), FileUrlItem("nonexistent123", FileVariant.ORIGINAL)))
        assertEquals(listOf(4048, 4048), res.map { it.code })
        val e = runCatching { u3.download(sent.body.image!!.fileId, FileVariant.THUMB) }.exceptionOrNull()
        assertEquals(4048, (e as IMError).code)
    }

    @Test
    fun `TC-FILE-01-12（真实）当天额度用完：申请上传 429 4292，消息失败、不重试`() = runBlocking {
        val a = uid("qa")
        val b = uid("qb")
        srv.importUsers(b to null)
        val u1 = login(a)
        val conv = c2cConvId(a, b)
        repeat(2) {
            val big = File(tmp, "q-${n++}.pdf").apply {
                val bytes = ByteArray(20 shl 20)
                "%PDF-1.4\n".toByteArray().copyInto(bytes)
                writeBytes(bytes)
            }
            u1.sendFile(conv, FileInput(big, "q$it.pdf", "application/pdf"))
        }
        until("two sent", 30_000) { u1.messages(conv).count { it.status == MessageStatus.SENT } == 2 }
        u1.sendFile(conv, FileInput(File(tmp, "x-${n++}.txt").apply { writeText("x") }, "x.txt", "text/plain"))
        val failed = until("failed") { u1.messages(conv).firstOrNull { it.status == MessageStatus.FAILED } }
        assertEquals(SendError.Code(4292), failed.error)
        delay(1500)
        assertEquals(1, u1.messages(conv).count { it.status != MessageStatus.SENT })
    }

    @Test
    fun `TC-FILE-02-05（真实）PUT 进行中取消：消息消失，服务端的上传被取消（之后 complete 404）`() = runBlocking {
        val a = uid("ca")
        val b = uid("cb")
        srv.importUsers(b to null)
        val token = srv.token(a)
        var fileId = ""
        // 记下申请到的 fileId：包一层 FileAuth
        val auth = object : FileAuth {
            val inner = StaticTokenAuth(srv.httpURL, token)
            override suspend fun authorized(method: String, path: String, body: kotlinx.serialization.json.JsonElement?): kotlinx.serialization.json.JsonElement {
                val r = inner.authorized(method, path, body)
                if (path == "/v1/files/uploads" && method == "POST") fileId = (r as kotlinx.serialization.json.JsonObject)["fileId"]!!.str
                return r
            }
        }
        val root = File(tmp, "c${n++}")
        val u1 = IMClient(IMClientOptions(url = srv.wsURL, logger = logger, files = FileClient(auth), attachmentDir = File(root, "a"), fileCacheDir = File(root, "c")))
        clients.add(u1)
        u1.connect(token)
        until("connected") { u1.state.value == ConnState.CONNECTED }
        val conv = c2cConvId(a, b)
        val m = u1.sendImage(conv, ProcessedImage(LocalFile(jpeg(800, 600, 8 shl 20), "image/jpeg"), LocalFile(jpeg(80, 60, 1000), "image/jpeg"), 800, 600))
        until("upload granted") { fileId.isNotEmpty() }
        assertTrue(u1.cancelUpload(conv, m.clientMsgId))
        assertEquals(emptyList(), u1.messages(conv))
        delay(500)
        val res = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("${srv.httpURL}/v1/files/$fileId/complete")).header("Authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(404, res.statusCode())
        until("sandbox cleaned") { File(root, "a").listFiles().isNullOrEmpty() }
        Unit
    }
}
