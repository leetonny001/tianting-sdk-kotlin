// 举报（PRD-chat F-MOD-03，第 13.1 节，C6）：参数在本地先校验，不合法直接抛 4000，不发帧。语义同 sdk-ts 的 src/report.ts。
package com.tianting.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** note 最多 200 个码点（和服务端一样按码点数，frontend-chat 第 84 条）。 */
public const val REPORT_NOTE_MAX: Int = 200

/** 一次最多举报 20 条消息（协议上限；第一批页面只举报单条，第 85 条）。 */
public const val REPORT_MSG_MAX: Int = 20

/** 举报理由（F-MOD-03 规则 1）。界面文案（frontend-chat 第 78 条）：垃圾广告、辱骂骚扰、色情低俗、诈骗、违法违规、其他。 */
public enum class ReportReason(override val wire: String) : WireEnum {
    SPAM("spam"), ABUSE("abuse"), PORN("porn"), FRAUD("fraud"), ILLEGAL("illegal"), OTHER("other"),
}

/**
 * 举报对象（report 命令的 targetType，按类型互斥）。Messages：同一会话（单聊或群聊）的 1～20 条消息，第一批页面只传 1 条（R-CF-21）；
 * User：资料卡举报用户；Group：群信息页举报群。
 */
public sealed interface ReportTarget {
    public data class Messages(val convId: String, val msgIds: List<String>) : ReportTarget
    public data class User(val userId: String) : ReportTarget
    public data class Group(val groupId: String) : ReportTarget
}

/** report 的 ack（C6）：24 小时内对同一对象重复提交返回第一次的 reportId。 */
@Serializable
internal data class ReportAck(val reportId: String)

private val REPORT_FIELDS = setOf("targetType", "convId", "msgIds", "userId", "groupId", "reason", "note")
private val REPORT_ID_RE = Regex("^[A-Za-z0-9_-]{1,64}$")

/** 组装 report 命令的 data（还没校验）。note 为 null 时不带，空串照发。 */
internal fun reportData(target: ReportTarget, reason: ReportReason, note: String?): JsonObject = buildJsonObject {
    when (target) {
        is ReportTarget.Messages -> {
            put("targetType", "message")
            put("convId", target.convId)
            putJsonArray("msgIds") { target.msgIds.forEach { add(JsonPrimitive(it)) } }
        }
        is ReportTarget.User -> {
            put("targetType", "user")
            put("userId", target.userId)
        }
        is ReportTarget.Group -> {
            put("targetType", "group")
            put("groupId", target.groupId)
        }
    }
    put("reason", reason.wire)
    if (note != null) put("note", note)
}

/** 本地校验不通过：4000，details.field 指出是哪个字段。错误信息里不带 note 和其他参数的值。 */
private fun bad(field: String): IMError =
    IMError(ErrorCode.BAD_REQUEST, "invalid report $field", details = buildJsonObject { put("field", field) })

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun isValidId(e: JsonElement?): Boolean = (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { REPORT_ID_RE.matches(it) } == true

/**
 * 校验 report 命令的 data（和 sdk-ts 的 reportRequest 同一个顺序）：不认识的字段、reason、note（不是字符串或超过 200 个码点）、
 * 按 targetType 互斥的字段（带了别的对象的，值是什么都不行）、convId（严格格式，单聊自己必须是参与者，聊天室不行）、
 * msgIds（1～20 条合法 ID）、userId、groupId。self 为空（还不知道自己是谁）时只校验格式，参与者由服务端判断。
 */
internal fun checkReport(data: JsonObject, self: String) {
    for (k in data.keys) if (k !in REPORT_FIELDS) throw bad(k) // 和 Schema 一样不允许多余字段
    val reason = data.string("reason")
    if (reason == null || ReportReason.entries.none { it.wire == reason }) throw bad("reason")
    if ("note" in data) {
        val note = data.string("note") ?: throw bad("note")
        if (countCodePoints(note) > REPORT_NOTE_MAX) throw bad("note")
    }
    when (data.string("targetType")) {
        "message" -> {
            if ("userId" in data || "groupId" in data) throw bad("targetType")
            val convId = data.string("convId") ?: throw bad("convId")
            try {
                parseConvIdStrict(convId, self.ifEmpty { convId.split(":").getOrElse(1) { "" } })
            } catch (_: ConvIdError) {
                throw bad("convId")
            }
            val ids = data["msgIds"] as? JsonArray ?: throw bad("msgIds")
            if (ids.size !in 1..REPORT_MSG_MAX || !ids.all { isValidId(it) }) throw bad("msgIds")
        }
        "user" -> {
            if ("convId" in data || "msgIds" in data || "groupId" in data) throw bad("targetType")
            if (!isValidId(data["userId"])) throw bad("userId")
        }
        "group" -> {
            if ("convId" in data || "msgIds" in data || "userId" in data) throw bad("targetType")
            if (!isValidId(data["groupId"])) throw bad("groupId")
        }
        else -> throw bad("targetType")
    }
}
