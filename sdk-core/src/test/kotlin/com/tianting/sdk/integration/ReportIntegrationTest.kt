// FE-691（C6）× 真实 im-server（IM_MOD_ENABLED=1）：举报、4510。真 OkHttp、真协程线程。只用特征串，没有真实敏感词。
// 语义同 sdk-ts 的 test/integration/report.test.ts。服务端随 BE-492～496：在那之前 features.moderation 是 false、report 返回 4001、
// 词库接口不存在（调用方的 moderation.words scope 不认识，第二个服务端启动失败），这里的用例如实失败，不跳过、不放宽。本地结果，CI 不运行。
package com.tianting.sdk.integration

import com.tianting.sdk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.Base64
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 只给集成测试用的证据密钥（32 字节，base64），和 IM_QUARANTINE_KEY、JWT 密钥都不同。 */
private val MOD_ENV = mapOf(
    "IM_MOD_ENABLED" to "1",
    "IM_REPORT_KEY" to Base64.getEncoder().encodeToString("sdk-it-report-key-0123456789abcd".toByteArray()),
)
private const val WORD = "zxqmodword" // 特征词
private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")

/** 真实 WebSocket，记下收到的每一帧的 cmd 和时间（ack、pong 之外的就是推送和事件）。 */
private class RecordingTransport(private val inner: Transport = OkHttpTransport()) : Transport {
    val inbound: MutableList<Pair<String, Long>> = Collections.synchronizedList(ArrayList())

    override fun open(url: String, handlers: TransportHandlers): TransportConnection = inner.open(
        url,
        object : TransportHandlers by handlers {
            override fun onMessage(text: String) {
                runCatching { (Json.parseToJsonElement(text).jsonObject["cmd"] as? JsonPrimitive)?.contentOrNull }.getOrNull()
                    ?.let { inbound.add(it to System.currentTimeMillis()) }
                handlers.onMessage(text)
            }
        },
    )

    fun pushedSince(t: Long): List<String> = synchronized(inbound) { inbound.toList() }.filter { it.second >= t && it.first != "ack" && it.first != "pong" }.map { it.first }
}

@Tag("integration")
class ReportIntegrationTest {
    private val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val logger = object : Logger {
        override fun debug(message: String) { logs.add(message) }
        override fun warn(message: String) { logs.add(message) }
        override fun error(message: String) { logs.add(message) }
    }
    private var n = 0
    private fun uid(base: String) = "$base${System.currentTimeMillis().toString(36)}${n++}"

    private fun test(block: suspend CoroutineScope.() -> Any?) { runBlocking { withTimeout(60_000) { block() } } }

    private suspend fun <T : Any> until(what: String, timeout: Long = 5_000, fn: suspend () -> T?): T {
        val deadline = System.currentTimeMillis() + timeout
        while (true) {
            val v = fn()
            if (v != null && v != false) return v
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            delay(20)
        }
    }

    /** 每个服务端一组客户端。 */
    abstract inner class Suite {
        lateinit var srv: TestServer
        val clients = ArrayList<IMClient>()
        private val transports = HashMap<IMClient, RecordingTransport>()
        fun pushedSince(im: IMClient, t: Long): List<String> = transports[im]!!.pushedSince(t)

        suspend fun login(userId: String): IMClient {
            val t = RecordingTransport()
            val im = IMClient(IMClientOptions(url = srv.wsURL, timings = IMClientTimings(backoffBaseMs = 50, backoffMaxMs = 100), transport = t, logger = logger))
            clients.add(im)
            transports[im] = t
            im.connect(srv.token(userId))
            until("$userId connected") { im.state.value == ConnState.CONNECTED && im.userId.value == userId }
            return im
        }

        fun users(vararg ids: String) = srv.importUsers(*ids.map { it to null }.toTypedArray())

        suspend fun sent(im: IMClient, convId: String, text: String): Message {
            val r = assertIs<SendResult.Ok>(im.send(convId, text))
            return until("sent") { im.messages(convId).firstOrNull { it.clientMsgId == r.message.clientMsgId && it.status == MessageStatus.SENT } }
        }

        @AfterEach
        fun closeClients() = runBlocking {
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }

        @AfterAll
        fun down() {
            if (this::srv.isInitialized) srv.stop()
            val all = logs.joinToString("\n")
            assertFalse("ZXQ" in all || WORD in all, all)
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class Report : Suite() {
        @BeforeAll
        fun up() = runBlocking { srv = TestServer.start(MOD_ENV) }

        @Test
        fun `backend-chat 第 96 条：GET v1 config 的 features-moderation 读到 true`() = test {
            assertTrue(AccountClient(AccountClientOptions(baseUrl = srv.httpURL, logger = logger)).getConfig().features.moderation)
        }

        @Test
        fun `TC-MOD-03-01（AC-MOD-03-1 的客户端侧）：u2 举报 u1 的单聊消息（abuse）成功，返回 reportId；2 秒内 u1、u2 都收不到任何帧；重复提交返回同一个 reportId`() = test {
            val (u1, u2) = uid("ra") to uid("rb")
            users(u1, u2)
            val conv = c2cConvId(u1, u2)
            val a = login(u1)
            val b = login(u2)
            val m = sent(a, conv, "ZXQ-RPT-3 被举报的消息")
            until("u2 has conv") { b.conversations.value.firstOrNull { it.convId == conv } }
            delay(300) // 发送带来的推送先收完
            val t0 = System.currentTimeMillis()
            val id = b.report(ReportTarget.Messages(conv, listOf(m.msgId!!)), ReportReason.ABUSE, "ZXQ-RPT-NOTE")
            assertTrue(ID.matches(id), id)
            delay(2_000)
            assertEquals(emptyList(), pushedSince(a, t0))
            assertEquals(emptyList(), pushedSince(b, t0))
            assertEquals(id, b.report(ReportTarget.Messages(conv, listOf(m.msgId!!)), ReportReason.ABUSE))
        }

        @Test
        fun `举报用户、群：成功；非参与者举报消息、举报不存在的用户都是 4040 reportTargetInvalid（响应相同）`() = test {
            val (u1, u2, u3) = Triple(uid("rc"), uid("rd"), uid("re"))
            users(u1, u2, u3)
            val conv = c2cConvId(u1, u2)
            val a = login(u1)
            val b = login(u2)
            val c = login(u3)
            val m = sent(a, conv, "ZXQ-RPT-4")
            until("u2 has conv") { b.conversations.value.firstOrNull { it.convId == conv } }
            assertTrue(ID.matches(b.report(ReportTarget.User(u1), ReportReason.SPAM)))
            val g = a.createGroup(listOf(u2), "ZXQ 举报群")
            until("u2 in group") { b.conversations.value.firstOrNull { it.convId == groupConvId(g.groupId) } }
            assertTrue(ID.matches(b.report(ReportTarget.Group(g.groupId), ReportReason.ILLEGAL)))

            // c 不是这个单聊的参与者：本地 convId 校验就会拦下（4000），所以用群消息试服务端的 4040。
            val gm = sent(a, groupConvId(g.groupId), "ZXQ-RPT-5")
            val e1 = runCatching { c.report(ReportTarget.Messages(groupConvId(g.groupId), listOf(gm.msgId!!)), ReportReason.ABUSE) }.exceptionOrNull()
            val e2 = runCatching { c.report(ReportTarget.User(uid("nobody")), ReportReason.ABUSE) }.exceptionOrNull()
            for (e in listOf(e1, e2)) {
                assertIs<IMError>(e)
                assertEquals(4040 to "reportTargetInvalid", e.code to e.reason)
            }
            assertEquals(e1!!.message, e2!!.message)
            val local = runCatching { c.report(ReportTarget.Messages(conv, listOf(m.msgId!!)), ReportReason.ABUSE) }.exceptionOrNull()
            assertIs<IMError>(local)
            assertEquals(4000, local.code)
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class Blocked : Suite() {
        @BeforeAll
        fun up() {
            runBlocking {
                srv = TestServer.start(MOD_ENV + ("IM_REGISTER_MODE" to "invite"), extraScopes = listOf("moderation.words"))
                srv.signed("POST", "/admin/moderation/words", obj("word" to WORD))
            }
        }

        @Test
        fun `TC-MOD-02-09（AC-MOD-02-9 的 SDK 部分）：发送含特征词的消息：标记失败、error=4510、不能重发；对方收不到，服务端没有保存`() = test {
            val (u1, u2) = uid("ba") to uid("bb")
            users(u1, u2)
            val conv = c2cConvId(u1, u2)
            val a = login(u1)
            val b = login(u2)
            sent(a, conv, "先发一条正常的")
            val failed = Collections.synchronizedList(ArrayList<IMEvent.SendFailed>())
            a.eventTap = { if (it is IMEvent.SendFailed) failed.add(it) }
            // 词库 10 秒内生效（AC-MOD-01-1）：每 500 毫秒发一条新的，直到被拦截。
            var blocked: Message? = null
            val deadline = System.currentTimeMillis() + 11_000
            while (blocked == null && System.currentTimeMillis() < deadline) {
                val r = assertIs<SendResult.Ok>(a.send(conv, "含 $WORD 的消息 ${System.currentTimeMillis()}"))
                val m = until("send settled") { a.messages(conv).firstOrNull { it.clientMsgId == r.message.clientMsgId && it.status != MessageStatus.SENDING } }
                if (m.status == MessageStatus.FAILED) blocked = m else delay(500)
            }
            val x = blocked ?: throw AssertionError("11 秒内没有被拦截")
            assertEquals(SendError.Code(4510), x.error)
            assertNull(x.errorReason)
            assertFalse(a.canRetry(x))
            assertEquals(IMEvent.SendFailed(conv, x.clientMsgId, SendError.Code(4510)), failed.last())
            a.retry(conv, x.clientMsgId)
            delay(2_000)
            assertEquals(MessageStatus.FAILED, a.messages(conv).single { it.clientMsgId == x.clientMsgId }.status)
            assertFalse(b.messages(conv).any { it.clientMsgId == x.clientMsgId })
            b.loadHistory(conv)
            assertFalse(b.messages(conv).any { it.clientMsgId == x.clientMsgId })
        }

        @Test
        fun `建群的群名含特征词：IMError 4510 原样抛出，不带 details`() = test {
            val (u1, u2) = uid("bc") to uid("bd")
            users(u1, u2)
            val a = login(u1)
            val e = runCatching { a.createGroup(listOf(u2), "群 $WORD") }.exceptionOrNull()
            assertIs<IMError>(e)
            assertEquals(4510, e.code)
            assertNull(e.details)
        }

        @Test
        fun `注册的昵称、改昵称含特征词：AccountError {status 451, code 4510}，不带 details`() = test {
            fun invite(): String = srv.signedInvite(1)["invites"]!!.jsonArray[0].jsonObject["code"]!!.jsonPrimitive.content
            val password = "Tt-good-pass-2026!"
            val username = "mod_${System.currentTimeMillis().toString(36)}"
            val store = MemoryStore()
            val account = AccountClient(AccountClientOptions(baseUrl = srv.httpURL, store = store, logger = logger))
            val e1 = runCatching { account.register(username, password, nickname = "昵称$WORD", inviteCode = invite()) }.exceptionOrNull()
            assertIs<AccountError>(e1)
            assertEquals(451 to 4510, e1.status to e1.code)
            assertNull(e1.details)
            val r = account.register(username, password, nickname = "正常昵称", inviteCode = invite())
            account.ackRecoveryCode(r.recoveryCodeGen!!)
            val e2 = runCatching { account.updateProfile(nickname = "新$WORD") }.exceptionOrNull()
            assertIs<AccountError>(e2)
            assertEquals(451 to 4510, e2.status to e2.code)
            assertNull(e2.details)
        }
    }
}
