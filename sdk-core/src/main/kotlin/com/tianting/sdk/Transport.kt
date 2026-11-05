package com.tianting.sdk

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 传输层接口：生产环境用 OkHttp WebSocket，测试用 FakeTransport。回调可以在任意线程，Connection 负责切回 SDK 线程。 */
public interface Transport {
    public fun open(url: String, handlers: TransportHandlers): TransportConnection
}

public interface TransportHandlers {
    public fun onOpen()
    public fun onMessage(text: String)

    /** 连接结束：code 为关闭码；没有收到关闭帧（断网等）时为 1006。 */
    public fun onClose(code: Int, reason: String)
}

public interface TransportConnection {
    public fun send(text: String)
    public fun close(code: Int, reason: String)
}

/**
 * OkHttp WebSocket。OkHttp 不限制下行帧大小，满足 C-07（500 个会话的 convs 约 125KB）。
 * 心跳在协议层（ping / pong），这里关掉 OkHttp 自己的 ping；readTimeout 必须是 0。
 * 不打开 HttpLoggingInterceptor：URL 虽然不带 token，但帧内容不能进日志。
 */
public class OkHttpTransport(private val client: OkHttpClient = DEFAULT_CLIENT) : Transport {
    override fun open(url: String, handlers: TransportHandlers): TransportConnection {
        val finished = AtomicBoolean(false)
        val opened = AtomicBoolean(false)
        fun finish(code: Int, reason: String) {
            if (finished.compareAndSet(false, true)) handlers.onClose(code, reason)
        }
        val ws = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.set(true)
                    handlers.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) = handlers.onMessage(text)

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    finish(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(code, reason)

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = finish(1006, "")
            },
        )
        return object : TransportConnection {
            override fun send(text: String) {
                if (opened.get() && !finished.get()) ws.send(text)
            }

            override fun close(code: Int, reason: String) {
                finished.set(true)
                if (!ws.close(code, reason)) ws.cancel()
            }
        }
    }

    private companion object {
        val DEFAULT_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(0, TimeUnit.MILLISECONDS).build()
        }
    }
}
