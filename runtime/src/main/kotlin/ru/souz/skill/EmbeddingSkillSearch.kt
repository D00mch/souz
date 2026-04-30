package ru.souz.skill

import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.agent.skill.AgentSkill
import ru.souz.agent.skill.AgentSkillMatch
import ru.souz.db.SettingsProvider
import ru.souz.db.hasKey
import ru.souz.llms.EmbeddingInputKind
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.local.LocalEmbeddingProfiles
import ru.souz.llms.local.LocalModelStore

class EmbeddingSkillSearch(
    private val catalog: FilesystemSkillCatalog = FilesystemSkillCatalog(),
    private val api: LLMChatAPI,
    private val settingsProvider: SettingsProvider,
    private val localModelStore: LocalModelStore = LocalModelStore(),
    private val index: SkillVectorIndex = SkillVectorIndex(),
) {
    private val refreshMutex = Mutex()

    suspend fun searchRelevantSkills(
        query: String,
        limit: Int = 3,
    ): List<AgentSkillMatch> {
        if (query.isBlank() || limit <= 0 || !isEmbeddingsReady()) return emptyList()
        ensureIndexFresh()
        val embedding = requestEmbeddings(listOf(query), EmbeddingInputKind.QUERY).firstOrNull() ?: return emptyList()
        return index.searchSimilar(embedding, limit)
    }

    suspend fun loadSkill(name: String): AgentSkill? = catalog.loadSkill(name)

    fun listSkillSummaries() = catalog.listSkillSummaries()

    private suspend fun ensureIndexFresh() = refreshMutex.withLock {
        val entries = catalog.listSkillEntries()
        val metadata = SkillIndexMetadata(
            embeddingsModelFingerprint = currentEmbeddingsFingerprint(),
            catalogFingerprint = catalogFingerprint(entries),
        )
        if (index.readMetadata() == metadata) return
        if (entries.isEmpty()) {
            index.replaceAll(emptyList(), emptyList(), metadata)
            return
        }

        val batchSize = if (settingsProvider.embeddingsModel.provider == LlmProvider.LOCAL) {
            LOCAL_EMBEDDINGS_BATCH_SIZE
        } else {
            REMOTE_EMBEDDINGS_BATCH_SIZE
        }
        val embeddings = ArrayList<List<Double>>(entries.size)
        entries.chunked(batchSize).forEach { chunk ->
            embeddings += requestEmbeddings(
                input = chunk.map(SkillCatalogEntry::embeddingText),
                inputKind = EmbeddingInputKind.DOCUMENT,
            )
        }
        index.replaceAll(entries, embeddings, metadata)
    }

    private suspend fun requestEmbeddings(
        input: List<String>,
        inputKind: EmbeddingInputKind,
    ): List<List<Double>> = when (val response = api.embeddings(
        LLMRequest.Embeddings(
            input = input,
            inputKind = inputKind,
        )
    )) {
        is LLMResponse.Embeddings.Ok -> response.data.map { it.embedding }
        is LLMResponse.Embeddings.Error -> throw IllegalStateException("Embeddings error: ${response.message}")
    }

    private fun isEmbeddingsReady(): Boolean {
        val model = settingsProvider.embeddingsModel
        if (!settingsProvider.hasKey(model.provider)) return false
        if (model.provider != LlmProvider.LOCAL) return true
        val profile = LocalEmbeddingProfiles.forAlias(model.alias) ?: return false
        return localModelStore.isPresent(profile)
    }

    private fun currentEmbeddingsFingerprint(): String = settingsProvider.embeddingsModel.name

    private fun catalogFingerprint(entries: List<SkillCatalogEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.sortedBy { it.summary.name.lowercase() }.forEach { entry ->
            digest.update(entry.fingerprint.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val REMOTE_EMBEDDINGS_BATCH_SIZE = 500
        const val LOCAL_EMBEDDINGS_BATCH_SIZE = 64
    }
}
