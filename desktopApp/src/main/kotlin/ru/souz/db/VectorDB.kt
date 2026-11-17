package ru.souz.db

import java.nio.ByteOrder
import java.nio.file.Path
import java.util.PriorityQueue
import org.slf4j.LoggerFactory
import ru.souz.memory.cosineSimilarityDouble
import ru.souz.memory.toBlob
import ru.souz.memory.toFloatArray
import ru.souz.memory.vectorNorm

/** Desktop SQLite vectors. Search scans stored vectors and retains only the best [searchSimilar] limit. */
class VectorDB(dbPath: Path) {
    private val store = DesktopSqliteStore(dbPath)
    private val logger = LoggerFactory.getLogger(javaClass)

    fun insert(data: List<StorredData>, embeddings: List<List<Double>>) {
        require(data.size == embeddings.size) { "Each document needs an embedding" }
        if (data.isEmpty()) return
        val vectors = embeddings.map(::toVector)
        require(vectors.all { it.size == vectors.first().size }) { "Embedding dimensions must match" }
        store.withConnection { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use {
                    it.executeQuery("select dimension from desktop_vectors limit 1").use { rows ->
                        require(!rows.next() || rows.getInt(1) == vectors.first().size) { "Embedding dimensions must match" }
                    }
                }
                connection.prepareStatement(
                    "insert into desktop_vectors(text, type, dimension, embedding) values (?, ?, ?, ?)"
                ).use { statement ->
                    data.forEachIndexed { index, item ->
                        statement.setString(1, item.text)
                        statement.setString(2, item.type.name)
                        statement.setInt(3, vectors[index].size)
                        statement.setBytes(4, vectors[index].toBlob(ByteOrder.BIG_ENDIAN))
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.commit()
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        }
    }

    fun clearAllData() {
        store.withConnection { connection ->
            connection.createStatement().use { it.executeUpdate("delete from desktop_vectors") }
        }
    }

    fun searchSimilar(embedding: List<Double>, limit: Int = 5, minScore: Float = 0.65f): List<StorredData> {
        if (limit <= 0) return emptyList()
        return try {
            val query = toVector(embedding)
            val queryNorm = vectorNorm(query)
            val best = PriorityQueue(compareBy<Hit> { it.score }.thenByDescending { it.id })
            store.withConnection { connection ->
                connection.prepareStatement("select id, text, type, embedding from desktop_vectors where dimension = ?").use {
                    it.setInt(1, query.size)
                    it.executeQuery().use { rows ->
                        while (rows.next()) {
                            val vector = rows.getBytes(4).toFloatArray(ByteOrder.BIG_ENDIAN)
                            // Preserve Lucene COSINE's nonnegative score and threshold scale.
                            val score = ((1.0 + cosineSimilarityDouble(query, vector, queryNorm)) / 2.0).coerceIn(0.0, 1.0).toFloat()
                            if (score < minScore) continue
                            best.add(Hit(rows.getLong(1), score, StorredData(rows.getString(2), StorredType.valueOf(rows.getString(3)))))
                            if (best.size > limit) best.poll()
                        }
                    }
                }
            }
            best.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.id }).map { it.data }
        } catch (error: Exception) {
            logger.warn("Error searching vector db", error)
            emptyList()
        }
    }

    private fun toVector(values: List<Double>): FloatArray {
        val vector = values.take(1024).map(Double::toFloat).toFloatArray()
        require(vector.isNotEmpty() && vector.all { it.isFinite() } && vectorNorm(vector) > 0) { "Invalid embedding" }
        return vector
    }

    private data class Hit(val id: Long, val score: Float, val data: StorredData)
}
