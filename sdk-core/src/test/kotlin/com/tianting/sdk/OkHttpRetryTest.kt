// FE-611 第 4 条，PRD-chat 第 21 节 16：OkHttp 的隐式重试不能把同一个 refresh 多发。用本机的裸 socket 当服务端（不新增依赖），数实际到达的请求数。
// 对照组用默认 OkHttpClient：证明这些场景默认真的会多发，测试不是空转。本地 JVM + OkHttp 4.12 结果。
package com.tianting.sdk

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val JSON_OK = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}"

/** 一个极小的 HTTP/1.1 服务端：handler 决定每个到达的请求怎么回（返回 null 表示读完请求就断开）。 */
private class RawServer(private val handler: (index: Int, requestLine: String) -> String?) {
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val requests: MutableList<String> = Collections.synchronizedList(ArrayList())
    val url = "http://127.0.0.1:${socket.localPort}"
    private val conns: MutableList<Socket> = Collections.synchronizedList(ArrayList())

    init {
        thread(isDaemon = true) {
            try {
                while (true) {
                    val s = socket.accept()
                    conns.add(s)
                    thread(isDaemon = true) { serve(s) }
                }
            } catch (_: IOException) {
                // 关了
            }
        }
    }

    private fun serve(s: Socket) {
        try {
            val input = BufferedInputStream(s.getInputStream())
            while (true) {
                val head = readHead(input) ?: return
                val line = head.substringBefore("\r\n")
                val length = Regex("(?i)content-length: *(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                input.readNBytes(length)
                val index = synchronized(requests) {
                    requests.add(line)
                    requests.size - 1
                }
                val response = handler(index, line)
                if (response == null) {
                    s.close()
                    return
                }
                s.getOutputStream().write(response.toByteArray())
                s.getOutputStream().flush()
            }
        } catch (_: IOException) {
            // 客户端断开
        }
    }

    private fun readHead(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return null
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    fun stop() {
        socket.close()
        conns.forEach { runCatching { it.close() } }
    }
}

class OkHttpRetryTest {
    private val servers = ArrayList<RawServer>()

    private fun server(handler: (Int, String) -> String?) = RawServer(handler).also { servers.add(it) }

    @AfterEach
    fun stop() = servers.forEach { it.stop() }

    /** 发一个请求，等回调。返回状态码；null 表示 onFailure。 */
    private fun send(t: HttpTransport, url: String, method: String = "POST", body: String? = """{"refreshToken":"R1"}"""): Int? {
        val latch = CountDownLatch(1)
        var status: Int? = null
        t.request(
            HttpRequest(method, url, mapOf("Accept" to "application/json"), body),
            object : HttpCallback {
                override fun onResponse(response: HttpResponse) {
                    status = response.status
                    latch.countDown()
                }

                override fun onFailure() = latch.countDown()
            },
        )
        assertTrue(latch.await(20, TimeUnit.SECONDS), "没有回调")
        return status
    }

    /** 对照组：默认 OkHttpClient 发 POST。 */
    private fun defaultPost(client: OkHttpClient, url: String): Int? = try {
        client.newCall(
            Request.Builder().url(url).post("""{"refreshToken":"R1"}""".toRequestBody("application/json".toMediaType())).build(),
        ).execute().use { it.code }
    } catch (_: IOException) {
        null
    }

    /** 第一个请求正常回（连接进池），第二个请求读完就断：复用的连接断在半路。之后的正常回。 */
    private fun staleAfterFirst() = server { i, _ -> if (i == 1) null else JSON_OK }

    @Test
    fun `第 21 节 16 复用的连接断在半路：默认 OkHttp 会把 POST 再发一次（对照组）`() {
        val s = staleAfterFirst()
        val client = OkHttpClient()
        assertEquals(200, defaultPost(client, "${s.url}/a"))
        assertEquals(200, defaultPost(client, "${s.url}${REFRESH}"))
        assertEquals(3, s.requests.size, "默认配置应该隐式重发，否则这个测试没有意义")
    }

    @Test
    fun `第 21 节 16 复用的连接断在半路：SDK 的传输层只发一次，交给上层按退避处理`() {
        val s = staleAfterFirst()
        val t = OkHttpHttpTransport()
        assertEquals(200, send(t, "${s.url}/a"))
        assertEquals(null, send(t, "${s.url}${REFRESH}"))
        assertEquals(2, s.requests.size)
        assertEquals(1, s.requests.count { it.contains(REFRESH) })
    }

    @Test
    fun `第 21 节 16 传进来的 client 也会套上这些设置`() {
        val s = staleAfterFirst()
        val t = OkHttpHttpTransport(OkHttpClient.Builder().retryOnConnectionFailure(true).followRedirects(true).build())
        assertEquals(200, send(t, "${s.url}/a"))
        assertEquals(null, send(t, "${s.url}${REFRESH}"))
        assertEquals(2, s.requests.size)
    }

    private val retry503 = "HTTP/1.1 503 Unavailable\r\nRetry-After: 0\r\nContent-Length: 0\r\n\r\n"

    @Test
    fun `第 21 节 16 503 + Retry-After 0：默认 OkHttp 关掉 retryOnConnectionFailure 也会再发一次（对照组）`() {
        val s = server { i, _ -> if (i == 0) retry503 else JSON_OK }
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        assertEquals(200, defaultPost(client, "${s.url}${REFRESH}"))
        assertEquals(2, s.requests.size, "只关 retryOnConnectionFailure 拦不住，所以请求体要 one-shot")
    }

    @Test
    fun `第 21 节 16 503 + Retry-After 0：SDK 只发一次，状态码交给上层`() {
        val s = server { _, _ -> retry503 }
        assertEquals(503, send(OkHttpHttpTransport(), "${s.url}${REFRESH}"))
        assertEquals(1, s.requests.size)
    }

    @Test
    fun `第 21 节 16 408：SDK 只发一次`() {
        val s = server { _, _ -> "HTTP/1.1 408 Timeout\r\nContent-Length: 0\r\n\r\n" }
        assertEquals(408, send(OkHttpHttpTransport(), "${s.url}${REFRESH}"))
        assertEquals(1, s.requests.size)
    }

    @Test
    fun `第 21 节 16 重定向不跟随：请求体和 Bearer 不带到别的地址`() {
        val other = server { _, _ -> JSON_OK }
        val s = server { _, _ -> "HTTP/1.1 307 Temporary Redirect\r\nLocation: ${other.url}/x\r\nContent-Length: 0\r\n\r\n" }
        assertEquals(307, send(OkHttpHttpTransport(), "${s.url}${REFRESH}"))
        assertEquals(1, s.requests.size)
        assertEquals(0, other.requests.size)
    }

    @Test
    fun `连不上：只回调一次 onFailure；URL 不合法也不抛异常`() {
        val port = ServerSocket(0).use { it.localPort }
        assertEquals(null, send(OkHttpHttpTransport(), "http://127.0.0.1:$port/x"))
        assertEquals(null, send(OkHttpHttpTransport(), "not a url"))
    }

    @Test
    fun `GET 没有请求体，PATCH 有；任何状态码都正常返回`() {
        val s = server { _, line ->
            if (line.startsWith("GET")) JSON_OK else "HTTP/1.1 401 Unauthorized\r\nContent-Length: 2\r\n\r\n{}"
        }
        val t = OkHttpHttpTransport()
        assertEquals(200, send(t, "${s.url}/a", "GET", null))
        assertEquals(401, send(t, "${s.url}/a", "PATCH", "{}"))
    }

    @Test
    fun `cancel 之后回调 onFailure，不崩溃`() {
        val block = CountDownLatch(1)
        val s = server { _, _ ->
            block.await(10, TimeUnit.SECONDS)
            JSON_OK
        }
        val latch = CountDownLatch(1)
        var failed = false
        val call = OkHttpHttpTransport().request(
            HttpRequest("POST", "${s.url}/a", emptyMap(), "{}"),
            object : HttpCallback {
                override fun onResponse(response: HttpResponse) = latch.countDown()
                override fun onFailure() {
                    failed = true
                    latch.countDown()
                }
            },
        )
        while (s.requests.isEmpty()) Thread.sleep(5)
        call.cancel()
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertTrue(failed)
        assertFalse(s.requests.size > 1)
        block.countDown()
    }
}
