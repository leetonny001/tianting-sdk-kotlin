package com.tianting.sdk.android

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 真机 / 模拟器：系统自带的 SQLite 和 android.icu。 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidDeviceTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx get() = InstrumentationRegistry.getInstrumentation().context

    private inner class Env(file: File) {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val clock = FakeClock()
        val transport = FakeTransport { scheduler.runCurrent() }
        val im = IMClient(
            IMClientOptions(url = "ws://t/ws", database = AndroidLocalDatabase.open(file), transport = transport, clock = clock, dispatcher = dispatcher),
        )

        init {
            clock.afterEach = { scheduler.runCurrent() }
            scheduler.runCurrent()
        }

        fun close() {
            CoroutineScope(dispatcher + SupervisorJob()).launch { im.close() }
            scheduler.runCurrent()
        }
    }

    private fun filesContaining(dir: File, needle: String): List<String> {
        val n = needle.toByteArray()
        return dir.walkTopDown().filter { it.isFile }.filter { f ->
            val h = f.readBytes()
            (0..h.size - n.size).any { i -> n.indices.all { j -> h[i + j] == n[j] } }
        }.map { it.name }.toList()
    }

    private fun msg(seq: Int, text: String) =
        """{"cmd":"msg","data":{"msgId":"g#$seq","clientMsgId":"c$seq","convId":"group:g1","seq":$seq,"from":"bob","type":"text","body":{"text":"$text"},"ts":$seq}}"""

    @Test
    fun pinyinGroupsVectors() {
        val spec = Json.parseToJsonElement(testCtx.assets.open("pinyin-groups.json").bufferedReader().readText()).jsonObject
        val collator = assertNotNull(AndroidPinyin.collator(), "这台设备的 ICU 不支持拼音排序")
        val wrong = spec["cases"]!!.jsonArray.map { it.jsonObject }.filter { c ->
            pinyinGroup(c["name"]!!.jsonPrimitive.content, collator) != c["group"]!!.jsonPrimitive.content
        }.map { it["name"]!!.jsonPrimitive.content }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun msgDeletedWipesDatabaseFileAcAdm314() {
        val dir = File(ctx.cacheDir, "im-test-${System.nanoTime()}").also { it.mkdirs() }
        try {
            val secret = "ZXQ-DEL-DEVICE"
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
            e.close()
            assertEquals(emptyList(), filesContaining(dir, secret))

            val e2 = Env(file)
            val list = e2.im.messages("group:g1")
            assertEquals(listOf(1L, 2L, 3L), list.map { it.seq })
            assertEquals(MsgBody(), list[1].body)
            e2.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun reportsDeviceSqliteSettings() {
        val dir = File(ctx.cacheDir, "im-test-${System.nanoTime()}").also { it.mkdirs() }
        try {
            val db = AndroidLocalDatabase.open(File(dir, "x.db"))
            val secure = db.query("PRAGMA secure_delete=ON").first().values.first()
            val journal = db.query("PRAGMA journal_mode=DELETE").first().values.first()
            android.util.Log.i("TiantingSdkTest", "secure_delete=$secure journal_mode=$journal sqlite=${db.query("select sqlite_version() v").first()}")
            assertEquals(1L, secure)
            assertEquals("delete", journal.toString().lowercase())
            db.close()
            SQLiteDatabase.deleteDatabase(File(dir, "x.db"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
