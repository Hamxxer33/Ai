package io.kestrel.engine.store

import java.io.Closeable
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** Row accessor; column indices are 0-based. */
interface SqlRow {
    fun long(i: Int): Long
    fun double(i: Int): Double
    fun string(i: Int): String?
    fun blob(i: Int): ByteArray?
    fun isNull(i: Int): Boolean
}

/**
 * The few SQLite operations the engine needs. Android implements it over the bundled SQLite
 * driver (FTS5 guaranteed), the desktop over sqlite-jdbc. Implementations must be safe to call
 * from one thread at a time; [interrupt] may be called from any thread.
 */
interface SqlDb : Closeable {
    fun <T> query(sql: String, args: List<Any?> = emptyList(), map: (SqlRow) -> T): List<T>
    fun execute(sql: String, args: List<Any?> = emptyList())
    fun interrupt() {}
}

/** JDBC implementation (desktop CLI, tests). Needs org.xerial:sqlite-jdbc on the classpath. */
class JdbcSqlDb(private val conn: Connection) : SqlDb {
    @Volatile private var current: java.sql.Statement? = null

    override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> {
        conn.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            current = st
            try {
                st.executeQuery().use { rs ->
                    val row = JdbcRow(rs)
                    val out = ArrayList<T>()
                    while (rs.next()) out += map(row)
                    return out
                }
            } finally {
                current = null
            }
        }
    }

    override fun execute(sql: String, args: List<Any?>) {
        conn.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.execute()
        }
    }

    override fun interrupt() {
        runCatching { current?.cancel() }
    }

    override fun close() = conn.close()

    private class JdbcRow(private val rs: ResultSet) : SqlRow {
        override fun long(i: Int) = rs.getLong(i + 1)
        override fun double(i: Int) = rs.getDouble(i + 1)
        override fun string(i: Int): String? = rs.getString(i + 1)
        override fun blob(i: Int): ByteArray? = rs.getBytes(i + 1)
        override fun isNull(i: Int): Boolean { rs.getObject(i + 1); return rs.wasNull() }
    }

    companion object {
        /** Opens a SQLite file read-only (requires the sqlite-jdbc driver). */
        fun openReadOnly(path: String): JdbcSqlDb {
            Class.forName("org.sqlite.JDBC")
            val props = java.util.Properties().apply {
                setProperty("open_mode", "1") // SQLITE_OPEN_READONLY
                setProperty("cache_size", "-65536")
                setProperty("mmap_size", "268435456")
            }
            return JdbcSqlDb(DriverManager.getConnection("jdbc:sqlite:file:$path?immutable=1", props))
        }
    }
}
