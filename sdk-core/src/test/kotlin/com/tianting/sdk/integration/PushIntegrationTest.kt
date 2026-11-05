// 系统推送 × 真实 im-server（PRD-live 第 4 节，IM_PUSH_ENABLED=1）：真 OkHttp、真协程线程。开关关闭的情况单独起一个服务端。
package com.tianting.sdk.integration

import com.tianting.sdk.*
import com.tianting.sdk.jdbc.JdbcLocalDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 打开系统推送（默认关闭）。 */
val PUSH_ENV = mapOf("IM_PUSH_ENABLED" to "1")

/** 真实 WebSocket，多两件事：记下发出的帧，能模拟闪断。 */
private class SpyTransport(private val inner: Transport = OkHttpTransport()) : Transport {
    val sent: MutableList<String> = Collections.synchronizedList(ArrayList())
    @Volatile private var conn: TransportConnection? = null

    override fun open(url: String, handlers: TransportHandlers): TransportConnection {
        val c = inner.open(url, handlers)
        conn = c
        return object : TransportConnection {
            override fun send(text: String) {
                sent.add(text)
                c.send(text)
            }

            override fun close(code: Int, reason: String) = c.close(code, reason)
        }
    }

    /** 闪断：服务端看到连接断开，客户端按退避重连。客户端 close 后回调被屏蔽，所以另外通知一次。 */
    fun syncs(): List<JsonObject> = synchronized(sent) { sent.toList() }.map { Json.parseToJsonElement(it).jsonObject }
        .filter { it["cmd"]!!.str == "pushSync" }.map { it.dataObj }
}

private class FlashTransport(private val inner: OkHttpTransport = OkHttpTransport()) : Transport {
    @Volatile private var cut: (() -> Unit)? = null

    override fun open(url: String, handlers: TransportHandlers): TransportConnection {
        val c = inner.open(url, handlers)
        cut = {
            c.close(4000, "flash")
            handlers.onClose(1006, "")
        }
        return c
    }

    fun flash() = cut?.invoke()
}

private class CaptureLogger : Logger {
    val lines: MutableList<String> = Collections.synchronizedList(ArrayList())
    override fun debug(message: String) { lines.add(message) }
    override fun warn(message: String) { lines.add(message) }
    override fun error(message: String) { lines.add(message) }
}

private class Rig(val im: IMClient, val transport: SpyTransport, val flash: FlashTransport, val logs: CaptureLogger) {
    val got: MutableList<Push> = Collections.synchronizedList(ArrayList())
    val synced: MutableList<Long> = Collections.synchronizedList(ArrayList())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

/** 存在文件里的最简实现：SDK 只认接口，Android 的文件实现在 sdk-android 里测。 */
private class FileCursorStore(private val dir: File) : PushCursorStore {
    override fun load(userId: String): PushCursorState? {
        val f = File(dir, "$userId.txt")
        if (!f.exists()) return null
        val lines = f.readLines()
        return PushCursorState(lines[0].toLong(), lines.drop(1))
    }

    override fun save(userId: String, cursor: Long, recentIds: List<String>) {
        File(dir, "$userId.txt").writeText((listOf(cursor.toString()) + recentIds).joinToString("\n"))
    }
}

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PushIntegrationTest {
    private lateinit var srv: TestServer
    private lateinit var off: TestServer
    private val rigs = ArrayList<Rig>()
    private var n = 0

    private fun uid(base: String) = "$base${System.currentTimeMillis().toString(36)}${n++}"

    @BeforeAll
    fun up() = runBlocking {
        srv = TestServer.start(PUSH_ENV + MODERATION_ENV)
        off = TestServer.start()
    }

    @AfterEach
    fun closeClients() = runBlocking {
        rigs.forEach {
            it.scope.cancel()
            runCatching { it.im.close() }
        }
        rigs.clear()
    }

    @AfterAll
    fun down() {
        srv.stop()
        off.stop()
    }

    private fun test(block: suspend CoroutineScope.() -> Any?) { runBlocking { withTimeout(90_000) { block() } } }

    private suspend fun rig(
        server: TestServer = srv,
        store: PushCursorStore? = null,
        database: LocalDatabase? = null,
        pushStart: PushStart = PushStart.BEGINNING,
        platform: Platform = Platform.ANDROID,
        collect: Boolean = true,
    ): Rig {
        val flash = FlashTransport()
        val spy = SpyTransport(flash)
        val logs = CaptureLogger()
        val im = IMClient(
            IMClientOptions(
                url = server.wsURL, platform = platform, transport = spy, logger = logs, database = database, pushCursorStore = store, pushStart = pushStart,
                timings = IMClientTimings(backoffBaseMs = 50, backoffMaxMs = 100),
            ),
        )
        val r = Rig(im, spy, flash, logs)
        rigs.add(r)
        if (collect) r.scope.launch { im.pushes.collect { r.got.add(it) } }
        r.scope.launch { im.pushSynced.collect { r.synced.add(it) } }
        delay(50) // 收集者注册好（连接之前有没有注册不影响结果，只是让第一轮的断言确定）
        return r
    }

    private suspend fun <T : Any> until(what: String, timeout: Long = 8_000, fn: suspend () -> T?): T {
        val deadline = System.currentTimeMillis() + timeout
        while (true) {
            val v = fn()
            if (v != null && v != false) return v
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            delay(10)
        }
    }

    private suspend fun online(r: Rig, u: String, server: TestServer = srv) {
        r.im.connect(server.token(u))
        until("$u connected") { r.im.state.value == ConnState.CONNECTED && r.im.userId.value == u }
    }

    /** 登录并等第一轮补拉结束。 */
    private suspend fun ready(r: Rig, u: String) {
        val before = r.synced.size
        online(r, u)
        until("$u first pushSynced") { r.synced.size > before }
    }

    private fun users(vararg ids: String) = srv.importUsers(*ids.map { it to null }.toTypedArray())

    private fun pushTo(u: String, text: String = "t-${UUID.randomUUID()}", ttl: Long? = null, pushId: String = UUID.randomUUID().toString(), type: String = "notice"): String {
        srv.signed(
            "POST",
            "/admin/push",
            objNoNull("target" to "user", "userIds" to listOf(u), "type" to type, "data" to obj("text" to text), "pushId" to pushId, "ttlSeconds" to ttl),
        )
        return pushId
    }

    private fun ids(r: Rig) = synchronized(r.got) { r.got.map { it.pushId } }

    @Test
    fun `AC-PUSH-01-1：在线用户 3 秒内收到，字段正确；游标和 pushSynced 正确`() = test {
        val u = uid("live")
        users(u)
        val r = rig()
        ready(r, u)
        assertEquals(listOf(0L), r.synced.toList())
        val start = System.currentTimeMillis()
        val text = "t-${UUID.randomUUID()}"
        val id = pushTo(u, text)
        val p = until("live push", 3_000) { r.got.firstOrNull() }
        assertTrue(System.currentTimeMillis() - start < 3_000)
        assertEquals(id, p.pushId)
        assertEquals("notice", p.type)
        assertEquals(obj("text" to text), p.data)
        assertEquals("user", p.target)
        assertEquals(1L, p.pushSeq)
        assertFalse(p.replayed)
        until("cursor") { r.im.pushCursor() == 1L }
    }

    @Test
    fun `离线期间的推送上线后按序收到，replayed=true；AC-PUSH-03-15 120 条翻两页；AC-PUSH-07-4 不含别人的`() = test {
        val u = uid("page")
        val other = uid("other")
        users(u, other)
        pushTo(other)
        val sent = (1..120).map { pushTo(u) }
        delay(5) // T0 严格晚于这些推送的 ts
        val r = rig()
        ready(r, u)
        assertEquals(sent, ids(r))
        assertEquals((1L..120L).toList(), r.got.map { it.pushSeq })
        assertTrue(r.got.all { it.replayed })
        assertEquals(listOf(120L), r.synced.toList())
        assertEquals(120L, r.im.pushCursor())
        assertEquals(listOf(obj("afterSeq" to 0, "limit" to 100), obj("afterSeq" to 100, "limit" to 100)), r.transport.syncs())
    }

    @Test
    fun `AC-PUSH-03-5、AC-PUSH-04-1：ttlSeconds=0 和全员只投在线，不带 pushSeq，不动游标；离线的补不到`() = test {
        val on = uid("on")
        val gone = uid("gone")
        users(on, gone)
        val r = rig()
        ready(r, on)
        val zero = pushTo(on, ttl = 0)
        pushTo(gone, ttl = 0)
        val allId = UUID.randomUUID().toString()
        srv.signed("POST", "/admin/push", obj("target" to "all", "type" to "notice", "data" to obj("text" to "all"), "pushId" to allId))
        until("both frames") { r.got.size == 2 }
        assertEquals(setOf(zero, allId), ids(r).toSet())
        assertTrue(r.got.all { it.pushSeq == null && !it.replayed })
        assertEquals("all", r.got.first { it.pushId == allId }.target)
        assertEquals(0L, r.im.pushCursor())

        val late = rig()
        ready(late, gone)
        delay(100)
        assertEquals(emptyList(), late.got.toList())
    }

    @Test
    fun `AC-PUSH-07-1、07-2：两个在线连接各收一次；离线设备之后自己补收`() = test {
        val u = uid("multi")
        users(u)
        val web = rig(platform = Platform.WEB)
        val pc = rig(platform = Platform.PC)
        ready(web, u)
        ready(pc, u)
        val id = pushTo(u)
        until("both") { web.got.size == 1 && pc.got.size == 1 }
        assertEquals(listOf(id), ids(web))
        assertEquals(listOf(id), ids(pc))
        val android = rig()
        ready(android, u)
        assertEquals(listOf(id), ids(android))
        delay(100)
        assertEquals(1, web.got.size)
    }

    @Test
    fun `AC-PUSH-07-3、07-5、07-6 无持久化：同实例换号各交付一次，回到 u1 从 0 回放`() = test {
        val u1 = uid("swap")
        val u2 = uid("swap")
        users(u1, u2)
        val id = UUID.randomUUID().toString()
        srv.signed("POST", "/admin/push", obj("target" to "user", "userIds" to listOf(u1, u2), "type" to "notice", "data" to obj("text" to "shared"), "pushId" to id))
        delay(5)
        val r = rig()
        ready(r, u1)
        assertEquals(listOf(id), ids(r))
        r.im.disconnect()
        until("cursor cleared") { r.im.pushCursor() == null }
        ready(r, u2)
        assertEquals(listOf(id, id), ids(r))
        r.im.disconnect()
        ready(r, u1)
        assertEquals(listOf(id, id, id), ids(r))
        assertTrue(r.got[2].replayed)
        assertEquals(obj("afterSeq" to 0, "limit" to 100), r.transport.syncs().last())
    }

    @Test
    fun `AC-PUSH-03-34：没有注入 store 且 database 为 null，新实例从 afterSeq=0 回放，不报错`() = test {
        val u = uid("nostore")
        users(u)
        val id = pushTo(u)
        delay(5)
        val a = rig()
        ready(a, u)
        assertEquals(listOf(id), ids(a))
        a.im.close()
        val b = rig()
        ready(b, u)
        assertEquals(listOf(obj("afterSeq" to 0, "limit" to 100)), b.transport.syncs())
        assertEquals(listOf(id), ids(b))
        assertTrue(b.got[0].replayed)
    }

    @Test
    fun `AC-PUSH-03-16 真实服务端：退出、被踢（真实封禁）、Schema 升级后都按原游标补拉，已收到的不回放，离线新推送只补新的`(@TempDir dir: File) = test {
        val u = uid("keep")
        users(u)
        val store = FileCursorStore(dir)
        val db = File(dir, "im.db")
        val first = pushTo(u)
        val a = rig(store = store, database = JdbcLocalDatabase(db))
        ready(a, u)
        assertEquals(listOf(first), ids(a))
        assertEquals(1L, a.im.pushCursor())

        // 退出登录：清库、游标留着。
        a.im.disconnect()
        a.im.close()
        val second = pushTo(u)
        val b = rig(store = store, database = JdbcLocalDatabase(db))
        ready(b, u)
        assertEquals(listOf(obj("afterSeq" to 1, "limit" to 100)), b.transport.syncs())
        assertEquals(listOf(second), ids(b), "只补新的")

        // 被踢：真实封禁 → kicked → 关闭码；解封后重登。
        srv.signed("POST", "/admin/users/$u/ban", obj("durationSeconds" to null, "reason" to "it"))
        until("kicked") { b.im.fatal.value != null }
        val third = pushTo(u)
        srv.signed("POST", "/admin/users/$u/unban", obj("reason" to "it"))
        b.im.close()
        val c = rig(store = store, database = JdbcLocalDatabase(db))
        ready(c, u)
        assertEquals(listOf(obj("afterSeq" to 2, "limit" to 100)), c.transport.syncs())
        assertEquals(listOf(third), ids(c))
        c.im.close()

        // Schema 升级：表重建，游标不受影响。
        java.sql.DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { it.createStatement().execute("PRAGMA user_version = 999") }
        val d = rig(store = store, database = JdbcLocalDatabase(db))
        ready(d, u)
        assertEquals(listOf(obj("afterSeq" to 3, "limit" to 100)), d.transport.syncs())
        assertEquals(emptyList(), ids(d))

        // 服务端真的再发同一 pushId：见过的不重复（幂等重放不投递，这里用游标之内不可能出现，直接确认存储里有）。
        assertEquals(listOf(first, second, third), store.load(u)!!.recentIds)
    }

    @Test
    fun `AC-PUSH-03-17：pushStart=LATEST 不回放历史，只收之后的；已有游标时按游标`(@TempDir dir: File) = test {
        val u = uid("latest")
        users(u)
        pushTo(u)
        pushTo(u)
        val store = FileCursorStore(dir)
        val r = rig(pushStart = PushStart.LATEST, store = store)
        ready(r, u)
        assertEquals(emptyList(), ids(r))
        assertEquals(2L, r.im.pushCursor())
        assertEquals(listOf(obj("fromLatest" to true)), r.transport.syncs())
        val id = pushTo(u)
        until("next") { r.got.firstOrNull() }
        assertEquals(id, r.got[0].pushId)
        assertEquals(3L, r.got[0].pushSeq)
        assertFalse(r.got[0].replayed)
        until("saved") { store.load(u)?.cursor == 3L }
        r.im.close()
        val r2 = rig(pushStart = PushStart.LATEST, store = store)
        ready(r2, u)
        assertEquals(listOf(obj("afterSeq" to 3, "limit" to 100)), r2.transport.syncs())
    }

    @Test
    fun `闪断期间的推送重连后补到，replayed=false（T0 不变），不重复；之后的实时帧接上`() = test {
        val u = uid("flash")
        users(u)
        val r = rig()
        ready(r, u)
        val first = pushTo(u)
        until("first") { r.got.size == 1 }
        r.flash.flash()
        until("disconnected") { r.im.state.value != ConnState.CONNECTED }
        val a = pushTo(u)
        val b = pushTo(u)
        until("caught up") { r.got.size == 3 }
        assertEquals(listOf(first, a, b), ids(r))
        assertTrue(r.got.none { it.replayed })
        until("cursor 3") { r.im.pushCursor() == 3L }
        val c = pushTo(u)
        until("live") { r.got.size == 4 }
        assertEquals(c, r.got[3].pushId)
        delay(100)
        assertEquals(4, r.got.size)
    }

    @Test
    fun `AC-PUSH-11-2：服务端跳过一条实时帧，SDK 发现缺口后 500ms 补拉，按序交付`() = test {
        val u = uid("gap")
        users(u)
        val r = rig()
        ready(r, u)
        val lost = UUID.randomUUID().toString()
        srv.admin("POST", "/admin/test/faults/push-skip-delivery", obj("pushId" to lost))
        pushTo(u, pushId = lost)
        delay(100)
        assertEquals(emptyList(), ids(r))
        val next = pushTo(u)
        until("gap filled", 3_000) { r.got.size == 2 }
        assertEquals(listOf(lost, next), ids(r))
        assertTrue(r.got.none { it.replayed })
        assertEquals(obj("afterSeq" to 0, "limit" to 100), r.transport.syncs().last())
    }

    @Test
    fun `AC-PUSH-06-1、06-2：同一 pushId 同内容重放 created=false 只交付一次，不同内容 409`() = test {
        val u = uid("idem")
        users(u)
        val r = rig()
        ready(r, u)
        val id = UUID.randomUUID().toString()
        val body = obj("target" to "user", "userIds" to listOf(u), "type" to "notice", "data" to obj("text" to "once"), "pushId" to id)
        assertEquals("true", srv.signed("POST", "/admin/push", body)["created"]!!.jsonPrimitive.content)
        until("first") { r.got.size == 1 }
        assertEquals("false", srv.signed("POST", "/admin/push", body)["created"]!!.jsonPrimitive.content)
        val conflict = obj("target" to "user", "userIds" to listOf(u), "type" to "notice", "data" to obj("text" to "other"), "pushId" to id)
        assertTrue("409" in assertFailsWith<IllegalStateException> { srv.signed("POST", "/admin/push", conflict) }.message!!)
        delay(200)
        assertEquals(1, r.got.size)
        val inbox = srv.admin("GET", "/admin/test/push-inbox/$u")
        assertEquals(1, inbox["rows"]!!.jsonArray.size)
    }

    @Test
    fun `AC-PUSH-08-3：真实收发和重连之后日志里没有 data，token 只在 auth 帧里、不在 URL`() = test {
        val u = uid("logs")
        users(u)
        val r = rig()
        val token = srv.token(u)
        r.im.connect(token)
        until("first round") { r.synced.size == 1 }
        val secret = "SECRET-${UUID.randomUUID()}"
        pushTo(u, secret)
        until("delivery") { r.got.size == 1 }
        r.flash.flash()
        until("second round") { r.synced.size == 2 }
        val logs = r.logs.lines.joinToString("\n")
        assertFalse(secret in logs)
        assertFalse(token in logs)
        assertFalse(token in srv.wsURL)
        val framesWithToken = synchronized(r.transport.sent) { r.transport.sent.toList() }.filter { token in it }
        assertTrue(framesWithToken.isNotEmpty())
        assertTrue(framesWithToken.all { Json.parseToJsonElement(it).jsonObject["cmd"]!!.str == "auth" })
        assertEquals(obj("text" to secret), r.got[0].data)
    }

    @Test
    fun `AC-PUSH-03-25 真实服务端：没有收集者时不发 pushSync，开始收集后补齐`() = test {
        val u = uid("nosub")
        users(u)
        val a = pushTo(u)
        delay(5)
        val r = rig(collect = false)
        online(r, u)
        val b = pushTo(u)
        delay(300)
        assertEquals(emptyList(), r.transport.syncs())
        assertEquals(0L, r.im.pushCursor())
        r.scope.launch { r.im.pushes.collect { r.got.add(it) } }
        until("both") { r.got.size == 2 }
        assertEquals(listOf(a, b), ids(r))
        assertEquals(listOf(obj("afterSeq" to 0, "limit" to 100)), r.transport.syncs())
    }

    @Test
    fun `AC-PUSH-01-4、AC-PUSH-02-14：开关关闭时 pushSync 得到 4001，不抛错、不重试、消息照常；重连后再发一次；管理接口 404`() = test {
        assertTrue(
            "404" in assertFailsWith<IllegalStateException> {
                off.signed("POST", "/admin/push", obj("target" to "user", "userIds" to listOf("x"), "type" to "notice", "data" to obj(), "pushId" to UUID.randomUUID().toString()))
            }.message!!,
        )
        val a = uid("quiet")
        val b = uid("quiet")
        off.importUsers(a to null, b to null)
        val r = rig(server = off)
        online(r, a, off)
        until("one pushSync") { r.transport.syncs().size == 1 }
        delay(2_500) // 退避第一次 1 秒、第二次 2 秒：如果重试，这里已经发生了
        assertEquals(listOf(obj("afterSeq" to 0, "limit" to 100)), r.transport.syncs())
        assertEquals(emptyList(), r.got.toList())
        assertEquals(emptyList(), r.synced.toList())
        assertEquals(0L, r.im.pushCursor())

        // 退订再订阅也不发。
        val again = r.scope.launch { r.im.pushes.collect { } }
        delay(50)
        again.cancel()
        delay(100)
        assertEquals(1, r.transport.syncs().size)

        val conv = c2cConvId(a, b)
        r.im.send(conv, "开关关闭时消息照常")
        until("message sent") { r.im.messages(conv).firstOrNull()?.status == MessageStatus.SENT }

        r.flash.flash()
        until("second pushSync") { r.transport.syncs().size == 2 }
        delay(1_200)
        assertEquals(2, r.transport.syncs().size)
        assertNull(r.im.fatal.value)
    }
}
