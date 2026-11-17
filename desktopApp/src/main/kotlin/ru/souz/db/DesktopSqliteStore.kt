package ru.souz.db

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Short-lived JDBC connections keep ownership at the synchronous desktop persistence boundary. */
internal class DesktopSqliteStore(private val dbPath: Path) {
    fun <T> withConnection(block: (Connection) -> T): T {
        Files.createDirectories(dbPath.toAbsolutePath().parent)
        return DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.createStatement().use {
                it.execute("pragma busy_timeout = 5000")
                it.execute("pragma journal_mode = WAL")
                it.execute("""
                    create table if not exists desktop_vectors (
                        id integer primary key, text text not null, type text not null,
                        dimension integer not null, embedding blob not null
                    )
                """.trimIndent())
                it.execute("""
                    create table if not exists graph_sessions (
                        id text primary key, start_time integer not null,
                        saved_at integer not null, payload text not null
                    )
                """.trimIndent())
                it.execute("create index if not exists graph_sessions_saved_at on graph_sessions(saved_at)")
            }
            block(connection)
        }
    }
}
