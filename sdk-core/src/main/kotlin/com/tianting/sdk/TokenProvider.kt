package com.tianting.sdk

/**
 * 连接收到的踢线。reason 为 null 表示没有 kicked 帧，或者 reason 不认识（按关闭码处理）。
 * sid：这条连接用的 token 的 sid。连接和账号不在同一条线程上，踢线可能比"token 换了"的通知晚一步到账号这边，要靠它认出这是旧 sid 的踢线。
 */
public data class KickInfo(val reason: String?, val code: Int, val recoverBefore: Long? = null, val sid: String? = null)

/** PASS：按踢线处理（回登录页）。IGNORE：这是自己换 sid 造成的，用新 token 重连。 */
public enum class KickDecision { PASS, IGNORE }

public enum class SessionEndAction { LOGIN, BAN_NOTICE, LOGOUT }

/**
 * 会话结束，App 要回登录页。
 * reason：结束的直接原因（4019 的 reason，或踢线原因）。fromKick：来自连接上的踢线，IMClient 的 Fatal 已经带了同样的信息。
 */
public data class SessionEnd(
    val action: SessionEndAction,
    val dialog: SessionDialog? = null,
    val recoverBefore: Long? = null,
    val reason: String? = null,
    val code: Int? = null,
    val fromKick: Boolean = false,
)

/**
 * IMClient.connect(tokenProvider) 用的 token 来源（PRD-chat 第 13.1 节，F-ACC-03 规则 5、RS5-10：HTTP 和 WS 共用）。
 * AccountClient.tokenProvider 实现了它。保留的 connect(token) 没有这些行为（演示模式）。
 * 回调可能在别的线程，IMClient 自己切回 SDK 线程。
 */
public interface TokenProvider {
    /** 拨号前调用。已过期时先刷新再返回；会话结束时抛 SessionEndedError。 */
    public suspend fun accessToken(): String

    /** 连接收到 kicked/tokenExpired 或关闭码 4011（规则 7）。返回后再 accessToken() 就是可用的；会话结束时抛 SessionEndedError。 */
    public suspend fun tokenRejected(token: String)

    /** 连接被踢（关闭码 4010、4012）。可以先缓存再决定（F-ACC-04 规则 3）。 */
    public suspend fun kickReceived(info: KickInfo): KickDecision

    /** 连接状态：已连接时才用定时器刷新（规则 5）。 */
    public fun connectionChanged(connected: Boolean)

    /** 回到前台（规则 5）。 */
    public fun foreground()

    /** token 换了：已连接时用 1000 断开，立即用新 token 重连（规则 5）。返回取消订阅的函数。 */
    public fun onTokenChanged(listener: () -> Unit): () -> Unit

    public fun onEnded(listener: (SessionEnd) -> Unit): () -> Unit

    /** 账号资料改了（F-ACC-06）：本端立即显示新昵称。 */
    public fun onProfileChanged(listener: (userId: String, nickname: String) -> Unit): () -> Unit

    /** 服务端说最新一代恢复码没有确认（F-ACC-05 规则 1）。 */
    public fun recoveryCodeUnacked()
}
