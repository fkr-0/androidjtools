package dev.androidjtools.sync.samplelib

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/** In-memory SQLite used only by JVM tests so the production SQL runs against a real engine. */
class JdbcSqlDatabase : SqlDatabase, AutoCloseable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    override fun <T> transaction(block: (SqlConnection) -> T): T = synchronized(this) {
        connection.autoCommit = false
        try {
            val result = block(JdbcConnection(connection))
            connection.commit()
            result
        } catch (t: Throwable) {
            connection.rollback()
            throw t
        } finally {
            connection.autoCommit = true
        }
    }

    override fun close() = connection.close()
}

private class JdbcConnection(private val connection: Connection) : SqlConnection {
    override fun execute(sql: String, vararg args: Any?) {
        connection.prepareStatement(sql).use { statement ->
            bind(statement, args)
            statement.executeUpdate()
        }
    }

    override fun <T> query(sql: String, vararg args: Any?, map: (SqlRow) -> T): List<T> =
        connection.prepareStatement(sql).use { statement ->
            bind(statement, args)
            statement.executeQuery().use { rs ->
                buildList { while (rs.next()) add(map(JdbcRow(rs))) }
            }
        }

    private fun bind(statement: PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
    }
}

private class JdbcRow(private val rs: ResultSet) : SqlRow {
    override fun string(index: Int): String? = rs.getString(index + 1)
    override fun long(index: Int): Long = rs.getLong(index + 1)
}
