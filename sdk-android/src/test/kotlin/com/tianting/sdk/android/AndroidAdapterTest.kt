package com.tianting.sdk.android

import com.tianting.sdk.IMClient
import com.tianting.sdk.IMClientOptions
import com.tianting.sdk.MessageStatus
import com.tianting.sdk.MsgBody
import com.tianting.sdk.SendError
import com.tianting.sdk.PushCursorState
import com.tianting.sdk.pinyinGroup
import com.tianting.sdk.testing.FakeClock
import com.tianting.sdk.testing.FakeTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Android 适配层：Robolectric 的原生 SQLite + android.icu。真机 / 模拟器上的验证由 tianting-android 的仪器测试负责。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidAdapterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val secret = "ZXQ-DEL-ANDROID"

    private inner class Env(file: File) {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val clock = FakeClock()
        val transport = FakeTransport { scheduler.runCurrent() }
        val im = IMClient(
            IMClientOptions(url = "ws://t/ws", database = AndroidLocalDatabase.open(file), transport = transport, clock = clock, dispatcher = dispatcher),
        )
        val scope = CoroutineScope(dispatcher + SupervisorJob())

        init {
            clock.afterEach = { scheduler.runCurrent() }
            scheduler.runCurrent()
        }

        fun close() {
            scope.launch { im.close() }
            scheduler.runCurrent()
        }
    }

    private fun msg(seq: Int, text: String) =
        """{"cmd":"msg","data":{"msgId":"g#$seq","clientMsgId":"c$seq","convId":"group:g1","seq":$seq,"from":"bob","type":"text","body":{"text":"$text"},"ts":$seq}}"""

    private fun filesContaining(dir: File, needle: String): List<String> {
        val n = needle.toByteArray()
        return dir.walkTopDown().filter { it.isFile }.filter { f ->
            val h = f.readBytes()
            (0..h.size - n.size).any { i -> n.indices.all { j -> h[i + j] == n[j] } }
        }.map { it.name }.toList()
    }

    @Test
    fun `FE-691：4510 的失败消息在系统 SQLite 上带失败原因保存，冷启动后仍是 4510、不能重发（frontend-chat 第 81 条）`() {
        val dir = tmp.newFolder()
        val file = File(dir, "im.db")
        val e = Env(file)
        e.im.connect("t")
        e.scheduler.runCurrent()
        val c = e.transport.current
        c.open()
        c.ack("auth", Json.parseToJsonElement("""{"userId":"alice","serverTime":1}"""))
        c.ack(
            "convs",
            Json.parseToJsonElement("""{"convs":[{"convId":"group:g1","type":"group","name":"g","maxSeq":0,"readSeq":0,"minSeq":1,"unread":0,"lastMsg":null,"updatedAt":0}],"truncated":false}"""),
        )
        e.scope.launch { e.im.send("group:g1", "含 ZXQWORD 的消息") }
        e.scheduler.runCurrent()
        c.ackError("send", 4510, "content blocked")
        val m = e.im.messages("group:g1").single()
        assertEquals(SendError.Code(4510), m.error)
        e.close()

        val e2 = Env(file)
        val r = e2.im.messages("group:g1").single()
        assertEquals(m.clientMsgId, r.clientMsgId)
        assertEquals(MessageStatus.FAILED, r.status)
        assertEquals(SendError.Code(4510), r.error)
        assertEquals(false, e2.im.canRetry(r))
        e2.close()
    }

    @Test
    fun `msgDeleted 后数据库文件搜不到特征串（AC-ADM-31-4）；重启后占位还在`() {
        val dir = tmp.newFolder()
        val file = File(dir, "im.db")
        val e = Env(file)
        e.im.connect("t")
        e.scheduler.runCurrent()
        val c = e.transport.current
        c.open()
        c.ack("auth", Json.parseToJsonElement("""{"userId":"alice","serverTime":1}"""))
        c.ack(
            "convs",
            Json.parseToJsonElement("""{"convs":[{"convId":"group:g1","type":"group","name":"g","maxSeq":0,"readSeq":0,"minSeq":1,"unread":0,"lastMsg":null,"updatedAt":0}],"truncated":false}"""),
        )
        c.receive(msg(1, "keep me"))
        c.receive(msg(2, "前缀 $secret 后缀"))
        c.receive(msg(3, "tail"))
        assertTrue(filesContaining(dir, secret).isNotEmpty(), "对照：删除之前能搜到")
        c.receive("""{"cmd":"event","data":{"type":"msgDeleted","convId":"group:g1","msgIds":["g#2"],"seqs":[2]}}""")
        assertEquals(emptyList(), filesContaining(dir, secret))
        assertTrue(filesContaining(dir, "keep me").isNotEmpty())
        assertEquals(emptyList(), dir.listFiles()!!.map { it.name }.filter { it.endsWith("-wal") })
        e.close()

        val e2 = Env(file)
        assertEquals("alice", e2.im.userId.value)
        val list = e2.im.messages("group:g1")
        assertEquals(listOf(1L, 2L, 3L), list.map { it.seq })
        assertEquals(true, list[1].deleted)
        assertEquals(MsgBody(), list[1].body)
        e2.close()
    }

    @Test
    fun `FE-661：第 2 版的库在系统 SQLite 上原地升级（现在到第 4 版，旧行保留）；撤回后数据库文件搜不到原文和引用原文，冷启动仍是撤回`() {
        val dir = tmp.newFolder()
        val file = File(dir, "im.db")
        // 第 2 版的库（C2 的表结构），里面有一条含特征串的消息和引用它的消息。
        AndroidLocalDatabase.open(file).use { db ->
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
            db.execute("PRAGMA user_version = 2")
            db.execute("INSERT INTO meta(key, value) VALUES('owner', 'alice')")
            db.execute(
                "INSERT INTO conversations(conv_id, read_base, placeholder, json) VALUES('group:g1', 2, 0, ?)",
                listOf("""{"convId":"group:g1","type":"group","name":"g","maxSeq":2,"readSeq":2,"minSeq":1,"unread":0,"updatedAt":2}"""),
            )
            db.execute(
                "INSERT INTO messages(conv_id, row_key, seq, json) VALUES('group:g1', 's1', 1, ?)",
                listOf("""{"clientMsgId":"c1","msgId":"g-1","seq":1,"convId":"group:g1","from":"bob","type":"text","body":{"text":"前缀 $secret"},"ts":1,"status":"SENT"}"""),
            )
            db.execute(
                "INSERT INTO messages(conv_id, row_key, seq, json) VALUES('group:g1', 's2', 2, ?)",
                listOf("""{"clientMsgId":"c2","msgId":"g-2","seq":2,"convId":"group:g1","from":"carol","type":"text","body":{"text":"回复","quote":{"msgId":"g-1","seq":1,"from":"bob","type":"text","state":"normal","text":"前缀 $secret"}},"ts":2,"status":"SENT"}"""),
            )
            db.execute("INSERT INTO deleted_refs(conv_id, kind, ref) VALUES('group:g1', 'client', 'gone')")
        }
        val e = Env(file)
        assertEquals(listOf(1L, 2L), e.im.messages("group:g1").map { it.seq })
        e.im.connect("t")
        e.scheduler.runCurrent()
        val c = e.transport.current
        c.open()
        c.ack("auth", Json.parseToJsonElement("""{"userId":"alice","serverTime":1}"""))
        c.ack(
            "convs",
            Json.parseToJsonElement("""{"convs":[{"convId":"group:g1","type":"group","name":"g","maxSeq":2,"readSeq":2,"minSeq":1,"unread":0,"lastMsg":null,"updatedAt":2}],"truncated":false}"""),
        )
        c.receive("""{"cmd":"event","data":{"type":"msgDeleted","convId":"group:g1","msgIds":["g-1"],"seqs":[1],"recalled":{"by":"bob","at":5}}}""")
        assertEquals(emptyList(), filesContaining(dir, secret))
        assertEquals(emptyList(), dir.listFiles()!!.map { it.name }.filter { it.endsWith("-wal") })
        e.close()
        AndroidLocalDatabase.open(file).use { db ->
            assertEquals(4L, db.query("PRAGMA user_version").single().values.single())
            assertEquals(setOf("client", "id", "seq", "recalled"), db.query("SELECT kind FROM deleted_refs").map { it["kind"] }.toSet())
        }
        val e2 = Env(file)
        val list = e2.im.messages("group:g1")
        assertEquals(listOf(true, false), list.map { it.recalled })
        assertEquals("recalled", list[1].body.quote!!.state.wire)
        e2.close()
        assertEquals(emptyList(), filesContaining(dir, secret))
    }

    @Test
    fun `FE-681：第 3 版的库在系统 SQLite 上原地升级到第 4 版；convs 的 deleted 和其他设备的 convDeleted 物理清除，数据库文件搜不到特征串，没有 -wal；冷启动仍隐藏`() {
        val dir = tmp.newFolder()
        val file = File(dir, "im.db")
        val s2 = "ZXQ-CVS-ANDROID"
        AndroidLocalDatabase.open(file).use { db ->
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
            for (g in listOf("g1", "g2")) {
                db.execute(
                    "INSERT INTO conversations(conv_id, read_base, placeholder, json) VALUES(?, 2, 0, ?)",
                    listOf("group:$g", """{"convId":"group:$g","type":"group","name":"$g","maxSeq":2,"readSeq":2,"minSeq":1,"unread":0,"updatedAt":2}"""),
                )
                for (seq in 1..2) {
                    db.execute(
                        "INSERT INTO messages(conv_id, row_key, seq, json) VALUES(?, ?, ?, ?)",
                        listOf("group:$g", "s$seq", seq.toLong(), """{"clientMsgId":"$g-$seq","msgId":"$g-$seq","seq":$seq,"convId":"group:$g","from":"bob","type":"text","body":{"text":"$s2 $g $seq"},"ts":$seq,"status":"SENT"}"""),
                    )
                }
            }
        }
        val e = Env(file)
        assertEquals(listOf(1L, 2L), e.im.messages("group:g1").map { it.seq })
        e.im.connect("t")
        e.scheduler.runCurrent()
        val c = e.transport.current
        c.open()
        c.ack("auth", Json.parseToJsonElement("""{"userId":"alice","serverTime":1}"""))
        // g1 被我在别的设备上删除（deleted 里，不是"你已不在该群"）；g2 还在列表里，之后另一台设备删除它。
        c.ack(
            "convs",
            Json.parseToJsonElement("""{"convs":[{"convId":"group:g2","type":"group","name":"g2","maxSeq":2,"readSeq":2,"minSeq":1,"unread":0,"lastMsg":null,"updatedAt":2,"memberCount":3,"myRole":"member","modVer":0,"pinned":true,"pinnedAt":5}],"truncated":false,"deleted":[{"convId":"group:g1","clearedSeq":2}]}"""),
        )
        assertEquals(listOf("group:g2"), e.im.conversations.value.map { it.convId })
        assertEquals(emptyList(), e.im.messages("group:g1"))
        assertEquals(emptyList(), filesContaining(dir, "$s2 g1"))
        assertTrue(filesContaining(dir, "$s2 g2").isNotEmpty(), "对照：g2 还没删")
        c.receive("""{"cmd":"event","data":{"type":"convDeleted","convId":"group:g2","clearedSeq":2}}""")
        assertEquals(emptyList(), e.im.conversations.value)
        assertEquals(emptyList(), filesContaining(dir, s2))
        assertEquals(emptyList(), dir.listFiles()!!.map { it.name }.filter { it.endsWith("-wal") })
        e.close()
        assertEquals(emptyList(), filesContaining(dir, s2))
        AndroidLocalDatabase.open(file).use { db ->
            assertEquals(4L, db.query("PRAGMA user_version").single().values.single())
            assertEquals(setOf("group:g1", "group:g2"), db.query("SELECT conv_id FROM cleared_convs").map { it["conv_id"] }.toSet())
        }
        val e2 = Env(file)
        assertEquals(emptyList(), e2.im.conversations.value)
        assertEquals(emptyList(), e2.im.messages("group:g2"))
        e2.close()
    }

    @Test
    fun `Robolectric 的 ICU 没有中文排序数据：collator 返回 null，汉字归井号，不崩溃`() {
        val c = AndroidPinyin.collator()
        // 真机上的用例在 androidTest（AndroidDeviceTest）。这里只验证退化路径不出错。
        assertEquals(if (c == null) "#" else "Z", pinyinGroup("张三", c))
        assertEquals("A", pinyinGroup("Alice", c))
    }

    @Test
    fun `AndroidPushCursorStore：按 userId 分键、读回一致、不含内容；文件坏了按没有；不进数据库文件（4_9 节）`() {
        val dir = tmp.newFolder()
        val store = AndroidPushCursorStore(dir)
        assertEquals(null, store.load("u1"))
        store.save("u1", 41, listOf("p1", "p2"))
        store.save("../../u2", 7, emptyList())
        assertEquals(PushCursorState(41, listOf("p1", "p2")), store.load("u1"))
        assertEquals(PushCursorState(7, emptyList()), store.load("../../u2"))
        assertEquals(2, dir.listFiles()!!.size, "userId 不进路径")
        assertTrue(dir.listFiles()!!.none { "u1" in it.name || "u2" in it.name })

        for (f in dir.listFiles()!!) f.writeText("{not json")
        assertEquals(null, store.load("u1"))
        for (f in dir.listFiles()!!) f.writeText("""{"cursor":"x","recentIds":[]}""")
        assertEquals(null, store.load("u1"))
        for (f in dir.listFiles()!!) f.writeText("""{"cursor":-1,"recentIds":[]}""")
        assertEquals(null, store.load("u1"))
        store.save("u1", 42, listOf("p3"))
        assertEquals(PushCursorState(42, listOf("p3")), store.load("u1"))
    }

    @Test
    fun `AC-PUSH-03-16（Android）：游标文件独立于本地库，退出登录清库后新实例按原游标补拉，内容不落进游标文件`() {
        val dbDir = tmp.newFolder()
        val pushDir = tmp.newFolder()
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        fun client(t: FakeTransport) = IMClient(
            IMClientOptions(
                url = "ws://t/ws",
                database = AndroidLocalDatabase.open(File(dbDir, "im.db")),
                transport = t,
                clock = FakeClock(),
                dispatcher = dispatcher,
                pushCursorStore = AndroidPushCursorStore(pushDir),
            ),
        )
        val scope = CoroutineScope(UnconfinedTestDispatcher(scheduler) + SupervisorJob())
        val transport = FakeTransport { scheduler.runCurrent() }
        val im = client(transport)
        val got = ArrayList<String>()
        val job = scope.launch { im.pushes.collect { got.add(it.pushId) } }
        scheduler.runCurrent()
        im.connect("t")
        scheduler.runCurrent()
        val c = transport.current
        c.open()
        c.ack("auth", Json.parseToJsonElement("""{"userId":"alice","serverTime":1}"""))
        c.ack("pushSync", Json.parseToJsonElement("""{"pushes":[{"pushId":"p41","pushSeq":41,"type":"notice","data":{"text":"$secret"},"ts":5,"target":"user"}],"hasMore":false,"latestSeq":41}"""))
        assertEquals(listOf("p41"), got)
        assertEquals(emptyList(), filesContaining(pushDir, secret))
        assertEquals(1, filesContaining(pushDir, "p41").size)
        scope.launch { im.disconnect() }
        scheduler.runCurrent()
        scope.launch { im.close() }
        scheduler.runCurrent()
        job.cancel()

        val t2 = FakeTransport { scheduler.runCurrent() }
        val im2 = client(t2)
        scope.launch { im2.pushes.collect { got.add(it.pushId) } }
        scheduler.runCurrent()
        im2.connect("t")
        scheduler.runCurrent()
        val c2 = t2.current
        c2.open()
        c2.ack("auth", Json.parseToJsonElement("""{"userId":"alice","serverTime":9}"""))
        val data = c2.last("pushSync")["data"].toString()
        assertEquals("""{"afterSeq":41,"limit":100}""", data)
        scope.launch { im2.close() }
        scheduler.runCurrent()
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }
}
