package ru.souz.skill

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.min
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
import org.slf4j.LoggerFactory
import ru.souz.agent.skill.AgentSkillMatch
import ru.souz.agent.skill.AgentSkillSource
import ru.souz.agent.skill.AgentSkillSummary
import ru.souz.llms.restJsonMapper
import ru.souz.tool.files.FilesToolUtil

internal data class SkillIndexMetadata(
    val embeddingsModelFingerprint: String,
    val catalogFingerprint: String,
)

class SkillVectorIndex(
    private val indexDirectory: Path = defaultIndexDirectory(),
) {
    private val l = LoggerFactory.getLogger(SkillVectorIndex::class.java)
    private val metadataFile = indexDirectory.resolve("skills-index-metadata.json")

    internal fun readMetadata(): SkillIndexMetadata? {
        if (!Files.isRegularFile(metadataFile)) return null
        return runCatching {
            restJsonMapper.readValue(Files.readString(metadataFile), SkillIndexMetadata::class.java)
        }.onFailure { error ->
            l.warn("Failed to read skills index metadata: {}", error.message)
        }.getOrNull()
    }

    internal fun replaceAll(
        entries: List<SkillCatalogEntry>,
        embeddings: List<List<Double>>,
        metadata: SkillIndexMetadata,
    ) {
        Files.createDirectories(indexDirectory)
        val directory = FSDirectory.open(indexDirectory)
        IndexWriter(directory, IndexWriterConfig()).use { writer ->
            writer.deleteAll()
            entries.indices.forEach { idx ->
                writer.addDocument(skillDocument(entries[idx], embeddings[idx]))
            }
            writer.commit()
        }
        Files.writeString(metadataFile, restJsonMapper.writeValueAsString(metadata))
    }

    fun searchSimilar(
        embedding: List<Double>,
        limit: Int,
        minScore: Float = DEFAULT_MIN_SCORE,
    ): List<AgentSkillMatch> {
        if (limit <= 0 || isZeroVector(embedding)) return emptyList()
        Files.createDirectories(indexDirectory)
        val directory = FSDirectory.open(indexDirectory)
        if (!DirectoryReader.indexExists(directory)) return emptyList()
        return runCatching {
            DirectoryReader.open(directory).use { reader ->
                val searcher = IndexSearcher(reader)
                val query = KnnFloatVectorQuery(FIELD_EMBEDDING, toFloatArray(embedding), limit)
                searcher.search(query, limit).scoreDocs.mapNotNull { scoredDoc ->
                    if (scoredDoc.score < minScore) return@mapNotNull null
                    val stored = searcher.storedFields().document(scoredDoc.doc)
                    AgentSkillMatch(
                        summary = stored.toSummary(),
                        score = scoredDoc.score,
                    )
                }
            }
        }.onFailure { error ->
            l.warn("Failed to search skills index: {}", error.message)
        }.getOrDefault(emptyList())
    }

    private fun skillDocument(
        entry: SkillCatalogEntry,
        embedding: List<Double>,
    ): Document = Document().apply {
        add(StoredField(FIELD_NAME, entry.summary.name))
        add(StoredField(FIELD_DESCRIPTION, entry.summary.description))
        add(StoredField(FIELD_WHEN_TO_USE, entry.summary.whenToUse))
        add(StoredField(FIELD_DISABLE_MODEL_INVOCATION, entry.summary.disableModelInvocation.toString()))
        add(StoredField(FIELD_USER_INVOCABLE, entry.summary.userInvocable.toString()))
        entry.summary.allowedTools.forEach { add(StoredField(FIELD_ALLOWED_TOOL, it)) }
        entry.summary.requiresBins.forEach { add(StoredField(FIELD_REQUIRED_BIN, it)) }
        entry.summary.supportedOs.forEach { add(StoredField(FIELD_SUPPORTED_OS, it)) }
        add(StoredField(FIELD_SOURCE, entry.summary.source.name))
        add(StoredField(FIELD_FOLDER_NAME, entry.summary.folderName))
        add(KnnFloatVectorField(FIELD_EMBEDDING, toFloatArray(embedding), VectorSimilarityFunction.COSINE))
    }

    private fun Document.toSummary(): AgentSkillSummary =
        AgentSkillSummary(
            name = get(FIELD_NAME).orEmpty(),
            description = get(FIELD_DESCRIPTION).orEmpty(),
            whenToUse = get(FIELD_WHEN_TO_USE).orEmpty(),
            disableModelInvocation = get(FIELD_DISABLE_MODEL_INVOCATION)?.toBooleanStrictOrNull() ?: false,
            userInvocable = get(FIELD_USER_INVOCABLE)?.toBooleanStrictOrNull() ?: true,
            allowedTools = getValues(FIELD_ALLOWED_TOOL).toSet(),
            requiresBins = getValues(FIELD_REQUIRED_BIN).toList(),
            supportedOs = getValues(FIELD_SUPPORTED_OS).toList(),
            source = get(FIELD_SOURCE)?.let(AgentSkillSource::valueOf) ?: AgentSkillSource.WORKSPACE,
            folderName = get(FIELD_FOLDER_NAME).orEmpty(),
        )

    private fun toFloatArray(list: List<Double>): FloatArray {
        val size = min(list.size, MAX_DIM)
        return FloatArray(size) { idx -> list[idx].toFloat() }
    }

    private fun isZeroVector(values: List<Double>): Boolean = values.none { value -> value != 0.0 }

    companion object {
        private const val FIELD_NAME = "name"
        private const val FIELD_DESCRIPTION = "description"
        private const val FIELD_WHEN_TO_USE = "whenToUse"
        private const val FIELD_DISABLE_MODEL_INVOCATION = "disableModelInvocation"
        private const val FIELD_USER_INVOCABLE = "userInvocable"
        private const val FIELD_ALLOWED_TOOL = "allowedTool"
        private const val FIELD_REQUIRED_BIN = "requiredBin"
        private const val FIELD_SUPPORTED_OS = "supportedOs"
        private const val FIELD_SOURCE = "source"
        private const val FIELD_FOLDER_NAME = "folderName"
        private const val FIELD_EMBEDDING = "embedding"
        private const val MAX_DIM = 1024
        private const val DEFAULT_MIN_SCORE = 0.55f

        private fun defaultIndexDirectory(): Path =
            Path.of(FilesToolUtil.homeStr, ".local", "state", "souz", "index", "skills")
    }
}
