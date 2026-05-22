package ru.souz.memory

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.lucene.document.Document
import org.apache.lucene.document.KnnFloatVectorField
import org.apache.lucene.document.StoredField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.KnnFloatVectorQuery
import org.apache.lucene.store.FSDirectory
import ru.souz.llms.EmbeddingInputKind
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import kotlin.math.min

internal class MemoryVectorIndex(
    private val indexDir: Path,
) {
    suspend fun rebuild(
        docs: List<MemoryIndexedDoc>,
        embeddingsApi: LLMChatAPI,
        fingerprint: String,
    ) {
        Files.createDirectories(indexDir)
        val embeddings = if (docs.isEmpty()) {
            emptyList()
        } else {
            when (
                val response = embeddingsApi.embeddings(
                    LLMRequest.Embeddings(
                        input = docs.map { it.text },
                        inputKind = EmbeddingInputKind.DOCUMENT,
                    )
                )
            ) {
                is LLMResponse.Embeddings.Ok -> response.data.map { it.embedding }
                is LLMResponse.Embeddings.Error -> error("Embeddings rebuild failed: ${response.message}")
            }
        }
        withContext(Dispatchers.IO) {
            IndexWriter(openDirectory(), IndexWriterConfig()).use { writer ->
                writer.deleteAll()
                docs.forEachIndexed { index, doc ->
                    writer.addDocument(
                        Document().apply {
                            add(StoredField("id", doc.id))
                            add(StoredField("sourceRecordId", doc.sourceRecordId))
                            add(StoredField("text", doc.text))
                            add(StoredField("scopeType", doc.scopeType))
                            add(StoredField("scopeId", doc.scopeId))
                            add(KnnFloatVectorField("embedding", toFloatArray(embeddings[index]), VectorSimilarityFunction.COSINE))
                        }
                    )
                }
                writer.commit()
            }
            Files.writeString(indexDir.resolve(FINGERPRINT_FILE_NAME), fingerprint)
        }
    }

    suspend fun search(
        query: String,
        embeddingsApi: LLMChatAPI,
        fingerprint: String,
        limit: Int,
    ): List<MemoryIndexedDocHit> {
        if (query.isBlank()) return emptyList()
        if (!Files.isDirectory(indexDir)) return emptyList()
        if (storedFingerprint() != fingerprint) return emptyList()
        val embedding = when (
            val response = embeddingsApi.embeddings(
                LLMRequest.Embeddings(
                    input = listOf(query),
                    inputKind = EmbeddingInputKind.QUERY,
                )
            )
        ) {
            is LLMResponse.Embeddings.Ok -> response.data.firstOrNull()?.embedding.orEmpty()
            is LLMResponse.Embeddings.Error -> return emptyList()
        }
        return withContext(Dispatchers.IO) {
            DirectoryReader.open(openDirectory()).use { reader ->
                val searcher = IndexSearcher(reader)
                val topDocs = searcher.search(KnnFloatVectorQuery("embedding", toFloatArray(embedding), limit), limit)
                buildList {
                    topDocs.scoreDocs.forEach { scoreDoc ->
                        val doc = searcher.storedFields().document(scoreDoc.doc)
                        add(
                            MemoryIndexedDocHit(
                                id = doc.get("id"),
                                sourceRecordId = doc.get("sourceRecordId"),
                                text = doc.get("text"),
                                scopeType = doc.get("scopeType"),
                                scopeId = doc.get("scopeId"),
                            )
                        )
                    }
                }
            }
        }
    }

    private suspend fun storedFingerprint(): String? =
        withContext(Dispatchers.IO) {
            val file = indexDir.resolve(FINGERPRINT_FILE_NAME)
            if (Files.exists(file)) Files.readString(file) else null
        }

    private fun openDirectory(): FSDirectory {
        Files.createDirectories(indexDir)
        return FSDirectory.open(indexDir)
    }

    private fun toFloatArray(values: List<Double>): FloatArray {
        val size = min(values.size, MAX_DIMENSIONS)
        return FloatArray(size) { index -> values[index].toFloat() }
    }

    private companion object {
        private const val FINGERPRINT_FILE_NAME = ".fingerprint"
        private const val MAX_DIMENSIONS = 1024
    }
}

internal data class MemoryIndexedDoc(
    val id: String,
    val sourceRecordId: String,
    val text: String,
    val scopeType: String,
    val scopeId: String,
)

internal data class MemoryIndexedDocHit(
    val id: String?,
    val sourceRecordId: String?,
    val text: String?,
    val scopeType: String?,
    val scopeId: String?,
)
