// SDK × 真实 im-server（IM_V13_ADMIN_FEATURES=1）：真 OkHttp、真协程线程、真 SQLite 文件。
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 可以"拔网线"的传输层，同时记录发出的帧。 */
class GatedTransport(private val inner: Transport = OkHttpTransport()) : Transport {
    @Volatile
    var online = true
    val sent: MutableList<String> = Collections.synchronizedList(ArrayList())
    private var cut: (() -> Unit)? = null

    override fun open(url: String, handlers: TransportHandlers): TransportConnection {
        if (!online) {
            Thread { handlers.onClose(1006, "") }.start()
            return object : TransportConnection {
                override fun send(text: String) = Unit
                override fun close(code: Int, reason: String) = Unit
            }
        }
        val conn = inner.open(url, handlers)
        cut = {
            conn.close(4000, "cut")
            handlers.onClose(1006, "")
        }
        return object : TransportConnection {
            override fun send(text: String) {
                sent.add(text)
                conn.send(text)
            }

            override fun close(code: Int, reason: String) = conn.close(code, reason)
        }
    }

    fun disconnect() {
        online = false
        cut?.invoke()
    }

    fun firstSocialSync(): JsonObject = sent.map { Json.parseToJsonElement(it).jsonObject }.first { it["cmd"]?.str == "socialSync" }.dataObj
}

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SdkIntegrationTest {
    private lateinit var srv: TestServer
    private val clients = ArrayList<IMClient>()
    private var n = 0

    private fun uid(base: String) = "$base${System.currentTimeMillis().toString(36)}${n++}"

    @BeforeAll
    fun up() = runBlocking { srv = TestServer.start(MODERATION_ENV) }

    @AfterEach
    fun closeClients() = runBlocking {
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    @AfterAll
    fun down() = srv.stop()

    private fun test(block: suspend CoroutineScope.() -> Any?) { runBlocking { withTimeout(60_000) { block() } } }

    private fun client(
        timings: IMClientTimings = IMClientTimings(backoffBaseMs = 50, backoffMaxMs = 100),
        url: String = srv.wsURL,
        database: LocalDatabase? = null,
        transport: Transport? = null,
        newId: (() -> String)? = null,
    ): IMClient {
        val im = IMClient(
            if (newId != null) {
                IMClientOptions(url = url, timings = timings, database = database, transport = transport, newId = newId)
            } else {
                IMClientOptions(url = url, timings = timings, database = database, transport = transport)
            },
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

    private suspend fun loginLoaded(userId: String, im: IMClient = client()): IMClient {
        login(userId, im)
        until("$userId social loaded") { im.socialLoaded.value }
        return im
    }

    private fun users(vararg ids: String) = srv.importUsers(*ids.map { it to it }.toTypedArray())

    private fun group(vararg members: String): Pair<String, String> {
        val groupId = uid("g")
        srv.importUsers(*members.map { it to null }.toTypedArray())
        srv.admin("POST", "/admin/groups", obj("groupId" to groupId, "name" to groupId, "members" to members.toList()))
        return groupId to "group:$groupId"
    }

    private fun collectEvents(im: IMClient): Pair<MutableList<IMEvent>, CoroutineScope> {
        val out = Collections.synchronizedList(ArrayList<IMEvent>())
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope.launch { im.events.collect { out.add(it) } }
        Thread.sleep(50)
        return out to scope
    }

    private fun IMClient.conv(id: String) = conversations.value.firstOrNull { it.convId == id }

    // ---- 核心 ----

    @Test
    fun `单聊收发、会话列表、未读、已读（AC-CONV-02-2）`() = test {
        val (a, b) = uid("alice") to uid("bob")
        srv.importUsers(b to "鲍勃")
        val alice = login(a)
        val bob = login(b)
        val conv = c2cConvId(a, b)
        assertTrue(alice.send(conv, "  你好 👋  ") is SendResult.Ok)
        until("ack") { alice.messages(conv).firstOrNull()?.status == MessageStatus.SENT }
        assertEquals(1, alice.messages(conv)[0].seq)
        assertEquals("你好 👋", alice.messages(conv)[0].body.text)
        // 第一次和 bob 聊天：本地先补临时会话，随后用服务端数据替换，会话名变成昵称。
        until("refreshed") { alice.conv(conv)?.name == "鲍勃" }
        assertEquals(0, alice.conv(conv)!!.unread)
        assertEquals(1, alice.conv(conv)!!.readSeq)

        val got = until("bob receives") { bob.messages(conv).firstOrNull() }
        assertEquals(a, got.from)
        assertEquals(MessageStatus.SENT, got.status)
        until("bob unread 1") { bob.conv(conv)?.unread == 1L }
        bob.setActiveConversation(conv)
        until("bob read") { bob.conv(conv)?.unread == 0L }
        // 服务端也记下了：重新登录后未读仍为 0。
        val bob2 = login(b)
        until("bob2 convs") { bob2.conv(conv) }
        assertEquals(0, bob2.conv(conv)!!.unread)
    }

    @Test
    fun `给不存在的用户发消息：4042 透传（AC-CONV-03-3）`() = test {
        val a = uid("alice")
        val alice = login(a)
        val conv = c2cConvId(a, uid("ghost"))
        alice.send(conv, "hi?")
        val m = until("failed") { alice.messages(conv).firstOrNull { it.status == MessageStatus.FAILED } }
        assertEquals(SendError.Code(4042), m.error)
    }

    @Test
    fun `ack 被丢弃后手动重发：对方只收到一条，msgId 与第一次一致（AC-MSG-03-4）`() = test {
        val (a, b) = uid("alice") to uid("bob")
        users(b)
        val id = uid("lost-ack-")
        val alice = login(a, client(timings = IMClientTimings(sendTimeoutMs = 800), newId = { id }))
        val bob = login(b)
        val conv = c2cConvId(a, b)
        srv.admin("POST", "/admin/test/drop-ack", obj("clientMsgId" to id))
        alice.send(conv, "lost ack")
        val fromBob = until("bob receives") { bob.messages(conv).firstOrNull() }
        until("times out") { alice.messages(conv).firstOrNull()?.status == MessageStatus.FAILED }
        alice.retry(conv, id)
        until("retry sent") { alice.messages(conv).firstOrNull()?.status == MessageStatus.SENT }
        delay(300)
        assertEquals(1, bob.messages(conv).size)
        assertEquals(1, alice.messages(conv).size)
        assertEquals(fromBob.msgId, alice.messages(conv)[0].msgId)
    }

    @Test
    fun `被拉进群收到 convAdded；群消息多端同步`() = test {
        val (a, c) = uid("alice") to uid("carol")
        val alice = login(a)
        val carol = login(c)
        val g = uid("g")
        srv.admin("POST", "/admin/groups", obj("groupId" to g, "name" to "测试群", "members" to listOf(a, c)))
        until("convAdded") { carol.conv("group:$g") }
        assertEquals("测试群", carol.conv("group:$g")!!.name)
        alice.send("group:$g", "hello group")
        until("carol receives") { carol.messages("group:$g").firstOrNull()?.body?.text == "hello group" }
    }

    @Test
    fun `被强制下线：停止重连，通知界面（AC-AUTH-03-2、AC-AUTH-03-5）`() = test {
        val a = uid("alice")
        val alice = login(a)
        srv.admin("POST", "/admin/users/$a/force-logout", obj())
        val f = until("fatal") { alice.fatal.value }
        assertEquals(FatalReason.Kicked("banned", 4012, null), f)
        delay(500)
        assertEquals(ConnState.DISCONNECTED, alice.state.value)
    }

    @Test
    fun `下行大帧不受限制（C-07）：一次 sync 超过 100KB`() = test {
        val (a, b) = uid("alice") to uid("bob")
        users(a, b)
        val alice = login(a)
        val conv = c2cConvId(a, b)
        val big = "字".repeat(5000)
        repeat(20) { alice.send(conv, big) }
        until("all sent") { alice.messages(conv).count { it.status == MessageStatus.SENT } == 20 }
        val bob = login(b)
        until("bob convs") { bob.conv(conv) }
        bob.loadHistory(conv, 20)
        assertEquals(20, bob.messages(conv).size)
        assertEquals(big, bob.messages(conv)[19].body.text)
    }

    @Test
    fun `离线发送排队，重连后自动发出；对方只收到一条（AC-MSG-03-2）`() = test {
        val (a, b) = uid("alice") to uid("bob")
        users(a, b)
        val gate = GatedTransport()
        val alice = login(a, client(transport = gate))
        val bob = login(b)
        val conv = c2cConvId(a, b)
        gate.disconnect()
        until("offline") { alice.state.value != ConnState.CONNECTED }
        alice.send(conv, "offline")
        assertEquals(MessageStatus.QUEUED, alice.messages(conv)[0].status)
        gate.online = true
        alice.networkOnline()
        until("sent") { alice.messages(conv)[0].status == MessageStatus.SENT }
        until("bob receives") { bob.messages(conv).size == 1 }
        delay(200)
        assertEquals(1, bob.messages(conv).size)
    }

    @Test
    fun `被服务端断开后自动重连（4408），断线期间的消息按序补齐、不重复（AC-CONN-01-4）`() = test {
        val idle = TestServer.start(MODERATION_ENV + mapOf("IM_IDLE_TIMEOUT" to "1s"))
        try {
            val (a, b) = uid("alice") to uid("bob")
            idle.importUsers(a to null, b to null)
            val alice = client(timings = IMClientTimings(heartbeatMs = 60_000, backoffBaseMs = 200), url = idle.wsURL)
            alice.connect(idle.token(a))
            val bob = client(timings = IMClientTimings(heartbeatMs = 300), url = idle.wsURL)
            bob.connect(idle.token(b))
            until("both") { alice.state.value == ConnState.CONNECTED && bob.state.value == ConnState.CONNECTED && bob.userId.value == b }
            val conv = c2cConvId(a, b)
            bob.send(conv, "m1")
            until("first") { alice.messages(conv).size == 1 }
            until("idle disconnect") { alice.state.value != ConnState.CONNECTED }
            for (t in listOf("m2", "m3", "m4")) bob.send(conv, t)
            until("caught up", 8_000) { alice.messages(conv).size == 4 }
            assertEquals(listOf("m1", "m2", "m3", "m4"), alice.messages(conv).map { it.body.text })
            assertEquals(listOf(1L, 2L, 3L, 4L), alice.messages(conv).map { it.seq })
        } finally {
            idle.stop()
        }
    }

    // ---- 社交 ----

    @Test
    fun `申请、红点、同意、多端（AC-SOC-02-1、AC-SOC-03-1、AC-SOC-04-2、AC-SOC-06-2、AC-SOC-09-1）`() = test {
        val (a, b) = uid("s1a") to uid("s1b")
        srv.importUsers(a to "小A", b to "小B")
        val alice = loginLoaded(a)
        val bob = loginLoaded(b)
        val bobPc = loginLoaded(b)
        val found = alice.searchUser(b)!!
        assertEquals("小B", found.nickname)
        assertEquals(Relation.NONE, found.relation)
        assertEquals(RequestStatus.PENDING, alice.applyFriend(b, ApplyFriendOptions(message = "我是小A", source = ApplySource.SEARCH)))
        assertEquals(Relation.PENDING_OUT, alice.relationOf(b))
        assertEquals(Relation.PENDING_OUT, alice.searchUser(b)!!.relation)
        val out = alice.friendRequests.value.items[0]
        assertEquals(listOf<Any?>(b, RequestDirection.OUT, "小B", null), listOf(out.userId, out.direction, out.displayName, out.profile))
        until("red dot on both devices") { bob.friendRequests.value.unseen == 1 && bobPc.friendRequests.value.unseen == 1 }
        val inc = bob.friendRequests.value.items[0]
        assertEquals(listOf<Any?>(a, RequestDirection.IN, "我是小A", ApplySource.SEARCH, "小A"), listOf(inc.userId, inc.direction, inc.message, inc.source, inc.displayName))
        bob.markFriendRequestsSeen()
        until("red dot cleared on the other device", 2_000) { bobPc.friendRequests.value.unseen == 0 }
        bob.respondFriend(a, FriendAction.ACCEPT, remark = "同学A")
        assertEquals(listOf("同学A"), bob.friends.value.map { it.displayName })
        until("friends on both sides", 2_000) { alice.friends.value.size == 1 && bobPc.friends.value.size == 1 }
        assertEquals(Relation.FRIEND, alice.relationOf(b))
        assertEquals("同学A", bobPc.friends.value[0].displayName)
    }

    @Test
    fun `红点数与排序（AC-SOC-04-1）`() = test {
        val (b, c, d) = Triple(uid("b"), uid("c"), uid("d"))
        users(b, c, d)
        val bob = loginLoaded(b)
        loginLoaded(c).applyFriend(b)
        delay(20)
        loginLoaded(d).applyFriend(b)
        until("2 requests") { bob.friendRequests.value.unseen == 2 }
        assertEquals(listOf(d, c), bob.friendRequests.value.items.map { it.userId })
    }

    @Test
    fun `单向删除：本端立即移除，另一端同步移除，对方不受影响（AC-SOC-05-1）`() = test {
        val (a, b) = uid("a") to uid("b")
        users(a, b)
        val alice = loginLoaded(a)
        val alicePc = loginLoaded(a)
        val bob = loginLoaded(b)
        alice.applyFriend(b)
        until("req") { bob.friendRequests.value.items.isNotEmpty() }
        bob.respondFriend(a, FriendAction.ACCEPT)
        until("friends") { alicePc.friends.value.size == 1 && alice.friends.value.size == 1 }
        alice.deleteFriend(b)
        assertEquals(emptyList(), alice.friends.value)
        until("other device", 2_000) { alicePc.friends.value.isEmpty() }
        delay(300)
        assertEquals(listOf(a), bob.friends.value.map { it.userId })
    }

    @Test
    fun `备注多端同步，对方和管理接口都看不到（AC-SOC-05-4）`() = test {
        val (a, b) = uid("a") to uid("b")
        users(a, b)
        val alice = loginLoaded(a)
        val alicePc = loginLoaded(a)
        val bob = loginLoaded(b)
        alice.applyFriend(b)
        until("req") { bob.friendRequests.value.items.isNotEmpty() }
        bob.respondFriend(a, FriendAction.ACCEPT)
        until("friends") { alicePc.friends.value.size == 1 }
        alice.setFriendRemark(b, "老板")
        until("pc shows", 2_000) { alicePc.friends.value[0].displayName == "老板" }
        delay(200)
        assertFalse(bob.friends.value.toString().contains("老板"))
        assertFalse(bob.friendRequests.value.toString().contains("老板"))
        assertFalse(srv.signed("GET", "/admin/users/$a/friends").toString().contains("老板"))
    }

    @Test
    fun `断网期间发生的同意：重连后补齐（AC-SOC-06-2）`() = test {
        val (a, b) = uid("a") to uid("b")
        users(a, b)
        val gate = GatedTransport()
        val alice = loginLoaded(a, client(transport = gate))
        val bob = loginLoaded(b)
        alice.applyFriend(b)
        until("req") { bob.friendRequests.value.items.isNotEmpty() }
        gate.disconnect()
        until("offline") { alice.state.value != ConnState.CONNECTED }
        bob.respondFriend(a, FriendAction.ACCEPT)
        gate.online = true
        alice.networkOnline()
        until("caught up within 5s", 5_000) { alice.friends.value.size == 1 }
    }

    @Test
    fun `拉黑：对方私聊 4036 标记失败、不自动重发；黑名单多端同步；取消后可以发（AC-SOC-07-1、AC-SOC-07-5）`() = test {
        val (a, b) = uid("s2a") to uid("s2b")
        users(a, b)
        val alice = loginLoaded(a)
        val alicePc = loginLoaded(a)
        val bob = login(b)
        alice.block(b)
        assertEquals(Relation.BLOCKED, alice.relationOf(b))
        until("block synced", 2_000) { alicePc.blocks.value.firstOrNull()?.userId == b }
        val conv = c2cConvId(a, b)
        bob.send(conv, "hi")
        val m = until("failed") { bob.messages(conv).firstOrNull { it.status == MessageStatus.FAILED } }
        assertEquals(SendError.Code(4036), m.error)
        assertEquals("消息已发出，但被对方拒收了", messageFor("send", 4036))
        delay(300)
        assertEquals(1, bob.messages(conv).size)
        assertEquals(emptyList(), alice.messages(conv))
        alice.unblock(b)
        until("unblock synced", 2_000) { alicePc.blocks.value.isEmpty() }
        bob.send(conv, "again")
        until("sent") { bob.messages(conv).any { it.status == MessageStatus.SENT } }
    }

    @Test
    fun `好友制 4035、陌生人限额 4137 的 N 来自配置（AC-SOC-08-8、AC-SOC-01-6）`() = test {
        val (a, b) = uid("a") to uid("b")
        users(a, b)
        val alice = loginLoaded(a)
        val alicePc = loginLoaded(a)
        val conv = c2cConvId(a, b)
        try {
            srv.signed("PATCH", "/admin/settings/social", obj("strangerMsgLimit" to 1))
            until("settings on both", 1_000) { alice.socialSettings.value.strangerMsgLimit == 1 && alicePc.socialSettings.value.strangerMsgLimit == 1 }
            alice.send(conv, "1")
            until("1st") { alice.messages(conv).firstOrNull()?.status == MessageStatus.SENT }
            alice.send(conv, "2")
            val m = until("4137") { alice.messages(conv).firstOrNull { it.status == MessageStatus.FAILED } }
            assertEquals(SendError.Code(4137), m.error)
            assertEquals("对方回复之前，最多只能发送 1 条消息", messageFor("send", 4137, alice.socialSettings.value.strangerMsgLimit))

            srv.signed("PATCH", "/admin/settings/social", obj("mode" to "friends"))
            until("mode") { alice.socialSettings.value.mode == SocialMode.FRIENDS }
            alice.send(conv, "3")
            val f = until("4035") { alice.messages(conv).firstOrNull { it.error == SendError.Code(4035) } }
            assertEquals(MessageStatus.FAILED, f.status)
        } finally {
            srv.signed("PATCH", "/admin/settings/social", obj("mode" to "open", "strangerMsgLimit" to 0))
        }
    }

    @Test
    fun `离线期间改配置，重连后从同步应答头拿到新值（TC-SOC-01-09）`() = test {
        val a = uid("a")
        val gate = GatedTransport()
        val alice = loginLoaded(a, client(transport = gate))
        gate.disconnect()
        until("offline") { alice.state.value != ConnState.CONNECTED }
        try {
            srv.signed("PATCH", "/admin/settings/social", obj("strangerMsgLimit" to 7))
            gate.online = true
            alice.networkOnline()
            until("new value") { alice.socialSettings.value.strangerMsgLimit == 7 }
        } finally {
            srv.signed("PATCH", "/admin/settings/social", obj("strangerMsgLimit" to 0))
        }
    }

    @Test
    fun `隐私开关多端同步（AC-SOC-09-7）；官方账号标识（AC-SOC-13-7）`() = test {
        val (a, o) = uid("a") to uid("off")
        users(a, o)
        val alice = loginLoaded(a)
        val alicePc = loginLoaded(a)
        alice.setSearchable(false)
        until("pc off", 2_000) { !alicePc.socialSettings.value.searchable }
        srv.signed("PUT", "/admin/users/$o/official", obj("official" to true))
        assertEquals(true, alice.searchUser(o)!!.official)
        assertEquals(true, alice.getUsers(listOf(o))[0].official)
    }

    @Test
    fun `getUsers 只返回可见的人（AC-SOC-10-7）；loadMyGroups（AC-SOC-11-3）；被管理接口移出群 reason=removed`() = test {
        val (a, b, c) = Triple(uid("s3a"), uid("s3b"), uid("s3c"))
        srv.importUsers(a to null, b to "群友", c to null)
        val alice = login(a)
        val gs = listOf(uid("g1x"), uid("g2x"), uid("g3x")).sorted()
        for (g in gs) srv.admin("POST", "/admin/groups", obj("groupId" to g, "name" to g, "members" to listOf(a, b)))
        assertEquals(gs.map { it to 2 }, alice.loadMyGroups().map { it.groupId to it.memberCount })
        assertEquals(listOf("群友"), alice.getUsers(listOf(b, c, "nobody")).map { it.nickname })
        val (events, scope) = collectEvents(alice)
        until("convs") { alice.conv("group:${gs[0]}") }
        srv.admin("DELETE", "/admin/groups/${gs[0]}/members", obj("members" to listOf(a)))
        until("convRemoved") { events.filterIsInstance<IMEvent.ConvRemoved>().firstOrNull() }
        assertEquals(IMEvent.ConvRemoved("group:${gs[0]}", "removed"), events.filterIsInstance<IMEvent.ConvRemoved>()[0])
        scope.cancel()
    }

    @Test
    fun `通讯录：3 个好友、2 个群、1 条未读申请（AC-SOC-11-3）；陌生人单聊不在好友列表（AC-SOC-11-4）`() = test {
        val a = uid("a")
        val (f1, f2, f3, r, s) = listOf(uid("f"), uid("f"), uid("f"), uid("r"), uid("s"))
        users(a, f1, f2, f3, r, s)
        srv.signed("POST", "/admin/friends/import", obj("pairs" to listOf(f1, f2, f3).map { obj("a" to a, "b" to it) }))
        repeat(2) { group(a, f1) }
        loginLoaded(r).applyFriend(a)
        loginLoaded(s).send(c2cConvId(a, s), "hi")
        val alice = loginLoaded(a)
        until("data") { alice.friends.value.size == 3 && alice.friendRequests.value.unseen == 1 }
        assertEquals(2, alice.loadMyGroups().size)
        until("stranger conv") { alice.conv(c2cConvId(a, s)) }
        assertFalse(alice.friends.value.any { it.userId == s })
    }

    @Test
    fun `同步或 ack 带来的新资料立即覆盖缓存（AC-SOC-10-9）`() = test {
        val (a, b) = uid("a") to uid("b")
        srv.importUsers(a to null, b to "旧昵称")
        srv.signed("POST", "/admin/friends/import", obj("pairs" to listOf(obj("a" to a, "b" to b))))
        val alice = loginLoaded(a)
        assertEquals("旧昵称", alice.getUsers(listOf(b))[0].nickname)
        srv.importUsers(b to "新昵称")
        alice.setFriendRemark(b, "x") // ack 的 items 带当前资料
        assertEquals("新昵称", alice.profileOf(b)?.nickname)
    }

    // ---- 群 ----

    private suspend fun befriend(a: IMClient, aId: String, b: IMClient, bId: String) {
        a.applyFriend(bId)
        until("request") { b.friendRequests.value.items.any { it.userId == aId } }
        b.respondFriend(aId, FriendAction.ACCEPT)
        until("friends") { a.relationOf(bId) == Relation.FRIEND }
    }

    @Test
    fun `建群、提示、邀请、设管理员、改名、踢人、转让、退群、解散（AC-SOC-21-1～27-1、28-5、29-5、20-10）`() = test {
        val (ia, ib, ic, id) = listOf(uid("gma"), uid("gmb"), uid("gmc"), uid("gmd"))
        users(ia, ib, ic, id)
        val a = loginLoaded(ia)
        val b = loginLoaded(ib)
        val c = loginLoaded(ic)
        val d = loginLoaded(id)
        befriend(a, ia, b, ib)
        befriend(a, ia, c, ic)
        befriend(a, ia, d, id)

        val created = a.createGroup(listOf(ib, ic))
        val groupId = created.groupId
        val convId = created.conv.convId
        assertEquals(GroupRole.OWNER, created.conv.myRole)
        assertEquals(3, created.conv.memberCount)
        fun tipOf(im: IMClient, kind: String) = im.messages(convId).firstOrNull { it.type == MsgType.TIP && it.body.tip?.kind == kind }
        b.setActiveConversation(convId)
        until("b sees group") { b.conv(convId)?.myRole == GroupRole.MEMBER }
        b.loadHistory(convId)
        val tip = until("created tip") { tipOf(b, "created") }
        assertEquals(1, tip.seq)
        assertEquals(ia, tip.from)
        assertEquals(listOf(ib, ic), tip.body.tip!!.targets)
        assertEquals("$ia 创建了群聊", tipText(tip.body.tip!!, ib) { it })
        assertEquals("你创建了群聊", tipText(tip.body.tip!!, ia) { it })

        assertEquals(GroupInviteResult(listOf(id), emptyList()), a.inviteToGroup(groupId, listOf(id)))
        until("d added") { d.conv(convId) }
        val joined = until("joined tip") { tipOf(b, "joined") }
        assertEquals("你邀请 $id 加入了群聊", tipText(joined.body.tip!!, ia) { it })
        assertEquals("$ia 邀请 $id 加入了群聊", tipText(joined.body.tip!!, ib) { it })
        until("memberCount via groupChanged") { b.conv(convId)?.memberCount == 4 }

        // 本地生成的摘要与服务端 lastMsg.text 一致（刷新前后不跳变）。
        fun summary() = b.conv(convId)?.lastMsg?.text
        assertEquals("$ia 邀请 $id 加入了群聊", summary())
        val fresh = login(ib)
        assertEquals(summary(), until("server summary") { fresh.conv(convId) }.lastMsg?.text)

        a.setGroupAdmin(groupId, ib, true)
        until("b admin") { b.conv(convId)?.myRole == GroupRole.ADMIN }

        b.updateGroup(groupId, name = "周六球局", notice = "九点")
        until("renamed") { a.conv(convId)?.name == "周六球局" }
        assertEquals("周六球局", until("convs name") { login(ic).conv(convId) }.name)
        val info = a.getGroupInfo(groupId)
        assertEquals(listOf<Any?>("九点", ib, ia, false), listOf(info.notice, info.noticeBy, info.owner, info.managed))

        // 管理员不能动群主；错误码原样透传。
        assertEquals(4030, (runCatching { b.kickFromGroup(groupId, listOf(ia)) }.exceptionOrNull() as IMError).code)
        assertEquals(listOf(id), b.kickFromGroup(groupId, listOf(id)))
        until("d removed") { d.conversationSnapshot(convId)?.reason == "removed" }
        d.send(convId, "after kick")
        until("d 4033") { d.messages(convId).any { it.error == SendError.Code(4033) } }

        val page = a.loadGroupMembers(groupId)
        assertEquals(listOf(ia to GroupRole.OWNER, ib to GroupRole.ADMIN, ic to GroupRole.MEMBER), page.members.map { it.userId to it.role })

        a.transferGroupOwner(groupId, ic)
        until("c owner") { c.conv(convId)?.myRole == GroupRole.OWNER }
        until("owner tip") { tipOf(b, "ownerChanged") }
        assertEquals(4030, (runCatching { a.kickFromGroup(groupId, listOf(ib)) }.exceptionOrNull() as IMError).code)
        assertEquals(ic, a.getGroupInfo(groupId).owner)
        assertEquals(GroupRole.MEMBER, a.getGroupInfo(groupId).myRole)

        val aPc = login(ia)
        until("aPc conv") { aPc.conv(convId) }
        a.leaveGroup(groupId)
        assertFalse(a.conversations.value.any { it.convId == convId })
        assertEquals("left", a.conversationSnapshot(convId)?.reason)
        until("other device left") { aPc.conversationSnapshot(convId)?.reason == "left" }
        a.send(convId, "after leave")
        until("a 4033") { a.messages(convId).any { it.error == SendError.Code(4033) } }

        c.dissolveGroup(groupId)
        until("b dissolved") { b.conversationSnapshot(convId)?.reason == "dissolved" }
        val offline = login(ib, client())
        delay(100)
        assertNull(offline.conv(convId))
    }

    @Test
    fun `建群失败带 userId：不是好友的人返回 4035（AC-SOC-21-1 错误路径）`() = test {
        val (e, f, g) = Triple(uid("e"), uid("f"), uid("g"))
        users(e, f, g)
        val ee = loginLoaded(e)
        befriend(ee, e, loginLoaded(f), f)
        val err = runCatching { ee.createGroup(listOf(f, g)) }.exceptionOrNull() as IMError
        assertEquals(4035, err.code)
        assertEquals(g, err.userId)
    }

    // ---- 第二批 ----

    @Test
    fun `封禁：两个在线连接都收到 suspended + until，不重连；旧 token 握手时同样被踢（AC-ADM-13-1、AC-ADM-13-11）`() = test {
        val u = uid("ban")
        users(u)
        val oldToken = srv.token(u)
        val a = login(u)
        val b = login(u)
        val before = System.currentTimeMillis()
        srv.signed("POST", "/admin/users/$u/ban", obj("durationSeconds" to 86_400, "reason" to "it"))
        for (x in listOf(a, b)) {
            val r = until("kicked") { x.fatal.value } as FatalReason.Kicked
            assertEquals("suspended", r.reason)
            assertEquals(4012, r.code)
            assertTrue(kotlin.math.abs(r.until!! - before - 86_400_000) < 5_000)
        }
        delay(300) // 退避上限 100ms：如果重连，这段时间内就连上了
        assertEquals(ConnState.DISCONNECTED, a.state.value)
        assertEquals(ConnState.DISCONNECTED, b.state.value)

        val late = client()
        late.connect(oldToken)
        val r = until("old token kicked") { late.fatal.value } as FatalReason.Kicked
        assertEquals("suspended", r.reason)
        assertEquals(4012, r.code)
    }

    @Test
    fun `永久封禁 until = null`() = test {
        val u = uid("ban")
        val im = login(u)
        srv.signed("POST", "/admin/users/$u/ban", obj("durationSeconds" to null, "reason" to "it"))
        assertEquals(FatalReason.Kicked("suspended", 4012, null), until("kicked") { im.fatal.value })
    }

    @Test
    fun `消息删除：在线连接都清原文；本地库文件搜不到特征串；摘要占位，未读不变；离线的上线后一样（AC-ADM-31-1、31-3、31-4、31-5）`() = test {
        val (s, m1, off) = Triple(uid("s"), uid("m"), uid("off"))
        val (_, convId) = group(s, m1, off)
        val dbDir = Files.createTempDirectory("im-sdk-it-local").toFile()
        val a = login(s)
        val a2 = login(s)
        val b = login(m1, client(database = JdbcLocalDatabase(File(dbDir, "im.db"))))
        val secret = "ZXQ-DEL-${uid("x")}"
        a.send(convId, secret)
        fun find(x: IMClient) = x.messages(convId).firstOrNull { it.body.text == secret }
        until("ack") { find(a)?.status == MessageStatus.SENT }
        val msgId = find(a)!!.msgId!!
        for (x in listOf(a2, b)) until("delivered") { find(x) }
        assertTrue(filesContaining(dbDir, secret).isNotEmpty(), "对照：删除之前本地库里有")
        val unread = b.conv(convId)!!.unread
        assertTrue(unread > 0)

        srv.signed("POST", "/admin/messages/delete", obj("convId" to convId, "msgIds" to listOf(msgId), "reason" to "it"))
        for (x in listOf(a, a2, b)) {
            until("deleted") { x.messages(convId).firstOrNull { it.msgId == msgId }?.deleted }
            assertFalse(x.debugExport().toString().contains(secret))
            val l = x.conv(convId)!!.lastMsg!!
            assertEquals(msgId, l.msgId)
            assertEquals(true, l.deleted)
        }
        assertEquals(unread, b.conv(convId)!!.unread)
        delay(100)
        assertEquals(emptyList(), filesContaining(dbDir, secret), "数据库文件里还能搜到")

        val offline = login(off)
        until("offline convs") { offline.conv(convId) }
        val v = offline.conv(convId)!!
        assertEquals(true, v.lastMsg?.deleted)
        assertNull(v.lastMsg?.text)
        offline.loadHistory(convId)
        assertEquals(true, offline.messages(convId).last().deleted)
        assertEquals(MsgBody(), offline.messages(convId).last().body)
        assertFalse(offline.debugExport().toString().contains(secret))
        assertEquals(v.unread, offline.conv(convId)!!.unread)
    }

    @Test
    fun `离线期间删除 + 冷启动：本地库的原文在重新连上后被清掉；socialSync 从持久化的 ver 增量（FE-131、FE-435）`() = test {
        val (s, m) = uid("s") to uid("m")
        val (_, convId) = group(s, m)
        srv.signed("POST", "/admin/friends/import", obj("pairs" to listOf(obj("a" to m, "b" to s))))
        val dbDir = Files.createTempDirectory("im-sdk-it-cold").toFile()
        val a = login(s)
        val b = loginLoaded(m, client(database = JdbcLocalDatabase(File(dbDir, "im.db"))))
        val verBefore = until("friends") { b.friends.value.firstOrNull()?.ver }
        val secret = "ZXQ-COLD-${uid("x")}"
        a.send(convId, secret)
        until("ack") { a.messages(convId).firstOrNull { it.body.text == secret }?.status == MessageStatus.SENT }
        val msgId = a.messages(convId).first { it.body.text == secret }.msgId!!
        until("delivered") { b.messages(convId).any { it.body.text == secret } }
        b.close()
        clients.remove(b)
        assertTrue(filesContaining(dbDir, secret).isNotEmpty())

        srv.signed("POST", "/admin/messages/delete", obj("convId" to convId, "msgIds" to listOf(msgId), "reason" to "it"))
        val gate = GatedTransport()
        val b2 = client(database = JdbcLocalDatabase(File(dbDir, "im.db")), transport = gate)
        until("restored") { b2.userId.value == m }
        assertEquals(listOf(s), b2.friends.value.map { it.userId }) // 离线也有
        login(m, b2)
        until("wiped from memory") { b2.messages(convId).firstOrNull { it.msgId == msgId }?.deleted }
        delay(100)
        assertEquals(emptyList(), filesContaining(dbDir, secret))
        until("social sync sent") { gate.sent.any { it.contains("socialSync") } }
        assertEquals(verBefore.toString(), gate.firstSocialSync()["sinceVer"]!!.jsonPrimitive.content)
    }

    @Test
    fun `convRemoved：本地库删除该会话；被封禁清库（FE-435、FE-131）`() = test {
        val (u, p) = uid("u") to uid("p")
        val (groupId, convId) = group(u, p)
        val dbDir = Files.createTempDirectory("im-sdk-it-rm").toFile()
        val sender = login(p)
        val im = login(u, client(database = JdbcLocalDatabase(File(dbDir, "im.db"))))
        until("conv") { im.conv(convId) }
        val secret = "ZXQ-RM-${uid("x")}"
        sender.send(convId, secret)
        until("delivered") { im.messages(convId).any { it.body.text == secret } }
        assertTrue(filesContaining(dbDir, secret).isNotEmpty())
        srv.admin("DELETE", "/admin/groups/$groupId/members", obj("members" to listOf(u)))
        until("removed") { im.conversationSnapshot(convId)?.reason == "removed" }
        delay(100)
        assertEquals(emptyList(), filesContaining(dbDir, secret))

        // 再发一条单聊，被封禁后整个库清空
        val c2c = c2cConvId(u, p)
        val secret2 = "ZXQ-BAN-${uid("x")}"
        sender.send(c2c, secret2)
        until("c2c delivered") { im.messages(c2c).any { it.body.text == secret2 } }
        assertTrue(filesContaining(dbDir, secret2).isNotEmpty())
        srv.signed("POST", "/admin/users/$u/ban", obj("durationSeconds" to 60, "reason" to "it"))
        until("kicked") { im.fatal.value }
        delay(100)
        assertEquals(emptyList(), filesContaining(dbDir, secret2))
    }

    @Test
    fun `用户级禁言：muted 事件、全部会话禁言，发送 4031 不重试；解除后发送成功（AC-ADM-40-4、40-5）`() = test {
        val (u, p) = uid("u") to uid("p")
        val (_, convId) = group(u, p)
        val im = login(u)
        val c2c = c2cConvId(u, p)
        srv.signed("POST", "/admin/mute", obj("scope" to "user", "userId" to u, "durationSeconds" to 600, "reason" to "it"))
        until("muted") { im.muteState(convId) }
        assertEquals(MuteScope.USER, im.muteState(c2c)?.scope)
        assertTrue(im.muteState(convId)!!.mutedUntil!! > im.serverNow())

        val r = im.send(convId, "x") as SendResult.Ok
        val id = r.message.clientMsgId
        until("4031") { im.messages(convId).firstOrNull { it.clientMsgId == id }?.status == MessageStatus.FAILED }
        assertEquals(SendError.Code(4031), im.messages(convId).first { it.clientMsgId == id }.error)

        srv.signed("POST", "/admin/unmute", obj("scope" to "user", "userId" to u, "reason" to "it"))
        until("unmuted") { im.muteState(convId) == null }
        val back = im.send(convId, "back") as SendResult.Ok
        until("sent") { im.messages(convId).firstOrNull { it.clientMsgId == back.message.clientMsgId }?.status == MessageStatus.SENT }
    }

    @Test
    fun `离线时用户级禁言：auth ack 带 mutedUntil，上线就禁言；永久为 null（AC-ADM-40-6）`() = test {
        val u = uid("u")
        users(u)
        srv.signed("POST", "/admin/mute", obj("scope" to "user", "userId" to u, "durationSeconds" to null, "reason" to "it"))
        val im = login(u)
        assertEquals(MuteInfo(MuteScope.USER, null, null), im.muteState(c2cConvId(u, "other")))
    }

    @Test
    fun `离线时群内禁言：convs 带 mutedUntil，只有该群；在线时 muted 事件也只影响该群（AC-ADM-41-6）`() = test {
        val (u, p) = uid("u") to uid("p")
        val g1 = group(u, p)
        val g2 = group(u, p)
        srv.signed("POST", "/admin/mute", obj("scope" to "group", "groupId" to g1.first, "userId" to u, "durationSeconds" to 3600, "reason" to "it"))
        val im = login(u)
        until("g1 muted from convs") { im.muteState(g1.second) }
        assertEquals(MuteScope.GROUP, im.muteState(g1.second)?.scope)
        assertEquals(g1.first, im.muteState(g1.second)?.groupId)
        assertNull(im.muteState(g2.second))
        assertNull(im.muteState(c2cConvId(u, p)))
        val ok = im.send(g2.second, "ok") as SendResult.Ok
        val no = im.send(g1.second, "no") as SendResult.Ok
        until("g2 sent") { im.messages(g2.second).any { it.clientMsgId == ok.message.clientMsgId && it.status == MessageStatus.SENT } }
        until("g1 4031") { im.messages(g1.second).any { it.clientMsgId == no.message.clientMsgId && it.status == MessageStatus.FAILED } }

        srv.signed("POST", "/admin/unmute", obj("scope" to "group", "groupId" to g1.first, "userId" to u, "reason" to "it"))
        until("g1 unmuted") { im.muteState(g1.second) == null }
        srv.signed("POST", "/admin/mute", obj("scope" to "group", "groupId" to g2.first, "userId" to u, "durationSeconds" to null, "reason" to "it"))
        until("g2 muted by event") { im.muteState(g2.second) }
        assertNull(im.muteState(g2.second)?.mutedUntil)
        assertNull(im.muteState(g1.second))
    }
}
