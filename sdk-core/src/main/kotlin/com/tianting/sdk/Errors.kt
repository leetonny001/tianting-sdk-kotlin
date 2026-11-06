package com.tianting.sdk

import kotlinx.serialization.json.JsonObject

/** 错误码的分类，界面据此决定文案（PRD F-SYS-01、C-09）。 */
public enum class ErrorCategory { BAD_REQUEST, FORBIDDEN, NOT_FOUND, RATE_LIMITED, SERVER }

/** name 是 errors.json 里的名称；不认识的错误码为 "UNKNOWN"。 */
public data class ErrorDescription(val code: Int, val name: String, val category: ErrorCategory, val retryable: Boolean)

/**
 * 描述一个错误码。不认识的错误码按前三位区段兜底（C-09）：
 * 403x 无权限、404x 不存在、429x 太频繁、其他 4xxx 请求出错（都不重试）；5xxx 服务端错误（可以重试）。
 */
public fun describeError(code: Int): ErrorDescription {
    val known = ERROR_INFO[code]
    return ErrorDescription(code, known?.name ?: "UNKNOWN", categorize(code), known?.retryable ?: (code >= 5000))
}

private fun categorize(code: Int): ErrorCategory = when (code / 10) {
    403 -> ErrorCategory.FORBIDDEN
    404 -> ErrorCategory.NOT_FOUND
    429 -> ErrorCategory.RATE_LIMITED
    else -> if (code >= 5000) ErrorCategory.SERVER else ErrorCategory.BAD_REQUEST
}

/** 服务端返回的错误（ack.error）。userId：建群失败时是哪个人（error.userId，兼容 details.userId）。details 原样透传。 */
public open class IMError(
    public val code: Int,
    message: String,
    public val userId: String? = null,
    public val details: JsonObject? = null,
) : Exception("$code $message") {
    public val description: ErrorDescription get() = describeError(code)
}

/** 请求超时：ack 可能只是晚到了。 */
public class TimeoutError(cmd: String) : Exception("$cmd timed out")

/** 请求发出后、收到 ack 之前连接断开了。 */
public class ConnectionClosedError : Exception("connection closed")

/** 当前没有已鉴权的连接。 */
public class NotConnectedError : Exception("not connected")

/** ack 的 data 无法按协议解析（异常信息里不带原始内容，避免原文进日志）。 */
public class ProtocolDecodeError(cmd: String) : Exception("$cmd: malformed ack")

/**
 * "命令 + 错误码"文案（PRD-social 9.4 节、ERRORS.md 第 2 节；用例 protocol/vectors/social.json 的 copy）。
 * 这里只列与命令相关的码；没有列出的返回 null，界面用该码的默认文案。
 * strangerMsgLimit 填进 4137 的 N（取最近一次同步 / 事件中的值）；name 填进建群 / 邀请失败的 {name}。
 */
public fun messageFor(cmd: String, code: Int, strangerMsgLimit: Int = 0, name: String = ""): String? {
    val t = COPY["$cmd:$code"] ?: COPY["*:$code"] ?: return null
    return t.replace("{N}", strangerMsgLimit.toString()).replace("{name}", name)
}

private const val DAILY = "今天的次数已用完，明天再试"
private val COPY: Map<String, String> = mapOf(
    "send:4290" to "发送太频繁，请稍后再试",
    "*:4290" to "操作太频繁，请稍后再试",
    "send:4035" to "对方开启了好友验证，你还不是他的好友",
    "send:4036" to "消息已发出，但被对方拒收了",
    "send:4137" to "对方回复之前，最多只能发送 {N} 条消息",
    "send:4292" to "今天发起新聊天的次数已用完，请稍后再试",
    "friendApply:4132" to "你的好友数量已达上限",
    "friendRespond:4132" to "你的好友数量已达上限",
    "friendRespond:4133" to "对方好友数量已达上限",
    "friendApply:4133" to "对方好友数量已达上限",
    "friendRespond:4045" to "该申请已过期",
    "block:4134" to "黑名单已满",
    "friendApply:4292" to DAILY,
    "userSearch:4292" to DAILY,
    "block:4292" to DAILY,
    "unblock:4292" to DAILY,
    "groupCreate:4035" to "{name} 不是你的好友，无法邀请",
    "groupInvite:4035" to "{name} 不是你的好友，无法邀请",
    "groupCreate:4135" to "{name} 加入的群聊已达上限",
    "groupInvite:4135" to "{name} 加入的群聊已达上限",
    "groupCreate:4030" to "当前不允许创建群聊",
    "groupCreate:4292" to DAILY,
    "groupTransfer:4046" to "对方已不在群里",
    "groupSetAdmin:4046" to "对方已不在群里",
    "groupSetAdmin:4136" to "管理员最多 10 人",
)
