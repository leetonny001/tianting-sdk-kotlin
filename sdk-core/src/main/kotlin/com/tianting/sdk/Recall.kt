// 撤回文案、引用摘要和上行 body（PRD-chat F-RCL-02 规则 1a、F-QUO-01、F-QUO-02 规则 2、3、F-MEN-01）。纯函数，页面和 IMClient 共用。
// 语义同 sdk-ts 的 src/recall.ts。
package com.tianting.sdk

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val QUOTE_LEN = 100

/**
 * 撤回文案的判断数据（F-RCL-02 规则 1a）。聊天页的提示行和会话列表摘要用同一份（AC-RCL-02-4、03-6、03-10）。
 * operator 是撤回的操作者（recalledBy；老数据没有时按发送者本人撤回），sender 是被撤回的消息的发送者。
 */
public data class RecallNotice(
    val convType: ConvType,
    val operator: String,
    val sender: String,
    /** 操作者是我。 */
    val byMe: Boolean,
    /** 被撤回的是我的消息。 */
    val ofMe: Boolean,
    /** 发送者自己撤回（不是群主、管理员撤回别人的）。 */
    val own: Boolean,
)

private fun notice(convId: String, from: String, recalledBy: String?, me: String): RecallNotice {
    val operator = recalledBy?.takeIf { it.isNotEmpty() } ?: from
    return RecallNotice(
        convType = if (convId.startsWith("group:")) ConvType.GROUP else ConvType.C2C,
        operator = operator,
        sender = from,
        byMe = operator == me,
        ofMe = from == me,
        own = operator == from,
    )
}

/** 消息的撤回文案数据；不是撤回的（包括后台删除）返回 null。 */
public fun recallNoticeOf(convId: String, m: Message, me: String): RecallNotice? =
    if (m.recalled) notice(convId, m.from, m.recalledBy, me) else null

/** 会话摘要（lastMsg）的撤回文案数据；不是撤回的返回 null。 */
public fun recallNoticeOf(convId: String, last: LastMsg, me: String): RecallNotice? =
    if (last.recalled) notice(convId, last.from, last.recalledBy, me) else null

/**
 * F-RCL-02 规则 1a 的表：
 * 单聊自己撤回：你撤回了一条消息 / 对方撤回了一条消息；群聊自己撤回：你撤回了一条消息 / {操作者}撤回了一条消息；
 * 群主、管理员撤回别人的：你撤回了 {被撤回者} 的一条消息 / {操作者}撤回了你的一条消息 / {操作者}撤回了一条成员消息。
 * nameOf 是各端的显示名规则（问题 19：备注只在本地显示）。
 */
public fun recallText(n: RecallNotice, nameOf: (String) -> String): String = when {
    n.byMe -> if (n.own) "你撤回了一条消息" else "你撤回了 ${nameOf(n.sender)} 的一条消息"
    n.convType == ConvType.C2C -> "对方撤回了一条消息"
    n.own -> "${nameOf(n.operator)}撤回了一条消息"
    n.ofMe -> "${nameOf(n.operator)}撤回了你的一条消息"
    else -> "${nameOf(n.operator)}撤回了一条成员消息"
}

/**
 * 引用摘要（F-QUO-02 规则 2，和服务端组装的规则相同）：文本取前 100 个码点（带引用的消息只取它自己的文字，F-QUO-01 规则 4）；
 * 图片"[图片]"；文件"[文件] {文件名}"。
 */
public fun quoteSummary(m: Message): String = when (m.type) {
    MsgType.IMAGE -> "[图片]"
    MsgType.FILE -> "[文件] ${m.body.file?.name ?: ""}"
    else -> takeCodePoints(m.body.text ?: "", QUOTE_LEN)
}

/**
 * 引用条显示的文字（F-QUO-02 规则 3）：NORMAL 显示摘要；RECALLED "引用内容已被撤回"；DELETED "引用内容已被删除"；
 * EXPIRED "[图片] 已过期" 或 "[文件] 已过期"（问题 22）；不认识的 state "引用内容不可用"。页面按纯文本渲染（AC-QUO-02-5）。
 */
public fun quoteLabel(q: QuoteView): String = when (q.state) {
    QuoteState.NORMAL -> q.text ?: ""
    QuoteState.RECALLED -> "引用内容已被撤回"
    QuoteState.DELETED -> "引用内容已被删除"
    QuoteState.EXPIRED -> "${if (q.type == MsgType.IMAGE) "[图片]" else "[文件]"} 已过期"
    QuoteState.UNKNOWN -> "引用内容不可用"
}

/** userIds 去重、去空；什么都没有时返回 null（Schema 不允许空的 mentions）。 */
internal fun normalizeMentions(m: Mentions?): Mentions? {
    m ?: return null
    val ids = m.userIds.filter { it.isNotEmpty() }.distinct()
    return if (ids.isEmpty() && !m.all) null else Mentions(ids, m.all)
}

/** 上行的文本 body：引用只发 msgId（下行的 seq、state、text 都不发，Schema 不允许）。 */
internal fun textWire(body: MsgBody): JsonElement = buildJsonObject {
    put("text", body.text ?: "")
    body.quote?.let { q -> put("quote", buildJsonObject { put("msgId", q.msgId) }) }
    body.mentions?.let { men ->
        put(
            "mentions",
            buildJsonObject {
                if (men.userIds.isNotEmpty()) putJsonArray("userIds") { men.userIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                if (men.all) put("all", true)
            },
        )
    }
}
