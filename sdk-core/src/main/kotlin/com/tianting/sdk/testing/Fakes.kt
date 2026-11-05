// 测试替身（FE-007）：SDK 自己的单元测试和界面的测试都可以用。
package com.tianting.sdk.testing

import com.tianting.sdk.Clock
import com.tianting.sdk.Timer
import com.tianting.sdk.Transport
import com.tianting.sdk.TransportConnection
import com.tianting.sdk.TransportHandlers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 可控时钟：advance(ms) 时按到期顺序执行定时器，心跳、超时、退避、节流都能确定性地测试。afterEach 在每个定时器之后调用。 */
public open class FakeClock(start: Long = 1_700_000_000_000) : Clock {
    private class Item(val id: Long, val at: Long, val fn: () -> Unit)

    private var t = start
    private var nextId = 1L
    private val timers = ArrayList<Item>()

    /** 每个定时器执行后调用（测试里用来让协程调度器跑完挂起的工作）。 */
    public var afterEach: () -> Unit = {}

    override fun now(): Long = t

    override fun schedule(delayMs: Long, fn: () -> Unit): Timer {
        val item = Item(nextId++, t + maxOf(0, delayMs), fn)
        timers.add(item)
        return Timer { timers.remove(item) }
    }

    /** 时间前进 ms，期间到期的定时器按时间顺序执行（定时器里新建的、在窗口内到期的也会执行）。 */
    public fun advance(ms: Long) {
        val end = t + ms
        while (true) {
            val due = timers.filter { it.at <= end }.minWithOrNull(compareBy<Item> { it.at }.thenBy { it.id }) ?: break
            timers.remove(due)
            t = due.at
            due.fn()
            afterEach()
        }
        t = end
        afterEach()
    }

    /** 待执行的定时器数量。 */
    public val pending: Int get() = timers.size
}

/** 一条假连接：记录客户端发出的帧，测试代码可以脚本化地下发帧、打开、关闭。 */
public class FakeConnection(public val url: String, private val h: TransportHandlers, private val afterEach: () -> Unit) : TransportConnection {
    public val sent: MutableList<String> = ArrayList()
    public var closedWith: Pair<Int, String>? = null
        private set

    /** 每发一帧时调用（测试里用来校验 Schema）。 */
    public var onSent: (String) -> Unit = {}

    override fun send(text: String) {
        if (closedWith == null) {
            sent.add(text)
            onSent(text)
        }
    }

    override fun close(code: Int, reason: String) {
        if (closedWith == null) closedWith = code to reason
    }

    /** 模拟握手完成。 */
    public fun open() {
        h.onOpen()
        afterEach()
    }

    /** 模拟服务端下发一帧。 */
    public fun receive(frame: String) {
        h.onMessage(frame)
        afterEach()
    }

    public fun receive(frame: JsonElement): Unit = receive(frame.toString())

    /** 模拟连接结束（服务端关闭或断网，断网用 1006）。 */
    public fun drop(code: Int = 1006, reason: String = "") {
        h.onClose(code, reason)
        afterEach()
    }

    /** 客户端发出的帧（已解析）。 */
    public fun frames(): List<JsonObject> = sent.map { Json.parseToJsonElement(it) as JsonObject }

    public fun frames(cmd: String): List<JsonObject> = frames().filter { (it["cmd"] as JsonPrimitive).contentOrNull == cmd }

    /** 最后一个指定命令的请求。 */
    public fun last(cmd: String): JsonObject = frames(cmd).lastOrNull() ?: throw AssertionError("no $cmd frame sent")

    /** 对最后一个指定命令的请求回 ack。 */
    public fun ack(cmd: String, data: JsonElement = JsonObject(emptyMap())) {
        val id = (last(cmd)["id"] as JsonPrimitive).content
        receive(buildJsonObject { put("id", id); put("cmd", "ack"); put("data", data) })
    }

    public fun ackError(cmd: String, code: Int, msg: String = "error") {
        val id = (last(cmd)["id"] as JsonPrimitive).content
        receive(
            buildJsonObject {
                put("id", id)
                put("cmd", "ack")
                put("error", buildJsonObject { put("code", code); put("msg", msg) })
            },
        )
    }
}

/** 假传输层：每次 open 产生一条 FakeConnection，测试代码通过 connections 控制它们。 */
public class FakeTransport(public var afterEach: () -> Unit = {}) : Transport {
    public val connections: MutableList<FakeConnection> = ArrayList()
    public var onSent: (String) -> Unit = {}

    override fun open(url: String, handlers: TransportHandlers): TransportConnection {
        val c = FakeConnection(url, handlers) { afterEach() }
        c.onSent = { onSent(it) }
        connections.add(c)
        return c
    }

    /** 最近一条连接。 */
    public val current: FakeConnection get() = connections.lastOrNull() ?: throw AssertionError("no connection opened")
}
