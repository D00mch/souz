package ru.abledo.db

import org.apache.lucene.document.Document
import org.apache.lucene.document.KnnFloatVectorField
import org.apache.lucene.document.StoredField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.KnnFloatVectorQuery
import kotlin.math.min
import org.apache.lucene.store.FSDirectory
import java.nio.file.Paths

/**
 * Handles initialization of a Lucene index and stores/retrieves user desktop
 * data embeddings.
 */
object VectorDB {
    private const val INDEX_PATH = "build/rag_index"
    private const val INIT_KEY = "rag_db_initialized"

    /** Create the index directory once. */
    fun initializeOnce() {
        if (ConfigStore.get(INIT_KEY, false)) return
        val dir = FSDirectory.open(Paths.get(INDEX_PATH))
        IndexWriter(dir, IndexWriterConfig()).use { }
        ConfigStore.put(INIT_KEY, true)
    }

    /** Store a batch of texts, their types and embeddings. */
    fun insert(data: List<StorredData>, embeddings: List<List<Double>>) {
        val dir = FSDirectory.open(Paths.get(INDEX_PATH))
        IndexWriter(dir, IndexWriterConfig()).use { writer ->
            data.indices.forEach { idx ->
                val doc = Document()
                doc.add(StoredField("text", data[idx].text))
                doc.add(StoredField("type", data[idx].type.name))
                doc.add(KnnFloatVectorField("embedding", toFloatArray(embeddings[idx])))
                writer.addDocument(doc)
            }
        }
    }

    /** Obtain all stored desktop data. */
    fun getAllData(): List<StorredData> {
        val dir = FSDirectory.open(Paths.get(INDEX_PATH))
        DirectoryReader.open(dir).use { reader ->
            val list = mutableListOf<StorredData>()
            for (i in 0 until reader.maxDoc()) {
                val doc = reader.storedFields().document(i)
                val text = doc.get("text") ?: continue
                val type = doc.get("type")?.let { StorredType.valueOf(it) } ?: continue
                list.add(StorredData(text, type))
            }
            return list
        }
    }

    /** clears the database */
    fun clearAllData() {
        val dir = FSDirectory.open(Paths.get(INDEX_PATH))
        IndexWriter(dir, IndexWriterConfig()).use { writer ->
            writer.deleteAll()
        }
    }

    /**
     * Return texts ordered by similarity to the provided embedding using
     * Lucene's k-NN search.
     */
    fun searchSimilar(embedding: List<Double>, limit: Int = 5): List<StorredData> {
        val dir = FSDirectory.open(Paths.get(INDEX_PATH))
        DirectoryReader.open(dir).use { reader ->
            val searcher = IndexSearcher(reader)
            val query = KnnFloatVectorQuery("embedding", toFloatArray(embedding), limit)
            val topDocs = searcher.search(query, limit)
            val texts = mutableListOf<StorredData>()
            topDocs.scoreDocs.forEach { sd ->
                val doc = searcher.storedFields().document(sd.doc)
                val text = doc.get("text") ?: return@forEach
                val type = doc.get("type")?.let { StorredType.valueOf(it) } ?: return@forEach
                texts.add(StorredData(text, type))
            }
            return texts
        }
    }

    private fun toFloatArray(list: List<Double>): FloatArray {
        val size = min(list.size, MAX_DIM)
        val arr = FloatArray(size)
        for (i in 0 until size) {
            arr[i] = list[i].toFloat()
        }
        return arr
    }

    private const val MAX_DIM = 1024
}

fun main() {
//    VectorDB.clearAllData()
    println(VectorDB.getAllData().size)
}