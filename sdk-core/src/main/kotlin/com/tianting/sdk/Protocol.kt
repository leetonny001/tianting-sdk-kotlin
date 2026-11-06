// 协议类型（protocol/schema/frame.schema.json v1）。社交类型在 Social.kt。
// 所有下行模型都容忍未知字段（ImJson.ignoreUnknownKeys）和未知枚举值（解码成 UNKNOWN，不抛异常）。
package com.tianting.sdk

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal val ImJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

/** 枚举在协议里的字符串值。 */
public interface WireEnum {
    public val wire: String
}

/** 不认识的枚举值解码成 unknown（协议 README 第 3 节规则 9：新版本服务端可以加枚举值）。 */
internal open class WireEnumSerializer<E>(name: String, values: Array<E>, private val unknown: E) : KSerializer<E>
    where E : Enum<E>, E : WireEnum {
    private val byWire = values.associateBy { it.wire }
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): E = byWire[decoder.decodeString()] ?: unknown
    override fun serialize(encoder: Encoder, value: E): Unit = encoder.encodeString(value.wire)
}

@Serializable(with = ConvTypeSerializer::class)
public enum class ConvType(override val wire: String) : WireEnum {
    C2C("c2c"), GROUP("group"), ROOM("room"), UNKNOWN("unknown"),
}

internal object ConvTypeSerializer : WireEnumSerializer<ConvType>("ConvType", ConvType.entries.toTypedArray(), ConvType.UNKNOWN)

public enum class Platform(public val wire: String) { ANDROID("android"), IOS("ios"), WEB("web"), PC("pc") }

@Serializable(with = GroupRoleSerializer::class)
public enum class GroupRole(override val wire: String) : WireEnum {
    OWNER("owner"), ADMIN("admin"), MEMBER("member"), UNKNOWN("unknown"),
}

internal object GroupRoleSerializer : WireEnumSerializer<GroupRole>("GroupRole", GroupRole.entries.toTypedArray(), GroupRole.UNKNOWN)

@Serializable(with = MsgTypeSerializer::class)
public enum class MsgType(override val wire: String) : WireEnum {
    TEXT("text"), CUSTOM("custom"), TIP("tip"), UNKNOWN("unknown"),
}

internal object MsgTypeSerializer : WireEnumSerializer<MsgType>("MsgType", MsgType.entries.toTypedArray(), MsgType.UNKNOWN)

/** 群提示消息（PRD-social F-SOC-29）：actor 为空表示系统操作；targets 最多 20 个，超过时带 count。 */
@Serializable
public data class TipBody(
    val kind: String,
    val actor: String = "",
    val targets: List<String> = emptyList(),
    val count: Int? = null,
    val text: String = "",
)

@Serializable
public data class MsgBody(
    val text: String? = null,
    val data: JsonElement? = null,
    val tip: TipBody? = null,
)

/** 服务端的一条消息（msg 帧、sync 结果）。被删除的消息没有 body（PRD-core F-MSG-05 规则 7）。 */
@Serializable
internal data class ServerMessage(
    val msgId: String,
    val clientMsgId: String,
    val convId: String,
    val seq: Long,
    val from: String,
    val type: MsgType,
    val body: MsgBody? = null,
    val ts: Long,
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
)

/** 会话摘要。最后一条消息被删除时没有 text（deleted = true）。 */
@Serializable
public data class LastMsg(
    val msgId: String,
    val from: String,
    val type: MsgType,
    val text: String? = null,
    val ts: Long,
    val deleted: Boolean = false,
)

/**
 * 到期时间字段：协议里"没有这个字段"（没有禁言）和 null（永久）含义不同，Kotlin 的 Long? 分不开，单独建模。
 * 字段类型必须是非空的 Deadline，默认值 Absent；序列化器自己处理 JSON null。
 */
@Serializable(with = DeadlineSerializer::class)
internal sealed interface Deadline {
    data object Absent : Deadline
    data object Forever : Deadline
    data class At(val ms: Long) : Deadline
}

internal object DeadlineSerializer : KSerializer<Deadline> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Deadline", PrimitiveKind.LONG).nullable

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): Deadline =
        if (decoder.decodeNotNullMark()) Deadline.At(decoder.decodeLong()) else decoder.decodeNull().let { Deadline.Forever }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Deadline) {
        when (value) {
            is Deadline.At -> encoder.encodeLong(value.ms)
            else -> encoder.encodeNull()
        }
    }
}

/** convs 的一项、convAdded 的内容。 */
@Serializable
internal data class ServerConversation(
    val convId: String,
    val type: ConvType,
    val name: String = "",
    val maxSeq: Long,
    val readSeq: Long,
    val minSeq: Long,
    val unread: Long,
    val lastMsg: LastMsg? = null,
    val updatedAt: Long,
    val memberCount: Int? = null,
    val myRole: GroupRole? = null,
    val mutedUntil: Deadline = Deadline.Absent,
    /** 该群开启了全群禁言时才有（P1，F-ADM-42）；群主、管理员也带，但不受限。 */
    val allMuted: Boolean = false,
)

@Serializable
internal data class SendAck(val msgId: String, val convId: String, val seq: Long, val ts: Long)

@Serializable
internal data class SyncAck(val messages: List<ServerMessage> = emptyList(), val hasMore: Boolean = false)

@Serializable
internal data class ConvsAck(val convs: List<ServerConversation> = emptyList(), val truncated: Boolean = false)

@Serializable
internal data class AuthAck(
    val userId: String,
    val serverTime: Long,
    val mutedUntil: Deadline = Deadline.Absent,
    val recoveryCodeUnacked: Boolean = false,
)

/** ack 的 error。details 原样透传（4031 的 {scope, groupId?, mutedUntil}，建群失败的 userId）。 */
@Serializable
internal data class ErrorBody(
    val code: Int,
    val msg: String = "",
    val userId: String? = null,
    val details: JsonObject? = null,
)

/** 单聊会话 ID：c2c:{较小 userId}:{较大 userId}，按 ASCII 字节序比较（与服务端一致）。 */
public fun c2cConvId(a: String, b: String): String = if (a < b) "c2c:$a:$b" else "c2c:$b:$a"

public fun groupConvId(groupId: String): String = "group:$groupId"

private val ID_RE = Regex("^[A-Za-z0-9_-]{1,64}$")

/** 会话 ID 不合法（INVALID），或者合法但自己不是参与者（NOT_PARTICIPANT）。 */
public class ConvIdError(public val kind: Kind, convId: String) : IllegalArgumentException("$kind convId \"$convId\"") {
    public enum class Kind { INVALID, NOT_PARTICIPANT }
}

public data class ParsedConvId(val convType: ConvType, val to: String)

/**
 * 严格解析会话 ID（PRD-ui G-UI-03，用例 protocol/vectors/convid.json）：界面用它判断路由。
 * 单聊必须恰好三段、两个 userId 合法且按 ASCII 字节序递增（相同时只能是 self 自己），其中一个是 self；
 * 群必须恰好两段且 groupId 合法；一期没有聊天室，room 和其他前缀一律不合法。
 */
public fun parseConvIdStrict(convId: String, self: String): ParsedConvId {
    val parts = convId.split(":")
    if (parts[0] == "c2c" && parts.size == 3) {
        val (_, a, b) = parts
        if (!ID_RE.matches(a) || !ID_RE.matches(b) || a > b) throw ConvIdError(ConvIdError.Kind.INVALID, convId)
        // 两个 ID 相同只能是给自己发消息（F-MSG-01 规则 4、AC-UI-05-3，勘误 E-23）。
        if (a != self && b != self) throw ConvIdError(ConvIdError.Kind.NOT_PARTICIPANT, convId)
        return ParsedConvId(ConvType.C2C, if (a == self) b else a)
    }
    if (parts[0] == "group" && parts.size == 2 && ID_RE.matches(parts[1])) return ParsedConvId(ConvType.GROUP, parts[1])
    throw ConvIdError(ConvIdError.Kind.INVALID, convId)
}

/** 单聊会话的对方 userId；群会话返回 null。 */
public fun peerId(conv: Conversation, self: String): String? {
    if (conv.type != ConvType.C2C) return null
    val parts = conv.convId.split(":")
    val a = parts.getOrElse(1) { "" }
    val b = parts.getOrElse(2) { "" }
    return if (a == self) b else a
}

/** 只给 SDK 内部用：不校验格式和参与者，界面不得用它判断路由（用 parseConvIdStrict）。 */
internal fun parseConvId(convId: String, self: String): ParsedConvId {
    val parts = convId.split(":")
    val a = parts.getOrElse(1) { "" }
    val b = parts.getOrElse(2) { "" }
    return when (parts[0]) {
        "c2c" -> ParsedConvId(ConvType.C2C, if (a == self) b else a)
        "group" -> ParsedConvId(ConvType.GROUP, a)
        "room" -> ParsedConvId(ConvType.ROOM, a)
        else -> throw IllegalArgumentException("invalid convId")
    }
}
