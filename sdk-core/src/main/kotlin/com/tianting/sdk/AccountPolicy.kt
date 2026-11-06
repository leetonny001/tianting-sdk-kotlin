// 账号会话的判断（PRD-chat F-ACC-03 规则 5、6、6a、7）：纯函数，不碰网络、存储和定时器。
// 和 server/e2e/accountmodel/model.go、sdk-ts 的 src/account-policy.ts 逐条对应，协议用例 protocol/vectors/account-session.json 约束它。
// 语义说明见 tianting-server 的 docs/sdk/README.md 第 6 节。
package com.tianting.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** 不足这么久就刷新（IM_ACCESS_TTL 的 1/4，规则 5）。剩余恰好 6 小时不刷新。 */
public const val REFRESH_BELOW_MS: Long = 6 * 3_600_000L

/** 连续收到这么多次 superseded 就回登录页（规则 6）。 */
public const val MAX_SUPERSEDED: Int = 3

/** 刷新、改密等请求的超时（规则 5，RS5-15）。 */
public const val ACCOUNT_REQUEST_TIMEOUT_MS: Long = 30_000L

/** 回登录页时的提示（规则 6、6a）。wire 和 model.go 相同。 */
public enum class SessionDialog(public val wire: String) {
    SESSION_EXPIRED("sessionExpired"), // "登录已失效，请重新登录"
    CREDENTIAL_ANOMALY("credentialAnomaly"), // "检测到登录凭证异常…"
    RECOVER_BEFORE("recoverBefore"), // "如果不是你本人操作，请在 {日期} 前用恢复码找回"
    SUPERSEDED_EXCEEDED("supersededExceeded"), // superseded 重试用完
    ACCOUNT_OFFLINE("accountOffline"), // kicked 没有日期时按关闭码 4012 的文案
    RECOVERY_CODE_STALE("recoveryCodeStale"), // "恢复码已更新，请重新登录查看"
}

/** 一步判断的结果。动作的名字和 model.go 相同。只有动作和代号，没有凭证。 */
public sealed interface SessionOutcome {
    public data object None : SessionOutcome
    public data object UseLocal : SessionOutcome
    public data object Backoff : SessionOutcome
    public data object KeepToken : SessionOutcome
    public data object BanNotice : SessionOutcome
    public data class Refresh(val attempt: Long) : SessionOutcome
    public data class RetryAfter(val retryAfterSeconds: Int? = null) : SessionOutcome
    public data class Login(val dialog: SessionDialog? = null, val recoverBefore: Long? = null) : SessionOutcome
}

public enum class RefreshSource { START, FOREGROUND, TIMER, KICKED }

/** 刷新失败。code 为 0 表示没有拿到服务端的错误码：网络错误、超时，或者响应不是约定的 JSON。 */
public data class RefreshFailure(
    val code: Int,
    val reason: String? = null,
    val retryAfterSeconds: Int? = null,
    val recoverBefore: Long? = null,
)

/** token 的身份。sid 不同就是不同的链，代号不能互相比较（改密、退出其他设备后 gen 从头开始）。 */
public data class TokenLineage(val sid: String, val gen: Long)

/** 被拒绝的 token 的身份，字段可以缺（向量只给 gen）。 */
public data class RejectedToken(val sid: String? = null, val gen: Long? = null)

internal fun TokenLineage.asRejected(): RejectedToken = RejectedToken(sid, gen)

@OptIn(ExperimentalEncodingApi::class)
private val BASE64URL = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

/** JWT 的 payload，只用来比较和看到期，不验签。解析不了返回 null。不用 java.util.Base64：minSdk 24 没有。 */
@OptIn(ExperimentalEncodingApi::class)
private fun jwtClaims(token: String): JsonObject? = try {
    Json.parseToJsonElement(BASE64URL.decode(token.split('.')[1]).decodeToString()) as? JsonObject
} catch (_: IllegalArgumentException) {
    null
} catch (_: IndexOutOfBoundsException) {
    null
}

/** 从 JWT 取 sid 和 gen。解析不了按 {sid:"", gen:0}：最不新，走刷新这条安全的路。 */
public fun lineageOf(token: String): TokenLineage {
    val c = jwtClaims(token) ?: return TokenLineage("", 0)
    val sid = (c["sid"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
    val gen = (c["gen"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: 0
    return TokenLineage(sid, gen)
}

/** JWT 的 exp（毫秒）。老版本留下的 Session 没有到期时间时用它（R-CF-11）。 */
internal fun jwtExpiresAt(token: String): Long? =
    (jwtClaims(token)?.get("exp") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.let { it * 1000 }

/** 规则 7：本地 token 比被拒绝的新吗。换过 sid 一定更新：SDK 只会从旧 sid 换到新 sid。 */
public fun isLocalNewer(local: TokenLineage, rejected: RejectedToken): Boolean {
    if (rejected.sid != null && rejected.sid != local.sid) return true
    return local.gen > (rejected.gen ?: 0)
}

/** 规则 5 和 RS5-11：现在该主动刷新吗。 */
public fun shouldRefresh(accessExpiresAt: Long, absoluteExpiresAt: Long?, now: Long): Boolean {
    if (accessExpiresAt == absoluteExpiresAt) return false
    return accessExpiresAt - now < REFRESH_BELOW_MS
}

/**
 * 规则 6、6a：刷新失败怎么办。superseded 是这次之前已经连续收到的次数。
 * 和 model.go 的 RefreshFailed 一致：code 0、4290、5xx 留在当前页面退避；没有列出的码回登录页。
 */
public fun classifyRefreshFailure(f: RefreshFailure, superseded: Int): SessionOutcome {
    if (f.code == 0 || f.code == 4290 || f.code >= 5000) return SessionOutcome.Backoff
    if (f.code == 4034) return SessionOutcome.BanNotice
    if (f.code == 4019) {
        if (f.reason == "superseded") {
            if (superseded + 1 >= MAX_SUPERSEDED) return SessionOutcome.Login(SessionDialog.SUPERSEDED_EXCEEDED)
            return SessionOutcome.RetryAfter(f.retryAfterSeconds?.takeIf { it > 0 })
        }
        if (f.reason == "replayDetected") {
            return SessionOutcome.Login(SessionDialog.CREDENTIAL_ANOMALY, f.recoverBefore?.takeIf { it > 0 })
        }
        return loginWithDate(SessionDialog.SESSION_EXPIRED, f.recoverBefore)
    }
    return SessionOutcome.Login(SessionDialog.SESSION_EXPIRED)
}

/** kicked 时回登录页的提示：有日期统一提示日期（规则 6a）。 */
public fun kickOutcome(reason: String?, recoverBefore: Long?): SessionOutcome {
    if (reason == "tokenInvalid" || reason == "tokenExpired") return SessionOutcome.Login(SessionDialog.SESSION_EXPIRED)
    return loginWithDate(SessionDialog.ACCOUNT_OFFLINE, recoverBefore)
}

private fun loginWithDate(dialog: SessionDialog, recoverBefore: Long?): SessionOutcome =
    if (recoverBefore != null && recoverBefore > 0) {
        SessionOutcome.Login(SessionDialog.RECOVER_BEFORE, recoverBefore)
    } else {
        SessionOutcome.Login(dialog)
    }

/** 这个踢线说明设备会话已经不能用了（关闭码 4010、4012）：本地 token 要清除。 */
public fun kickEndsSession(reason: String?, code: Int): Boolean {
    val c = if (reason == null) code else KICK_CLOSE_CODE[reason]
    return c == 4010 || c == 4012
}

/** 改密、退出其他设备、注销进行中要缓存的踢线（F-ACC-04 规则 3）。封禁等其他原因不等。不认识的原因看关闭码。 */
public fun kickIsHoldable(reason: String?, code: Int): Boolean {
    if (reason == "passwordChanged" || reason == "sessionRevoked" || reason == "accountDeleted") return true
    return reason == null && code == 4012
}

/** PRD 规定的提示文字（规则 6、6a，F-ACC-05 规则 1）。三端显示同一句，放在 SDK。formatDate 由 App 决定时区和格式。 */
public fun sessionDialogText(
    dialog: SessionDialog?,
    recoverBefore: Long? = null,
    formatDate: (Long) -> String = { SimpleDateFormat("yyyy/M/d", Locale.CHINA).format(Date(it)) },
): String? = when (dialog) {
    SessionDialog.CREDENTIAL_ANOMALY -> "检测到登录凭证异常，这台设备已下线。如果不是你本人操作，请尽快改密码"
    SessionDialog.RECOVER_BEFORE -> "你的账号凭证有变化。如果不是你本人操作，请在 ${formatDate(recoverBefore ?: 0)} 前用恢复码找回"
    SessionDialog.SESSION_EXPIRED, SessionDialog.SUPERSEDED_EXCEEDED -> "登录已失效，请重新登录"
    SessionDialog.ACCOUNT_OFFLINE -> "账号已被下线"
    SessionDialog.RECOVERY_CODE_STALE -> "恢复码已更新，请重新登录查看"
    null -> null
}
