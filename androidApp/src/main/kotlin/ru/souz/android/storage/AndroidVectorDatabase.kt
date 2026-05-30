package ru.souz.android.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ru.souz.db.StorredData
import ru.souz.db.StorredType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class AndroidVectorDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DB_NAME,
    null,
    DB_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE vector_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                text TEXT NOT NULL,
                type TEXT NOT NULL,
                embedding BLOB NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX vector_items_type_idx ON vector_items(type)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS vector_items")
        onCreate(db)
    }

    fun initializeOnce() {
        writableDatabase
    }

    fun insert(data: List<StorredData>, embeddings: List<List<Double>>) {
        require(data.size == embeddings.size) {
            "Vector data and embeddings sizes differ: ${data.size} != ${embeddings.size}"
        }
        writableDatabase.beginTransaction()
        try {
            data.indices.forEach { index ->
                val values = ContentValues().apply {
                    put("text", data[index].text)
                    put("type", data[index].type.name)
                    put("embedding", encodeEmbedding(embeddings[index]))
                    put("created_at", System.currentTimeMillis())
                }
                writableDatabase.insertOrThrow("vector_items", null, values)
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun getAllData(): List<StorredData> {
        val items = mutableListOf<StorredData>()
        readableDatabase.query(
            "vector_items",
            arrayOf("text", "type"),
            null,
            null,
            null,
            null,
            "id ASC",
        ).use { cursor ->
            val textIndex = cursor.getColumnIndexOrThrow("text")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            while (cursor.moveToNext()) {
                val type = runCatching { StorredType.valueOf(cursor.getString(typeIndex)) }.getOrNull() ?: continue
                items += StorredData(cursor.getString(textIndex), type)
            }
        }
        return items
    }

    fun clearAllData() {
        writableDatabase.delete("vector_items", null, null)
    }

    fun searchSimilar(
        embedding: List<Double>,
        limit: Int = 5,
        minScore: Float = DEFAULT_MIN_SCORE,
    ): List<StorredData> {
        val query = embedding.take(MAX_DIM).map(Double::toFloat)
        if (query.isEmpty() || limit <= 0) return emptyList()

        val candidates = mutableListOf<ScoredItem>()
        readableDatabase.query(
            "vector_items",
            arrayOf("text", "type", "embedding"),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            val textIndex = cursor.getColumnIndexOrThrow("text")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            val embeddingIndex = cursor.getColumnIndexOrThrow("embedding")
            while (cursor.moveToNext()) {
                val itemEmbedding = decodeEmbedding(cursor.getBlob(embeddingIndex))
                val score = cosineSimilarity(query, itemEmbedding)
                if (score >= minScore) {
                    val type = runCatching { StorredType.valueOf(cursor.getString(typeIndex)) }.getOrNull() ?: continue
                    candidates += ScoredItem(
                        data = StorredData(cursor.getString(textIndex), type),
                        score = score,
                    )
                }
            }
        }
        return candidates
            .sortedByDescending { it.score }
            .take(limit)
            .map { it.data }
    }

    private fun encodeEmbedding(values: List<Double>): ByteArray {
        val floats = values.take(MAX_DIM).map(Double::toFloat)
        val buffer = ByteBuffer.allocate(floats.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach(buffer::putFloat)
        return buffer.array()
    }

    private fun decodeEmbedding(bytes: ByteArray): List<Float> {
        if (bytes.isEmpty()) return emptyList()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = ArrayList<Float>(bytes.size / Float.SIZE_BYTES)
        while (buffer.remaining() >= Float.SIZE_BYTES) {
            floats += buffer.float
        }
        return floats
    }

    private fun cosineSimilarity(left: List<Float>, right: List<Float>): Float {
        val size = minOf(left.size, right.size)
        if (size == 0) return 0f

        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in 0 until size) {
            val l = left[index].toDouble()
            val r = right[index].toDouble()
            dot += l * r
            leftNorm += l * l
            rightNorm += r * r
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) return 0f
        return (dot / (sqrt(leftNorm) * sqrt(rightNorm))).toFloat()
    }

    private data class ScoredItem(
        val data: StorredData,
        val score: Float,
    )

    private companion object {
        const val DB_NAME = "souz_android_vector.db"
        const val DB_VERSION = 1
        const val DEFAULT_MIN_SCORE = 0.65f
        const val MAX_DIM = 1024
    }
}
