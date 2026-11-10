// FE-661、FE-662（C4）× 真实 im-server（IM_MSG_ACTIONS_ENABLED=1）：撤回、引用、提及。真 OkHttp、真协程线程、真 SQLite 文件。
// 语义同 sdk-ts 的 test/integration/msg-actions.test.ts，另加冷启动（AC-RCL-03-4）。本地结果，CI 不运行。
package com.tianting.sdk.integration

import com.tianting.sdk.*
import com.tianting.sdk.jdbc.JdbcLocalDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MsgActionsIntegrationTest {
    private lateinit var srv: TestServer
    private val clients = ArrayList<IMClient>()
    private val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val tmp: File = Files.createTempDirectory("tt-c4-it-").toFile()
    private var n = 0
    private val logger = object : Logger {
        override fun debug(message: String) { logs.add(message) }
        override fun warn(message: String) { logs.add(message) }
        override fun error(message: String) { logs.add(message) }
    }

    private fun uid(base: String) = "$base${System.currentTimeMillis().toString(36)}${n++}"

    @BeforeAll
    fun up() = runBlocking {
        val key = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }) // 测试现场生成（TC-RCL 通用约定 E1）
        srv = TestServer.start(mapOf("IM_MSG_ACTIONS_ENABLED" to "1", "IM_QUARANTINE_KEY" to key, "IM_MENTION_ALL_HOURLY" to "1")) // 第二次 @ 所有人就 4293
    }

    @AfterEach
    fun closeClients() = runBlocking {
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    @AfterAll
    fun down() {
        srv.stop()
        tmp.deleteRecursively()
        // 第 13.1 节：日志不打印引用文字、@ 的内容、原文。
        val all = logs.joinToString("\n")
        assertFalse("ZXQ" in all, all)
    }

    private fun test(block: suspend CoroutineScope.() -> Any?) { runBlocking { withTimeout(60_000) { block() } } }

    private fun client(transport: Transport? = null, database: LocalDatabase? = null): IMClient {
        val im = IMClient(IMClientOptions(url = srv.wsURL, timings = IMClientTimings(backoffBaseMs = 50, backoffMaxMs = 100), transport = transport, database = database, logger = logger))
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

    private suspend fun sent(im: IMClient, convId: String, text: String, quote: QuoteRef? = null, mentions: Mentions? = null): Message {
        val r = assertIs<SendResult.Ok>(im.send(convId, text, quote, mentions))
        return until("sent $text") { im.messages(convId).firstOrNull { it.clientMsgId == r.message.clientMsgId && it.status == MessageStatus.SENT } }
    }

    private fun byId(im: IMClient, convId: String, msgId: String) = im.messages(convId).firstOrNull { it.msgId == msgId }

    private fun conv(im: IMClient, convId: String) = im.conversations.value.firstOrNull { it.convId == convId }

    private suspend fun clean(im: IMClient, needle: String) = assertFalse(im.debugExport().toString().contains(needle), "内存里还有 $needle")

    private fun sentCmds(g: GatedTransport, cmd: String) =
        g.sent.toList().map { Json.parseToJsonElement(it).jsonObject }.filter { it["cmd"]?.str == cmd }

    // ---- 撤回 ----

    @Test
    fun `TC-RCL-01-01（AC-RCL-01-1）：u1 撤回：ack 成功；u2 和 u1 的另一端 2 秒内收到带 recalled 的 msgDeleted，文案各自正确，内存搜不到原文`() = test {
        val (u1, u2) = uid("ra") to uid("rb")
        users(u1, u2)
        val conv = c2cConvId(u1, u2)
        val a = login(u1)
        val a2 = login(u1)
        val b = login(u2)
        val m1 = sent(a, conv, "ZXQ-RCL-2 原文")
        until("u2 receives") { byId(b, conv, m1.msgId!!) }
        until("u1' receives") { byId(a2, conv, m1.msgId!!) }
        assertTrue(a.canRecall(m1))

        val t0 = System.currentTimeMillis()
        a.recall(conv, m1.msgId!!)
        byId(a, conv, m1.msgId!!)!!.let { assertTrue(it.deleted && it.recalled); assertEquals(u1, it.recalledBy); assertEquals(MsgBody(), it.body) }
        for (im in listOf(b, a2)) {
            val x = until("recalled event", 2_000) { byId(im, conv, m1.msgId!!)?.takeIf { it.recalled } }
            assertEquals(u1, x.recalledBy)
            assertTrue(x.deleted)
        }
        assertTrue(System.currentTimeMillis() - t0 < 2_000)
        assertEquals("你撤回了一条消息", a.recallText(byId(a, conv, m1.msgId!!)!!))
        assertEquals("你撤回了一条消息", a2.recallText(byId(a2, conv, m1.msgId!!)!!))
        assertEquals("对方撤回了一条消息", b.recallText(byId(b, conv, m1.msgId!!)!!))
        assertEquals(emptyList(), b.messageActions(byId(b, conv, m1.msgId!!)!!))
        for (im in listOf(a, a2, b)) clean(im, "ZXQ-RCL-2")

        // 重复撤回幂等成功（AC-RCL-01-4 的客户端一侧）。
        a.recall(conv, m1.msgId!!)
        // 服务端的会话摘要也是撤回（AC-RCL-03-5），新连接按 lastMsg 显示撤回文案。
        val b2 = login(u2)
        val last = until("convs") { conv(b2, conv)?.lastMsg }
        assertTrue(last.deleted && last.recalled)
        assertEquals(u1, last.recalledBy)
        assertEquals("对方撤回了一条消息", b2.recallText(conv, last))
    }

    @Test
    fun `TC-RCL-01-08（AC-RCL-01-8）：服务端时间过了 121 秒，recall 返回 4097（windowSeconds=120），消息不变`() = test {
        val (u1, u2) = uid("wa") to uid("wb")
        users(u1, u2)
        val conv = c2cConvId(u1, u2)
        val a = login(u1)
        val m1 = sent(a, conv, "过期")
        srv.admin("POST", "/admin/test/clock", buildJsonObject { put("nowMs", m1.ts + 121_000) })
        try {
            val err = assertIs<IMError>(runCatching { a.recall(conv, m1.msgId!!) }.exceptionOrNull())
            assertEquals(4097, err.code)
            val ws = (err.details!!["windowSeconds"] as JsonPrimitive).long
            assertEquals(120L, ws)
            assertEquals("发送时间超过 2 分钟，无法撤回", messageFor("recall", err.code, windowSeconds = ws))
            assertEquals("过期", byId(a, conv, m1.msgId!!)!!.body.text)
            assertFalse(byId(a, conv, m1.msgId!!)!!.deleted)
        } finally {
            srv.admin("POST", "/admin/test/clock", buildJsonObject { put("nowMs", JsonNull) }) // 恢复真实时钟
        }
    }

    @Test
    fun `TC-RCL-03-03、TC-RCL-03-11（AC-RCL-03-3、03-11）：u2 已加载两个会话后断网，u1 撤回其中一个的消息；u2 恢复后只对这个会话重拉，消息变撤回文案`() = test {
        val (u1, u2, u3) = Triple(uid("oa"), uid("ob"), uid("oc"))
        users(u1, u2, u3)
        val conv = c2cConvId(u1, u2)
        val quiet = c2cConvId(u3, u2)
        val a = login(u1)
        val c = login(u3)
        val m1 = sent(a, conv, "ZXQ-RCL-2 离线撤回")
        val m3 = sent(c, quiet, "不变的会话")
        // u2 上线后打开两个会话：登录时 convs 的 modVer 是基线（问题 24）。
        val gate = GatedTransport()
        val b = login(u2, client(gate))
        until("u2 convs") { b.conversations.value.size == 2 }
        b.loadHistory(conv)
        b.loadHistory(quiet)
        until("u2 loaded both") { byId(b, conv, m1.msgId!!) != null && byId(b, quiet, m3.msgId!!) != null }
        gate.disconnect()
        until("u2 offline") { b.state.value != ConnState.CONNECTED }
        a.recall(conv, m1.msgId!!)
        gate.sent.clear()
        gate.online = true
        b.networkOnline()
        until("u2 back online") { b.state.value == ConnState.CONNECTED }
        until("recalled after reconnect") { byId(b, conv, m1.msgId!!)?.recalled }
        val resynced = sentCmds(gate, "sync").map { it.dataObj["convId"]!!.str }
        assertTrue(conv in resynced, "$resynced")
        assertFalse(quiet in resynced, "$resynced")
        assertEquals("对方撤回了一条消息", b.recallText(byId(b, conv, m1.msgId!!)!!))
        clean(b, "ZXQ-RCL-2")
    }

    @Test
    fun `TC-RCL-03-04（AC-RCL-03-4 的 SDK 部分）：u2 本地库有 m1 和引用它的 m2，进程结束期间 u1 撤回 m1；冷启动连接后变撤回文案，本地库文件搜不到原文`() = test {
        val (u1, u2) = uid("ka") to uid("kb")
        users(u1, u2)
        val conv = c2cConvId(u1, u2)
        val a = login(u1)
        val dir = File(tmp, "kill-${n++}").apply { mkdirs() }
        val b = login(u2, client(database = JdbcLocalDatabase(File(dir, "im.db"))))
        val m1 = sent(a, conv, "ZXQ-RCL-2 杀进程时撤回")
        val m2 = sent(a, conv, "补一句", quote = QuoteRef(m1.msgId!!))
        until("u2 receives both") { byId(b, conv, m2.msgId!!) }
        b.loadHistory(conv) // 建 modVer 基线（问题 24）
        assertEquals(QuoteState.NORMAL, byId(b, conv, m2.msgId!!)!!.body.quote!!.state)
        b.close() // 进程结束：本地库保留
        clients.remove(b)
        assertTrue(filesContaining(dir, "ZXQ-RCL-2").isNotEmpty(), "对照：原文在本地库里")
        a.recall(conv, m1.msgId!!)

        val b2 = client(database = JdbcLocalDatabase(File(dir, "im.db")))
        assertEquals("ZXQ-RCL-2 杀进程时撤回", until("restored") { byId(b2, conv, m1.msgId!!) }.body.text) // 离线时还是上次的内容
        login(u2, b2)
        val x = until("recalled after cold start") { byId(b2, conv, m1.msgId!!)?.takeIf { it.recalled } }
        assertEquals("对方撤回了一条消息", b2.recallText(x))
        until("quote cleared") { byId(b2, conv, m2.msgId!!)?.body?.quote?.state == QuoteState.RECALLED }
        assertEquals("引用内容已被撤回", quoteLabel(byId(b2, conv, m2.msgId!!)!!.body.quote!!))
        clean(b2, "ZXQ-RCL-2")
        b2.close()
        clients.remove(b2)
        assertEquals(emptyList(), filesContaining(dir, "ZXQ-RCL-2"), "本地库里还有原文")
    }

    // ---- 引用 ----

    @Test
    fun `TC-QUO-01-01（AC-QUO-01-1）：u1 引用 u2 的消息回复，u2 收到 quote state=normal 带原文摘要；之后 u2 撤回被引用的，两边的引用变"引用内容已被撤回"`() = test {
        val (u1, u2) = uid("qa") to uid("qb")
        users(u1, u2)
        val conv = c2cConvId(u1, u2)
        val a = login(u1)
        val b = login(u2)
        val m1 = sent(b, conv, "ZXQ-QUO-1 被引用的原文")
        until("u1 receives m1") { byId(a, conv, m1.msgId!!) }
        val m2 = sent(a, conv, "回复", quote = QuoteRef(m1.msgId!!))
        val got = until("u2 receives m2") { byId(b, conv, m2.msgId!!) }
        assertEquals(QuoteView(m1.msgId!!, m1.seq, u2, MsgType.TEXT, QuoteState.NORMAL, "ZXQ-QUO-1 被引用的原文"), got.body.quote)

        b.recall(conv, m1.msgId!!)
        until("quote recalled on u1") { byId(a, conv, m2.msgId!!)?.body?.quote?.state == QuoteState.RECALLED }
        assertEquals("引用内容已被撤回", quoteLabel(byId(a, conv, m2.msgId!!)!!.body.quote!!))
        assertEquals("引用内容已被撤回", quoteLabel(byId(b, conv, m2.msgId!!)!!.body.quote!!))
        for (im in listOf(a, b)) clean(im, "ZXQ-QUO-1")
    }

    // ---- 群：提及、群主和管理员撤回 ----

    @Test
    fun `TC-MEN-01-01、TC-MEN-03-07、TC-RCL-02-04、TC-MEN-02-05、AC-RCL-02-5（群：提及和群主、管理员撤回）`() = test {
        val (o, a, m) = Triple(uid("o"), uid("a"), uid("m"))
        val nn = uid("n")
        users(o, a, m, nn)
        val groupId = uid("g")
        val convId = "group:$groupId"
        srv.signed("POST", "/admin/groups", obj("groupId" to groupId, "name" to groupId, "members" to listOf(o, a, m, nn), "owner" to o))
        val ownerGate = GatedTransport()
        val owner = login(o, client(ownerGate))
        until("owner role") { conv(owner, convId)?.myRole == GroupRole.OWNER }
        owner.setGroupAdmin(groupId, a, true)
        val adm = login(a)
        val mem = login(m)
        val other = login(nn)
        for (im in listOf(owner, adm, mem, other)) until("group in list") { conv(im, convId) }
        until("admin role") { conv(adm, convId)?.myRole == GroupRole.ADMIN }

        // 成员搜索（F-MEN-01 规则 6）：userId 前缀。
        assertEquals(listOf(nn), mem.searchGroupMembers(groupId, nn).members.map { it.userId })

        // TC-MEN-01-01：m @ n。n 的会话 mentionUnread=1（实时推算，重新登录后服务端也是 1）；o 没有。
        val at = sent(mem, convId, "@$nn 看一下", mentions = Mentions(listOf(nn)))
        val got = until("n receives") { byId(other, convId, at.msgId!!) }
        assertEquals(Mentions(listOf(nn)), got.body.mentions)
        assertEquals(1L, conv(other, convId)!!.mentionUnread)
        assertEquals(at.seq, conv(other, convId)!!.firstMentionSeq)
        until("o receives") { byId(owner, convId, at.msgId!!) }
        assertEquals(0L, conv(owner, convId)!!.mentionUnread ?: 0L)
        val other2 = login(nn)
        until("server mentionUnread=1") { conv(other2, convId)?.mentionUnread == 1L }

        // TC-MEN-03-07：对方撤回提及消息，n 的两个连接都在 5 秒内重新拉 convs，"[有人@我]" 消失。
        mem.recall(convId, at.msgId!!)
        for (im in listOf(other, other2)) until("mention cleared", 5_000) { conv(im, convId)?.mentionUnread == 0L }

        // TC-RCL-02-04：a 撤回 m 的消息（最后一条），a、m、n 的聊天页和列表摘要。
        val target = sent(mem, convId, "ZXQ-RCL-2 成员的消息")
        for (im in listOf(adm, other)) until("loaded") { byId(im, convId, target.msgId!!) }
        assertTrue(adm.canRecall(byId(adm, convId, target.msgId!!)!!))
        adm.recall(convId, target.msgId!!)
        for ((im, text) in listOf(adm to "你撤回了 $m 的一条消息", mem to "${a}撤回了你的一条消息", other to "${a}撤回了一条成员消息")) {
            val x = until(text) { byId(im, convId, target.msgId!!)?.takeIf { it.recalled } }
            assertEquals(a, x.recalledBy)
            assertEquals(text, im.recallText(x))
            val last = until("$text 列表") { conv(im, convId)?.lastMsg?.takeIf { it.msgId == target.msgId && it.recalled } }
            assertEquals(a, last.recalledBy)
            assertEquals(text, im.recallText(convId, last))
            clean(im, "ZXQ-RCL-2")
        }

        // AC-RCL-02-5：管理员撤回群主的消息，4030，提示"你没有权限撤回这条消息"，消息不变。
        val om = sent(owner, convId, "群主的消息")
        until("admin loaded") { byId(adm, convId, om.msgId!!) }
        assertTrue(MessageAction.RECALL in adm.messageActions(byId(adm, convId, om.msgId!!)!!))
        val err = assertIs<IMError>(runCatching { adm.recall(convId, om.msgId!!) }.exceptionOrNull())
        assertEquals(4030, err.code)
        assertEquals("你没有权限撤回这条消息", messageFor("recall", err.code))
        assertEquals("群主的消息", byId(adm, convId, om.msgId!!)!!.body.text)

        // TC-MEN-02-05：每小时 1 次（测试配置），第二次 @ 所有人 4293：失败，不自动重发。
        sent(owner, convId, "@所有人 第一次", mentions = Mentions(all = true))
        val r = assertIs<SendResult.Ok>(owner.send(convId, "@所有人 第二次", mentions = Mentions(all = true)))
        val failed = until("4293") { owner.messages(convId).firstOrNull { it.clientMsgId == r.message.clientMsgId && it.status == MessageStatus.FAILED } }
        assertEquals(SendError.Code(4293), failed.error)
        assertEquals("@所有人次数已用完", messageFor("send", 4293))
        ownerGate.sent.clear()
        ownerGate.disconnect()
        until("owner offline") { owner.state.value != ConnState.CONNECTED }
        ownerGate.online = true
        owner.networkOnline()
        until("owner back online") { owner.state.value == ConnState.CONNECTED }
        delay(300)
        assertEquals(emptyList(), sentCmds(ownerGate, "send"))
        assertEquals(SendError.Code(4293), owner.messages(convId).first { it.clientMsgId == r.message.clientMsgId }.error)
    }
}
