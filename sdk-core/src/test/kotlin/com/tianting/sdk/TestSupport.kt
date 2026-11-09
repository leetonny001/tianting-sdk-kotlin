package com.tianting.sdk

import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.fasterxml.jackson.databind.ObjectMapper
import com.tianting.sdk.jdbc.JdbcLocalDatabase
import com.tianting.sdk.testing.FakeClock
import com.tianting.sdk.testing.FakeConnection
import com.tianting.sdk.testing.FakeTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.fail

/** 协议副本目录。 */
val protocolDir: File by lazy {
    val root = System.getenv("TIANTING_ROOT")?.let { File(it) } ?: File("..")
    File(root, "protocol").also { check(it.isDirectory) { "protocol dir not found: $it" } }
}

fun vector(name: String): JsonElement = Json.parseToJsonElement(File(protocolDir, "vectors/$name").readText())

/** 宽松地把 Kotlin 值变成 JSON：测试里写帧更短。 */
fun js(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k as String to js(x) })
    is List<*> -> JsonArray(v.map { js(it) })
    else -> throw IllegalArgumentException("$v")
}

fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.toMap().mapValues { js(it.value) })

/** 协议字段缺失的标记（obj 里 null 表示 JSON null）。 */
fun objNoNull(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.filter { it.second != null }.toMap().mapValues { js(it.value) })

val JsonObject.dataObj: JsonObject get() = this["data"]!!.jsonObject

fun event(type: String, vararg pairs: Pair<String, Any?>): JsonObject = obj("cmd" to "event", "data" to obj("type" to type, *pairs))

fun msgFrame(m: JsonObject): JsonObject = obj("cmd" to "msg", "data" to m)

const val ABSENT = "__absent__"

fun conv(
    convId: String,
    type: String? = null,
    name: String = convId,
    maxSeq: Long = 0,
    readSeq: Long = 0,
    minSeq: Long = 1,
    unread: Long = 0,
    lastMsg: JsonObject? = null,
    updatedAt: Long = 0,
    memberCount: Int? = null,
    myRole: String? = null,
    allMuted: Boolean = false,
    mutedUntil: Any? = ABSENT,
    modVer: Long? = null,
): JsonObject {
    val m = linkedMapOf<String, Any?>(
        "convId" to convId,
        "type" to (type ?: if (convId.startsWith("group:")) "group" else "c2c"),
        "name" to name,
        "maxSeq" to maxSeq,
        "readSeq" to readSeq,
        "minSeq" to minSeq,
        "unread" to unread,
        "lastMsg" to lastMsg,
        "updatedAt" to updatedAt,
    )
    memberCount?.let { m["memberCount"] = it }
    myRole?.let { m["myRole"] = it }
    if (allMuted) m["allMuted"] = true
    if (mutedUntil != ABSENT) m["mutedUntil"] = mutedUntil
    modVer?.let { m["modVer"] = it }
    return js(m).jsonObject
}

fun msg(convId: String, seq: Long, from: String = "bob", text: String = "m$seq", ts: Long = 1000 + seq): JsonObject = obj(
    "msgId" to "$convId#$seq",
    "clientMsgId" to "$from-$convId-$seq",
    "convId" to convId,
    "seq" to seq,
    "from" to from,
    "type" to "text",
    "body" to obj("text" to text),
    "ts" to ts,
)

fun deletedMsg(convId: String, seq: Long, from: String = "bob", msgId: String = "$convId#$seq", clientMsgId: String = "$from-$convId-$seq"): JsonObject = obj(
    "msgId" to msgId,
    "clientMsgId" to clientMsgId,
    "convId" to convId,
    "seq" to seq,
    "from" to from,
    "type" to "text",
    "ts" to 1000 + seq,
    "deleted" to true,
    "deletedAt" to 5000,
)

const val NOW = 1_700_000_000_000L

fun profile(nickname: String) = obj("nickname" to nickname, "avatar" to "", "official" to false)

fun friend(userId: String, ver: Long, remark: String = "", deleted: Boolean = false, profile: JsonObject? = profile(userId.uppercase())): JsonObject {
    val m = linkedMapOf<String, Any?>(
        "userId" to userId, "remark" to remark, "source" to "other", "createdAt" to NOW, "ver" to ver, "profile" to profile,
    )
    if (deleted) m["deleted"] = true
    return js(m).jsonObject
}

fun req(
    userId: String,
    direction: String,
    ver: Long,
    status: String = "pending",
    updatedAt: Long = NOW,
    expireAt: Long = NOW + 7 * 86_400_000,
    message: String = "",
): JsonObject = obj(
    "userId" to userId, "direction" to direction, "status" to status, "message" to message, "source" to "other", "applyCount" to 1,
    "createdAt" to NOW, "updatedAt" to updatedAt, "expireAt" to expireAt, "ver" to ver,
    "profile" to if (direction == "in") profile(userId) else null,
)

fun page(
    untilVer: Long = 0,
    full: Boolean = true,
    latestVer: Long = untilVer,
    hasMore: Boolean = false,
    cursor: String = "",
    friends: List<JsonObject> = emptyList(),
    requests: List<JsonObject> = emptyList(),
    blocks: List<JsonObject> = emptyList(),
    requestSeenVer: Long = 0,
    searchable: Boolean = true,
    mode: String = "open",
    strangerMsgLimit: Int = 0,
    official: Boolean = false,
): JsonObject = obj(
    "full" to full, "untilVer" to untilVer, "latestVer" to latestVer, "hasMore" to hasMore, "cursor" to cursor,
    "friends" to friends, "requests" to requests, "blocks" to blocks, "requestSeenVer" to requestSeenVer,
    "settings" to obj("searchable" to searchable), "mode" to mode, "strangerMsgLimit" to strangerMsgLimit, "official" to official,
)

fun items(friends: List<JsonObject> = emptyList(), requests: List<JsonObject> = emptyList(), blocks: List<JsonObject> = emptyList()) =
    obj("friends" to friends, "requests" to requests, "blocks" to blocks)

object FrameSchema {
    private val mapper = ObjectMapper()
    private val schema: JsonSchema by lazy {
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(File(protocolDir, "schema/frame.schema.json").readText())
    }

    fun errors(frame: String): List<String> = schema.validate(mapper.readTree(frame)).map { it.message }
}

/** 所有单元测试的基类：SDK 发出的每一帧都要符合 frame.schema.json。 */
abstract class SdkTest {
    private val harnesses = ArrayList<Harness>()

    protected fun setup(
        platform: Platform = Platform.ANDROID,
        database: LocalDatabase? = null,
        clock: FakeClock = FakeClock(),
        random: () -> Double = { 0.5 }, // 抖动为 0，退避间隔精确
        pushCursorStore: PushCursorStore? = null,
        pushLimits: PushLimits = PushLimits(),
        timings: IMClientTimings = IMClientTimings(),
        filesRoot: File? = null,
    ): Harness = Harness(platform, database, clock, random, pushCursorStore, pushLimits, timings, filesRoot).also { harnesses.add(it) }

    @AfterEach
    fun framesMatchSchema() {
        for (h in harnesses) assertEquals(emptyList(), h.schemaViolations, "SDK 发出的帧不符合 schema")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class Harness(
    platform: Platform,
    database: LocalDatabase?,
    val clock: FakeClock,
    random: () -> Double,
    pushCursorStore: PushCursorStore? = null,
    pushLimits: PushLimits = PushLimits(),
    timings: IMClientTimings = IMClientTimings(),
    /** 附件沙盒和下载缓存的根目录；null 时用新的临时目录。冷启动用例传同一个。 */
    filesRoot: File? = null,
) {
    val scheduler = TestCoroutineScheduler()
    val dispatcher = StandardTestDispatcher(scheduler)
    val callScope = CoroutineScope(dispatcher + SupervisorJob())
    val transport = FakeTransport { flush() }

    /** 文件接口的 Bearer 来源：AccountHarness 设成它的 AccountClient；默认是固定 token。 */
    var fileAuth: FileAuth? = null
    val objects = com.tianting.sdk.testing.FakeObjects { flush() }
    val filesRoot: File = filesRoot ?: java.nio.file.Files.createTempDirectory("tt-files-").toFile()
    val attachmentDir = File(this.filesRoot, "attachments")
    val cacheDir = File(this.filesRoot, "cache")
    val files = FileClient(
        object : FileAuth {
            override suspend fun authorized(method: String, path: String, body: JsonElement?): JsonElement =
                (fileAuth ?: error("fileAuth not set")).authorized(method, path, body)
        },
        objects,
    )
    val logs = ArrayList<String>()
    var visible = true
    val events = ArrayList<IMEvent>()
    val schemaViolations = ArrayList<String>()
    private var n = 0

    val im = IMClient(
        IMClientOptions(
            url = "ws://im.test/ws",
            platform = platform,
            database = database,
            transport = transport,
            clock = clock,
            random = random,
            newId = { "cid-${(++n).toString().padStart(4, '0')}" },
            logger = object : Logger {
                override fun debug(message: String) { logs.add(message) }
                override fun warn(message: String) { logs.add(message) }
                override fun error(message: String) { logs.add(message) }
            },
            isVisible = { visible },
            dispatcher = dispatcher,
            pushCursorStore = pushCursorStore,
            pushLimits = pushLimits,
            timings = timings,
            files = files,
            attachmentDir = attachmentDir,
            fileCacheDir = cacheDir,
            ioDispatcher = dispatcher,
        ),
    )

    init {
        clock.afterEach = { flush() }
        im.eventTap = { events.add(it) }
        transport.onSent = { f ->
            val errs = FrameSchema.errors(f)
            // token 不进报错信息
            if (errs.isNotEmpty()) schemaViolations.add("${Json.parseToJsonElement(f).jsonObject["cmd"]}: $errs")
        }
        flush()
    }

    fun flush() = scheduler.runCurrent()

    fun connect(token: String = "t") {
        im.connect(token)
        flush()
    }

    /** 调一个应该立即完成的 suspend 方法。 */
    fun <T> call(block: suspend IMClient.() -> T): T = await(start(block))

    /** 调一个还要等 ack 的 suspend 方法：先发出去，之后 await。 */
    fun <T> start(block: suspend IMClient.() -> T): Deferred<T> {
        val d = callScope.async { im.block() }
        flush()
        return d
    }

    fun <T> await(d: Deferred<T>): T {
        flush()
        if (!d.isCompleted) fail("还没有完成")
        return d.getCompleted()
    }

    /** 打开当前连接并完成 auth；convs 用给定列表回复。 */
    fun login(userId: String = "alice", convs: List<JsonObject> = emptyList()): FakeConnection {
        val c = transport.current
        c.open()
        c.ack("auth", obj("userId" to userId, "serverTime" to clock.now()))
        c.ack("convs", obj("convs" to convs, "truncated" to false))
        return c
    }

    /** 断线后退避重连，完成 auth，但不回 convs。 */
    fun reconnectAuth(userId: String = "alice", waitMs: Long = 1_000, extra: Map<String, Any?> = emptyMap()): FakeConnection {
        clock.advance(waitMs)
        val c = transport.current
        c.open()
        c.ack("auth", js(mapOf("userId" to userId, "serverTime" to clock.now()) + extra))
        return c
    }

    fun failed(): List<IMEvent.SendFailed> = events.filterIsInstance<IMEvent.SendFailed>()
    fun fatals(): List<FatalReason> = events.filterIsInstance<IMEvent.Fatal>().map { it.reason }
    fun removed(): List<IMEvent.ConvRemoved> = events.filterIsInstance<IMEvent.ConvRemoved>()
    fun muteEvents(): Int = events.count { it === IMEvent.MuteChanged }

    fun status(convId: String): List<String> = im.messages(convId).map { m ->
        val err = when (val e = m.error) {
            null -> ""
            is SendError.Code -> "(${e.code})"
            SendError.Timeout -> "(timeout)"
            SendError.Kicked -> "(kicked)"
            SendError.Network -> "(network)"
            SendError.Process -> "(process)"
            SendError.Storage -> "(storage)"
            SendError.SourceMissing -> "(sourceMissing)"
        }
        "${m.clientMsgId}:${m.status.name.lowercase()}$err${m.seq?.let { "#$it" } ?: ""}"
    }

    fun convOf(convId: String): Conversation = im.conversations.value.first { it.convId == convId }
}

/** 走对象图（Map、集合、数组、SDK 自己的类的字段），找内存里任何一份副本。比 debugExport 更严：不依赖导出函数写对。 */
fun holds(root: Any?, needle: String, seen: MutableSet<Int> = HashSet()): Boolean {
    when (root) {
        null -> return false
        is String -> return root.contains(needle)
        is JsonPrimitive -> return root.content.contains(needle)
    }
    if (!seen.add(System.identityHashCode(root))) return false
    return when (root) {
        is Map<*, *> -> root.entries.any { holds(it.key, needle, seen) || holds(it.value, needle, seen) }
        is Iterable<*> -> root.any { holds(it, needle, seen) }
        is Array<*> -> root.any { holds(it, needle, seen) }
        else -> {
            var c: Class<*>? = root.javaClass
            // 测试替身（Harness 的事件记录、FakeConnection 发出的帧）不算 SDK 的内存。
            if (root is Enum<*> || !c!!.name.startsWith("com.tianting.sdk") || c.name.contains("Harness") || c.name.contains(".testing.") || root is IMClientOptions) return false
            var found = false
            while (c != null && c.name.startsWith("com.tianting.sdk")) {
                for (f in c.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
                    f.isAccessible = true
                    if (holds(f.get(root), needle, seen)) found = true
                }
                c = c.superclass
            }
            found
        }
    }
}

fun storeOf(im: IMClient): Any = IMClient::class.java.getDeclaredField("store").apply { isAccessible = true }.get(im)

fun String.jsonStr(): JsonPrimitive = JsonPrimitive(this)

val JsonElement.str: String get() = jsonPrimitive.content

/** 打开临时目录里的本地库。 */
fun tempDb(dir: File): JdbcLocalDatabase = JdbcLocalDatabase(File(dir, "im.db"))

/** 在目录的所有文件（主库、-wal、-journal、-shm）的原始字节里找特征串。 */
fun filesContaining(dir: File, needle: String): List<String> {
    val bytes = needle.toByteArray(Charsets.UTF_8)
    return dir.walkTopDown().filter { it.isFile }.filter { f -> indexOf(f.readBytes(), bytes) >= 0 }.map { it.name }.toList()
}

private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
    outer@ for (i in 0..hay.size - needle.size) {
        for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}
