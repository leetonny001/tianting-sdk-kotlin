package com.tianting.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 日志接口。SDK 不把 token、消息正文、帧内容写进日志（C-52）。 */
public interface Logger {
    public fun debug(message: String)
    public fun warn(message: String)
    public fun error(message: String)
}

public object SilentLogger : Logger {
    override fun debug(message: String): Unit = Unit
    override fun warn(message: String): Unit = Unit
    override fun error(message: String): Unit = Unit
}

/** 日志里只写错误码或异常类型：异常信息可能带帧内容（序列化异常会带 JSON 片段）。 */
internal fun safeDescribe(e: Throwable): String = if (e is IMError) "IMError ${e.code}" else e.javaClass.simpleName

public fun interface Timer {
    public fun cancel()
}

/** 时钟接口：SDK 里所有时间相关的逻辑都走它，测试时注入 FakeClock。回调都在 SDK 的单线程上执行。 */
public interface Clock {
    public fun now(): Long
    public fun schedule(delayMs: Long, fn: () -> Unit): Timer
}

/** 生产环境的时钟：协程 delay，回调在 scope 的调度器（SDK 的单线程）上执行。 */
internal class CoroutineClock(private val scope: CoroutineScope) : Clock {
    override fun now(): Long = System.currentTimeMillis()
    override fun schedule(delayMs: Long, fn: () -> Unit): Timer {
        val job = scope.launch {
            delay(delayMs)
            fn()
        }
        return Timer { job.cancel() }
    }
}
