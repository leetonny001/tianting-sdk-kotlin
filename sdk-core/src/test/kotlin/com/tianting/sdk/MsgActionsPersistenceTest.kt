// FE-661、FE-662（C4）：本地库、缓存目录、冷启动、升级、下载中被撤回（AC-RCL-03-2、03-4、03-12 的 SDK 部分、03-13，AC-QUO-02-2、02-6）。
// 真 SQLite 文件（sdk-sqlite-jdbc），重新打开就是"进程被杀后重启"；搜数据库文件（含 -journal、-wal）的原始字节。本地单测结果，CI 不运行。
package com.tianting.sdk

import com.tianting.sdk.jdbc.JdbcLocalDatabase
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MsgActionsPersistenceTest : SdkTest() {
    @TempDir
    lateinit var dir: File

    @TempDir
    lateinit var files: File

    private val AB = C4_AB
    private val G = C4_G
    private val dbDir get() = File(dir, "db").apply { mkdirs() }

    private fun open(): Harness = setup(database = tempDb(dbDir), filesRoot = files).also { it.fileAuth = fakeUrls(it) }

    private fun Harness.closeDb() = call { close() }

    private fun clean(needle: String) = assertEquals(emptyList(), filesContaining(dbDir, needle), "数据库文件里还能搜到 $needle")

    private fun present(needle: String) = assertTrue(filesContaining(dbDir, needle).isNotEmpty(), "对照：应该能在数据库文件里搜到 $needle")

    /** 文件接口的替身：/v1/files/urls 给每一项发一个地址（地址里没有特征串）。 */
    private fun fakeUrls(h: Harness) = object : FileAuth {
        override suspend fun authorized(method: String, path: String, body: JsonElement?): JsonElement {
            check(path == "/v1/files/urls") { path }
            val items = (body as JsonObject)["items"]!!.jsonArray.map { it.jsonObject }
            return obj(
                "items" to items.map {
                    obj("fileId" to it["fileId"]!!, "variant" to it["variant"]!!, "url" to "https://s3.test/${it["fileId"]!!.str}?sig", "expiresAt" to h.clock.now() + 120_000)
                },
            )
        }
    }

    private fun img(convId: String, seq: Long, fileId: String) = JsonObject(
        m(convId, seq, body = obj("image" to obj("fileId" to fileId, "width" to 10, "height" to 10, "size" to 3, "mime" to "image/png"))) +
            mapOf("type" to JsonPrimitive("image")),
    )

    private fun fileMsg(convId: String, seq: Long, fileId: String, name: String) = JsonObject(
        m(convId, seq, body = obj("file" to obj("fileId" to fileId, "name" to name, "size" to 3, "mime" to "application/pdf"))) +
            mapOf("type" to JsonPrimitive("file")),
    )

    private fun cached(): List<String> = File(files, "cache").listFiles()?.map { it.name } ?: emptyList()

    // ---- AC-RCL-03-2：本地库和缓存目录 ----

    @Test
    fun `TC-RCL-03-02（AC-RCL-03-2）：两条带 recalled 的 msgDeleted 后，数据库文件（含 journal）搜不到原文和引用原文，缩略图、原图缓存文件不存在`() {
        val h = open()
        h.connect()
        val c = h.login("alice", listOf(conv(G, maxSeq = 3, lastMsg = lastOf(G, 3, "carol", "回复"))))
        h.loaded(
            c,
            G,
            listOf(
                m(G, 1, "bob", "$RCL_SECRET 原文"),
                img(G, 2, "f-img"),
                m(G, 3, "carol", "回复", body = obj("text" to "回复", "quote" to quoteOf(G, 1, "$RCL_SECRET 原文"))),
            ),
        )
        for (v in listOf(FileVariant.THUMB, FileVariant.ORIGINAL)) {
            val d = h.start { download("f-img", v) }
            h.objects.gets.last().respond(200, byteArrayOf(1, 2, 3))
            h.await(d)
        }
        assertEquals(2, cached().size)
        present(RCL_SECRET)
        c.receive(recalledEvent(G, 1, "bob"))
        c.receive(recalledEvent(G, 2, "bob"))
        h.flush()
        assertEquals(emptyList(), cached())
        clean(RCL_SECRET)
        h.closeDb()
        clean(RCL_SECRET)
        clean("f-img")
        assertEquals(emptyList(), cached())
        // 冷启动：撤回状态和清除后的引用都在（restoreRefs、messages.json），原文没有回来。
        val h2 = open()
        assertEquals(listOf(true, true, false), h2.im.messages(G).map { it.recalled })
        assertEquals(QuoteState.RECALLED, h2.bySeq(G, 3).body.quote!!.state)
        assertFalse(holds(storeOf(h2.im), RCL_SECRET))
    }

    // ---- AC-RCL-03-4：App 被杀时被撤回 ----

    @Test
    fun `TC-RCL-03-04（AC-RCL-03-4）：本地库有 m1 和引用它的 m2，App 被杀期间 m1 被撤回；冷启动连接后按 modVer 重拉，变撤回文案，本地库搜不到原文`() {
        val h = open()
        h.connect()
        val c = h.login("bob", listOf(conv(AB, maxSeq = 2, modVer = 1)))
        h.loaded(c, AB, listOf(m(AB, 1, "alice", "$RCL_SECRET 原文"), m(AB, 2, "alice", "补一句", body = obj("text" to "补一句", "quote" to quoteOf(AB, 1, "$RCL_SECRET 原文", "alice")))))
        h.closeDb() // App 被杀（本地库保留）
        present(RCL_SECRET)

        val h2 = open()
        assertEquals("$RCL_SECRET 原文", h2.bySeq(AB, 1).body.text) // 离线也能看到上次的内容
        h2.connect()
        val c2 = h2.login("bob", listOf(conv(AB, maxSeq = 2, modVer = 2, lastMsg = obj("msgId" to idOf(AB, 2), "from" to "alice", "type" to "text", "text" to "补一句", "ts" to 1002))))
        assertEquals(listOf(AB), c2.frames("sync").map { it.dataObj["convId"]!!.str })
        c2.ack(
            "sync",
            obj(
                "messages" to listOf(
                    recalledMsg(AB, 1, "alice"),
                    m(AB, 2, "alice", "补一句", body = obj("text" to "补一句", "quote" to obj("msgId" to idOf(AB, 1), "seq" to 1, "from" to "alice", "type" to "text", "state" to "recalled"))),
                ),
                "hasMore" to false,
            ),
        )
        val x = h2.bySeq(AB, 1)
        assertTrue(x.recalled)
        assertEquals("对方撤回了一条消息", h2.call { recallText(x) })
        assertEquals("引用内容已被撤回", quoteLabel(h2.bySeq(AB, 2).body.quote!!))
        clean(RCL_SECRET)
        h2.closeDb()
        clean(RCL_SECRET)
    }

    // ---- AC-RCL-03-13 跨冷启动：deleted_refs 的 recalled ----

    @Test
    fun `TC-RCL-03-13（AC-RCL-03-13）：没加载过的 m5 被撤回，只记在 deleted_refs；冷启动后迟到的旧应答带 m5 原文和引用 m5 的 m6（state=normal），都不恢复`() {
        val h = open()
        h.connect()
        val c = h.login("alice", listOf(conv(G, maxSeq = 6)))
        h.loaded(c, G, listOf(m(G, 6, "carol", "最新"))) // m4、m5 没加载过
        c.receive(recalledEvent(G, 5, "bob", at = 77))
        h.closeDb()

        val h2 = open()
        h2.connect()
        val c2 = h2.login("alice", listOf(conv(G, maxSeq = 6)))
        val late = listOf(
            m(G, 4, "carol", "回复", body = obj("text" to "回复", "quote" to quoteOf(G, 5, "$RCL_SECRET 原文"))),
            m(G, 5, "bob", "$RCL_SECRET 原文"),
        )
        val d = h2.start { loadHistory(G) }
        c2.ack("sync", obj("messages" to late, "hasMore" to false))
        h2.await(d)
        val m5 = h2.bySeq(G, 5)
        assertTrue(m5.deleted && m5.recalled)
        assertEquals("bob", m5.recalledBy)
        assertEquals(77L, m5.recalledAt)
        assertEquals("bob撤回了一条消息", h2.call { recallText(m5) })
        assertEquals(QuoteView(idOf(G, 5), 5, "bob", MsgType.TEXT, QuoteState.RECALLED), h2.bySeq(G, 4).body.quote)
        assertFalse(holds(storeOf(h2.im), RCL_SECRET))
        clean(RCL_SECRET)
        h2.closeDb()
        clean(RCL_SECRET)
        JdbcLocalDatabase(File(dbDir, "im.db")).use { db ->
            val kinds = db.query("SELECT kind FROM deleted_refs WHERE conv_id = ?", listOf(G)).map { it["kind"] }
            assertTrue("recalled" in kinds && "id" in kinds && "seq" in kinds, "$kinds")
        }
    }

    // ---- AC-QUO-02-2：引用里的原文（内存和数据库） ----

    @Test
    fun `TC-QUO-02-02（AC-QUO-02-2）：本地有 m1 和引用它的 m2、待发的 m3；收到 m1 的 msgDeleted（带 recalled）后数据库文件搜不到特征串，冷启动后显示"引用内容已被撤回"`() {
        val h = open()
        h.connect()
        val c = h.login("alice", listOf(conv(G, maxSeq = 2)))
        h.loaded(c, G, listOf(m(G, 1, "bob", "$QUO_SECRET 原文"), m(G, 2, "carol", "回复", body = obj("text" to "回复", "quote" to quoteOf(G, 1, "$QUO_SECRET 原文")))))
        c.drop(1006)
        h.call { send(G, "我也回复", quote = QuoteRef(idOf(G, 1))) } // 排队中，已写进本地库
        present(QUO_SECRET)
        val c2 = h.reconnectAuth()
        c2.receive(recalledEvent(G, 1, "bob"))
        clean(QUO_SECRET)
        assertFalse(holds(storeOf(h.im), QUO_SECRET))
        h.closeDb()
        clean(QUO_SECRET)
        val h2 = open()
        assertEquals(listOf("引用内容已被撤回", "引用内容已被撤回"), h2.im.messages(G).mapNotNull { it.body.quote }.map { quoteLabel(it) })
    }

    // ---- AC-RCL-03-12 的 SDK 部分：下载中被撤回 ----

    @Test
    fun `TC-RCL-03-12（AC-RCL-03-12 的 SDK 部分）：文件下载中对方撤回：下载中止，抛 DownloadAbortedError(recalled)，发 DownloadAborted 事件，不留部分文件`() {
        val h = open()
        h.connect()
        val c = h.login("alice", listOf(conv(AB, maxSeq = 2)))
        h.loaded(c, AB, listOf(fileMsg(AB, 1, "f-big", "ZXQ-FILE-NAME.pdf"), fileMsg(AB, 2, "f-other", "other.pdf")))
        val d = h.start { download("f-big", FileVariant.ORIGINAL) }
        val other = h.start { download("f-other", FileVariant.ORIGINAL) }
        val get = h.objects.gets.first { "f-big" in it.url }
        // 写了一半（假对象存储把收到的字节写进 SDK 给的临时文件）。
        val dest = FakeObjectCallDest.of(get)
        dest.writeBytes(ByteArray(1000) { 7 })
        get.sent(1000)
        assertTrue(cached().any { ".part" in it })
        c.receive(recalledEvent(AB, 1, "bob"))
        val e = assertIs<DownloadAbortedError>(h.failure(d))
        assertEquals("f-big", e.fileId)
        assertTrue(e.recalled)
        assertTrue(get.cancelled)
        assertEquals(listOf(IMEvent.DownloadAborted("f-big", true)), h.events.filterIsInstance<IMEvent.DownloadAborted>())
        assertEquals(emptyList(), cached().filter { hashPart("f-big") in it }) // 下了一半的临时文件已删除
        // 另一个文件的下载不受影响。
        h.objects.gets.first { "f-other" in it.url }.respond(200, byteArrayOf(1))
        assertTrue(h.await(other).isFile)
        assertEquals(1, cached().size)
        // 后台删除（不是撤回）同样中止，recalled=false。
        val d2 = h.start { download("f-other", FileVariant.THUMB) }
        c.receive(deletedEvent(AB, 2))
        assertFalse(assertIs<DownloadAbortedError>(h.failure(d2)).recalled)
        assertEquals(IMEvent.DownloadAborted("f-other", false), h.events.filterIsInstance<IMEvent.DownloadAborted>().last())
        h.flush()
        assertEquals(emptyList(), cached()) // 部分文件和之前的缓存都删了
        assertFalse(h.logs.joinToString().contains("ZXQ-FILE"))
    }

    @Test
    fun `调用方自己取消下载：照常抛 CancellationException，不发 DownloadAborted`() {
        val h = open()
        h.connect()
        val c = h.login("alice", listOf(conv(AB, maxSeq = 1)))
        h.loaded(c, AB, listOf(fileMsg(AB, 1, "f-big", "a.pdf")))
        val d = h.start { download("f-big", FileVariant.ORIGINAL) }
        d.cancel()
        h.flush()
        assertTrue(d.isCancelled)
        assertEquals(emptyList(), h.events.filterIsInstance<IMEvent.DownloadAborted>())
        assertEquals(emptyList(), cached())
        c.receive(recalledEvent(AB, 1, "bob")) // 之后再撤回：已经没有进行中的下载
        assertEquals(emptyList(), h.events.filterIsInstance<IMEvent.DownloadAborted>())
    }

    private fun hashPart(fileId: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(fileId.toByteArray()).joinToString("") { "%02x".format(it) }.take(40)

    // ---- 提及数进本地库（FE-662 第 1 条） ----

    @Test
    fun `FE-662：mentionUnread、firstMentionSeq 进本地库，冷启动恢复；本端读到最新后清零也落库`() {
        val h = open()
        h.connect()
        val c = h.login("alice", listOf(conv(G, maxSeq = 9, readSeq = 4, unread = 5, mentionUnread = 2, firstMentionSeq = 5)))
        c.receive(msgFrame(m(G, 10, "bob", "@所有人", body = obj("text" to "@所有人", "mentions" to obj("all" to true)))))
        h.closeDb()
        val h2 = open()
        assertEquals(3L, h2.convOf(G).mentionUnread)
        assertEquals(5L, h2.convOf(G).firstMentionSeq)
        h2.connect()
        val c2 = h2.login("alice", listOf(conv(G, maxSeq = 10, readSeq = 4, unread = 6, mentionUnread = 3, firstMentionSeq = 5)))
        h2.im.setActiveConversation(G)
        h2.flush()
        assertEquals(obj("convId" to G, "seq" to 10), c2.last("read").dataObj)
        assertEquals(0L, h2.convOf(G).mentionUnread)
        h2.closeDb()
        val h3 = open()
        assertEquals(0L, h3.convOf(G).mentionUnread)
        assertNull(h3.convOf(G).firstMentionSeq)
    }

    // ---- 本地库第 2 版 → 第 3 版 ----

    private fun createV2(file: File, version: Long = 2) = JdbcLocalDatabase(file).use { db ->
        db.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execute("CREATE TABLE conversations(conv_id TEXT PRIMARY KEY, read_base INTEGER NOT NULL, placeholder INTEGER NOT NULL, json TEXT NOT NULL)")
        db.execute("CREATE TABLE messages(conv_id TEXT NOT NULL, row_key TEXT NOT NULL, seq INTEGER, json TEXT NOT NULL, PRIMARY KEY(conv_id, row_key))")
        db.execute("CREATE TABLE deleted_refs(conv_id TEXT NOT NULL, kind TEXT NOT NULL, ref TEXT NOT NULL, PRIMARY KEY(conv_id, kind, ref))")
        db.execute("CREATE TABLE social_meta(id INTEGER PRIMARY KEY CHECK(id = 1), social_ver INTEGER NOT NULL, request_seen_ver INTEGER NOT NULL, settings TEXT NOT NULL, official INTEGER NOT NULL, loaded INTEGER NOT NULL)")
        db.execute("CREATE TABLE friends(user_id TEXT PRIMARY KEY, ver INTEGER NOT NULL, json TEXT NOT NULL)")
        db.execute("CREATE TABLE friend_requests(user_id TEXT NOT NULL, direction TEXT NOT NULL, ver INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(user_id, direction))")
        db.execute("CREATE TABLE blocks(user_id TEXT PRIMARY KEY, ver INTEGER NOT NULL, json TEXT NOT NULL)")
        db.execute("CREATE TABLE attachments(client_msg_id TEXT PRIMARY KEY, conv_id TEXT NOT NULL, json TEXT NOT NULL)")
        db.execute("CREATE TABLE mod_vers(conv_id TEXT PRIMARY KEY, mod_ver INTEGER NOT NULL)")
        db.execute("PRAGMA user_version = $version")
        db.execute("INSERT INTO meta(key, value) VALUES('owner', 'alice')")
        db.execute(
            "INSERT INTO conversations(conv_id, read_base, placeholder, json) VALUES(?, 2, 0, ?)",
            listOf(G, """{"convId":"$G","type":"group","name":"老群","maxSeq":3,"readSeq":2,"minSeq":1,"unread":1,"updatedAt":5,"modVer":4}"""),
        )
        db.execute(
            "INSERT INTO messages(conv_id, row_key, seq, json) VALUES(?, 's1', 1, ?)",
            listOf(G, """{"clientMsgId":"x1","msgId":"${idOf(G, 1)}","seq":1,"convId":"$G","from":"bob","type":"text","body":{"text":"老消息"},"ts":5,"status":"SENT"}"""),
        )
        db.execute(
            "INSERT INTO messages(conv_id, row_key, seq, json) VALUES(?, 's2', 2, ?)",
            listOf(G, """{"clientMsgId":"x2","msgId":"${idOf(G, 2)}","seq":2,"convId":"$G","from":"bob","type":"text","body":{},"ts":6,"status":"SENT","deleted":true}"""),
        )
        db.execute(
            "INSERT INTO messages(conv_id, row_key, seq, json) VALUES(?, 'cx3', NULL, ?)",
            listOf(G, """{"clientMsgId":"x3","convId":"$G","from":"alice","type":"text","body":{"text":"失败的"},"ts":7,"status":"FAILED","error":"timeout"}"""),
        )
        db.execute("INSERT INTO deleted_refs(conv_id, kind, ref) VALUES(?, 'id', ?)", listOf(G, idOf(G, 2)))
        db.execute("INSERT INTO deleted_refs(conv_id, kind, ref) VALUES(?, 'seq', '2')", listOf(G))
        db.execute("INSERT INTO deleted_refs(conv_id, kind, ref) VALUES(?, 'client', 'gone-1')", listOf(G))
        db.execute("INSERT INTO mod_vers(conv_id, mod_ver) VALUES(?, 4)", listOf(G))
    }

    @Test
    fun `本地库第 2 版原地升级到第 3 版：会话、消息（含失败、已删除）、deleted_refs 旧行、modVer 基线都保留，旧行没有 C4 字段按默认值`() {
        val file = File(dbDir, "im.db")
        createV2(file)
        val h = setup(database = JdbcLocalDatabase(file), filesRoot = files)
        assertEquals(listOf("x1:sent#1", "x2:sent#2", "x3:failed(timeout)"), h.status(G))
        assertTrue(h.bySeq(G, 2).deleted)
        assertFalse(h.bySeq(G, 2).recalled)
        assertEquals("老群", h.convOf(G).name)
        assertNull(h.convOf(G).mentionUnread)
        h.connect()
        // modVer 没变：不重拉（基线保留）；迟到的旧应答带着已删除的 m2 原文也写不回来（deleted_refs 旧行有效）。
        val c = h.login("alice", listOf(conv(G, maxSeq = 3, readSeq = 2, unread = 1, modVer = 4)))
        assertEquals(1, c.frames("sync").size) // 只有补拉 seq 3
        c.ack("sync", obj("messages" to listOf(m(G, 2, "bob", "ZXQ-DEL-OLD"), m(G, 3)), "hasMore" to false))
        assertTrue(h.bySeq(G, 2).deleted)
        c.receive(recalledEvent(G, 1, "bob"))
        h.closeDb()
        JdbcLocalDatabase(file).use { db ->
            assertEquals(3L, db.query("PRAGMA user_version").single().values.single())
            assertEquals(setOf("id", "seq", "client", "recalled"), db.query("SELECT DISTINCT kind FROM deleted_refs").map { it["kind"] }.toSet())
            assertEquals(4L, db.query("SELECT mod_ver FROM mod_vers").single()["mod_ver"])
        }
        clean("ZXQ-DEL-OLD")
        clean("老消息")
        val h2 = setup(database = JdbcLocalDatabase(file), filesRoot = files)
        assertEquals(listOf(true to true, true to false, false to false), h2.im.messages(G).filter { it.seq != null }.map { it.deleted to it.recalled })
    }

    @Test
    fun `本地库版本比第 3 版新（或未知）：按缓存重建，不报错`() {
        val file = File(dbDir, "im.db")
        createV2(file, version = 4)
        val h = setup(database = JdbcLocalDatabase(file), filesRoot = files)
        assertEquals(emptyList(), h.im.conversations.value)
        h.closeDb()
        JdbcLocalDatabase(file).use { db -> assertEquals(3L, db.query("PRAGMA user_version").single().values.single()) }
    }
}

/** 测试用：拿到假 GET 的目标文件（SDK 给的临时文件），模拟写了一半。 */
private object FakeObjectCallDest {
    fun of(c: com.tianting.sdk.testing.FakeObjectCall): File =
        c.javaClass.getDeclaredField("dest").apply { isAccessible = true }.get(c) as File
}
