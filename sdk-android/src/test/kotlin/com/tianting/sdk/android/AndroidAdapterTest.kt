package com.tianting.sdk.android

import com.tianting.sdk.IMClient
import com.tianting.sdk.IMClientOptions
import com.tianting.sdk.MsgBody
import com.tianting.sdk.pinyinGroup
import com.tianting.sdk.testing.FakeClock
import com.tianting.sdk.testing.FakeTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Android 适配层：Robolectric 的原生 SQLite + android.icu。真机 / 模拟器上的验证由 tianting-android 的仪器测试负责。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidAdapterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val secret = "ZXQ-DEL-ANDROID"

    private inner class Env(file: File) {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val clock = FakeClock()
        val transport = FakeTransport { scheduler.runCurrent() }
        val im = IMClient(
            IMClientOptions(url = "ws://t/ws", database = AndroidLocalDatabase.open(file), transport = transport, clock = clock, dispatcher = dispatcher),
        )
        val scope = CoroutineScope(dispatcher + SupervisorJob())

        init {
            clock.afterEach = { scheduler.runCurrent() }
            scheduler.runCurrent()
        }

        fun close() {
            scope.launch { im.close() }
            scheduler.runCurrent()
        }
    }

    private fun msg(seq: Int, text: String) =
        """{"cmd":"msg","data":{"msgId":"g#$seq","clientMsgId":"c$seq","convId":"group:g1","seq":$seq,"from":"bob","type":"text","body":{"text":"$text"},"ts":$seq}}"""

    private fun filesContaining(dir: File, needle: String): List<String> {
        val n = needle.toByteArray()
        return dir.walkTopDown().filter { it.isFile }.filter { f ->
            val h = f.readBytes()
            (0..h.size - n.size).any { i -> n.indices.all { j -> h[i + j] == n[j] } }
        }.map { it.name }.toList()
    }

    @Test
    fun `msgDeleted 后数据库文件搜不到特征串（AC-ADM-31-4）；重启后占位还在`() {
        val dir = tmp.newFolder()
        val file = File(dir, "im.db")
        val e = Env(file)
        e.im.connect("t")
        e.scheduler.runCurrent()
        val c = e.transport.current
        c.open()
        c.ack("auth", Json.parseToJsonElement("""{"userId":"alice","serverTime":1}"""))
        c.ack(
            "convs",
            Json.parseToJsonElement("""{"convs":[{"convId":"group:g1","type":"group","name":"g","maxSeq":0,"readSeq":0,"minSeq":1,"unread":0,"lastMsg":null,"updatedAt":0}],"truncated":false}"""),
        )
        c.receive(msg(1, "keep me"))
        c.receive(msg(2, "前缀 $secret 后缀"))
        c.receive(msg(3, "tail"))
        assertTrue(filesContaining(dir, secret).isNotEmpty(), "对照：删除之前能搜到")
        c.receive("""{"cmd":"event","data":{"type":"msgDeleted","convId":"group:g1","msgIds":["g#2"],"seqs":[2]}}""")
        assertEquals(emptyList(), filesContaining(dir, secret))
        assertTrue(filesContaining(dir, "keep me").isNotEmpty())
        assertEquals(emptyList(), dir.listFiles()!!.map { it.name }.filter { it.endsWith("-wal") })
        e.close()

        val e2 = Env(file)
        assertEquals("alice", e2.im.userId.value)
        val list = e2.im.messages("group:g1")
        assertEquals(listOf(1L, 2L, 3L), list.map { it.seq })
        assertEquals(true, list[1].deleted)
        assertEquals(MsgBody(), list[1].body)
        e2.close()
    }

    @Test
    fun `Robolectric 的 ICU 没有中文排序数据：collator 返回 null，汉字归井号，不崩溃`() {
        val c = AndroidPinyin.collator()
        // 真机上的用例在 androidTest（AndroidDeviceTest）。这里只验证退化路径不出错。
        assertEquals(if (c == null) "#" else "Z", pinyinGroup("张三", c))
        assertEquals("A", pinyinGroup("Alice", c))
    }
}
