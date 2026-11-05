// 系统推送的 pushes 流（PRD-live 4.3、4.5、4.8、4.9 节，规则 C1～C12）。三端一致的判据是 protocol/vectors/push-sync.json。
package com.tianting.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

public enum class PushStart { BEGINNING, LATEST }

/** 交给业务的一条推送（4.8 节）。data 由业务解释，SDK 不写日志（F-PUSH-08 规则 3），toString 也不带它。 */
public data class Push(
    val pushId: String,
    val type: String,
    val data: JsonElement,
    val ts: Long,
    val target: String,
    val roomId: String? = null,
    val pushSeq: Long? = null,
    /** ts 早于本实例上该账号第一次 auth 的 serverTime（4.5 节规则 4）。 */
    val replayed: Boolean = false,
) {
    override fun toString(): String = "Push(pushId=$pushId, type=$type, target=$target, pushSeq=$pushSeq, replayed=$replayed)"
}

/** 都未校准（4.3、4.4 节），测试和灰度时可以覆盖。 */
public data class PushLimits(
    val stashMax: Int = 200,
    val noSeqQueueMax: Int = 500,
    val seenMax: Int = 2000,
    val gapWaitMs: Long = 500,
    val stashFlushMs: Long = 5000,
    val backoffMs: List<Long> = listOf(1000, 2000, 4000, 8000, 16000, 30000),
)

public data class PushCursorState(val cursor: Long, val recentIds: List<String>)

/**
 * 推送游标的持久化（4.9 节）：独立于本地库，退出、被踢、库重建、Schema 升级都不清，只有卸载、清应用数据、换设备才回放。
 * 按 userId 分键，只存游标和最近的 pushId，不存通知内容。在 SDK 线程上同步调用；抛异常时 SDK 记日志（只写异常类型）继续跑。
 * 没有注入时游标只在内存（AC-PUSH-03-34，和 H5 相同）；它和 IMClientOptions.database 互不依赖。
 */
public interface PushCursorStore {
    /** 这个账号没有存过时返回 null。 */
    public fun load(userId: String): PushCursorState?

    /** recentIds 按交付先后排列，最旧的在前。 */
    public fun save(userId: String, cursor: Long, recentIds: List<String>)
}

/** SDK 固定用 100（4.2 节）。 */
private const val SYNC_LIMIT = 100

private fun parsePushFrame(raw: JsonElement?): Push? {
    val o = raw as? JsonObject ?: return null
    fun str(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun num(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.longOrNull
    val pushId = str("pushId") ?: return null
    val type = str("type") ?: return null
    val target = str("target") ?: return null
    val ts = num(o["ts"]) ?: return null
    var seq: Long? = null
    if (o.containsKey("pushSeq")) seq = num(o["pushSeq"])?.takeIf { it > 0 } ?: return null
    return Push(pushId, type, o["data"] ?: JsonNull, ts, target, str("roomId"), seq)
}

/** 一个 pushes 收集者。collect 结束（正常、抛错、取消）时 gone 变 true；delivery 协程据此判断订阅者是否还在。 */
private class PushCollector {
    val inbox = Channel<PushDelivery>(Channel.UNLIMITED)

    @Volatile
    var gone = false
}

private class PushDelivery(val push: Push) {
    val done = CompletableDeferred<Unit>()

    @Volatile
    var cancelled = false
}

private class PushTick(val kind: Int) {
    var timer: Timer? = null
}

private const val TICK_GAP = 0
private const val TICK_FLUSH = 1
private const val TICK_BACKOFF = 2

/** afterSeq 为 null 时是 fromLatest。 */
private class PushInFlight(val afterSeq: Long?)

private sealed interface PushEvent {
    class Register(val c: PushCollector) : PushEvent
    class Unregister(val c: PushCollector) : PushEvent
    data object Activate : PushEvent
    class Auth(val userId: String, val serverTime: Long) : PushEvent
    data object Disconnected : PushEvent
    data object Logout : PushEvent
    class Live(val frame: Push) : PushEvent
    class Ack(val req: PushInFlight, val data: JsonElement?) : PushEvent
    class Fail(val req: PushInFlight, val error: Exception) : PushEvent
    class Fired(val tick: PushTick) : PushEvent
}

/**
 * 单游标补拉状态机，规则和 sdk-ts 的 PushSync 一一对应。不同的是交付要等收集者处理完，而 onFrame 是同步函数不能挂起（4.8 节）：
 * 所有事件（帧、ack、定时器、注册）进同一条串行队列，由一条单独的协程按序处理，处理一个事件时可以在交付上挂起，SDK 线程不被占住。
 * 一页交付完才前进游标、取下一页。收集者慢的时候帧在队列里等，队列有界：带 pushSeq 的溢出后丢弃、之后补拉一轮（都能从收件箱补回）；
 * 不带 pushSeq 的溢出丢最旧的（本来就是至多一次，C4）。
 *
 * 除了 flow 里的 Register / Unregister 投递和 pushCursor，全部方法都只在 SDK 线程上调用。
 */
internal class PushSync(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val log: Logger,
    private val pushStart: PushStart,
    private val store: PushCursorStore?,
    private val limits: PushLimits,
    private val request: suspend (JsonObject) -> JsonElement?,
    private val onSynced: (Long) -> Unit,
) {
    private val queue = ArrayDeque<PushEvent>()
    private var draining = false
    private var queuedSeq = 0
    private var queuedNoSeq = 0
    private var resync = false // 队列溢出丢过带 pushSeq 的帧：空闲时补拉一轮

    private val subs = LinkedHashSet<PushCollector>()
    private var pendingActivation = false // 已注册、还没到调度点
    private var activated = false // 到过调度点且还有订阅者
    private var gen = 0 // 回调内退订、退出登录时加一：正在进行的交付循环据此立刻停止
    private val outstanding = ArrayList<PushDelivery>()

    private var user: String? = null
    private var t0 = 0L

    @Volatile
    private var cursorValue: Long? = null
    private val seen = LinkedHashSet<String>()
    private val noSeqQueue = ArrayDeque<Push>()
    private val stash = java.util.TreeMap<Long, Push>()
    private var dirty = false

    private var connected = false
    private var disabled = false // 这次连接收到过 4001（C9）
    private var inflight: PushInFlight? = null
    private var failed = false
    private var attempt = 0
    private var roundMax = 0L // 本轮已交付的最大 pushSeq（4.8 节）
    private var gapTick: PushTick? = null
    private var flushTick: PushTick? = null
    private var backoffTick: PushTick? = null

    val cursor: Long? get() = cursorValue

    /** 只算这个流的收集者（4.8 节）。collect 的循环运行在收集者自己的协程里，异常也留在那里（F-PUSH-08 规则 2）。 */
    val flow: Flow<Push> = flow {
        val c = PushCollector()
        scope.launch { enqueue(PushEvent.Register(c)) }
        try {
            for (d in c.inbox) {
                if (d.cancelled) {
                    d.done.complete(Unit)
                    continue
                }
                var ok = false
                try {
                    emit(d.push)
                    ok = currentCoroutineContext().isActive // 回调里取消了自己：这一条算交付，后面的不能再给
                } finally {
                    if (!ok) c.gone = true
                    d.done.complete(Unit)
                }
            }
        } finally {
            c.gone = true
            c.inbox.close()
            while (true) (c.inbox.tryReceive().getOrNull() ?: break).done.complete(Unit)
            scope.launch { enqueue(PushEvent.Unregister(c)) }
        }
    }

    // ---- SDK 线程的入口 ----

    fun onAuth(userId: String, serverTime: Long) = enqueue(PushEvent.Auth(userId, serverTime))

    fun onDisconnected() = enqueue(PushEvent.Disconnected)

    /** 退出登录、换号：立刻打断交付，队列里属于旧账号的事件作废，不把 u1 的帧交给 u2（4.8 节）。 */
    fun logout() {
        gen++
        for (d in outstanding) {
            d.cancelled = true
            d.done.complete(Unit)
        }
        queue.removeAll { it !is PushEvent.Register && it !is PushEvent.Unregister && it !is PushEvent.Activate }
        queuedSeq = 0
        queuedNoSeq = 0
        resync = false
        enqueue(PushEvent.Logout)
    }

    fun onLive(raw: JsonElement?) {
        val f = parsePushFrame(raw)
        if (f == null) {
            log.warn("im: invalid push frame")
            return
        }
        if (f.pushSeq != null) {
            if (queuedSeq >= limits.stashMax) {
                resync = true // 收集者跟不上：丢掉，都能从收件箱补回
                return
            }
            queuedSeq++
        } else if (queuedNoSeq >= limits.noSeqQueueMax) {
            val it = queue.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e is PushEvent.Live && e.frame.pushSeq == null) {
                    it.remove()
                    break
                }
            }
        } else {
            queuedNoSeq++
        }
        enqueue(PushEvent.Live(f))
    }

    // ---- 串行队列 ----

    private fun enqueue(e: PushEvent) {
        queue.addLast(e)
        if (draining) return
        draining = true
        // 空闲时当场开始，直到第一次在交付上挂起：帧不会无谓地堆在队列里。
        scope.launch(start = CoroutineStart.UNDISPATCHED) { drain() }
    }

    private suspend fun drain() {
        try {
            while (true) {
                val e = queue.removeFirstOrNull() ?: break
                if (e is PushEvent.Live) {
                    if (e.frame.pushSeq != null) queuedSeq-- else queuedNoSeq--
                }
                try {
                    handle(e)
                } catch (c: CancellationException) {
                    throw c
                } catch (x: Exception) {
                    log.error("im: push event failed: ${safeDescribe(x)}")
                }
                persist()
                if (resync && queue.isEmpty()) resyncIfIdle()
            }
        } finally {
            draining = false
        }
    }

    private fun resyncIfIdle() {
        if (inflight != null) return // 结束时再判断：drain 里下一个事件是它的应答
        resync = false
        if (activated && backoffTick == null) startRound()
    }

    private suspend fun handle(e: PushEvent) {
        reapGone()
        if (pendingActivation && e !is PushEvent.Register && e !is PushEvent.Unregister) activate() // 调度点先于任何网络事件和定时器（4.8 节）
        when (e) {
            is PushEvent.Register -> {
                if (!e.c.gone) subs.add(e.c)
                if (!activated && !pendingActivation) {
                    pendingActivation = true
                    scope.launch { enqueue(PushEvent.Activate) } // 下一个调度点：同一调度点注册的都能收到积压
                }
            }
            is PushEvent.Unregister -> if (subs.remove(e.c) && subs.isEmpty()) stop()
            PushEvent.Activate -> Unit // 已经在上面处理
            is PushEvent.Auth -> handleAuth(e.userId, e.serverTime)
            PushEvent.Disconnected -> handleDisconnected()
            PushEvent.Logout -> clearAccount()
            is PushEvent.Live -> handleLive(e.frame)
            is PushEvent.Ack -> if (inflight === e.req) handleAck(e.req, e.data)
            is PushEvent.Fail -> if (inflight === e.req) handleFail(e.error)
            is PushEvent.Fired -> handleFired(e.tick)
        }
    }

    /** auth 成功（含重连）。按 userId 兜一次：不把 u1 的状态留给 u2。 */
    private suspend fun handleAuth(userId: String, serverTime: Long) {
        if (user != userId) {
            clearAccount()
            user = userId
            t0 = serverTime
            val saved = try {
                store?.load(userId)
            } catch (x: Exception) {
                if (x is CancellationException) throw x
                log.warn("im: push cursor load failed: ${safeDescribe(x)}")
                null
            }
            cursorValue = saved?.cursor ?: if (pushStart == PushStart.LATEST) null else 0L
            saved?.recentIds?.takeLast(limits.seenMax)?.let { seen.addAll(it) }
        }
        connected = true
        disabled = false
        if (activated) startRound()
    }

    /** 连接断开：进行中的请求算失败，定时器和退避清零，暂存保留到重连后合并（C9）。 */
    private fun handleDisconnected() {
        connected = false
        inflight = null
        failed = false
        attempt = 0
        cancelTimers()
    }

    private fun clearAccount() {
        persist()
        handleDisconnected()
        user = null
        t0 = 0
        cursorValue = null
        seen.clear()
        noSeqQueue.clear()
        stash.clear()
        roundMax = 0
        disabled = false
        dirty = false
    }

    private suspend fun handleLive(f: Push) {
        if (user == null) return
        val seq = f.pushSeq
        if (seq == null) {
            if (activated) {
                deliver(f)
            } else {
                noSeqQueue.addLast(f)
                if (noSeqQueue.size > limits.noSeqQueueMax) noSeqQueue.removeFirst()
            }
            return
        }
        if (!activated) return // C12
        val cur = cursorValue
        if (cur == null) {
            addToStash(f) // C10：fromLatest 应答前
        } else if (seq <= cur) {
            deliver(f) // C3
        } else if (seq == cur + 1) {
            if (!deliver(f)) return
            setCursor(seq) // C1
            drainStash(true)
        } else {
            addToStash(f) // C2
            armGap()
        }
    }

    private suspend fun activate() {
        pendingActivation = false
        reapGone()
        if (subs.isEmpty() || activated) return
        activated = true
        while (true) {
            val f = noSeqQueue.removeFirstOrNull() ?: break
            if (!deliver(f)) return
        }
        if (connected && inflight == null) startRound()
    }

    private fun reapGone() {
        if (subs.removeAll { it.gone } && subs.isEmpty()) stop()
    }

    /** 订阅者全部退出（C12）。进行中的请求不取消：迟到的 ack 丢弃，又有订阅者时按正常处理（不发第二个）。 */
    private fun stop() {
        gen++
        activated = false
        stash.clear()
        failed = false
        attempt = 0
        roundMax = 0
        cancelTimers()
    }

    /**
     * 交付一条（已见过的跳过，算成功）：交给每个收集者，等它们处理完。
     * 返回 false 表示这期间最后一个订阅者退出了或者退出登录了，调用方不能再动游标、不能继续。
     */
    private suspend fun deliver(f: Push): Boolean {
        if (f.pushId in seen) return true
        seen.add(f.pushId)
        dirty = true
        if (seen.size > limits.seenMax) seen.remove(seen.first())
        val seq = f.pushSeq
        if (seq != null && seq > roundMax) roundMax = seq
        val myGen = gen
        val item = f.copy(replayed = f.ts < t0)
        val waits = ArrayList<PushDelivery>(subs.size)
        for (c in subs) {
            val d = PushDelivery(item)
            if (c.inbox.trySend(d).isSuccess) waits.add(d) // 失败：这个收集者刚好结束
        }
        outstanding.addAll(waits)
        for (d in waits) d.done.await()
        outstanding.clear()
        reapGone()
        return myGen == gen
    }

    private fun addToStash(f: Push) {
        val seq = f.pushSeq!!
        if (!stash.containsKey(seq) && stash.size >= limits.stashMax) stash.clear() // 清掉的都能补回
        stash[seq] = f
        if (failed) armFlush()
    }

    /** C11：按序取出能接上的暂存帧。 */
    private suspend fun drainStash(allowTimer: Boolean): Boolean {
        for (seq in stash.keys.toList()) {
            val cur = cursorValue
            if (cur == null || seq > cur + 1) break
            val f = stash.remove(seq)!!
            if (!deliver(f)) return false
            if (seq == cur + 1) setCursor(seq)
        }
        if (stash.isEmpty()) {
            cancelGap()
            cancelFlush()
        } else if (allowTimer) {
            armGap()
        }
        return true
    }

    private fun schedule(kind: Int, ms: Long): PushTick {
        val tick = PushTick(kind)
        tick.timer = clock.schedule(ms) { enqueue(PushEvent.Fired(tick)) }
        return tick
    }

    private fun armGap() {
        if (gapTick != null || stash.isEmpty()) return
        if (!connected || disabled || inflight != null || backoffTick != null) return
        gapTick = schedule(TICK_GAP, limits.gapWaitMs)
    }

    private fun armFlush() {
        if (flushTick != null || stash.isEmpty()) return
        flushTick = schedule(TICK_FLUSH, limits.stashFlushMs)
    }

    private fun cancelGap() {
        gapTick?.timer?.cancel()
        gapTick = null
    }

    private fun cancelFlush() {
        flushTick?.timer?.cancel()
        flushTick = null
    }

    private fun cancelTimers() {
        cancelGap()
        cancelFlush()
        backoffTick?.timer?.cancel()
        backoffTick = null
    }

    /** 定时器到期后排队等处理的这段时间里可能已经被取消：只认当前这一个。 */
    private suspend fun handleFired(tick: PushTick) {
        when (tick.kind) {
            TICK_GAP -> if (gapTick === tick) {
                gapTick = null
                startRound()
            }
            TICK_FLUSH -> if (flushTick === tick) {
                flushTick = null
                val frames = stash.values.toList()
                stash.clear()
                for (f in frames) if (!deliver(f)) return // 游标不前进，之后的补拉靠 pushId 去重
            }
            TICK_BACKOFF -> if (backoffTick === tick) {
                backoffTick = null
                send()
            }
        }
    }

    /** 一轮补拉的开始：翻页和退避重试不算新一轮（4.8 节）。 */
    private fun startRound() {
        if (!connected || disabled || inflight != null) return
        roundMax = 0
        send()
    }

    private fun send() {
        val cur = cursorValue
        val req = PushInFlight(cur)
        inflight = req
        cancelGap()
        val data = if (cur == null) {
            buildJsonObject { put("fromLatest", true) }
        } else {
            buildJsonObject {
                put("afterSeq", cur)
                put("limit", SYNC_LIMIT)
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val ack = request(data)
                enqueue(PushEvent.Ack(req, ack))
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                enqueue(PushEvent.Fail(req, x))
            }
        }
    }

    private fun handleFail(x: Exception) {
        inflight = null
        if (x is IMError && x.code == ErrorCode.UNKNOWN_CMD) {
            disabled = true // 服务端未开启：静默，这次连接内不再发（C9）
            cancelGap()
            return
        }
        log.warn("im: pushSync failed: ${safeDescribe(x)}")
        if (!activated) return
        failed = true
        val delay = limits.backoffMs[minOf(attempt, limits.backoffMs.size - 1)]
        attempt++
        backoffTick = schedule(TICK_BACKOFF, delay)
        armFlush()
    }

    private suspend fun handleAck(req: PushInFlight, data: JsonElement?) {
        inflight = null
        if (!activated) return // C12：迟到的 ack 丢弃，不交付、不动游标
        val o = data as? JsonObject
        val pushes = o?.get("pushes") as? JsonArray
        val hasMore = (o?.get("hasMore") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        val latestSeq = (o?.get("latestSeq") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        if (pushes == null || hasMore == null || latestSeq == null) {
            handleFail(ProtocolDecodeError("pushSync"))
            return
        }
        val after = req.afterSeq
        if (after == null) {
            succeeded()
            setCursor(latestSeq) // C10：忽略 pushes 和 hasMore
            if (drainStash(true)) finishRound(latestSeq)
            return
        }
        val page = ArrayList<Push>(pushes.size)
        var pageMax = 0L
        for (raw in pushes) {
            val f = parsePushFrame(raw)
            val seq = f?.pushSeq
            if (seq == null) {
                log.warn("im: invalid push in pushSync ack")
                continue
            }
            page.add(f)
            if (seq > pageMax) pageMax = seq
        }
        if (hasMore) {
            if (pageMax <= after) {
                handleFail(ProtocolDecodeError("pushSync")) // C6：违反服务端保证，避免原地死循环
                return
            }
            succeeded()
            if (!deliverPage(page, pageMax)) return
            setCursor(maxOf(cursorValue ?: 0, pageMax))
            if (drainStash(false)) send() // C6：先接上暂存帧，再取下一页
            return
        }
        succeeded()
        if (latestSeq < after) {
            // C8：服务端从备份恢复。不走 C11，本轮重新开始，靠 pushId 去重。
            setCursor(0)
            stash.clear()
            cancelTimers()
            roundMax = 0
            send()
            return
        }
        if (!deliverPage(page, latestSeq)) return
        setCursor(maxOf(cursorValue ?: 0, latestSeq)) // C7：结果为空也前进，尾部过期不会让游标停住
        if (drainStash(true)) finishRound(latestSeq)
    }

    private fun succeeded() {
        failed = false
        attempt = 0
        cancelFlush()
    }

    /** C6、C7：本页和暂存区里不大于 limit 的帧合并，按序交付，见过的跳过。 */
    private suspend fun deliverPage(page: List<Push>, limit: Long): Boolean {
        val merged = ArrayList(page)
        val head = stash.headMap(limit, true)
        merged.addAll(head.values)
        head.clear()
        merged.sortBy { it.pushSeq }
        for (f in merged) if (!deliver(f)) return false
        return true
    }

    private fun finishRound(latestSeq: Long) = onSynced(maxOf(latestSeq, roundMax))

    private fun setCursor(v: Long) {
        cursorValue = v
        dirty = true
    }

    /** 一个事件处理完落盘一次（4.9 节）：在交付之后，进程中途退出时最多重复一批。fromLatest 应答前没有游标，不写。 */
    private fun persist() {
        if (!dirty) return
        val s = store
        val u = user
        val c = cursorValue
        if (s == null || u == null || c == null) return
        dirty = false
        try {
            s.save(u, c, seen.toList())
        } catch (x: Exception) {
            if (x is CancellationException) throw x
            log.warn("im: push cursor save failed: ${safeDescribe(x)}")
        }
    }
}
