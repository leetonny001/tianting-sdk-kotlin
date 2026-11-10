// TC-CHAT-90-13（AC-CHAT-90-15）、TC-FILE-06-11（AC-FILE-06-12）：共享用例 protocol/vectors/chat-sync.json（PRD-chat F-RCL-03 规则 2、2a）。
// C4（FE-661、FE-662）：TC-CHAT-90-33 和 TC-RCL-03-11、TC-RCL-03-13、TC-QUO-02-02、TC-QUO-02-06、TC-MEN-03-07 的同名用例也在这里跑：
// 步骤 event、advance，期望 convsFetches、mentionUnread。每个用例跑两遍：只在内存、带真 SQLite（最后再搜数据库文件里的特征串）。
// 真实的 IMClient + FakeTransport + 假时钟：connect 真的是重连后的 convs 应答，SDK 发出的每个 sync 都按 convId 回复。语义同 sdk-ts 的 test/chat-sync-vectors.test.ts。
package com.tianting.sdk

import com.tianting.sdk.testing.FakeConnection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.test.fail

class ChatSyncVectorsTest : SdkTest() {
    private val spec get() = vector("chat-sync.json").jsonObject
    private val self get() = spec["self"]!!.str

    private fun bool(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.boolean == true

    /** 按 format 展开成下行消息。 */
    private fun wire(convId: String, m: JsonObject): JsonObject {
        val seq = m["seq"]!!.jsonPrimitive.long
        val type = m["type"]!!.str
        val base = linkedMapOf<String, Any?>(
            "msgId" to "$convId#$seq", "clientMsgId" to "bob-$convId-$seq", "convId" to convId, "seq" to seq,
            "from" to "bob", "type" to type, "ts" to 1000 + seq,
        )
        if (bool(m, "deleted")) {
            base["deleted"] = true
            if (bool(m, "recalled")) {
                base["recalled"] = true
                base["recalledAt"] = 2000 + seq
                base["recalledBy"] = m["recalledBy"]!!.str
            }
            return js(base).jsonObject
        }
        val expired = if (bool(m, "expired")) true else null
        val body = when (type) {
            "image" -> obj("image" to objNoNull("fileId" to m["fileId"]!!.str, "width" to 10, "height" to 10, "size" to 100, "mime" to "image/png", "expired" to expired))
            "file" -> obj("file" to objNoNull("fileId" to m["fileId"]!!.str, "name" to (m["name"]?.str ?: ""), "size" to 100, "mime" to "application/pdf", "expired" to expired))
            else -> {
                val q = m["quote"] as? JsonObject
                objNoNull(
                    "text" to m["text"]!!.str,
                    "quote" to q?.let {
                        val qs = it["seq"]!!.jsonPrimitive.long
                        objNoNull("msgId" to "$convId#$qs", "seq" to qs, "from" to "bob", "type" to "text", "state" to it["state"]!!.str, "text" to it["text"]?.str)
                    },
                )
            }
        }
        base["body"] = body
        return js(base).jsonObject
    }

    private fun wireConv(c: JsonObject): JsonObject {
        val maxSeq = c["maxSeq"]!!.jsonPrimitive.long
        val base = conv(c["convId"]!!.str, maxSeq = maxSeq, readSeq = maxSeq, modVer = c["modVer"]?.jsonPrimitive?.long)
        val mu = c["mentionUnread"]?.jsonPrimitive?.long ?: return base
        val extra = linkedMapOf<String, Any?>("mentionUnread" to mu)
        if (mu > 0) extra["firstMentionSeq"] = maxSeq
        return JsonObject(base + extra.mapValues { js(it.value) })
    }

    /** SDK 看到的消息，按用例的写法比较。删除的消息只比较 seq、type、deleted、recalled、recalledBy，正文必须已清空。 */
    private fun view(m: Message): JsonObject {
        val out = linkedMapOf<String, Any?>("seq" to m.seq, "type" to m.type.wire)
        if (m.deleted) {
            assertEquals(MsgBody(), m.body, "seq ${m.seq} body")
            out["deleted"] = true
            if (m.recalled) out["recalled"] = true
            m.recalledBy?.let { out["recalledBy"] = it }
            return js(out).jsonObject
        }
        val f = m.body.image?.let { it.fileId to it.expired } ?: m.body.file?.let { it.fileId to it.expired }
        if (m.type == MsgType.IMAGE || m.type == MsgType.FILE) out["fileId"] = f?.first ?: ""
        if (m.type == MsgType.FILE) out["name"] = m.body.file?.name ?: ""
        if (f?.second == true) out["expired"] = true
        m.body.text?.let { out["text"] = it }
        m.body.quote?.let { q -> out["quote"] = js(listOfNotNull("seq" to q.seq, "state" to q.state.wire, q.text?.let { "text" to it }).toMap()) }
        return js(out).jsonObject
    }

    /** 用例里的消息去掉缺省的 expired=false，和 view 的写法一致。 */
    private fun normalize(m: JsonObject): JsonObject =
        if (m["expired"]?.jsonPrimitive?.boolean == false) JsonObject(m - "expired") else m

    private inner class Runner(val h: Harness) {
        private var c: FakeConnection? = null
        private val answered = HashSet<String>()
        private var events0 = 0

        /** 这一步里 SDK 发过的 convs 次数（event、advance）。 */
        var fetches = 0

        /** 回复所有还没回的 sync，直到 SDK 不再发。返回这期间发过 sync 的会话（排好序）。 */
        private fun drain(reply: (String) -> Any): List<String> {
            val synced = ArrayList<String>()
            repeat(50) {
                val open = c!!.frames("sync").filter { it["id"]!!.str !in answered }
                if (open.isEmpty()) return synced.sorted()
                for (f in open) {
                    val id = f["id"]!!.str
                    answered.add(id)
                    val convId = f.dataObj["convId"]!!.str
                    synced.add(convId)
                    when (val r = reply(convId)) {
                        is Int -> c!!.receive(obj("id" to id, "cmd" to "ack", "error" to obj("code" to r, "msg" to "error")))
                        else -> {
                            @Suppress("UNCHECKED_CAST")
                            val msgs = (r as List<JsonObject>).map { wire(convId, it) }
                            c!!.receive(obj("id" to id, "cmd" to "ack", "data" to obj("messages" to msgs, "hasMore" to false)))
                        }
                    }
                }
                h.flush()
            }
            fail("SDK 一直在发 sync")
        }

        /** 回复这一步里 SDK 为提及计数发出的 convs（F-MEN-03 规则 1）。返回这一步发了几次。 */
        private fun answerConvs(st: JsonObject): Int {
            var n = 0
            repeat(10) {
                val open = c!!.frames("convs").filter { it["id"]!!.str !in answered }
                if (open.isEmpty()) return n
                for (f in open) {
                    val id = f["id"]!!.str
                    answered.add(id)
                    n++
                    val convs = st["convs"] as? JsonArray ?: fail("step ${st["op"]} has no convs reply but the SDK fetched convs")
                    c!!.receive(obj("id" to id, "cmd" to "ack", "data" to obj("convs" to convs.map { wireConv(it.jsonObject) }, "truncated" to false)))
                }
                h.flush()
            }
            fail("SDK 一直在发 convs")
        }

        private fun list(a: Any?): List<JsonObject> = (a as? JsonArray)?.map { it.jsonObject } ?: emptyList()

        fun step(st: JsonObject): List<String>? {
            events0 = h.events.size
            fetches = 0
            return when (val op = st["op"]!!.str) {
                "connect" -> {
                    val prev = c
                    if (prev != null) h.clock.advance(1_000) else h.connect("token-value") // 退避后重连
                    c = h.login(self, list(st["convs"]).map { wireConv(it) })
                    if (prev != null) assertNotSame(prev, c, "重连应该是新连接")
                    // login 已经回了这条连接的 convs。
                    for (f in c!!.frames("convs")) answered.add(f["id"]!!.str)
                    val errors = st["errors"] as? JsonObject
                    val replies = st["replies"] as? JsonObject
                    drain { id -> errors?.get(id)?.jsonPrimitive?.int ?: list(replies?.get(id)) }
                }
                "disconnect" -> {
                    c!!.drop(1006)
                    null
                }
                "load" -> {
                    h.start { loadHistory(st["convId"]!!.str) }
                    val messages = list(st["messages"])
                    drain { messages }
                    null
                }
                "event" -> {
                    c!!.receive(obj("cmd" to "event", "data" to st["event"]!!))
                    fetches = answerConvs(st)
                    drain { emptyList<JsonObject>() }
                }
                "advance" -> {
                    h.clock.advance(st["ms"]!!.jsonPrimitive.long)
                    fetches = answerConvs(st)
                    drain { emptyList<JsonObject>() }
                }
                else -> fail("unknown op $op")
            }
        }

        fun filesExpired(): List<String> =
            h.events.drop(events0).filterIsInstance<IMEvent.FilesExpired>().flatMap { it.fileIds }.sorted()
    }

    /** 用例名带 TC 编号：C4 的用例是 TC-CHAT-90-33 加各自的 TC（docs/testcases），C2 的是 TC-CHAT-90-13。 */
    private val tcOfAc = mapOf(
        "AC-FILE-06-12" to "TC-FILE-06-11",
        "AC-RCL-03-11" to "TC-RCL-03-11",
        "AC-RCL-03-13" to "TC-RCL-03-13",
        "AC-QUO-02-2" to "TC-QUO-02-02",
        "AC-QUO-02-6" to "TC-QUO-02-06",
        "AC-MEN-03-7" to "TC-MEN-03-07",
    )

    private fun tcOf(acs: List<String>): String {
        val c4 = acs.any { it.startsWith("AC-QUO-") || it.startsWith("AC-MEN-") || it == "AC-RCL-03-13" }
        return (listOf(if (c4) "TC-CHAT-90-33" else "TC-CHAT-90-13") + acs.map { tcOfAc[it] ?: it }).joinToString(" ")
    }

    @Test
    fun `TC-CHAT-90-13 chat-sync 用例集覆盖了 modVer 变化、不变、老服务端、重拉失败、没加载过和迟到的旧应答`() {
        val cases = spec["cases"]!!.jsonArray
        assertTrue(cases.size >= 6)
        val ops = cases.flatMap { c -> c.jsonObject["steps"]!!.jsonArray.map { it.jsonObject["op"]!!.str } }.toSet()
        assertTrue(ops.containsAll(setOf("connect", "disconnect", "load")), "$ops")
    }

    @Test
    fun `TC-CHAT-90-33 C4 的用例都在（BE-478）：撤回合并、引用清除、提及计数，用到 event 和 advance`() {
        val cases = spec["cases"]!!.jsonArray.map { it.jsonObject }
        val acs = cases.flatMap { c -> c["acs"]!!.jsonArray.map { it.str } }
        for (ac in listOf("AC-RCL-03-13", "AC-QUO-02-2", "AC-QUO-02-6", "AC-MEN-03-7")) assertTrue(ac in acs, ac)
        assertEquals(4, cases.count { c -> c["steps"]!!.jsonArray.any { it.jsonObject["op"]!!.str in setOf("event", "advance") } })
        assertEquals(13, cases.size) // C2 7 个 + C4 6 个
        assertEquals(setOf("connect", "disconnect", "load", "event", "advance"), cases.flatMap { c -> c["steps"]!!.jsonArray.map { it.jsonObject["op"]!!.str } }.toSet())
    }

    private val marks = listOf("ZXQ-FILE-NAME", "ZXQ-RCL-2", "ZXQ-QUO-1")

    private fun run(c: JsonObject, dir: File?) {
        val h = setup(timings = IMClientTimings(heartbeatMs = 10 * 365 * 24 * 3_600_000L), database = dir?.let { tempDb(it) })
        val r = Runner(h)
        val steps = c["steps"]!!.jsonArray.map { it.jsonObject }
        for ((i, st) in steps.withIndex()) {
            val syncs = r.step(st)
            val e = st["expect"] as? JsonObject ?: continue
            val op = st["op"]!!.str
            val at = "step $i $op"
            val wantSyncs = e["syncs"]?.jsonArray?.map { it.str }
            if (wantSyncs != null) assertEquals(wantSyncs, syncs, "$at syncs")
            else if (op == "event" || op == "advance") assertEquals(emptyList(), syncs, "$at syncs")
            assertEquals(e["filesExpired"]!!.jsonArray.map { it.str }, r.filesExpired(), "$at filesExpired")
            for ((convId, want) in e["messages"]!!.jsonObject) {
                assertEquals(want.jsonArray.map { normalize(it.jsonObject) }, h.im.messages(convId).map { view(it) }, "$at $convId")
            }
            e["convsFetches"]?.let { assertEquals(it.jsonPrimitive.int, r.fetches, "$at convsFetches") }
            (e["mentionUnread"] as? JsonObject)?.let { want ->
                val got = h.im.conversations.value.filter { it.mentionUnread != null }.associate { it.convId to it.mentionUnread!! }
                assertEquals(want.mapValues { it.value.jsonPrimitive.long }, got, "$at mentionUnread")
            }
        }
        // 过期的文件名、撤回的原文、引用的原文不留在内存和本地库里（F-FILE-06 规则 3、F-RCL-03 规则 3、F-QUO-02 规则 4）：
        // 最后一步期望里没有的特征串，SDK 的内存里没有，数据库文件里也没有。
        val last = steps.last { it["expect"] != null }["expect"]!!.toString()
        for (mark in marks) {
            if (mark in last) continue
            assertFalse(holds(storeOf(h.im), mark), "$mark 还留在内存里")
            if (dir != null) {
                h.call { close() }
                assertEquals(emptyList(), filesContaining(dir, mark), "$mark 还留在本地库文件里")
            }
        }
    }

    @TestFactory
    fun `chat-sync json（F-RCL-03 规则 2、2a：单调合并、modVer 重拉；F-QUO-02 规则 4；F-MEN-03 规则 1）`(): List<DynamicTest> =
        spec["cases"]!!.jsonArray.flatMap { raw ->
            val c = raw.jsonObject
            val name = "${tcOf(c["acs"]!!.jsonArray.map { it.str })} ${c["name"]!!.str}（${c["rule"]!!.str}）"
            listOf(
                dynamicTest("$name [内存]") { run(c, null) },
                dynamicTest("$name [SQLite]") {
                    val dir = kotlin.io.path.createTempDirectory("chat-sync-").toFile()
                    try {
                        run(c, dir)
                    } finally {
                        dir.deleteRecursively()
                    }
                },
            )
        }
}
