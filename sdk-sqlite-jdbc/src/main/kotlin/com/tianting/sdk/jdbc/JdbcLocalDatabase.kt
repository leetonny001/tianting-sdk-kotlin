package com.tianting.sdk.jdbc

import com.tianting.sdk.LocalDatabase
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Types

/** LocalDatabase 的 JVM 实现（xerial sqlite-jdbc，内置原生 SQLite）。Android 上用 sdk-android 的 AndroidLocalDatabase。 */
public class JdbcLocalDatabase(file: File) : LocalDatabase {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    private var depth = 0

    private fun bind(st: PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { i, v ->
            when (v) {
                null -> st.setNull(i + 1, Types.NULL)
                is Long -> st.setLong(i + 1, v)
                is Int -> st.setLong(i + 1, v.toLong())
                is String -> st.setString(i + 1, v)
                else -> throw IllegalArgumentException("unsupported bind type ${v.javaClass.simpleName}")
            }
        }
    }

    override fun execute(sql: String, args: List<Any?>) {
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            st.execute()
        }
    }

    override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> =
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            val hasRows = st.execute()
            if (!hasRows) return@use emptyList()
            st.resultSet.use { rs ->
                val meta = rs.metaData
                val out = ArrayList<Map<String, Any?>>()
                while (rs.next()) {
                    val row = LinkedHashMap<String, Any?>()
                    for (c in 1..meta.columnCount) {
                        row[meta.getColumnLabel(c)] = when (val v = rs.getObject(c)) {
                            is Int -> v.toLong()
                            else -> v
                        }
                    }
                    out.add(row)
                }
                out
            }
        }

    override fun <T> transaction(block: () -> T): T {
        if (depth == 0) conn.autoCommit = false
        depth++
        var ok = false
        try {
            val r = block()
            ok = true
            return r
        } finally {
            depth--
            if (depth == 0) {
                if (ok) conn.commit() else conn.rollback()
                conn.autoCommit = true
            } else if (!ok) {
                // 内层失败：异常继续向外抛，由最外层回滚。
            }
        }
    }

    override fun close() {
        conn.close()
    }
}
