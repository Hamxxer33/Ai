package io.kestrel.research.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import io.kestrel.engine.store.SqlDb
import io.kestrel.engine.store.SqlRow

/**
 * SqlDb over AndroidX's bundled SQLite: the same SQLite build on every phone, with FTS5
 * (the platform SQLite does not guarantee FTS5).
 */
class AndroidSqlDb private constructor(private val conn: SQLiteConnection) : SqlDb {
    private val lock = Any()

    override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> = synchronized(lock) {
        conn.prepare(sql).use { st ->
            bind(st, args)
            val row = Row(st)
            val out = ArrayList<T>()
            while (st.step()) out += map(row)
            out
        }
    }

    override fun execute(sql: String, args: List<Any?>) = synchronized(lock) {
        conn.prepare(sql).use { st ->
            bind(st, args)
            while (st.step()) { /* drain */ }
        }
    }

    override fun close() = synchronized(lock) { conn.close() }

    private fun bind(st: SQLiteStatement, args: List<Any?>) {
        args.forEachIndexed { i, a ->
            val idx = i + 1
            when (a) {
                null -> st.bindNull(idx)
                is Long -> st.bindLong(idx, a)
                is Int -> st.bindLong(idx, a.toLong())
                is Double -> st.bindDouble(idx, a)
                is Float -> st.bindDouble(idx, a.toDouble())
                is ByteArray -> st.bindBlob(idx, a)
                is Boolean -> st.bindLong(idx, if (a) 1L else 0L)
                else -> st.bindText(idx, a.toString())
            }
        }
    }

    private class Row(private val st: SQLiteStatement) : SqlRow {
        override fun long(i: Int) = st.getLong(i)
        override fun double(i: Int) = st.getDouble(i)
        override fun string(i: Int): String? = if (st.isNull(i)) null else st.getText(i)
        override fun blob(i: Int): ByteArray? = if (st.isNull(i)) null else st.getBlob(i)
        override fun isNull(i: Int) = st.isNull(i)
    }

    companion object {
        fun openReadOnly(path: String): AndroidSqlDb =
            AndroidSqlDb(BundledSQLiteDriver().open(path, SQLITE_OPEN_READONLY or SQLITE_OPEN_FULLMUTEX))
    }
}
