package com.dumch.db

import com.dumch.tool.config.ConfigStore
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
object RagDatabase {
    private const val INDEX_PATH = "build/rag_index"
    private const val INIT_KEY = "rag_db_initialized"

    /** Create the index directory once. */
    fun initializeOnce() {
        if (ConfigStore.get(INIT_KEY, false)) return
        val dir = FSDirectory.open(Paths.get(INDEX_PATH))
        IndexWriter(dir, IndexWriterConfig()).use { }
        ConfigStore.put(INIT_KEY, true)
    }

    /** Store a batch of texts and their embeddings. */
    fun insert(data: List<String>, embeddings: List<List<Double>>) {
        val dir = FSDirectory.open(Paths.get(INDEX_PATH))
        IndexWriter(dir, IndexWriterConfig()).use { writer ->
            data.indices.forEach { idx ->
                val doc = Document()
                doc.add(StoredField("text", data[idx]))
                doc.add(KnnFloatVectorField("embedding", toFloatArray(embeddings[idx])))
                writer.addDocument(doc)
            }
        }
    }

    /** Obtain all stored desktop data texts. */
    fun getAllTexts(): List<String> {
        val dir = FSDirectory.open(Paths.get(INDEX_PATH))
        DirectoryReader.open(dir).use { reader ->
            val list = mutableListOf<String>()
            for (i in 0 until reader.maxDoc()) {
                val doc = reader.document(i)
                doc.get("text")?.let { list.add(it) }
            }
            return list
        }
    }

    /**
     * Return texts ordered by similarity to the provided embedding using
     * Lucene's k-NN search.
     */
    fun searchSimilar(embedding: List<Double>, limit: Int = 5): List<String> {
        val dir = FSDirectory.open(Paths.get(INDEX_PATH))
        DirectoryReader.open(dir).use { reader ->
            val searcher = IndexSearcher(reader)
            val query = KnnFloatVectorQuery("embedding", toFloatArray(embedding), limit)
            val topDocs = searcher.search(query, limit)
            val texts = mutableListOf<String>()
            topDocs.scoreDocs.forEach { sd ->
                searcher.doc(sd.doc).get("text")?.let { texts.add(it) }
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
