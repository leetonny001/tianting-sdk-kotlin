package com.tianting.sdk

/**
 * 一个 HTTP 请求。Bearer 只放在 headers 里，不进 URL（PRD-chat 第 4.2 节）。
 * toString 不带 headers、body 和查询串：这个对象不小心进了日志也不会泄露凭证。
 */
public class HttpRequest(
    public val method: String,
    public val url: String,
    public val headers: Map<String, String> = emptyMap(),
    public val body: String? = null,
) {
    override fun toString(): String = "HttpRequest($method ${url.substringBefore('?')})"
}

public class HttpResponse(public val status: Int, public val text: String) {
    override fun toString(): String = "HttpResponse($status)"
}

/** 只回调一次。可以在任意线程。失败不带原因：异常信息里可能有 URL，不能往上传（第 13.1 节）。 */
public interface HttpCallback {
    /** 收到任何状态码都走这里。 */
    public fun onResponse(response: HttpResponse)

    /** 连不上、读失败、被 cancel。 */
    public fun onFailure()
}

public fun interface HttpCall {
    /** 调用方放弃这个请求。已经发出去的响应仍然可能回调，调用方自己决定丢弃。 */
    public fun cancel()
}

/**
 * HTTP 传输层（PRD-chat 第 4.5 节）：生产环境用 OkHttpHttpTransport，测试用 FakeHttp。
 * 和 WS 的 Transport 一样是回调式。超时由调用方用 Clock 控制，这里不做定时，所以假时钟能推进它。
 */
public interface HttpTransport {
    public fun request(req: HttpRequest, callback: HttpCallback): HttpCall
}
