package com.tianting.sdk.android

import android.content.Context
import android.util.AtomicFile
import com.tianting.sdk.PushCursorState
import com.tianting.sdk.PushCursorStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * 推送游标的文件实现（PRD-live 4.9 节）：应用私有的 no-backup 目录，每个账号一个 JSON 文件，只有游标和最近的 pushId，不含通知内容。
 * 不在本地库里，退出、被踢、库重建、Schema 升级都不清；卸载、清应用数据才没有；no-backup 目录不随备份恢复到新设备。
 * 文件名是 userId 的 SHA-256（userId 不进路径）。AtomicFile 先写副本再替换，写到一半进程退出时还是旧内容；内容坏了按没有游标处理。
 */
public class AndroidPushCursorStore(private val dir: File) : PushCursorStore {
    public constructor(context: Context) : this(File(context.applicationContext.noBackupFilesDir, "tianting-push"))

    private fun file(userId: String): AtomicFile {
        val name = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(dir, "$name.json"))
    }

    override fun load(userId: String): PushCursorState? {
        val f = file(userId)
        if (!f.baseFile.exists()) return null
        return try {
            val o = Json.parseToJsonElement(String(f.readFully(), Charsets.UTF_8)) as JsonObject
            val cursor = o.getValue("cursor").jsonPrimitive.long
            if (cursor < 0) return null
            PushCursorState(cursor, (o.getValue("recentIds") as JsonArray).map { it.jsonPrimitive.content })
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: NoSuchElementException) {
            null
        } catch (_: ClassCastException) {
            null
        }
    }

    override fun save(userId: String, cursor: Long, recentIds: List<String>) {
        dir.mkdirs()
        val f = file(userId)
        val text = buildJsonObject {
            put("cursor", cursor)
            put("recentIds", JsonArray(recentIds.map { JsonPrimitive(it) }))
        }.toString()
        val out = f.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: java.io.IOException) {
            f.failWrite(out)
            throw e
        }
    }
}
