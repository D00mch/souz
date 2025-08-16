package com.dumch.db

import com.dumch.tool.config.ConfigStore
import org.sqlite.SQLiteConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.Connection
import java.sql.DriverManager

/**
 * Handles initialization of SQLite database with sqlite-vec extension and
 * stores/retrieves user desktop data embeddings.
 */
object RagDatabase {
    private const val DB_PATH = "build/rag.db"
    private const val INIT_KEY = "rag_db_initialized"

    /** Run migrations and load sqlite-vec extension once. */
    fun initializeOnce() {
        if (ConfigStore.get(INIT_KEY, false)) return
        getConnection().use { conn ->
            loadExtension(conn)
            runMigrations(conn)
        }
        ConfigStore.put(INIT_KEY, true)
    }

    /** Store a batch of texts and their embeddings. */
    fun insert(data: List<String>, embeddings: List<List<Double>>) {
        getConnection().use { conn ->
            loadExtension(conn)
            insertBatch(conn, data, embeddings)
        }
    }

    /** Obtain all stored desktop data texts. */
    fun getAllTexts(): List<String> {
        getConnection().use { conn ->
            loadExtension(conn)
            conn.createStatement().use { st ->
                val rs = st.executeQuery("SELECT text FROM user_desktop_data")
                val list = mutableListOf<String>()
                while (rs.next()) list.add(rs.getString("text"))
                return list
            }
        }
    }

    /**
     * Return texts from the database ordered by similarity to the provided embedding.
     * Uses sqlite-vec's `MATCH` operator which exposes a `distance` column for sorting.
     */
    fun searchSimilar(embedding: List<Double>, limit: Int = 5): List<String> {
        getConnection().use { conn ->
            loadExtension(conn)
            conn.prepareStatement(
                "SELECT text FROM user_desktop_data WHERE embedding MATCH ? ORDER BY distance LIMIT ?"
            ).use { ps ->
                ps.setBytes(1, doublesToBlob(embedding))
                ps.setInt(2, limit)
                val rs = ps.executeQuery()
                val list = mutableListOf<String>()
                while (rs.next()) list.add(rs.getString("text"))
                return list
            }
        }
    }

    private fun getConnection(): Connection {
        Class.forName("org.sqlite.JDBC")
        val config = SQLiteConfig().apply { enableLoadExtension(true) }
        return DriverManager.getConnection("jdbc:sqlite:$DB_PATH", config.toProperties())
    }

    private fun loadExtension(conn: Connection) {
        val path = System.getenv("SQLITE_VEC_PATH") ?: "sqlite-vec"
        conn.createStatement().use { it.execute("SELECT load_extension('$path')") }
    }

    private fun runMigrations(conn: Connection) {
        val sql = RagDatabase::class.java.getResource("/migrations/001_user_desktop_data.sql")
            ?.readText() ?: throw IllegalStateException("Migration file not found")
        conn.createStatement().use { it.executeUpdate(sql) }
    }

    private fun insertBatch(conn: Connection, data: List<String>, embeddings: List<List<Double>>) {
        conn.prepareStatement("INSERT INTO user_desktop_data(text, embedding) VALUES(?, ?)").use { ps ->
            data.indices.forEach { idx ->
                ps.setString(1, data[idx])
                ps.setBytes(2, doublesToBlob(embeddings[idx]))
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun doublesToBlob(list: List<Double>): ByteArray {
        val buf = ByteBuffer.allocate(list.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        list.forEach { buf.putFloat(it.toFloat()) }
        return buf.array()
    }
}
