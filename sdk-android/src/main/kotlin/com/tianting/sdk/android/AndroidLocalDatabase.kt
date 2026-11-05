package com.tianting.sdk.android

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.tianting.sdk.LocalDatabase
import java.io.File

/**
 * LocalDatabase 的 Android 实现：系统自带的 SQLite。SQL、表结构、事务边界都在 sdk-core 的 LocalDb，这里只做接口转换。
 * 关闭 WAL：Android 9 起未显式配置的数据库可能走 compatibility WAL，而原文不能留在 -wal 文件里（AC-ADM-31-4）。
 */
public class AndroidLocalDatabase(private val db: SQLiteDatabase) : LocalDatabase {
    init {
        db.disableWriteAheadLogging()
    }

    private fun bindArgs(args: List<Any?>): Array<Any?> = args.map { if (it is Int) it.toLong() else it }.toTypedArray()

    override fun execute(sql: String, args: List<Any?>) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, bindArgs(args))
    }

    override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> {
        // rawQuery 只接受字符串参数；数字作为文本绑定时 SQLite 按列亲和性比较，SDK 的查询参数只有 convId 这样的文本和 seq。
        db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c ->
            val out = ArrayList<Map<String, Any?>>()
            while (c.moveToNext()) {
                val row = LinkedHashMap<String, Any?>()
                for (i in 0 until c.columnCount) {
                    row[c.getColumnName(i)] = when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                        else -> c.getString(i)
                    }
                }
                out.add(row)
            }
            return out
        }
    }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    override fun close() {
        db.close()
    }

    public companion object {
        /** 打开（或创建）应用私有目录下的数据库文件。不要放到外部存储。 */
        public fun open(context: Context, name: String = "tianting-sdk.db"): AndroidLocalDatabase =
            open(context.getDatabasePath(name).also { it.parentFile?.mkdirs() })

        public fun open(file: File): AndroidLocalDatabase = AndroidLocalDatabase(SQLiteDatabase.openOrCreateDatabase(file, null))
    }
}
