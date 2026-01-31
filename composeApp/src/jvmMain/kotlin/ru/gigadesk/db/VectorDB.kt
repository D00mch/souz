package ru.gigadesk.db

import org.apache.lucene.document.Document
import org.apache.lucene.document.KnnFloatVectorField
import org.apache.lucene.document.StoredField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.VectorSimilarityFunction // <--- Добавлен импорт
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.KnnFloatVectorQuery
import kotlin.math.min
import org.apache.lucene.store.FSDirectory
import org.slf4j.LoggerFactory
import ru.gigadesk.db.VectorDB.getAllData
import ru.gigadesk.tool.files.FilesToolUtil
import java.nio.file.Paths

object VectorDB {
    private val l = LoggerFactory.getLogger(VectorDB::class.java)
    private val indexPath = "${FilesToolUtil.homeStr}/.local/state/gigadesk/"
    private const val INIT_KEY = "rag_db_initialized"
    private const val DEFAULT_MIN_SCORE = 0.92f

    fun initializeOnce() {
        if (ConfigStore.get(INIT_KEY, false)) return
        l.debug("About to initialize vector db")
        try {
            val dir = FSDirectory.open(Paths.get(indexPath))
            IndexWriter(dir, IndexWriterConfig()).use { }
            ConfigStore.put(INIT_KEY, true)
        } catch (e: Exception) {
            l.error("Can't initialize vector db, $e", e)
        }
    }

    fun insert(data: List<StorredData>, embeddings: List<List<Double>>) {
        l.debug("About to insert data, size ${data.size}")
        val dir = FSDirectory.open(Paths.get(indexPath))
        IndexWriter(dir, IndexWriterConfig()).use { writer ->
            data.indices.forEach { idx ->
                val doc = Document()
                doc.add(StoredField("text", data[idx].text))
                doc.add(StoredField("type", data[idx].type.name))

                doc.add(KnnFloatVectorField(
                    "embedding",
                    toFloatArray(embeddings[idx]),
                    VectorSimilarityFunction.COSINE
                ))

                writer.addDocument(doc)
            }
        }
    }

    fun getAllData(): List<StorredData> {
        val dir = FSDirectory.open(Paths.get(indexPath))
        try {
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
        } catch (e: Exception) {
            return emptyList()
        }
    }

    fun clearAllData() {
        val dir = FSDirectory.open(Paths.get(indexPath))
        IndexWriter(dir, IndexWriterConfig()).use { writer ->
            writer.deleteAll()
        }
    }

    fun searchSimilar(
        embedding: List<Double>,
        limit: Int = 5,
        minScore: Float = DEFAULT_MIN_SCORE
    ): List<StorredData> {
        val dir = FSDirectory.open(Paths.get(indexPath))
        try {
            DirectoryReader.open(dir).use { reader ->
                val searcher = IndexSearcher(reader)

                val query = KnnFloatVectorQuery("embedding", toFloatArray(embedding), limit)
                val topDocs = searcher.search(query, limit)
                val texts = mutableListOf<StorredData>()

                topDocs.scoreDocs.forEach { sd ->
                    l.debug("Doc id: ${sd.doc}, Score: ${sd.score}")

                    if (sd.score < minScore) {
                        return@forEach
                    }

                    val doc = searcher.storedFields().document(sd.doc)
                    val originalText = doc.get("text") ?: return@forEach
                    val type = doc.get("type")?.let { StorredType.valueOf(it) } ?: return@forEach
                    //val textWithScore = "$originalText [${String.format("%.4f", sd.score)}]"

                    texts.add(StorredData(originalText, type))
                }
                return texts
            }
        } catch (e: Exception) {
            l.warn("Error searching vector db: $e")
            return emptyList()
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