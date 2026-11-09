// TC-CHAT-90-13（AC-CHAT-90-15）、TC-FILE-06-11（AC-FILE-06-12）：共享用例 protocol/vectors/chat-sync.json（PRD-chat F-RCL-03 规则 2、2a）。
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.test.fail

class ChatSyncVectorsTest : SdkTest() {
    private val spec get() = vector("chat-sync.json").jsonObject
    private val self get() = spec["self"]!!.str

    /** 按 format 展开成下行消息。 */
    private fun wire(convId: String, m: JsonObject): JsonObject {
        val seq = m["seq"]!!.jsonPrimitive.long
        val type = m["type"]!!.str
        val expired = m["expired"]?.jsonPrimitive?.boolean == true
        val fileId = m["fileId"]!!.str
        val body = if (type == "image") {
            obj("image" to objNoNull("fileId" to fileId, "width" to 10, "height" to 10, "size" to 100, "mime" to "image/png", "expired" to if (expired) true else null))
        } else {
            obj("file" to objNoNull("fileId" to fileId, "name" to (m["name"]?.str ?: ""), "size" to 100, "mime" to "application/pdf", "expired" to if (expired) true else null))
        }
        return obj(
            "msgId" to "$convId#$seq", "clientMsgId" to "bob-$convId-$seq", "convId" to convId, "seq" to seq,
            "from" to "bob", "type" to type, "body" to body, "ts" to 1000 + seq,
        )
    }

    private fun wireConv(c: JsonObject): JsonObject {
        val maxSeq = c["maxSeq"]!!.jsonPrimitive.long
        return conv(c["convId"]!!.str, maxSeq = maxSeq, readSeq = maxSeq, modVer = c["modVer"]?.jsonPrimitive?.long)
    }

    /** SDK 看到的消息，按用例的写法比较。 */
    private fun view(m: Message): JsonObject {
        val fileId = m.body.image?.fileId ?: m.body.file?.fileId ?: ""
        val expired = m.body.image?.expired == true || m.body.file?.expired == true
        val out = linkedMapOf<String, Any?>("seq" to m.seq, "type" to m.type.wire, "fileId" to fileId)
        if (m.type == MsgType.FILE) out["name"] = m.body.file?.name ?: ""
        if (expired) out["expired"] = true
        return js(out).jsonObject
    }

    /** 用例里的消息去掉缺省的 expired=false，和 view 的写法一致。 */
    private fun normalize(m: JsonObject): JsonObject =
        if (m["expired"]?.jsonPrimitive?.boolean == false) JsonObject(m - "expired") else m

    private inner class Runner(val h: Harness) {
        private var c: FakeConnection? = null
        private val answered = HashSet<String>()
        private var events0 = 0

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

        private fun list(a: Any?): List<JsonObject> = (a as? JsonArray)?.map { it.jsonObject } ?: emptyList()

        fun step(st: JsonObject): List<String>? {
            events0 = h.events.size
            return when (val op = st["op"]!!.str) {
                "connect" -> {
                    val prev = c
                    if (prev != null) h.clock.advance(1_000) else h.connect("token-value") // 退避后重连
                    c = h.login(self, list(st["convs"]).map { wireConv(it) })
                    if (prev != null) assertNotSame(prev, c, "重连应该是新连接")
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
                else -> fail("unknown op $op")
            }
        }

        fun filesExpired(): List<String> =
            h.events.drop(events0).filterIsInstance<IMEvent.FilesExpired>().flatMap { it.fileIds }.sorted()
    }

    @Test
    fun `TC-CHAT-90-13 chat-sync 用例集覆盖了 modVer 变化、不变、老服务端、重拉失败、没加载过和迟到的旧应答`() {
        val cases = spec["cases"]!!.jsonArray
        assertTrue(cases.size >= 6)
        val ops = cases.flatMap { c -> c.jsonObject["steps"]!!.jsonArray.map { it.jsonObject["op"]!!.str } }.toSet()
        assertEquals(setOf("connect", "disconnect", "load"), ops)
    }

    @TestFactory
    fun `TC-CHAT-90-13 chat-sync json（F-RCL-03 规则 2、2a：expired 单调合并、modVer 重拉）`(): List<DynamicTest> = spec["cases"]!!.jsonArray.map { raw ->
        val c = raw.jsonObject
        dynamicTest(c["name"]!!.str) {
            val h = setup(timings = IMClientTimings(heartbeatMs = 10 * 365 * 24 * 3_600_000L))
            val r = Runner(h)
            val steps = c["steps"]!!.jsonArray.map { it.jsonObject }
            for ((i, st) in steps.withIndex()) {
                val syncs = r.step(st)
                val e = st["expect"] as? JsonObject ?: continue
                val at = "step $i ${st["op"]!!.str}"
                e["syncs"]?.let { want -> assertEquals(want.jsonArray.map { it.str }, syncs, "$at syncs") }
                assertEquals(e["filesExpired"]!!.jsonArray.map { it.str }, r.filesExpired(), "$at filesExpired")
                for ((convId, want) in e["messages"]!!.jsonObject) {
                    assertEquals(want.jsonArray.map { normalize(it.jsonObject) }, h.im.messages(convId).map { view(it) }, "$at $convId")
                }
            }
            // 过期的文件名不留在内存里（F-FILE-06 规则 3）：最后一步期望里没有的特征串，SDK 的内存里也没有。
            val last = steps.last { it["expect"] != null }["expect"]!!
            if ("ZXQ-FILE-NAME" !in last.toString()) assertFalse(holds(storeOf(h.im), "ZXQ-FILE-NAME"), "过期的文件名还留在内存里")
        }
    }
}
