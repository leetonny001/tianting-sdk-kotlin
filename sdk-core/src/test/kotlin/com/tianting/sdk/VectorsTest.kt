package com.tianting.sdk

import com.ibm.icu.text.Collator
import com.ibm.icu.util.ULocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 共享用例：protocol/vectors 下的文件，三套 SDK 和服务端跑同一批。 */
class VectorsTest : SdkTest() {
    @TestFactory
    fun `text json（C-26、AC-MSG-01-2、AC-MSG-01-3）`(): List<DynamicTest> =
        vector("text.json").jsonObject["cases"]!!.jsonArray.map { c ->
            val o = c.jsonObject
            dynamicTest(o["name"]!!.str) {
                assertEquals(o["trimmed"]!!.str, trimText(o["input"]!!.str))
                assertEquals(o["length"]!!.jsonPrimitive.int, countCodePoints(o["trimmed"]!!.str))
            }
        }

    @Test
    fun `checkText：空白、5000、5001`() {
        assertEquals(TextRejectReason.EMPTY, (checkText(" \n\t　 ") as TextCheck.Rejected).reason)
        assertIs<TextCheck.Ok>(checkText("字".repeat(5000)))
        assertIs<TextCheck.Ok>(checkText("😀".repeat(5000)))
        assertEquals(3, (checkText("字".repeat(5003)) as TextCheck.Rejected).over)
        assertEquals(TextCheck.Ok("hi"), checkText("  hi  "))
    }

    @TestFactory
    fun `convid json（AC-UI-33-13）`(): List<DynamicTest> {
        val spec = vector("convid.json").jsonObject
        val self = spec["self"]!!.str
        return spec["cases"]!!.jsonArray.map { c ->
            val o = c.jsonObject
            dynamicTest("[${o["convId"]!!.str}]") {
                val ok = o["ok"]?.jsonObject
                if (ok != null) {
                    val p = parseConvIdStrict(o["convId"]!!.str, self)
                    assertEquals(ok["convType"]!!.str, p.convType.wire)
                    assertEquals(ok["to"]!!.str, p.to)
                } else {
                    val e = assertFailsWith<ConvIdError> { parseConvIdStrict(o["convId"]!!.str, self) }
                    val want = if (o["error"]!!.str == "invalid") ConvIdError.Kind.INVALID else ConvIdError.Kind.NOT_PARTICIPANT
                    assertEquals(want, e.kind)
                }
            }
        }
    }

    @Test
    fun `peerId：单聊返回对方，群返回 null`() {
        fun c(id: String, t: ConvType) = Conversation(id, t, "", 0, 0, 1, 0, null, 0)
        assertEquals("bob", peerId(c("c2c:alice:bob", ConvType.C2C), "alice"))
        assertEquals("alice", peerId(c("c2c:alice:bob", ConvType.C2C), "bob"))
        assertNull(peerId(c("group:g1", ConvType.GROUP), "alice"))
    }

    @Test
    fun `avatar json 颜色下标和首字（AC-UI-21-2）`() {
        val spec = vector("avatar.json").jsonObject
        for (c in spec["colors"]!!.jsonArray) {
            val o = c.jsonObject
            assertEquals(o["fnv1a32"]!!.jsonPrimitive.long, fnv1a32(o["id"]!!.str), o["id"]!!.str)
            assertEquals(o["index"]!!.jsonPrimitive.int, avatarColorIndex(o["id"]!!.str), o["id"]!!.str)
        }
        for (c in spec["initials"]!!.jsonArray) {
            val o = c.jsonObject
            assertEquals(o["initial"]!!.str, avatarInitial(o["name"]!!.str), o["name"]!!.str)
        }
    }

    private val icu = PinyinCollator { a, b -> Collator.getInstance(ULocale.forLanguageTag("zh-Hans-CN-u-co-pinyin")).compare(a, b) }

    @TestFactory
    fun `pinyin-groups json（AC-UI-13-9，JVM 上用 ICU4J）`(): List<DynamicTest> =
        vector("pinyin-groups.json").jsonObject["cases"]!!.jsonArray.map { c ->
            val o = c.jsonObject
            dynamicTest("[${o["name"]!!.str}]") { assertEquals(o["group"]!!.str, pinyinGroup(o["name"]!!.str, icu)) }
        }

    @Test
    fun `拼音：不支持时汉字归井号；分组顺序和组内排序`() {
        assertEquals("#", pinyinGroup("张三", null))
        assertEquals("A", pinyinGroup("Ａlice", null)) // 全角
        data class P(val id: String, val name: String)
        val groups = groupByPinyin(listOf(P("1", "周末"), P("2", "123"), P("3", "Alpha"), P("4", "阿强"), P("5", "Alpha")), icu, { it.name }, { it.id })
        assertEquals(listOf("A", "Z", "#"), groups.map { it.group })
        assertEquals(listOf("3", "5", "4"), groups[0].items.map { it.id }) // 非汉字开头的先排，相同按 id
    }

    // ---- frames.json ----

    private val frames get() = vector("frames.json").jsonObject

    @TestFactory
    fun `frames json：valid 通过 schema`(): List<DynamicTest> = frames["valid"]!!.jsonArray.map { v ->
        dynamicTest(v.jsonObject["name"]!!.str) { assertEquals(emptyList(), FrameSchema.errors(v.jsonObject["frame"].toString())) }
    }

    @TestFactory
    fun `frames json：invalid 被 schema 拒绝`(): List<DynamicTest> = frames["invalid"]!!.jsonArray.map { v ->
        dynamicTest(v.jsonObject["name"]!!.str) { assertTrue(FrameSchema.errors(v.jsonObject["frame"].toString()).isNotEmpty()) }
    }

    /** SDK 自己的解码器：按帧内容选模型解码 valid 帧。 */
    @TestFactory
    fun `frames json：valid 的 ack 和 msg 能用 SDK 的模型解码`(): List<DynamicTest> = frames["valid"]!!.jsonArray.mapNotNull { v ->
        val f = v.jsonObject["frame"]!!.jsonObject
        val data = f["data"] as? JsonObject
        val decoder: ((JsonObject) -> Unit)? = when {
            f["cmd"]!!.str == "msg" -> { d -> ImJson.decodeFromJsonElement(ServerMessage.serializer(), d) }
            f["cmd"]!!.str != "ack" || data == null -> null
            "serverTime" in data -> { d -> ImJson.decodeFromJsonElement(AuthAck.serializer(), d) }
            "convs" in data -> { d -> ImJson.decodeFromJsonElement(ConvsAck.serializer(), d) }
            "seq" in data -> { d -> ImJson.decodeFromJsonElement(SendAck.serializer(), d) }
            "hasMore" in data && "messages" in data -> { d -> ImJson.decodeFromJsonElement(SyncAck.serializer(), d) }
            "user" in data -> { d -> ImJson.decodeFromJsonElement(SearchAck.serializer(), d) }
            else -> null
        }
        decoder?.let { dec -> dynamicTest(v.jsonObject["name"]!!.str) { dec(data ?: f["data"]!!.jsonObject) } }
    }

    @Test
    fun `frames json：全部下行帧（kicked 除外）交给客户端都不出错，连接保持`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv("group:g1")))
        val down = frames["valid"]!!.jsonArray.map { it.jsonObject["frame"]!!.jsonObject }
            .filter { it["cmd"]!!.str in setOf("msg", "event", "ack", "pong", "push") }
            .filter { !it.toString().contains("kicked") }
        assertTrue(down.size > 20)
        for (f in down) c.receive(f)
        assertEquals(ConnState.CONNECTED, h.im.state.value)
        assertEquals(emptyList(), h.logs.filter { it.contains("unexpected") })
    }

    @Test
    fun `frames json：invalid 帧交给客户端不崩溃（服务端的错误不应该打垮客户端），连接保持`() {
        val h = setup()
        h.connect()
        val c = h.login("alice", listOf(conv("group:g1")))
        val frames = frames["invalid"]!!.jsonArray.map { it.jsonObject["frame"]!!.jsonObject }
            .filter { !it.toString().contains("kicked") }
        for (f in frames) c.receive(f)
        c.receive("not json")
        c.receive("[]")
        c.receive("""{"cmd":1}""")
        assertEquals(ConnState.CONNECTED, h.im.state.value)
        assertEquals(emptyList(), h.logs.filter { it.contains("unexpected") })
    }

    @Test
    fun `mutedUntil 三态：没有字段、null、数字解码后互不相同`() {
        val base = """"userId":"u","serverTime":1"""
        assertEquals(Deadline.Absent, ImJson.decodeFromString(AuthAck.serializer(), "{$base}").mutedUntil)
        assertEquals(Deadline.Forever, ImJson.decodeFromString(AuthAck.serializer(), """{$base,"mutedUntil":null}""").mutedUntil)
        assertEquals(Deadline.At(5), ImJson.decodeFromString(AuthAck.serializer(), """{$base,"mutedUntil":5}""").mutedUntil)
    }

    // ---- social.json ----

    private val social get() = vector("social.json").jsonObject

    /** 跑一个 social.json 用例，可以带持久化出口。 */
    private fun runCase(c: JsonObject, s: SocialState): String {
        var last = ""
        for (st in c["steps"]!!.jsonArray) {
            val o = st.jsonObject
            when (o["type"]!!.str) {
                "begin" -> s.beginRound()
                "page" -> last = s.applyPage(ImJson.decodeFromJsonElement(SocialSyncAck.serializer(), o["data"]!!)).wire
                "event" -> s.onSocialChanged(o["ver"]!!.jsonPrimitive.long)
                else -> {
                    val a = ImJson.decodeFromJsonElement(SocialChangeAck.serializer(), o["data"]!!)
                    s.applyAck(a.ver, a.changed, a.items)
                }
            }
        }
        return last
    }

    private fun check(c: JsonObject, s: SocialState, last: String?) {
        val e = c["expect"]!!.jsonObject
        val now = c["nowMs"]!!.jsonPrimitive.long
        assertEquals(e["socialVer"]!!.jsonPrimitive.long, s.ver)
        assertEquals(e["friends"]!!.jsonObject.mapValues { it.value.str }, s.friends.values.filter { !it.deleted }.associate { it.userId to it.remark })
        assertEquals(e["requests"]!!.jsonObject.mapValues { it.value.str }, s.requestList(now).associate { "${it.userId}/${it.direction.wire}" to it.status.wire })
        assertEquals(e["blocks"]!!.jsonArray.map { it.str }, s.blocks.values.filter { !it.deleted }.map { it.userId }.sorted())
        assertEquals(e["unread"]!!.jsonPrimitive.int, s.unseen(now))
        if (last != null) {
            assertEquals(e["needSync"]!!.jsonPrimitive.content.toBoolean(), s.needSync)
            assertEquals(e["lastNext"]?.str ?: "", last)
        }
    }

    @TestFactory
    fun `social json：同步状态机`(): List<DynamicTest> = social["cases"]!!.jsonArray.map { c ->
        dynamicTest(c.jsonObject["name"]!!.str) {
            val s = SocialState()
            check(c.jsonObject, s, runCase(c.jsonObject, s))
        }
    }

    /** 同一批用例经过本地库：写入后重新打开，读出来的状态要和内存一致（ver 与数据同事务）。 */
    @TestFactory
    fun `social json：写入本地库后重新打开，状态一致（AC-SOC-06-2 的持久化部分）`(): List<DynamicTest> = social["cases"]!!.jsonArray.map { c ->
        dynamicTest(c.jsonObject["name"]!!.str) {
            val dir = File.createTempFile("social", "").let { it.delete(); it.mkdirs(); it }
            try {
                val db = tempDb(dir)
                val local = LocalDb(db, SilentLogger).also { it.open() }
                val s = SocialState(local)
                local.transaction { runCase(c.jsonObject, s) }
                db.close()
                val db2 = tempDb(dir)
                val r = LocalDb(db2, SilentLogger).also { it.open() }.load()
                val back = SocialState()
                back.restore(r.socialVer, r.requestSeenVer, r.settings, r.official, r.socialLoaded, r.friends, r.requests, r.blocks)
                db2.close()
                // needSync 和 lastNext 是一轮之内的运行时状态，不持久化。
                check(c.jsonObject, back, null)
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun `提示消息文案（F-UI-40、AC-SOC-29-5）`() {
        for (c in social["tips"]!!.jsonArray) {
            val o = c.jsonObject
            val names = (o["names"] as? JsonObject)?.mapValues { it.value.str } ?: emptyMap()
            val tip = ImJson.decodeFromJsonElement(TipBody.serializer(), o["tip"]!!)
            assertEquals(o["text"]!!.str, tipText(tip, o["me"]!!.str) { names[it] ?: it })
        }
    }

    @Test
    fun `命令加错误码文案（9-4 节、AC-SOC-08-8）`() {
        for (c in social["copy"]!!.jsonArray) {
            val o = c.jsonObject
            val want = o["text"]!!.str.replace("{N}", "3").replace("{name}", "bob")
            assertEquals(want, messageFor(o["cmd"]!!.str, o["code"]!!.jsonPrimitive.int, 3, "bob"), "${o["cmd"]} ${o["code"]}")
        }
        assertEquals("今天发起新聊天的次数已用完，请稍后再试", messageFor("send", ErrorCode.QUOTA_EXCEEDED))
        assertEquals("操作太频繁，请稍后再试", messageFor("setPrivacy", 4290))
        assertNull(messageFor("send", 4042))
    }

    // ---- errors.json ----

    @Test
    fun `生成的错误码与 errors json 完全一致（FE-130）`() {
        val spec = Json.parseToJsonElement(File(protocolDir, "errors.json").readText()).jsonObject
        val forSdk = spec["errors"]!!.jsonArray.map { it.jsonObject }.filter { e -> e["scope"]!!.jsonArray.any { it.str != "console" } }
        assertTrue(forSdk.size < spec["errors"]!!.jsonArray.size, "console 专属的码应该被过滤")
        val fields = ErrorCode::class.java.declaredFields.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == Int::class.java }
        assertEquals(forSdk.size, fields.size)
        for (e in forSdk) {
            val name = e["name"]!!.str
            val code = e["code"]!!.jsonPrimitive.int
            assertEquals(code, ErrorCode::class.java.getField(name).getInt(null), name)
            val d = describeError(code)
            assertEquals(name, d.name)
            assertEquals(e["retryable"]!!.jsonPrimitive.content.toBoolean(), d.retryable, name)
        }
        for (c in spec["closeCodes"]!!.jsonArray) {
            val o = c.jsonObject
            assertEquals(o["code"]!!.jsonPrimitive.int, CloseCode::class.java.getField(o["name"]!!.str).getInt(null))
            assertEquals(o["reconnect"]!!.jsonPrimitive.content.toBoolean(), CLOSE_RECONNECT[o["code"]!!.jsonPrimitive.int])
        }
        for (k in spec["kickReasons"]!!.jsonArray) {
            val o = k.jsonObject
            assertEquals(if (o["closeCode"] is JsonNull) null else o["closeCode"]!!.jsonPrimitive.int, KICK_CLOSE_CODE[o["reason"]!!.str])
        }
    }

    @Test
    fun `4292 不可重试；不认识的错误码按区段兜底（C-09）`() {
        assertEquals(false, describeError(4292).retryable)
        assertEquals(ErrorDescription(4999, "UNKNOWN", ErrorCategory.BAD_REQUEST, false), describeError(4999))
        assertEquals(ErrorCategory.FORBIDDEN, describeError(4039).category)
        assertEquals(ErrorCategory.NOT_FOUND, describeError(4049).category)
        assertEquals(ErrorCategory.RATE_LIMITED, describeError(4299).category)
        assertEquals(false, describeError(4299).retryable)
        assertEquals(ErrorCategory.SERVER, describeError(5999).category)
        assertEquals(true, describeError(5999).retryable)
    }
}
