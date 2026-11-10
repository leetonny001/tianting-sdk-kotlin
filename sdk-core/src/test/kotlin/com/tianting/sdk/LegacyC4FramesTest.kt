// TC-CHAT-90-26（AC-CHAT-90-7，老版本兼容）：C4 的新帧和新字段交给 SDK。只用 C4 之前就有的接口，
// 同一个文件也放进 `git archive 2819ce6`（C4 之前的 sdk-kotlin）解出的目录里跑过（FE-661 第 5 条）。本地单测结果，CI 不运行。
package com.tianting.sdk

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyC4FramesTest : SdkTest() {
    @TempDir
    lateinit var dir: File

    private val g = "group:g1"
    private val secret = "ZXQ-RCL-2"

    private fun legacyMsg(seq: Long, from: String, body: JsonObject, type: String = "text"): JsonObject = obj(
        "msgId" to "g1-$seq", "clientMsgId" to "$from-$seq", "convId" to g, "seq" to seq, "from" to from, "type" to type, "body" to body, "ts" to 1000 + seq,
    )

    private fun legacyRecalled(seq: Long): JsonObject = obj(
        "msgId" to "g1-$seq", "clientMsgId" to "bob-$seq", "convId" to g, "seq" to seq, "from" to "bob", "type" to "text", "ts" to 1000 + seq,
        "deleted" to true, "recalled" to true, "recalledAt" to 9000, "recalledBy" to "bob",
    )

    private fun legacyConv(maxSeq: Long, lastMsg: JsonObject?): JsonObject = JsonObject(
        conv(g, maxSeq = maxSeq, readSeq = 0, unread = maxSeq, lastMsg = lastMsg, myRole = "member") +
            mapOf("mentionUnread" to JsonPrimitive(2), "firstMentionSeq" to JsonPrimitive(3), "modVer" to JsonPrimitive(1)),
    )

    @Test
    fun `TC-CHAT-90-26 撤回（msgDeleted 带 recalled、sync 带 deleted+recalled）、quote、mentions、mentionUnread、lastMsg recalled：不崩溃，显示已删除，内存和本地库搜不到原文`() {
        val h = setup(database = tempDb(dir))
        h.connect()
        val c = h.login("alice", listOf(legacyConv(4, null)))
        // 已加载含特征串的消息；m2 引用 m1（带原文摘要）、@ 了我；m3 图片、m4 文件。
        val d = h.start { loadHistory(g) }
        c.ack(
            "sync",
            obj(
                "messages" to listOf(
                    legacyMsg(1, "bob", obj("text" to "$secret 原文")),
                    legacyMsg(
                        2,
                        "carol",
                        obj(
                            "text" to "@alice 回复",
                            "quote" to obj("msgId" to "g1-1", "seq" to 1, "from" to "bob", "type" to "text", "state" to "normal", "text" to "$secret 原文"),
                            "mentions" to obj("userIds" to listOf("alice")),
                        ),
                    ),
                    legacyMsg(3, "bob", obj("image" to obj("fileId" to "f1", "width" to 1, "height" to 1, "size" to 1, "mime" to "image/png")), "image"),
                    legacyMsg(4, "bob", obj("file" to obj("fileId" to "f2", "name" to "a.pdf", "size" to 1, "mime" to "application/pdf")), "file"),
                ),
                "hasMore" to false,
            ),
        )
        h.flush()
        assertTrue(d.isCompleted)
        assertTrue(filesContaining(dir, secret).isNotEmpty(), "对照：原文写进了本地库")
        // 实时：带 recalled 的 msgDeleted、带 mentions.all 和 quote state=recalled 的新消息、convAdded 带 mentionUnread。
        c.receive(event("msgDeleted", "convId" to g, "msgIds" to listOf("g1-1"), "seqs" to listOf(1), "recalled" to obj("by" to "bob", "at" to 9000)))
        c.receive(
            msgFrame(
                legacyMsg(
                    5,
                    "bob",
                    obj("text" to "@所有人", "mentions" to obj("all" to true), "quote" to obj("msgId" to "g1-1", "seq" to 1, "from" to "bob", "type" to "text", "state" to "recalled")),
                ),
            ),
        )
        c.receive(event("convAdded", "conv" to legacyConv(5, obj("msgId" to "g1-1", "from" to "bob", "type" to "text", "ts" to 1001, "deleted" to true, "recalled" to true, "recalledBy" to "bob"))))
        // 重连：convs 的 lastMsg 带 recalled，重拉时 sync 带 deleted+recalled。
        c.drop(1006)
        h.clock.advance(1_000)
        val again = JsonObject(legacyConv(5, obj("msgId" to "g1-5", "from" to "bob", "type" to "text", "text" to "@所有人", "ts" to 1005)) + mapOf("modVer" to JsonPrimitive(2)))
        val c2 = h.login("alice", listOf(again))
        for (f in c2.frames("sync")) {
            c2.receive(
                obj("id" to f["id"]!!.str, "cmd" to "ack", "data" to obj("messages" to listOf(legacyRecalled(1), legacyMsg(2, "carol", obj("text" to "@alice 回复"))), "hasMore" to false)),
            )
        }
        assertEquals(ConnState.CONNECTED, h.im.state.value)
        assertEquals(emptyList(), h.logs.filter { it.contains("unexpected") })
        val m1 = h.im.messages(g).first { it.seq == 1L }
        assertTrue(m1.deleted) // 老版本显示"该消息已被删除"
        assertEquals(MsgBody(), m1.body)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), h.im.messages(g).map { it.seq })
        assertFalse(holds(storeOf(h.im), secret), "内存里还有原文")
        h.call { close() }
        assertEquals(emptyList(), filesContaining(dir, secret), "本地库里还有原文")
    }
}
