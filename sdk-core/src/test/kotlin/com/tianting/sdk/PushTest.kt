// pushes 流里共享用例没有覆盖的部分：Kotlin 的收集者语义、持久化路径（库重建、Schema 升级、被踢）、落盘时机、日志、读帧不被阻塞。
package com.tianting.sdk

import com.tianting.sdk.jdbc.JdbcLocalDatabase
import com.tianting.sdk.testing.FakeConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PushTest : SdkTest() {
    private val secret = "ZXQ-PUSH-SECRET-777"

    private fun frame(pushId: String = "p1", seq: Long? = null, target: String = "user", ts: Long = 2_000_000_000_000, extra: Map<String, Any?> = emptyMap()): JsonObject {
        val m = linkedMapOf<String, Any?>("pushId" to pushId, "type" to "notice", "data" to obj("text" to secret), "ts" to ts, "target" to target)
        seq?.let { m["pushSeq"] = it }
        return obj("cmd" to "push", "data" to js(m + extra))
    }

    private fun pushJson(pushId: String, seq: Long, ts: Long = 2_000_000_000_000) =
        obj("pushId" to pushId, "pushSeq" to seq, "type" to "notice", "data" to obj("text" to secret), "ts" to ts, "target" to "user")

    private fun syncAck(vararg pushes: JsonObject, hasMore: Boolean = false, latestSeq: Long) =
        obj("pushes" to pushes.toList(), "hasMore" to hasMore, "latestSeq" to latestSeq)

    private fun Harness.collector(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(scheduler) + SupervisorJob())

    /** 一个不断收集的订阅者。 */
    private fun Harness.collect(into: MutableList<Push>): Job = collector().launch { im.pushes.collect { into.add(it) } }.also { flush() }

    private fun Harness.authed(user: String = "alice"): FakeConnection {
        connect("tok-SECRET-123")
        val c = transport.current
        c.open()
        c.ack("auth", obj("userId" to user, "serverTime" to clock.now()))
        flush()
        return c
    }

    private fun syncs(c: FakeConnection): List<JsonObject> = c.frames("pushSync").map { it.dataObj }

    private fun harness(store: PushCursorStore? = null, database: LocalDatabase? = null, limits: PushLimits = PushLimits()): Harness =
        setup(database = database, pushCursorStore = store, pushLimits = limits)

    private fun Harness.closeNow() {
        val d = start { close() }
        await(d)
    }

    @Test
    fun `AC-PUSH-03-7：有收集者时 auth 成功自动发一次 pushSync，limit 固定 100`() {
        val h = harness()
        h.collect(ArrayList())
        assertEquals(listOf(obj("afterSeq" to 0, "limit" to 100)), syncs(h.authed()))
    }

    @Test
    fun `AC-PUSH-03-25、AC-PUSH-08-9 的 Kotlin 对应：events、pushSynced 的收集者不算订阅者`() {
        val h = harness()
        val scope = h.collector()
        scope.launch { h.im.events.collect { } }
        scope.launch { h.im.pushSynced.collect { } }
        h.flush()
        val c = h.authed()
        c.receive(frame(seq = 1))
        h.clock.advance(60_000)
        assertEquals(emptyList(), syncs(c))
        assertEquals(0L, h.im.pushCursor())
    }

    @Test
    fun `AC-PUSH-03-24：convs 不回也照常补拉和交付；pushSync 失败不影响 convs`() {
        val h = harness()
        val got = ArrayList<Push>()
        h.collect(got)
        val c = h.authed()
        assertTrue(c.frames("convs").isNotEmpty())
        c.ack("pushSync", syncAck(pushJson("p1", 1), latestSeq = 1))
        assertEquals(listOf("p1"), got.map { it.pushId })

        val h2 = harness()
        h2.collect(ArrayList())
        val c2 = h2.authed()
        c2.ackError("pushSync", 5000)
        c2.ack("convs", obj("convs" to emptyList<Any>(), "truncated" to false))
        assertEquals(ConnState.CONNECTED, h2.im.state.value)
    }

    @Test
    fun `AC-PUSH-01-3：帧里有未知字段时正常交付；Push 的字段都对`() {
        val h = harness()
        val got = ArrayList<Push>()
        h.collect(got)
        val c = h.authed()
        c.receive(frame(target = "room", extra = mapOf("foo" to "bar", "roomId" to "r1")))
        val p = got.single()
        assertEquals(listOf<Any?>("p1", "notice", 2_000_000_000_000L, "room", "r1", null, false), listOf(p.pushId, p.type, p.ts, p.target, p.roomId, p.pushSeq, p.replayed))
        assertEquals(obj("text" to secret), p.data)
        assertFalse(secret in p.toString(), "toString 不带 data")
    }

    @Test
    fun `AC-PUSH-01-5：不认识的顶层 cmd 忽略，连接保持`() {
        val h = harness()
        val c = h.authed()
        c.receive(obj("cmd" to "futureThing", "data" to obj("a" to 1)))
        assertEquals(ConnState.CONNECTED, h.im.state.value)
        assertNull(c.closedWith)
    }

    @Test
    fun `不合法的帧丢弃，不影响后面的帧`() {
        val h = harness()
        val got = ArrayList<Push>()
        h.collect(got)
        val c = h.authed()
        c.receive(obj("cmd" to "push", "data" to obj("pushId" to 1)))
        c.receive(obj("cmd" to "push"))
        c.receive(obj("cmd" to "push", "data" to obj("pushId" to "x", "type" to "t", "ts" to "1", "target" to "all")))
        c.receive(frame(seq = null, extra = mapOf("pushSeq" to "x")))
        c.receive(frame(seq = null, extra = mapOf("pushSeq" to 0)))
        c.receive(frame("ok", target = "all"))
        assertEquals(listOf("ok"), got.map { it.pushId })
    }

    @Test
    fun `AC-PUSH-08-2（Kotlin）：收集器抛错后 SDK 不受影响，其他订阅者继续，重新 collect 可收到后续推送`() {
        val h = harness()
        val other = ArrayList<Push>()
        h.collect(other)
        val thrown = ArrayList<Throwable>()
        val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, e -> thrown.add(e) }
        val thrower = ArrayList<String>()
        CoroutineScope(UnconfinedTestDispatcher(h.scheduler) + SupervisorJob() + handler).launch {
            h.im.pushes.collect {
                thrower.add(it.pushId)
                throw IllegalStateException("boom")
            }
        }
        h.flush()
        val c = h.authed()
        c.ack("pushSync", syncAck(latestSeq = 0))
        c.receive(frame("x1", seq = 1))
        c.receive(frame("x2", seq = 2))
        assertEquals(listOf("x1"), thrower)
        assertEquals(1, thrown.size)
        assertEquals(listOf("x1", "x2"), other.map { it.pushId })
        assertEquals(2L, h.im.pushCursor())
        val again = ArrayList<Push>()
        h.collect(again)
        c.receive(frame("x3", seq = 3))
        assertEquals(listOf("x3"), again.map { it.pushId })
        assertEquals(listOf("x1", "x2", "x3"), other.map { it.pushId })
    }

    @Test
    fun `回调内退订的各种写法（first、takeWhile、抛错）：这一条算交付，后面的不交付，游标不动`() {
        for (mode in listOf("first", "takeWhile", "throw")) {
            val h = harness()
            val got = ArrayList<String>()
            val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> }
            val job = CoroutineScope(UnconfinedTestDispatcher(h.scheduler) + SupervisorJob() + handler).launch {
                when (mode) {
                    "first" -> got.add(h.im.pushes.first { it.pushId == "b" }.pushId)
                    "takeWhile" -> h.im.pushes.takeWhile { got.add(it.pushId); it.pushId != "b" }.collect { }
                    else -> h.im.pushes.collect {
                        got.add(it.pushId)
                        if (it.pushId == "b") throw IllegalStateException("boom")
                    }
                }
            }
            h.flush()
            val c = h.authed()
            c.ack("pushSync", syncAck(pushJson("a", 1), pushJson("b", 2), pushJson("c", 3), hasMore = true, latestSeq = 9))
            assertTrue(job.isCompleted, mode)
            assertEquals(listOf("b"), got.filter { it != "a" }, mode)
            assertEquals(0L, h.im.pushCursor(), "$mode：游标不前进")
            assertEquals(1, syncs(c).size, "$mode：不取下一页")
        }
    }

    @Test
    fun `AC-PUSH-08-10：没有收集者时 500 条全员帧加 1 条带序号的，读帧不被阻塞；收集后先 500 条，再经补拉拿到那一条`() {
        val h = harness()
        val c = h.authed()
        val conv = "c2c:alice:bob"
        c.ack("convs", obj("convs" to listOf(conv(conv)), "truncated" to false))
        h.call { loadHistory(conv) }
        for (n in 1..500) c.receive(frame("a$n", target = "all"))
        c.receive(frame("s1", seq = 1))
        c.receive(msgFrame(msg(conv, 1))) // 同时到达的消息正常处理
        assertEquals(listOf(1L), h.im.messages(conv).map { it.seq })
        assertEquals(emptyList(), syncs(c))
        assertEquals(0L, h.im.pushCursor())

        val got = ArrayList<Push>()
        h.collect(got)
        assertEquals((1..500).map { "a$it" }, got.map { it.pushId })
        assertEquals(listOf(obj("afterSeq" to 0, "limit" to 100)), syncs(c))
        c.ack("pushSync", syncAck(pushJson("s1", 1), latestSeq = 1))
        assertEquals("s1", got.last().pushId)
        assertEquals(501, got.size)
        assertEquals(1L, h.im.pushCursor())
    }

    /** 收集者卡在一条上：每条要手动放行。 */
    private class Gate {
        val got = ArrayList<String>()
        var release = CompletableDeferred<Unit>()
        fun open() {
            val r = release
            release = CompletableDeferred()
            r.complete(Unit)
        }
    }

    private fun Harness.gated(g: Gate) = collector().launch {
        im.pushes.collect {
            g.got.add(it.pushId)
            g.release.await()
        }
    }.also { flush() }

    @Test
    fun `一页交付完才前进游标、取下一页：收集者慢时 SDK 线程不被占住，游标和请求都在等`() {
        val store = MemoryCursorStore()
        val h = harness(store)
        val g = Gate()
        h.gated(g)
        val c = h.authed()
        c.ack("pushSync", syncAck(pushJson("a", 1), pushJson("b", 2), hasMore = true, latestSeq = 5))
        assertEquals(listOf("a"), g.got)
        assertEquals(0L, h.im.pushCursor())
        assertEquals(1, syncs(c).size)
        assertEquals(0, store.saves, "交付完才落盘")

        val conv = "c2c:alice:bob"
        c.ack("convs", obj("convs" to listOf(conv(conv)), "truncated" to false))
        h.call { loadHistory(conv) }
        c.receive(msgFrame(msg(conv, 1)))
        assertEquals(listOf(1L), h.im.messages(conv).map { it.seq }, "消息照常处理")

        g.open()
        h.flush()
        assertEquals(listOf("a", "b"), g.got)
        assertEquals(0L, h.im.pushCursor())
        g.open()
        h.flush()
        assertEquals(2L, h.im.pushCursor())
        assertEquals(listOf(obj("afterSeq" to 0, "limit" to 100), obj("afterSeq" to 2, "limit" to 100)), syncs(c))
        assertEquals(1, store.saves)
    }

    @Test
    fun `收集者卡住时队列有界：带序号的溢出后丢弃、恢复后补拉；不带序号的丢最旧；都不阻塞读帧`() {
        val h = harness(limits = PushLimits(stashMax = 3, noSeqQueueMax = 2))
        val g = Gate()
        h.gated(g)
        val c = h.authed()
        c.ack("pushSync", syncAck(latestSeq = 0))
        c.receive(frame("first", seq = 1)) // 卡在这一条
        assertEquals(listOf("first"), g.got)
        for (n in 2L..8L) c.receive(frame("s$n", seq = n))
        for (n in 1..4) c.receive(frame("a$n", target = "all"))
        var rounds = 0
        repeat(12) {
            g.open()
            h.flush()
            rounds++
        }
        // 队列里留下 s2、s3、s4（再多的丢弃），全员帧只留最新两条。
        assertEquals(listOf("first", "s2", "s3", "s4", "a3", "a4"), g.got)
        assertEquals(4L, h.im.pushCursor())
        assertEquals(listOf(obj("afterSeq" to 0, "limit" to 100), obj("afterSeq" to 4, "limit" to 100)), syncs(c), "空闲后补拉丢掉的")
        c.ack("pushSync", syncAck(pushJson("s5", 5), pushJson("s6", 6), pushJson("s7", 7), pushJson("s8", 8), latestSeq = 8))
        repeat(5) {
            g.open()
            h.flush()
        }
        assertEquals(listOf("s5", "s6", "s7", "s8"), g.got.takeLast(4))
        assertEquals(8L, h.im.pushCursor())
    }

    @Test
    fun `AC-PUSH-07-3：退出登录打断卡住的交付，u1 剩下的帧不会交给 u2；订阅者继续有效`() {
        val h = harness()
        val g = Gate()
        h.gated(g)
        val c = h.authed("u1")
        c.ack("pushSync", syncAck(pushJson("u1-a", 1), pushJson("u1-b", 2), latestSeq = 2))
        assertEquals(listOf("u1-a"), g.got)
        c.receive(frame("u1-live", seq = 3))
        h.call { disconnect() }
        g.open()
        h.flush()
        assertEquals(listOf("u1-a"), g.got)
        assertNull(h.im.pushCursor())

        val c2 = h.authed("u2")
        assertEquals(listOf(obj("afterSeq" to 0, "limit" to 100)), syncs(c2))
        c2.ack("pushSync", syncAck(pushJson("u2-a", 1), latestSeq = 1))
        assertEquals(listOf("u1-a", "u2-a"), g.got)
    }

    @Test
    fun `AC-PUSH-08-3：日志里没有 data 和 token，包括错误路径和收集器抛错`() {
        val h = harness()
        CoroutineScope(UnconfinedTestDispatcher(h.scheduler) + SupervisorJob() + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> }).launch {
            h.im.pushes.collect { throw IllegalStateException(secret) }
        }
        h.flush()
        val c = h.authed()
        c.receive(frame(seq = 9))
        h.clock.advance(500)
        c.ackError("pushSync", 5000)
        h.clock.advance(5000)
        c.receive(obj("cmd" to "push", "data" to obj("pushId" to 1, "data" to obj("text" to secret))))
        c.ack("pushSync", obj("pushes" to listOf(obj("pushId" to 2, "data" to obj("text" to secret))), "hasMore" to false, "latestSeq" to 9))
        assertTrue(h.logs.isNotEmpty())
        val all = h.logs.joinToString("\n")
        assertFalse(secret in all)
        assertFalse("tok-SECRET-123" in all)
        assertFalse("tok-SECRET-123" in c.url)
    }

    @Test
    fun `真实的 10 秒请求超时算失败，1 秒后重试`() {
        val h = harness()
        h.collect(ArrayList())
        val c = h.authed()
        assertEquals(1, syncs(c).size)
        h.clock.advance(10_000)
        h.clock.advance(999)
        assertEquals(1, syncs(c).size)
        h.clock.advance(1)
        assertEquals(2, syncs(c).size)
    }

    @Test
    fun `不合法的 ack 按失败退避，不动游标`() {
        val h = harness()
        h.collect(ArrayList())
        val c = h.authed()
        c.ack("pushSync", obj("pushes" to "x", "hasMore" to false, "latestSeq" to 3))
        assertEquals(0L, h.im.pushCursor())
        h.clock.advance(1000)
        assertEquals(2, syncs(c).size)
    }

    @Test
    fun `hasMore=true 但没有进展（页内序号都不大于 afterSeq）：按失败退避，不原地循环`() {
        val h = harness()
        h.collect(ArrayList())
        val c = h.authed()
        c.ack("pushSync", syncAck(latestSeq = 5))
        c.drop(1006)
        h.clock.advance(1000)
        val c2 = h.transport.current
        c2.open()
        c2.ack("auth", obj("userId" to "alice", "serverTime" to h.clock.now()))
        h.flush()
        c2.ack("pushSync", syncAck(pushJson("old", 5), hasMore = true, latestSeq = 9))
        assertEquals(1, syncs(c2).size)
        h.clock.advance(1000)
        assertEquals(2, syncs(c2).size)
    }

    // ---- 持久化（4.9 节） ----

    private fun corrupt(file: File) = file.writeBytes(ByteArray(4096) { 0x5A })

    private fun Harness.oneRound(user: String = "u1", want: Long = 41, latest: Long = want): FakeConnection {
        val c = authed(user)
        assertEquals(listOf(obj("afterSeq" to want, "limit" to 100)), syncs(c).takeLast(1))
        c.ack("pushSync", syncAck(latestSeq = latest))
        return c
    }

    @Test
    fun `AC-PUSH-03-16：退出登录再登录、被踢后重登、库损坏重建、Schema 升级，都按原游标补拉，本地库被清也不回放`(@TempDir dir: File) {
        val store = MemoryCursorStore()
        store.data["u1"] = PushCursorState(41, (1..41).map { "p$it" })
        val got = ArrayList<Push>()

        // 退出登录（清库）再登录，同一实例
        val db = File(dir, "a.db")
        val h = harness(store, JdbcLocalDatabase(db))
        h.collect(got)
        h.oneRound()
        h.call { disconnect() }
        h.oneRound()
        assertEquals(PushCursorState(41, (1..41).map { "p$it" }), store.data["u1"])

        // 被踢：清库；同一实例重登，以及新实例重登
        val c = h.transport.current
        c.receive(event("kicked", "reason" to "replaced"))
        c.drop(4011)
        h.flush()
        assertTrue(h.im.fatal.value != null)
        h.oneRound()
        h.closeNow()
        val h2 = harness(store, JdbcLocalDatabase(db))
        h2.collect(got)
        h2.oneRound()
        h2.closeNow()

        // 库损坏重建
        corrupt(db)
        val h3 = harness(store, runCatching { JdbcLocalDatabase(db) }.getOrElse { db.delete(); JdbcLocalDatabase(db) })
        h3.collect(got)
        h3.oneRound()
        h3.closeNow()

        // Schema 升级：user_version 不同，表全部重建
        db.delete()
        harness(null, JdbcLocalDatabase(db)).closeNow()
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { it.createStatement().execute("PRAGMA user_version = 999") }
        val h4 = harness(store, JdbcLocalDatabase(db))
        h4.collect(got)
        h4.oneRound()
        assertEquals(emptyList(), got, "一条都不回放")

        // 见过的集合也还在：服务端真的回放旧帧时也不重复交付
        h4.transport.current.receive(frame("p41", seq = 41))
        assertEquals(emptyList(), got)
    }

    @Test
    fun `AC-PUSH-08-7：回调已执行、落盘前进程退出，重启后这一条再交付一次，replayed 按规则计算`() {
        val store = MemoryCursorStore()
        val h = harness(store)
        val g = Gate()
        h.gated(g)
        val c = h.authed("u1")
        val t0 = h.clock.now()
        c.ack("pushSync", syncAck(pushJson("p1", 1, ts = t0 - 10), latestSeq = 1))
        assertEquals(listOf("p1"), g.got, "回调已执行")
        assertNull(store.data["u1"], "还没有落盘")
        h.closeNow() // 进程退出

        val h2 = harness(store)
        val got = ArrayList<Push>()
        h2.collect(got)
        val c2 = h2.authed("u1")
        assertEquals(listOf(obj("afterSeq" to 0, "limit" to 100)), syncs(c2))
        c2.ack("pushSync", syncAck(pushJson("p1", 1, ts = t0 - 10), latestSeq = 1))
        assertEquals(listOf("p1"), got.map { it.pushId })
        assertTrue(got[0].replayed)
        assertEquals(PushCursorState(1, listOf("p1")), store.data["u1"])
    }

    @Test
    fun `AC-PUSH-03-34：没有注入 PushCursorStore 时不报错，新实例从 afterSeq=0 开始；database 不为 null 也一样`(@TempDir dir: File) {
        for (db in listOf<LocalDatabase?>(null, JdbcLocalDatabase(File(dir, "b.db")))) {
            val h = harness(null, db)
            val got = ArrayList<Push>()
            h.collect(got)
            val c = h.authed("u1")
            c.ack("pushSync", syncAck(pushJson("p1", 1), latestSeq = 1))
            c.receive(frame("p2", seq = 2))
            assertEquals(listOf("p1", "p2"), got.map { it.pushId })
            assertEquals(2L, h.im.pushCursor())
            h.closeNow()
        }
        val db = JdbcLocalDatabase(File(dir, "b.db"))
        val h = harness(null, db)
        val got = ArrayList<Push>()
        h.collect(got)
        val c = h.authed("u1")
        assertEquals(listOf(obj("afterSeq" to 0, "limit" to 100)), syncs(c), "游标没有写进本地库")
        c.ack("pushSync", syncAck(pushJson("p1", 1), latestSeq = 1))
        assertEquals(listOf("p1"), got.map { it.pushId }, "新实例回放")
    }

    @Test
    fun `4_9 节：database 为 null 但注入了 store 时照常持久化，两者互不依赖`() {
        val store = MemoryCursorStore()
        val h = harness(store, null)
        h.collect(ArrayList())
        h.authed("u1").ack("pushSync", syncAck(pushJson("p1", 1), latestSeq = 1))
        h.closeNow()
        val h2 = harness(store, null)
        h2.collect(ArrayList())
        assertEquals(listOf(obj("afterSeq" to 1, "limit" to 100)), syncs(h2.authed("u1")))
    }

    @Test
    fun `4_9 节按批落盘：一个 ack 或一次实时帧处理完落盘一次；内容只有游标和 pushId`() {
        val store = MemoryCursorStore()
        val h = harness(store)
        h.collect(ArrayList())
        val c = h.authed("u1")
        assertEquals(0, store.saves, "连接本身不写")
        c.ack("pushSync", obj("pushes" to (1L..100L).map { pushJson("p$it", it) }, "hasMore" to true, "latestSeq" to 150))
        assertEquals(1, store.saves)
        assertEquals(100L, store.data["u1"]!!.cursor)
        c.ack("pushSync", obj("pushes" to (101L..150L).map { pushJson("p$it", it) }, "hasMore" to false, "latestSeq" to 150))
        assertEquals(2, store.saves)
        c.receive(frame("p151", seq = 151))
        assertEquals(3, store.saves)
        c.receive(frame("p151", seq = 151)) // 见过：什么都没变，不写
        assertEquals(3, store.saves)
        c.receive(frame("a1", target = "all"))
        assertEquals(4, store.saves)
        assertEquals(PushCursorState(151, (1..151).map { "p$it" } + "a1"), store.data["u1"])
    }

    @Test
    fun `AC-PUSH-06-18：默认上限下持久化的集合也只留最近 2000 个，加载时也按上限`() {
        val store = MemoryCursorStore()
        store.data["u1"] = PushCursorState(0, (1..2500).map { "old$it" })
        val h = harness(store)
        val got = ArrayList<Push>()
        h.collect(got)
        val c = h.authed("u1")
        c.ack("pushSync", syncAck(pushJson("new", 1), pushJson("old2500", 2), pushJson("old1", 3), latestSeq = 3))
        assertEquals(listOf("new", "old1"), got.map { it.pushId }, "old1 不在最近 2000 个里，old2500 在")
        assertEquals(2000, store.data["u1"]!!.recentIds.size)
    }

    @Test
    fun `存储抛异常不影响收发：load 失败按没有游标，save 失败记日志继续，日志只写异常类型`() {
        val store = object : PushCursorStore {
            override fun load(userId: String): PushCursorState = throw IllegalStateException(secret)
            override fun save(userId: String, cursor: Long, recentIds: List<String>) = throw IllegalStateException(secret)
        }
        val h = harness(store)
        val got = ArrayList<Push>()
        h.collect(got)
        val c = h.authed("u1")
        assertEquals(listOf(obj("afterSeq" to 0, "limit" to 100)), syncs(c))
        c.ack("pushSync", syncAck(pushJson("p1", 1), latestSeq = 1))
        c.receive(frame("p2", seq = 2))
        assertEquals(listOf("p1", "p2"), got.map { it.pushId })
        assertEquals(2L, h.im.pushCursor())
        assertTrue(h.logs.any { "IllegalStateException" in it })
        assertFalse(h.logs.any { secret in it })
    }

    @Test
    fun `AC-PUSH-07-3：有持久化时换号各用各的键，u1 的游标不给 u2`() {
        val store = MemoryCursorStore()
        store.data["u1"] = PushCursorState(41, listOf("a"))
        store.data["u2"] = PushCursorState(7, listOf("b"))
        val h = harness(store)
        h.collect(ArrayList())
        h.oneRound("u1")
        h.call { disconnect() }
        h.oneRound("u2", want = 7)
        h.call { disconnect() }
        h.oneRound("u1")
        assertEquals(41, store.data["u1"]!!.cursor)
        assertEquals(7, store.data["u2"]!!.cursor)
    }
}
