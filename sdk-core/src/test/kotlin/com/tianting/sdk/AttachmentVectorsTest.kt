// TC-CHAT-90-13（AC-CHAT-90-15）：account-session.json 的 attachmentCases（附件发送状态机，PRD-chat F-FILE-07 规则 1、3、4）。
// 真实的 IMClient + FileClient + 假 HTTP + 假对象存储（FakeObjects）+ FakeTransport + 假时钟。语义同 sdk-ts 的 test/attachment-vectors.test.ts。
// persistence=true 的用例（冷启动）也跑：用 JdbcLocalDatabase 同一个库文件、同一个附件根目录新建实例（restart）。
// 别人的 fileId（foreignFileId）：先往本地库写一条已失败（timeout）、带别人 fileId 的图片消息，再新建实例，这台设备没有上传过它。
package com.tianting.sdk

import com.tianting.sdk.jdbc.JdbcLocalDatabase
import com.tianting.sdk.testing.FakeClock
import com.tianting.sdk.testing.FakeConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

private const val AB = "c2c:$USER:bob"
private const val UPLOADS = "/v1/files/uploads"

class AttachmentVectorsTest : AccountTest() {
    private val spec get() = vector("account-session.json").jsonObject

    private inner class Runner(private val c: JsonObject, private val tmp: File) {
        private val start = c["start"]!!.jsonObject
        private val foreign = start["foreignFileId"]?.str?.takeIf { it.isNotEmpty() }
        private val persistent = c["persistence"]?.jsonPrimitive?.boolean == true || foreign != null
        private val dbDir = File(tmp, "db").apply { mkdirs() }
        private val root = File(tmp, "root")
        private val kind = start["kind"]?.str ?: ""
        private var n = 0
        private var cid = ""
        private var processed: CompletableDeferred<ProcessedImage>? = null

        /** 当前实例；restart 之后换成新的。 */
        var a: AccountHarness = newHarness(START_MS)
            private set
        private var connected = false

        init {
            a.session(1, DAY)
            online() // 账号已登录在线（本地库记下登录用户）；foreignFileId 的在 pick 里关掉、写库、新建实例再上线
        }

        private fun newHarness(at: Long): AccountHarness {
            val db = if (persistent) JdbcLocalDatabase(File(dbDir, "im.db")) else null
            return AccountHarness(setup(database = db, clock = FakeClock(at), timings = IMClientTimings(heartbeatMs = 10 * 365 * DAY), filesRoot = root))
                .also { made.add(it) }
        }

        private fun online(): FakeConnection = a.online().also { connected = true }

        private fun conn(): FakeConnection = a.transport.current

        private fun tmpFile(bytes: Int, fill: Int): File = File(tmp, "src-${++n}").apply { writeBytes(ByteArray(bytes) { fill.toByte() }) }

        private fun image(): ProcessedImage = ProcessedImage(LocalFile(tmpFile(1000, 1), "image/jpeg"), LocalFile(tmpFile(100, 2), "image/jpeg"), 40, 30)

        private val thumb get() = kind == "image"

        private fun put(key: String) = obj("url" to "https://s3.test/chat/tmp/$key", "headers" to obj(), "expiresAt" to 1)

        private fun grant(fileId: String) {
            a.http.last(UPLOADS).respond(200, objNoNull("fileId" to fileId, "put" to put("$fileId-o"), "thumbPut" to if (thumb) put("$fileId-t") else null))
        }

        private fun uploadOk(fileId: String) {
            grant(fileId)
            a.h.objects.pending[0].respond(200)
            if (thumb) a.h.objects.pending[0].respond(200)
            a.http.last("/v1/files/$fileId/complete").respond(
                200,
                objNoNull(
                    "fileId" to fileId, "size" to 1000, "mime" to if (thumb) "image/jpeg" else "application/pdf",
                    "width" to if (thumb) 40 else null, "height" to if (thumb) 30 else null,
                ),
            )
        }

        private fun pickForeign() {
            a.h.call { close() }
            JdbcLocalDatabase(File(dbDir, "im.db")).use { db ->
                db.execute(
                    "INSERT INTO messages(conv_id, row_key, seq, json) VALUES(?, 'cfwd-0001', NULL, ?)",
                    listOf(AB, """{"clientMsgId":"fwd-0001","convId":"$AB","from":"$USER","type":"image","body":{"image":{"fileId":"$foreign","width":10,"height":10,"size":100,"mime":"image/jpeg"}},"ts":6,"status":"FAILED","error":"timeout"}"""),
                )
            }
            a = newHarness(a.clock.now() + 60_000)
            a.session(1, DAY)
            online()
            cid = "fwd-0001"
        }

        private fun sends(): List<JsonObject> = a.transport.connections.flatMap { it.frames("send") }

        private fun bodyFileId(body: JsonElement?): String {
            val b = body?.jsonObject ?: return ""
            return (b["image"] ?: b["file"])?.jsonObject?.get("fileId")?.str ?: ""
        }

        fun run(st: JsonObject): JsonObject {
            val before = a
            val uploads0 = a.http.to(UPLOADS).size
            val sends0 = sends().size
            when (val op = st["op"]!!.str) {
                "pick" -> when {
                    foreign != null -> pickForeign()
                    kind == "file" -> cid = a.h.call { sendFile(AB, FileInput(tmpFile(10, 3), "a.pdf", "application/pdf")) }.clientMsgId
                    start["processing"]?.jsonPrimitive?.boolean == true -> {
                        val d = CompletableDeferred<ProcessedImage>().also { processed = it }
                        cid = a.h.call { sendImage(AB, null, null, { d.await() }) }.clientMsgId
                    }
                    else -> cid = a.h.call { sendImage(AB, image()) }.clientMsgId
                }
                "processed" -> {
                    processed!!.complete(image())
                    a.h.flush()
                }
                "uploadOk" -> uploadOk(st["fileId"]!!.str)
                "uploadFailed" -> {
                    grant("F-FAIL")
                    a.h.objects.pending[0].fail()
                }
                "sendAck" -> conn().ack("send", obj("msgId" to "m1", "convId" to AB, "seq" to 1, "ts" to 9))
                "sendError" -> conn().ackError("send", st["code"]!!.jsonPrimitive.int)
                "sendTimeout" -> a.clock.advance(10_000)
                "disconnect" -> conn().drop(1006)
                "connect" -> if (connected) {
                    a.clock.advance(1_000) // 退避后重连
                    a.authNew()
                } else {
                    online() // 冷启动之后第一次连接
                }
                "retry" -> a.h.call { retry(AB, cid) }
                "dropSource" -> a.h.attachmentDir.listFiles()?.forEach { it.deleteRecursively() } // 沙盒副本被删
                "restart" -> {
                    a.h.call { close() } // 杀进程：没有机会收尾
                    a = newHarness(a.clock.now() + 60_000)
                    a.session(1, DAY)
                    connected = false
                }
                else -> fail("unknown op $op")
            }
            // 这一步换了实例（冷启动、写库后新建）时，新实例的计数从 0 开始。
            val newSends = if (a === before) sends().drop(sends0) else sends()
            val uploads = if (a === before) a.http.to(UPLOADS).size - uploads0 else a.http.to(UPLOADS).size
            for (f in newSends) assertEquals(cid, f.dataObj["clientMsgId"]!!.str, "clientMsgId 始终不变")
            val m = a.im.messages(AB).firstOrNull { it.clientMsgId == cid } ?: fail("消息不在了：${a.h.status(AB)}")
            val out = linkedMapOf<String, Any?>(
                "state" to m.status.name.lowercase(),
                "fileId" to (m.body.image?.fileId ?: m.body.file?.fileId ?: ""),
                "uploads" to uploads,
                "sends" to newSends.map { bodyFileId(it.dataObj["body"]) },
            )
            when (val e = m.error) {
                null -> {}
                is SendError.Code -> out["error"] = e.code
                SendError.Timeout -> out["error"] = "timeout"
                SendError.Network -> out["error"] = "network"
                SendError.SourceMissing -> out["error"] = "sourceMissing"
                SendError.Kicked -> out["error"] = "kicked"
                SendError.Process -> out["error"] = "process"
                SendError.Storage -> out["error"] = "storage"
            }
            return js(out).jsonObject
        }
    }

    @Test
    fun `TC-CHAT-90-13 attachmentCases 覆盖了处理中、待发送、手动重发、4096、自己和别人的 4048、冷启动`() {
        val cases = spec["attachmentCases"]!!.jsonArray.map { it.jsonObject }
        val ops = cases.flatMap { c -> c["steps"]!!.jsonArray.map { it.jsonObject["op"]!!.str } }.toSet()
        for (op in listOf("pick", "processed", "uploadOk", "uploadFailed", "sendAck", "sendError", "sendTimeout", "disconnect", "connect", "retry", "dropSource", "restart")) {
            assertTrue(op in ops, op)
        }
        assertTrue(cases.any { it["start"]!!.jsonObject["foreignFileId"] != null })
        assertTrue(cases.any { it["persistence"]?.jsonPrimitive?.boolean == true })
    }

    @TestFactory
    fun `TC-CHAT-90-13 account-session json attachmentCases（F-FILE-07 规则 1、3、4，含冷启动）`(): List<DynamicTest> =
        spec["attachmentCases"]!!.jsonArray.map { raw ->
            val c = raw.jsonObject
            dynamicTest(c["name"]!!.str) {
                val tmp = Files.createTempDirectory("tt-attach-").toFile()
                try {
                    val r = Runner(c, tmp)
                    for ((i, st) in c["steps"]!!.jsonArray.map { it.jsonObject }.withIndex()) {
                        val got = r.run(st)
                        val want = st["expect"]!!.jsonObject
                        // error 写了才比较（冷启动转失败的原因各端自己定）
                        val cmp = if ("error" in want) got else JsonObject(got - "error")
                        assertEquals(want, cmp, "step $i ${st["op"]!!.str}")
                    }
                } finally {
                    tmp.deleteRecursively()
                }
            }
        }

    private val made = ArrayList<AccountHarness>()

    @org.junit.jupiter.api.AfterEach
    fun requestsOk() {
        for (a in made) assertEquals(emptyList(), a.badRequests) // 请求体都符合 http-client.schema.json
    }
}
