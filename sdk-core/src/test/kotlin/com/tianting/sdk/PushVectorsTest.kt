// 跑共享行为用例 protocol/vectors/push-sync.json（PRD-live 8.3 节）：走真实的 IMClient + FakeTransport + 假时钟，不改用例。
package com.tianting.sdk

import com.tianting.sdk.testing.FakeClock
import com.tianting.sdk.testing.FakeConnection
import com.tianting.sdk.testing.FakeTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** 比任何用例里推进的总时间都长：请求超时只由 requestTimeout 这一步触发。 */
private const val REQUEST_TIMEOUT = 9_000_000_000L

private class RequestTimer(val fn: () -> Unit, val handle: Timer, val sentBefore: Int)

/** 假时钟 + 记下请求超时定时器：requestTimeout 只触发 pushSync 的那一个，不推进时间。 */
private class RecordingClock : FakeClock() {
    val requestTimers = ArrayList<RequestTimer>()
    var sentCount: () -> Int = { 0 }

    override fun schedule(delayMs: Long, fn: () -> Unit): Timer {
        val handle = super.schedule(delayMs, fn)
        if (delayMs == REQUEST_TIMEOUT) requestTimers.add(RequestTimer(fn, handle, sentCount()))
        return handle
    }
}

class MemoryCursorStore : PushCursorStore {
    val data = HashMap<String, PushCursorState>()
    var saves = 0

    override fun load(userId: String): PushCursorState? = data[userId]

    override fun save(userId: String, cursor: Long, recentIds: List<String>) {
        saves++
        data[userId] = PushCursorState(cursor, recentIds)
    }
}

private fun expand(items: JsonElement?): List<JsonObject> = (items as? JsonArray).orEmpty().flatMap { raw ->
    val it = raw.jsonObject
    val range = it["seqRange"]?.jsonArray ?: return@flatMap listOf(it)
    val prefix = it["idPrefix"]?.jsonPrimitive?.content ?: "p"
    val rest = it.filterKeys { k -> k != "seqRange" && k != "idPrefix" }
    (range[0].jsonPrimitive.long..range[1].jsonPrimitive.long).map { n ->
        JsonObject(rest + mapOf("pushId" to JsonPrimitive("$prefix$n"), "pushSeq" to JsonPrimitive(n)))
    }
}

private fun toFrame(it: JsonObject) = JsonObject(
    mapOf("type" to JsonPrimitive("notice"), "data" to obj("text" to "x"), "target" to JsonPrimitive("user")) + it,
)

private fun field(p: Push, k: String): JsonElement = when (k) {
    "pushId" -> JsonPrimitive(p.pushId)
    "replayed" -> JsonPrimitive(p.replayed)
    "target" -> JsonPrimitive(p.target)
    "roomId" -> p.roomId?.let { JsonPrimitive(it) } ?: JsonNull
    "pushSeq" -> p.pushSeq?.let { JsonPrimitive(it) } ?: JsonNull
    "ts" -> JsonPrimitive(p.ts)
    "type" -> JsonPrimitive(p.type)
    else -> error("未知字段 $k")
}

@OptIn(ExperimentalCoroutinesApi::class)
private class VectorRun(private val c: JsonObject, private val limit: Int) {
    private val name = c["name"]!!.jsonPrimitive.content
    private val config = c["config"]?.jsonObject
    private val store: MemoryCursorStore? = if (config?.get("persistence")?.jsonPrimitive?.boolean == true) MemoryCursorStore() else null
    private val scheduler = TestCoroutineScheduler()
    private val collectScope = CoroutineScope(UnconfinedTestDispatcher(scheduler) + SupervisorJob())

    private lateinit var clock: RecordingClock
    private lateinit var transport: FakeTransport
    private lateinit var im: IMClient
    private val delivered = LinkedHashMap<String, MutableList<Push>>()
    private val subs = LinkedHashMap<String, Job>()
    private val synced = ArrayList<Long>()
    private var syncedJob: Job? = null
    private var onDeliver: JsonObject? = null
    private val seenFrames = HashMap<FakeConnection, Int>()
    private var lastReq: Triple<FakeConnection, String, Int>? = null

    private fun flush() = scheduler.runCurrent()

    private fun newClient() {
        clock = RecordingClock()
        transport = FakeTransport { flush() }
        clock.afterEach = { flush() }
        clock.sentCount = { transport.current.sent.size }
        val limits = PushLimits(
            stashMax = config?.get("stashMax")?.jsonPrimitive?.int ?: 200,
            noSeqQueueMax = config?.get("noSeqQueueMax")?.jsonPrimitive?.int ?: 500,
            seenMax = config?.get("seenMax")?.jsonPrimitive?.int ?: 2000,
        )
        im = IMClient(
            IMClientOptions(
                url = "ws://im.test/ws",
                transport = transport,
                clock = clock,
                random = { 0.5 },
                timings = IMClientTimings(requestTimeoutMs = REQUEST_TIMEOUT, sendTimeoutMs = REQUEST_TIMEOUT, heartbeatMs = REQUEST_TIMEOUT * 2),
                dispatcher = StandardTestDispatcher(scheduler),
                pushStart = if (config?.get("pushStart")?.jsonPrimitive?.content == "latest") PushStart.LATEST else PushStart.BEGINNING,
                pushCursorStore = store,
                pushLimits = limits,
            ),
        )
        syncedJob = collectScope.launch { im.pushSynced.collect { synced.add(it) } }
        flush()
    }

    private fun newRequests(): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        for (conn in transport.connections) {
            val frames = conn.frames()
            for (i in (seenFrames[conn] ?: 0) until frames.size) {
                val f = frames[i]
                if (f["cmd"]!!.jsonPrimitive.content != "pushSync") continue
                lastReq = Triple(conn, f["id"]!!.jsonPrimitive.content, i)
                out.add(f["data"]!!.jsonObject)
            }
            seenFrames[conn] = frames.size
        }
        return out
    }

    fun run() {
        (config?.get("stored") as? JsonObject)?.let { stored ->
            for ((u, v) in stored) {
                val cursor = v.jsonObject["cursor"]!!.jsonPrimitive.long
                store!!.data[u] = PushCursorState(cursor, expand(v.jsonObject["seen"]).map { it["pushId"]!!.jsonPrimitive.content })
            }
        }
        newClient()
        try {
            c["steps"]!!.jsonArray.forEachIndexed { i, s -> step(i, s.jsonObject) }
        } finally {
            collectScope.cancel()
        }
    }

    private fun step(i: Int, s: JsonObject) {
        val op = s["op"]!!.jsonPrimitive.content
        val where = "$name #$i $op"
        val networkOp = op in setOf("connect", "disconnect", "live", "ack", "ackError", "requestTimeout", "advance")
        val before = subs.keys.toSet()
        if (networkOp) flush() // 调度点先于网络事件和定时器；这期间发出的请求算这一步的
        onDeliver = s["unsubscribeOnDeliver"]?.jsonObject
        val stepRequests = newRequests()
        val current = lastReq

        when (op) {
            "subscribe" -> {
                val id = s["id"]!!.jsonPrimitive.content
                val list = delivered.getOrPut(id) { ArrayList() }
                subs[id] = collectScope.launch {
                    im.pushes.collect { p ->
                        list.add(p)
                        val od = onDeliver
                        if (od != null && od["pushId"]!!.jsonPrimitive.content == p.pushId && od["id"]!!.jsonPrimitive.content == id) {
                            subs.remove(id)!!.cancel() // 回调内退订
                        }
                    }
                }
            }
            "unsubscribe" -> subs.remove(s["id"]!!.jsonPrimitive.content)!!.cancel()
            "tick" -> Unit
            "connect" -> {
                // 用例里 u1 还连着就 connect u2（"userId 变化时先按 logout 处理"）：真实路径是旧连接断开后，重连时 auth 成了另一个账号。
                if (im.state.value == ConnState.CONNECTED) transport.current.drop(1006)
                im.connect("token")
                im.networkOnline()
                flush()
                val conn = transport.current
                conn.open()
                conn.ack("auth", obj("userId" to s["userId"], "serverTime" to s["serverTime"]))
            }
            "disconnect" -> transport.current.drop(1006)
            "logout" -> collectScope.launch { im.disconnect() }
            "live" -> for (f in expand(s["frames"])) transport.current.receive(obj("cmd" to "push", "data" to toFrame(f)))
            "ack" -> current?.let { (conn, id, _) ->
                conn.receive(
                    obj(
                        "id" to id,
                        "cmd" to "ack",
                        "data" to obj("pushes" to expand(s["pushes"]).map(::toFrame), "hasMore" to s["hasMore"], "latestSeq" to s["latestSeq"]),
                    ),
                )
            }
            "ackError" -> current?.let { (conn, id, _) ->
                conn.receive(obj("id" to id, "cmd" to "ack", "error" to obj("code" to s["code"], "msg" to "x")))
            }
            "requestTimeout" -> {
                val t = clock.requestTimers.firstOrNull { it.sentBefore == current!!.third }
                assertNotNull(t, where)
                t.handle.cancel()
                t.fn()
            }
            "advance" -> clock.advance(s["ms"]!!.jsonPrimitive.long)
            "restart" -> restart()
            else -> error("未知的 op $op")
        }
        if (op != "subscribe") flush() // 除 subscribe 外每步结束隐含一次调度点

        val want = s["expect"]?.jsonObject ?: JsonObject(emptyMap())
        val requests = stepRequests + newRequests()
        val wantRequests = (want["requests"] as? JsonArray).orEmpty().map { r ->
            if ("afterSeq" in r.jsonObject) JsonObject(r.jsonObject + ("limit" to JsonPrimitive(limit))) else r.jsonObject
        }
        assertEquals(wantRequests, requests, "$where requests")
        assertEquals((want["pushSynced"] as? JsonArray).orEmpty().map { it.jsonPrimitive.long }, synced.toList(), "$where pushSynced")
        synced.clear()

        val ids = before + subs.keys
        for ((id, got) in delivered) {
            val wanted = when {
                want["deliveredTo"] != null -> expand(want["deliveredTo"]!!.jsonObject[id])
                want["delivered"] != null && id in ids -> expand(want["delivered"])
                else -> emptyList()
            }
            val gotView = got.mapIndexed { n, p ->
                val keys = wanted.getOrNull(n)?.keys ?: setOf("replayed")
                JsonObject((keys + "pushId").associateWith { k -> field(p, k) })
            }
            assertEquals(wanted, gotView, "$where delivered to $id")
            got.clear()
        }
        if ("cursor" in want) {
            val cur = want["cursor"]
            assertEquals(if (cur is JsonNull) null else cur!!.jsonPrimitive.long, im.pushCursor(), "$where cursor")
        }
    }

    /** 进程退出后新建实例：内存全丢，订阅者也没了，只有持久化存储还在。 */
    private fun restart() {
        subs.values.forEach { it.cancel() }
        subs.clear()
        syncedJob?.cancel()
        flush()
        val old = im
        collectScope.launch { old.close() }
        flush()
        lastReq = null
        seenFrames.clear()
        newClient()
    }
}

class PushVectorsTest {
    private val file = vector("push-sync.json").jsonObject
    private val cases = file["cases"]!!.jsonArray
    private val limit = file["defaults"]!!.jsonObject["limit"]!!.jsonPrimitive.int

    @TestFactory
    fun `push-sync json 全部 71 个用例`(): List<DynamicTest> {
        assertEquals(71, cases.size)
        return cases.map { c -> dynamicTest(c.jsonObject["name"]!!.jsonPrimitive.content) { VectorRun(c.jsonObject, limit).run() } }
    }
}
