// FE-681（C5）：本地库物理清除（AC-CVS-03-10，F-CVS-03 规则 6 ②，R-CB-02）、"待清除"（F-CVS-03 异常）、第 3 版 → 第 4 版升级、
// 置顶和免打扰进本地库（FE-681 第 3 条）。真 SQLite 文件（sdk-sqlite-jdbc），重新打开就是"进程被杀后重启"；
// 搜数据库文件（含 -journal、-wal）和缓存目录的原始字节。本地单测结果，CI 不运行。
package com.tianting.sdk

import com.tianting.sdk.jdbc.JdbcLocalDatabase
import com.tianting.sdk.testing.FakeConnection
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SECRET = "ZXQ-CVS-10"

/** 本地库的替身：armed 时执行到含 failOn 的 SQL 抛异常一次（模拟"本地清除失败"，F-CVS-03 异常）。 */
private class FlakyDb(private val inner: LocalDatabase, private val failOn: String) : LocalDatabase by inner {
    var armed = false
    var failures = 0

    override fun execute(sql: String, args: List<Any?>) {
        if (armed && failOn in sql) {
            armed = false
            failures++
            throw java.sql.SQLException("disk I/O error")
        }
        inner.execute(sql, args)
    }
}

class ConvSettingsPersistenceTest : SdkTest() {
    @TempDir
    lateinit var dir: File

    @TempDir
    lateinit var files: File

    private val AB = "c2c:alice:bob"
    private val G1 = "group:g1"
    private val dbDir get() = File(dir, "db").apply { mkdirs() }
    private val cacheDir get() = File(files, "cache")

    private fun open(db: LocalDatabase = tempDb(dbDir)): Harness = setup(database = db, filesRoot = files).also { it.fileAuth = fakeUrls(it) }

    private fun Harness.closeDb() = call { close() }

    private fun clean(needle: String) {
        assertEquals(emptyList(), filesContaining(dbDir, needle), "数据库文件里还能搜到 $needle")
        assertEquals(emptyList(), filesContaining(cacheDir.apply { mkdirs() }, needle), "缓存目录里还能搜到 $needle")
    }

    private fun present(needle: String) = assertTrue(filesContaining(dbDir, needle).isNotEmpty(), "对照：应该能在数据库文件里搜到 $needle")

    private fun cached(): List<String> = cacheDir.listFiles()?.map { it.name } ?: emptyList()

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

    private fun fileMsg(convId: String, seq: Long, fileId: String, name: String) = JsonObject(
        m(convId, seq, body = obj("file" to obj("fileId" to fileId, "name" to name, "size" to 3, "mime" to "application/pdf"))) +
            mapOf("type" to JsonPrimitive("file")),
    )

    private fun login(h: Harness, vararg convs: JsonObject, deleted: List<JsonObject> = emptyList()): FakeConnection {
        h.connect()
        val c = h.transport.current
        c.open()
        c.ack("auth", obj("userId" to "alice", "serverTime" to h.clock.now()))
        val data = if (deleted.isEmpty()) obj("convs" to convs.toList(), "truncated" to false) else obj("convs" to convs.toList(), "truncated" to false, "deleted" to deleted)
        down(c, ackOf(c, "convs", data))
        return c
    }

    /** 本地库里有和 bob 的单聊：文本、文件（文件名和下载缓存的内容都含特征串）、自己没发出去的引用。 */
    private fun seeded(): Harness {
        val h = open()
        val c = login(h, sc(AB, maxSeq = 10, readSeq = 10, updatedAt = 10, name = "Bob", notifyOff = true))
        h.loaded(c, AB, listOf(m(AB, 9, text = "$SECRET 原文"), fileMsg(AB, 10, "f-cvs", "$SECRET.pdf")))
        val d = h.start { download("f-cvs", FileVariant.ORIGINAL) }
        h.objects.gets.last().respond(200, "$SECRET 文件内容".toByteArray())
        h.await(d)
        c.drop()
        h.call { send(AB, "回复", quote = QuoteRef(idOf(AB, 9))) } // 排队中，引用原文写进了本地库
        assertEquals(1, cached().size)
        present(SECRET)
        h.closeDb() // D2 离线（App 被杀）
        present(SECRET)
        return h
    }

    @Test
    fun `TC-CVS-03-10（AC-CVS-03-10）：D2 离线时 D1 删除单聊、u2 再发一条；D2 上线后本地库（含 journal、wal）和缓存目录搜不到旧特征串，只显示新消息`() {
        seeded()
        val h2 = open()
        assertEquals("$SECRET 原文", h2.bySeq(AB, 9).body.text) // 离线时还是上次的内容
        val c2 = login(h2, sc(AB, maxSeq = 11, readSeq = 10, minSeq = 11, unread = 1, updatedAt = 11, name = "Bob", notifyOff = true))
        assertEquals(obj("convId" to AB, "afterSeq" to 10, "limit" to 200), c2.last("sync").dataObj)
        c2.ack("sync", obj("messages" to listOf(m(AB, 11, text = "新消息")), "hasMore" to false))
        assertEquals(listOf(11L), h2.im.messages(AB).mapNotNull { it.seq })
        val pending = h2.im.messages(AB).single { it.seq == null }
        assertEquals(QuoteState.DELETED to null, pending.body.quote!!.let { it.state to it.text }) // 问题 62：没发出的引用清除原文
        assertEquals(listOf(listOf("f-cvs")), h2.events.filterIsInstance<IMEvent.FilesExpired>().map { it.fileIds })
        h2.flush()
        assertEquals(emptyList(), cached())
        clean(SECRET)
        assertFalse(holds(storeOf(h2.im), SECRET))
        h2.closeDb()
        clean(SECRET)
        JdbcLocalDatabase(File(dbDir, "im.db")).use { db -> assertEquals(0, db.query("SELECT * FROM pending_purges").size) }
        // 冷启动：旧消息没有回来，minSeq 记住了。
        val h3 = open()
        assertEquals(listOf(11L), h3.im.messages(AB).mapNotNull { it.seq })
        assertEquals(11L, h3.convOf(AB).minSeq)
        assertTrue(h3.convOf(AB).notifyOff)
    }

    @Test
    fun `AC-CVS-04-5 + 本地库：其他设备删除（convDeleted）后数据库文件和缓存目录搜不到特征串；冷启动后会话仍隐藏，新消息来了按删除时的状态出现`() {
        val h = open()
        val c = login(h, sc(AB, maxSeq = 10, readSeq = 10, updatedAt = 10, name = "Bob", notifyOff = true, pinnedAt = 7, lastMsg = lastOf(AB, 10, "bob", "$SECRET 摘要")))
        h.loaded(c, AB, listOf(m(AB, 9, text = "$SECRET 原文"), fileMsg(AB, 10, "f-cvs", "$SECRET.pdf")))
        val d = h.start { download("f-cvs", FileVariant.ORIGINAL) }
        h.objects.gets.last().respond(200, "$SECRET 文件内容".toByteArray())
        h.await(d)
        present(SECRET)
        downEvent(c, "convDeleted", "convId" to AB, "clearedSeq" to 10)
        h.flush()
        assertEquals(emptyList(), cached())
        clean(SECRET)
        h.closeDb()
        clean(SECRET)
        val h2 = open()
        assertEquals(emptyList(), h2.im.conversations.value)
        assertEquals(emptyList(), h2.im.messages(AB))
        val c2 = login(h2, deleted = listOf(obj("convId" to AB, "clearedSeq" to 10)))
        down(c2, msgFrame(m(AB, 11, text = "新的")))
        val x = h2.convOf(AB)
        assertEquals(listOf("Bob", 11L, 10L, 1L, true, false), listOf(x.name, x.minSeq, x.readSeq, x.unread, x.notifyOff, x.pinned))
        assertFalse(h2.call { loadHistory(AB) })
    }

    @Test
    fun `F-CVS-03 异常（本地清除失败下次启动再清）：清除的事务失败时「待清除」标记留下；下次启动连接之前就补清消息、引用原文、摘要和缓存文件`() {
        val flaky = FlakyDb(tempDb(dbDir), "DELETE FROM messages WHERE conv_id = ? AND seq < ?")
        val h = open(flaky)
        val c = login(h, sc(AB, maxSeq = 10, readSeq = 10, updatedAt = 10, name = "Bob", lastMsg = lastOf(AB, 10, "bob", "$SECRET 摘要")))
        h.loaded(c, AB, listOf(m(AB, 9, text = "$SECRET 原文"), m(AB, 10, text = "$SECRET 摘要"), fileMsg(AB, 8, "f-cvs", "a.pdf")))
        val d = h.start { download("f-cvs", FileVariant.ORIGINAL) }
        h.objects.gets.last().respond(200, "$SECRET 文件内容".toByteArray())
        h.await(d)
        c.drop()
        h.call { send(AB, "回复", quote = QuoteRef(idOf(AB, 9))) }
        val n = h.reconnectAuth(waitMs = 60_000)
        down(n, ackOf(n, "convs", obj("convs" to listOf(sc(AB, maxSeq = 10, readSeq = 10, updatedAt = 10, name = "Bob", lastMsg = lastOf(AB, 10, "bob", "$SECRET 摘要"))), "truncated" to false)))
        flaky.armed = true
        downEvent(n, "convDeleted", "convId" to AB, "clearedSeq" to 10)
        assertEquals(1, flaky.failures)
        assertEquals(emptyList(), h.im.conversations.value) // 内存照常清除
        assertFalse(holds(storeOf(h.im), SECRET))
        assertTrue(h.logs.any { "local database failed" in it })
        h.closeDb()
        present(SECRET) // 写库失败：原文还在库里（对照）
        JdbcLocalDatabase(File(dbDir, "im.db")).use { db ->
            val mark = db.query("SELECT conv_id, min_seq, file_ids FROM pending_purges").single()
            assertEquals(listOf(AB, 11L, """["f-cvs"]"""), listOf(mark["conv_id"], mark["min_seq"], mark["file_ids"]))
        }
        // 缓存文件这次已经删了；放回去，验证补清时也会删（标记里有 fileId）。
        val again = File(cacheDir, cachedName("f-cvs")).apply { writeBytes("$SECRET 文件内容".toByteArray()) }

        val h2 = open() // 下次启动：open() 先补清，不用等连接
        h2.flush()
        assertFalse(again.exists())
        clean(SECRET)
        assertEquals(emptyList(), h2.im.conversations.value) // 全部清除了：会话按删除隐藏，摘要不留在会话表里
        assertEquals(listOf(null), h2.im.messages(AB).map { it.seq })
        assertEquals(QuoteState.DELETED, h2.im.messages(AB).single().body.quote!!.state)
        assertFalse(holds(storeOf(h2.im), SECRET))
        h2.closeDb()
        clean(SECRET)
        JdbcLocalDatabase(File(dbDir, "im.db")).use { db -> assertEquals(0, db.query("SELECT * FROM pending_purges").size) }
    }

    private fun cachedName(fileId: String): String {
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(fileId.toByteArray()).joinToString("") { "%02x".format(it) }
        return "f-${h.take(40)}-original"
    }

    @Test
    fun `FE-681 第 3 条：置顶、免打扰进本地库，冷启动先显示（顺序、总未读）；convs 回来以服务端为准`() {
        val h = open()
        login(h, sc(AB, maxSeq = 3, unread = 3, updatedAt = 9, notifyOff = true), sc(G1, maxSeq = 1, unread = 1, updatedAt = 1, pinnedAt = 5))
        h.closeDb()
        val h2 = open()
        assertEquals(listOf(G1, AB), h2.im.conversations.value.map { it.convId })
        assertEquals(1L, h2.im.totalUnread.value)
        login(h2, sc(AB, maxSeq = 3, unread = 3, updatedAt = 9), sc(G1, maxSeq = 1, unread = 1, updatedAt = 1))
        assertEquals(listOf(AB, G1), h2.im.conversations.value.map { it.convId })
        assertEquals(4L, h2.im.totalUnread.value)
        h2.closeDb()
        val h3 = open()
        assertEquals(listOf(false, false), h3.im.conversations.value.map { it.pinned })
    }

    @Test
    fun `删除隐藏的群进本地库（cleared_convs）：冷启动后仍不在列表、不是「你已不在该群」；新消息来了按删除时的状态出现`() {
        val h = open()
        val c = login(h, sc(G1, maxSeq = 2, readSeq = 2, updatedAt = 2))
        h.loaded(c, G1, listOf(m(G1, 1, text = SECRET), m(G1, 2)))
        val r = h.start { deleteConv(G1) }
        c.ack("convDelete", obj("clearedSeq" to 2))
        h.await(r)
        h.closeDb()
        clean(SECRET)
        JdbcLocalDatabase(File(dbDir, "im.db")).use { db ->
            assertEquals(listOf(G1 to 2L), db.query("SELECT conv_id, cleared_seq FROM cleared_convs").map { it["conv_id"] to it["cleared_seq"] })
            assertEquals(0, db.query("SELECT * FROM conversations").size)
        }
        val h2 = open()
        assertEquals(emptyList(), h2.im.conversations.value)
        val c2 = login(h2, deleted = listOf(obj("convId" to G1, "clearedSeq" to 2)))
        assertEquals(emptyList(), h2.removed())
        assertNull(h2.call { conversationSnapshot(G1) })
        down(c2, msgFrame(m(G1, 3)))
        assertEquals(listOf(3L, 1L), h2.convOf(G1).let { listOf(it.minSeq, it.unread) })
    }

    // ---- 本地库第 3 版 → 第 4 版 ----

    private fun createV3(file: File) = JdbcLocalDatabase(file).use { db ->
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
        db.execute("PRAGMA user_version = 3")
        db.execute("INSERT INTO meta(key, value) VALUES('owner', 'alice')")
        db.execute(
            "INSERT INTO conversations(conv_id, read_base, placeholder, json) VALUES(?, 2, 0, ?)",
            listOf(G1, """{"convId":"$G1","type":"group","name":"老群","maxSeq":3,"readSeq":2,"minSeq":1,"unread":1,"updatedAt":5,"modVer":4,"mentionUnread":1,"firstMentionSeq":3}"""),
        )
        db.execute(
            "INSERT INTO conversations(conv_id, read_base, placeholder, json) VALUES(?, 0, 0, ?)",
            listOf(AB, """{"convId":"$AB","type":"c2c","name":"bob","maxSeq":0,"readSeq":0,"minSeq":1,"unread":0,"updatedAt":9}"""),
        )
        db.execute(
            "INSERT INTO messages(conv_id, row_key, seq, json) VALUES(?, 's1', 1, ?)",
            listOf(G1, """{"clientMsgId":"x1","msgId":"${idOf(G1, 1)}","seq":1,"convId":"$G1","from":"bob","type":"text","body":{"text":"$SECRET 老消息"},"ts":5,"status":"SENT"}"""),
        )
        db.execute(
            "INSERT INTO messages(conv_id, row_key, seq, json) VALUES(?, 's3', 3, ?)",
            listOf(G1, """{"clientMsgId":"x3","msgId":"${idOf(G1, 3)}","seq":3,"convId":"$G1","from":"bob","type":"text","body":{"text":"新一些"},"ts":7,"status":"SENT"}"""),
        )
        db.execute(
            "INSERT INTO messages(conv_id, row_key, seq, json) VALUES(?, 'cx4', NULL, ?)",
            listOf(G1, """{"clientMsgId":"x4","convId":"$G1","from":"alice","type":"text","body":{"text":"失败的"},"ts":8,"status":"FAILED","error":"timeout"}"""),
        )
        db.execute("INSERT INTO deleted_refs(conv_id, kind, ref) VALUES(?, 'recalled', ?)", listOf(G1, """{"k":"id","v":"${idOf(G1, 2)}","by":"bob"}"""))
        db.execute("INSERT INTO mod_vers(conv_id, mod_ver) VALUES(?, 4)", listOf(G1))
    }

    @Test
    fun `本地库第 3 版原地升级到第 4 版：会话、消息（含失败）、撤回记录、modVer 保留；旧行没有 C5 字段按不置顶、没有免打扰；新表可用`() {
        val file = File(dbDir, "im.db")
        createV3(file)
        val h = setup(database = JdbcLocalDatabase(file), filesRoot = files)
        assertEquals(listOf(AB, G1), h.im.conversations.value.map { it.convId })
        assertEquals(listOf(false to false, false to false), h.im.conversations.value.map { it.pinned to it.notifyOff })
        assertEquals("老群", h.convOf(G1).name)
        assertEquals(1L, h.convOf(G1).mentionUnread)
        assertEquals(listOf("x1:sent#1", "x3:sent#3", "x4:failed(timeout)"), h.status(G1))
        assertEquals(1L, h.im.totalUnread.value)
        h.closeDb()
        JdbcLocalDatabase(file).use { db ->
            assertEquals(4L, db.query("PRAGMA user_version").single().values.single())
            assertEquals(0, db.query("SELECT * FROM cleared_convs").size)
            assertEquals(0, db.query("SELECT * FROM pending_purges").size)
            assertEquals(setOf("recalled"), db.query("SELECT DISTINCT kind FROM deleted_refs").map { it["kind"] }.toSet())
            assertEquals(4L, db.query("SELECT mod_ver FROM mod_vers").single()["mod_ver"])
        }
        // 升级后的库走 C5 的路径：convs 带 minSeq=2 时按新表清除；置顶写进去。
        val h2 = setup(database = JdbcLocalDatabase(file), filesRoot = files)
        h2.connect()
        val c = h2.transport.current
        c.open()
        c.ack("auth", obj("userId" to "alice", "serverTime" to h2.clock.now()))
        down(c, ackOf(c, "convs", obj("convs" to listOf(sc(G1, name = "老群", maxSeq = 3, readSeq = 2, minSeq = 2, unread = 1, updatedAt = 5, pinnedAt = 9)), "truncated" to false, "deleted" to listOf(obj("convId" to AB, "clearedSeq" to 0)))))
        assertEquals(listOf("x3:sent#3", "x4:failed(timeout)"), h2.status(G1))
        assertEquals(listOf(G1), h2.im.conversations.value.map { it.convId })
        h2.closeDb()
        clean(SECRET)
        JdbcLocalDatabase(file).use { db ->
            assertEquals(listOf(AB), db.query("SELECT conv_id FROM cleared_convs").map { it["conv_id"] })
        }
        val h3 = setup(database = JdbcLocalDatabase(file), filesRoot = files)
        assertEquals(listOf(true), h3.im.conversations.value.map { it.pinned })
    }
}
