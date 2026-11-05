package com.tianting.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URLEncoder
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

public enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED }

/** 连接不再自动重连的原因：被踢（kicked 事件），或者收到不应重连的关闭码（1000、4009～4012、4429）。 */
public sealed interface FatalReason {
    /** until 只在 reason = suspended 时有意义：封禁到期时间（毫秒），永久为 null。 */
    public data class Kicked(val reason: String, val code: Int, val until: Long? = null) : FatalReason
    public data class Closed(val code: Int, val reason: String) : FatalReason
}

public data class IMClientTimings(
    val heartbeatMs: Long = 30_000,
    val pongTimeoutMs: Long = 10_000,
    val requestTimeoutMs: Long = 10_000,
    val backoffBaseMs: Long = 1_000,
    val backoffMaxMs: Long = 30_000,
    /** 退避抖动比例（0.2 表示 ±20%）。 */
    val jitter: Double = 0.2,
    /** 发送超时（PRD F-MSG-03 规则 1）。 */
    val sendTimeoutMs: Long = 10_000,
    /** 发现 seq 缺口后等多久还没补上就 sync（F-MSG-04 规则 2）。 */
    val gapWaitMs: Long = 500,
    /** 已读上报节流（F-CONV-02 规则 3）。 */
    val readThrottleMs: Long = 1_000,
    /** 断开持续多久才提示（F-CONN-02）。 */
    val bannerDelayMs: Long = 1_000,
)

/** 下行帧（msg、event，kicked 除外）。 */
internal class InboundFrame(val cmd: String, val data: JsonElement?)

internal interface ConnectionListener {
    fun onState(state: ConnState)
    fun onConnected(ack: AuthAck)
    fun onFrame(frame: InboundFrame)
    fun onFatal(reason: FatalReason)
}

/**
 * 连接管理（FE-003）：首帧鉴权、心跳、断线重连（指数退避 + 抖动）、被踢处理、请求与 ack 匹配。
 *
 * 状态：DISCONNECTED → CONNECTING（建立 WebSocket、发送 auth）→ CONNECTED（收到 auth 的 ack）。
 * token 只出现在 auth 帧里，不进 URL、不进日志（C-52）。除 transport 回调外，所有方法都在 SDK 的单线程上调用；
 * post 把 transport 的回调切回这个线程。
 */
internal class Connection(
    private val url: String,
    private val platform: Platform,
    private val transport: Transport,
    private val clock: Clock,
    private val log: Logger,
    private val random: () -> Double,
    private val t: IMClientTimings,
    private val post: (() -> Unit) -> Unit,
    private val listener: ConnectionListener,
) {
    private var token = ""
    private var active = false // start() 之后、stop() 或致命关闭之前为 true
    var state: ConnState = ConnState.DISCONNECTED
        private set
    private var conn: TransportConnection? = null
    private var connGen = 0 // 每次拨号加一，用来忽略旧连接迟到的回调
    private var attempt = 0 // 连续失败次数，用于退避；鉴权成功后清零
    private var reconnectTimer: Timer? = null
    private var heartbeatTimer: Timer? = null
    private var pongTimer: Timer? = null
    private var kickedReason: String? = null
    private var kickedUntil: Long? = null
    private var reqN = 0
    private var authId = ""
    private val pending = LinkedHashMap<String, Pending>()

    private class Pending(
        val cmd: String,
        val done: CompletableDeferred<JsonElement?>,
        val onLateAck: ((JsonElement?, IMError?) -> Unit)?,
    ) {
        var timer: Timer? = null
        var timedOut = false
    }

    /** 开始连接（已经在连接或已连接时只更新 token）。 */
    fun start(token: String) {
        this.token = token
        if (active) return
        active = true
        attempt = 0
        dial()
    }

    /** 主动退出：以 1000 关闭，不再重连（PRD F-AUTH-05）。 */
    fun stop() {
        active = false
        token = ""
        clearReconnect()
        val c = conn
        teardown()
        c?.close(1000, "logout")
        setState(ConnState.DISCONNECTED)
    }

    /** 网络恢复：正在等待退避时立即重连（PRD F-CONN-01 规则 3）。 */
    fun reconnectNow() {
        if (active && state == ConnState.DISCONNECTED && reconnectTimer != null) {
            clearReconnect()
            dial()
        }
    }

    /**
     * 发送请求并等待 ack。未连接时立即失败（NotConnectedError）；ack 带 error 时以 IMError 失败；
     * 超时以 TimeoutError 失败，之后 ack 若到达会调用 onLateAck。帧在挂起之前就同步发出。
     */
    suspend fun request(
        cmd: String,
        data: JsonObject,
        timeoutMs: Long = t.requestTimeoutMs,
        onLateAck: ((JsonElement?, IMError?) -> Unit)? = null,
    ): JsonElement? {
        val c = conn
        if (state != ConnState.CONNECTED || c == null) throw NotConnectedError()
        val id = "r${++reqN}"
        val p = Pending(cmd, CompletableDeferred(), onLateAck)
        p.timer = clock.schedule(timeoutMs) {
            p.timedOut = true
            p.done.completeExceptionally(TimeoutError(cmd))
            if (p.onLateAck == null) pending.remove(id)
        }
        pending[id] = p
        c.send(buildJsonObject { put("id", id); put("cmd", cmd); put("data", data) }.toString())
        return p.done.await()
    }

    private fun dial() {
        val gen = ++connGen
        kickedReason = null
        kickedUntil = null
        setState(ConnState.CONNECTING)
        val fullUrl = "$url?platform=${URLEncoder.encode(platform.wire, "UTF-8")}"
        conn = transport.open(
            fullUrl,
            object : TransportHandlers {
                override fun onOpen() = post {
                    if (gen != connGen) return@post
                    authId = "auth$gen"
                    // 不经过任何日志：auth 帧里有 token。
                    conn?.send(
                        buildJsonObject {
                            put("id", authId)
                            put("cmd", "auth")
                            put("data", buildJsonObject { put("token", token) })
                        }.toString(),
                    )
                }

                override fun onMessage(text: String) = post { if (gen == connGen) onText(text) }

                override fun onClose(code: Int, reason: String) = post { if (gen == connGen) onClosed(code, reason) }
            },
        )
    }

    private fun onText(text: String) {
        val f = try {
            ImJson.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        }
        val cmd = (f?.get("cmd") as? JsonPrimitive)?.contentOrNull
        if (f == null || cmd == null) {
            log.warn("im: invalid frame from server")
            return
        }
        when (cmd) {
            "ack" -> onAck(f)
            "pong" -> clearPong()
            "event" -> {
                val ev = f["data"] as? JsonObject
                if ((ev?.get("type") as? JsonPrimitive)?.contentOrNull == "kicked") {
                    // 先记下原因，等随后的关闭码到达时统一处理。
                    kickedReason = (ev["reason"] as? JsonPrimitive)?.contentOrNull ?: ""
                    kickedUntil = (ev["until"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
                    return
                }
                listener.onFrame(InboundFrame(cmd, f["data"]))
            }
            else -> listener.onFrame(InboundFrame(cmd, f["data"]))
        }
    }

    private fun onAck(f: JsonObject) {
        val id = (f["id"] as? JsonPrimitive)?.contentOrNull
        val errorBody = f["error"]?.let { e ->
            try {
                ImJson.decodeFromJsonElement(ErrorBody.serializer(), e)
            } catch (_: Exception) {
                log.warn("im: malformed error in ack")
                return
            }
        }
        if (id != null && id == authId && state == ConnState.CONNECTING) {
            if (errorBody != null) {
                log.warn("im: auth rejected ${errorBody.code}")
                return // 凭证问题由服务端随后的 kicked + 关闭码处理
            }
            val ack = try {
                ImJson.decodeFromJsonElement(AuthAck.serializer(), f["data"] ?: JsonObject(emptyMap()))
            } catch (_: Exception) {
                log.warn("im: malformed auth ack")
                return
            }
            attempt = 0
            setState(ConnState.CONNECTED)
            scheduleHeartbeat()
            listener.onConnected(ack)
            return
        }
        val p = id?.let { pending[it] } ?: return
        pending.remove(id)
        p.timer?.cancel()
        val err = errorBody?.let {
            IMError(it.code, it.msg, it.userId ?: (it.details?.get("userId") as? JsonPrimitive)?.contentOrNull, it.details)
        }
        if (p.timedOut) {
            p.onLateAck?.invoke(f["data"], err)
            return
        }
        if (err != null) p.done.completeExceptionally(err) else p.done.complete(f["data"])
    }

    private fun scheduleHeartbeat() {
        heartbeatTimer = clock.schedule(t.heartbeatMs) {
            heartbeatTimer = null
            val c = conn
            if (state != ConnState.CONNECTED || c == null) return@schedule
            c.send("""{"cmd":"ping"}""")
            // 10 秒收不到 pong：网络在线但服务端无响应，判定断线（AC-CONN-01-6）。
            if (pongTimer == null) {
                pongTimer = clock.schedule(t.pongTimeoutMs) {
                    pongTimer = null
                    log.warn("im: pong timeout, reconnecting")
                    val old = conn
                    onClosed(1006, "pong timeout")
                    old?.close(4000, "pong timeout")
                }
            }
            scheduleHeartbeat()
        }
    }

    private fun onClosed(code: Int, reason: String) {
        val kicked = kickedReason
        val until = kickedUntil
        teardown()
        setState(ConnState.DISCONNECTED)
        if (!active) return

        // 被踢不重连（PRD F-AUTH-03 规则 4），例外：服务端下线按退避重连（C-55）。
        if (kicked != null && kicked != "serverShutdown") {
            active = false
            token = ""
            listener.onFatal(FatalReason.Kicked(kicked, code, until))
            return
        }
        // 1000、4009～4012、4429 不重连；未列出的关闭码（1006 等）按可以重连处理。
        if (kicked == null && CLOSE_RECONNECT[code] == false) {
            active = false
            token = ""
            listener.onFatal(FatalReason.Closed(code, reason))
            return
        }
        scheduleReconnect()
    }

    /** 退避：1s、2s、4s… 最长 30s（上限是加抖动之前的值，C-22），再加 ±jitter。 */
    private fun scheduleReconnect() {
        val base = min(t.backoffBaseMs * 2.0.pow(attempt), t.backoffMaxMs.toDouble())
        val delay = (base * (1 + t.jitter * (2 * random() - 1))).roundToLong()
        attempt++
        log.debug("im: reconnect in ${delay}ms")
        reconnectTimer = clock.schedule(delay) {
            reconnectTimer = null
            if (active) dial()
        }
    }

    /** 断开后的清理：停定时器、让所有未完成的请求失败、忽略旧连接后续的回调。 */
    private fun teardown() {
        connGen++
        conn = null
        heartbeatTimer?.cancel()
        heartbeatTimer = null
        clearPong()
        val list = pending.values.toList()
        pending.clear()
        for (p in list) {
            p.timer?.cancel()
            if (!p.timedOut) p.done.completeExceptionally(ConnectionClosedError())
        }
    }

    private fun clearPong() {
        pongTimer?.cancel()
        pongTimer = null
    }

    private fun clearReconnect() {
        reconnectTimer?.cancel()
        reconnectTimer = null
    }

    private fun setState(s: ConnState) {
        if (s == state) return
        state = s
        listener.onState(s)
    }
}
