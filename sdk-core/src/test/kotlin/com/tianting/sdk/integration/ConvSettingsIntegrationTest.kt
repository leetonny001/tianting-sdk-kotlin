// FE-681（C5）× 真实 im-server（IM_CONV_SETTINGS_ENABLED=1）：置顶、免打扰、删除、多端同步、本地库物理清除。真 OkHttp、真协程线程、真 SQLite 文件。
// 语义同 sdk-ts 的 test/integration/conv-settings.test.ts，另加 AC-CVS-03-10（D2 的本地库和缓存目录）。
// 服务端命令随 BE-483～486；在那之前 convSet、convDelete 返回 4001，这里的用例如实失败，不跳过、不放宽。本地结果，CI 不运行。
package com.tianting.sdk.integration

import com.tianting.sdk.*
import com.tianting.sdk.jdbc.JdbcLocalDatabase
import kotlinx.coroutines.CoroutineScope
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
import java.nio.file.Files
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConvSettingsIntegrationTest {
    private lateinit var srv: TestServer
    private val clients = ArrayList<IMClient>()
    private val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val tmp: File = Files.createTempDirectory("tt-c5-it-").toFile()
    private var n = 0
    private val logger = object : Logger {
        override fun debug(message: String) { logs.add(message) }
        override fun warn(message: String) { logs.add(message) }
        override fun error(message: String) { logs.add(message) }
    }

    private fun uid(base: String) = "$base${System.currentTimeMillis().toString(36)}${n++}"

    @BeforeAll
    fun up() = runBlocking { srv = TestServer.start(mapOf("IM_CONV_SETTINGS_ENABLED" to "1")) }

    @AfterEach
    fun closeClients() = runBlocking {
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    @AfterAll
    fun down() {
        srv.stop()
        tmp.deleteRecursively()
        val all = logs.joinToString("\n")
        assertFalse("ZXQ" in all, all)
    }

    private fun test(block: suspend CoroutineScope.() -> Any?) { runBlocking { withTimeout(60_000) { block() } } }

    private fun client(transport: Transport? = null, database: LocalDatabase? = null, cacheDir: File? = null): IMClient {
        val im = IMClient(
            IMClientOptions(
                url = srv.wsURL, timings = IMClientTimings(backoffBaseMs = 50, backoffMaxMs = 100), transport = transport, database = database,
                logger = logger, fileCacheDir = cacheDir,
            ),
        )
        clients.add(im)
        return im
    }

    private suspend fun <T : Any> until(what: String, timeout: Long = 5_000, fn: suspend () -> T?): T {
        val deadline = System.currentTimeMillis() + timeout
        while (true) {
            val v = fn()
            if (v != null && v != false) return v
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            delay(20)
        }
    }

    private suspend fun login(userId: String, im: IMClient = client()): IMClient {
        im.connect(srv.token(userId))
        until("$userId connected") { im.state.value == ConnState.CONNECTED && im.userId.value == userId }
        return im
    }

    private fun users(vararg ids: String) = srv.importUsers(*ids.map { it to null }.toTypedArray())

    private suspend fun sent(im: IMClient, convId: String, text: String): Message {
        val r = assertIs<SendResult.Ok>(im.send(convId, text))
        return until("sent $text") { im.messages(convId).firstOrNull { it.clientMsgId == r.message.clientMsgId && it.status == MessageStatus.SENT } }
    }

    private fun conv(im: IMClient, convId: String) = im.conversations.value.firstOrNull { it.convId == convId }

    private fun order(im: IMClient) = im.conversations.value.map { listOf(it.convId, it.pinned, it.pinnedAt, it.notifyOff) }

    private suspend fun clean(im: IMClient, needle: String) = assertFalse(im.debugExport().toString().contains(needle), "内存里还有 $needle")

    @Test
    fun `backend-chat 第 75 条：GET v1 config 的 features-convSettings 读到 true`() = test {
        val account = AccountClient(AccountClientOptions(baseUrl = srv.httpURL, logger = logger))
        assertTrue(account.getConfig().features.convSettings)
    }

    @Test
    fun `TC-CVS-04-01（AC-CVS-04-1）：D1 置顶、免打扰：D2 2 秒内更新；ack 后 D1 的 pinnedAt 是服务端的`() = test {
        val (u1, u2) = uid("pa") to uid("pb")
        users(u1, u2)
        val c = c2cConvId(u1, u2)
        val d1 = login(u1)
        val d2 = login(u1)
        val b = login(u2)
        sent(b, c, "hi")
        until("conv on both devices") { conv(d1, c) != null && conv(d2, c) != null }
        val t0 = System.currentTimeMillis()
        d1.setConvPinned(c, true)
        d1.setConvNotifyOff(c, true)
        val pinnedAt = conv(d1, c)!!.pinnedAt!!
        assertTrue(conv(d1, c)!!.pinned && conv(d1, c)!!.notifyOff)
        until("D2 updated", 2_000) { conv(d2, c)?.let { it.pinned && it.notifyOff } }
        assertTrue(System.currentTimeMillis() - t0 < 2_000)
        assertEquals(pinnedAt, conv(d2, c)!!.pinnedAt)
        assertEquals(c, d2.conversations.value.first().convId)
        assertEquals(0L, d2.totalUnread.value) // 免打扰、没有 @
    }

    @Test
    fun `TC-CVS-04-05、TC-CVS-03-02（AC-CVS-04-5、03-2）：D1 删除单聊：D2 2 秒内收到 convDeleted，会话消失、内存搜不到；u2 再发 1 条，两端重新出现，未读 1，minSeq 正确`() = test {
        val (u1, u2) = uid("da") to uid("db")
        users(u1, u2)
        val c = c2cConvId(u1, u2)
        val d1 = login(u1)
        val d2 = login(u1)
        val b = login(u2)
        for (i in 1..3) sent(b, c, "ZXQ-CVS-1 第 $i 条")
        until("D2 has 3") { conv(d2, c)?.maxSeq == 3L }
        d2.loadHistory(c)
        assertEquals(3, d2.messages(c).size)
        val drafts = Collections.synchronizedList(ArrayList<String>())
        d2.eventTap = { e -> if (e is IMEvent.ConvDeleted) drafts.add(e.convId) }
        val t0 = System.currentTimeMillis()
        d1.deleteConv(c)
        assertNull(conv(d1, c))
        until("D2 convDeleted", 2_000) { c in drafts }
        assertTrue(System.currentTimeMillis() - t0 < 2_000)
        assertNull(conv(d2, c))
        assertNull(d2.conversationSnapshot(c))
        for (im in listOf(d1, d2)) clean(im, "ZXQ-CVS-1")

        sent(b, c, "新的一条")
        for (im in listOf(d1, d2)) {
            until("reappears with unread 1") { conv(im, c)?.unread == 1L }
            assertEquals(4L, conv(im, c)!!.minSeq)
            im.loadHistory(c)
            assertEquals(listOf("新的一条"), im.messages(c).map { it.body.text })
        }
        val d3 = login(u1)
        until("convs") { conv(d3, c) }
        assertEquals(4L to 1L, conv(d3, c)!!.let { it.minSeq to it.unread })
        assertEquals(1L, conv(b, c)!!.minSeq) // 对方不受影响
    }

    @Test
    fun `TC-CVS-03-05（AC-CVS-03-5）：D2 离线时 D1 删除 g1：D2 重连后 g1 消失，没有 ConvRemoved、没有快照，可以照常发言`() = test {
        val (u1, u2) = uid("ga") to uid("gb")
        users(u1, u2)
        val groupId = uid("g")
        srv.admin("POST", "/admin/groups", obj("groupId" to groupId, "name" to groupId, "members" to listOf(u1, u2)))
        val g = "group:$groupId"
        val gate = GatedTransport()
        val d1 = login(u1)
        val d2 = login(u1, client(gate))
        val b = login(u2)
        sent(b, g, "ZXQ-CVS-6")
        until("D2 has g") { conv(d2, g)?.maxSeq?.takeIf { it > 0 } }
        d2.loadHistory(g)
        val removed = Collections.synchronizedList(ArrayList<String>())
        d2.eventTap = { e -> if (e is IMEvent.ConvRemoved) removed.add(e.convId) }
        gate.disconnect()
        until("D2 offline") { d2.state.value != ConnState.CONNECTED }
        d1.deleteConv(g)
        gate.online = true
        until("D2 back", 10_000) { d2.state.value == ConnState.CONNECTED }
        until("g1 gone") { conv(d2, g) == null }
        assertEquals(emptyList(), removed.toList())
        assertNull(d2.conversationSnapshot(g))
        clean(d2, "ZXQ-CVS-6")
        assertTrue(d2.loadMyGroups().any { it.groupId == groupId })
        sent(d2, g, "我还在群里")
        until("u2 receives") { conv(b, g)?.lastMsg?.text == "我还在群里" }
    }

    @Test
    fun `TC-CVS-04-02（AC-CVS-04-2）：D2 离线时 D1 置顶 c1 并删除 c2：D2 重连后列表和 D1 一致，c2 本地消息被清除`() = test {
        val (u1, u2, u3) = Triple(uid("oa"), uid("ob"), uid("oc"))
        users(u1, u2, u3)
        val c1 = c2cConvId(u1, u2)
        val c2 = c2cConvId(u1, u3)
        val gate = GatedTransport()
        val d1 = login(u1)
        val d2 = login(u1, client(gate))
        sent(login(u2), c1, "c1")
        sent(login(u3), c2, "ZXQ-CVS-7")
        until("D2 has both") { conv(d2, c1) != null && conv(d2, c2) != null }
        d2.loadHistory(c2)
        gate.disconnect()
        until("D2 offline") { d2.state.value != ConnState.CONNECTED }
        d1.setConvPinned(c1, true)
        d1.deleteConv(c2)
        gate.online = true
        until("D2 back", 10_000) { d2.state.value == ConnState.CONNECTED }
        until("D2 synced") { conv(d2, c1)?.pinned == true && conv(d2, c2) == null }
        assertEquals(order(d1), order(d2))
        assertEquals(emptyList(), d2.messages(c2))
        clean(d2, "ZXQ-CVS-7")
    }

    @Test
    fun `TC-CVS-03-10（AC-CVS-03-10）：u1 的 D2 离线，本地库有和 u2 的单聊（含特征串）；D1 删除、u2 再发一条、D2 上线：D2 本地库和缓存目录搜不到旧特征串，只显示新消息`() = test {
        val (u1, u2) = uid("la") to uid("lb")
        users(u1, u2)
        val c = c2cConvId(u1, u2)
        val dbDir = File(tmp, "d2-$u1").apply { mkdirs() }
        val cache = File(tmp, "cache-$u1").apply { mkdirs() }
        val b = login(u2)
        var d2 = login(u1, client(database = JdbcLocalDatabase(File(dbDir, "im.db")), cacheDir = cache))
        sent(b, c, "ZXQ-CVS-10 原文一")
        sent(b, c, "ZXQ-CVS-10 原文二")
        until("D2 has 2") { conv(d2, c)?.maxSeq == 2L }
        d2.loadHistory(c)
        assertEquals(2, d2.messages(c).size)
        d2.close() // D2 离线（进程结束，本地库保留）
        assertTrue(bytesContaining(dbDir, "ZXQ-CVS-10").isNotEmpty(), "对照：删除前本地库能搜到")
        val d1 = login(u1)
        until("D1 has conv") { conv(d1, c) }
        d1.deleteConv(c)
        sent(b, c, "新的一条")
        d2 = login(u1, client(database = JdbcLocalDatabase(File(dbDir, "im.db")), cacheDir = cache))
        until("D2 shows only the new message") { d2.messages(c).map { it.body.text } == listOf("新的一条") }
        assertEquals(3L, conv(d2, c)!!.minSeq)
        clean(d2, "ZXQ-CVS-10")
        assertEquals(emptyList(), bytesContaining(dbDir, "ZXQ-CVS-10"))
        assertEquals(emptyList(), bytesContaining(cache, "ZXQ-CVS-10"))
        d2.close()
        assertEquals(emptyList(), bytesContaining(dbDir, "ZXQ-CVS-10"))
    }

    private fun bytesContaining(dir: File, needle: String): List<String> {
        val n = needle.toByteArray()
        return dir.walkTopDown().filter { it.isFile }.filter { f ->
            val h = f.readBytes()
            (0..h.size - n.size).any { i -> n.indices.all { j -> h[i + j] == n[j] } }
        }.map { it.name }.toList()
    }
}
