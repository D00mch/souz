package ru.souz.db

import org.kodein.di.DI
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.di.mainDiModule
import ru.souz.llms.EmbeddingInputKind
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.local.LocalEmbeddingProfiles
import ru.souz.llms.local.LocalModelStore
import ru.souz.skill.EmbeddingSkillSearch
import java.time.LocalDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * Orchestrates extraction of desktop data, embedding via LLM and persistence
 * into a Lucene index for similarity search.
 */
class DesktopInfoRepository(
    private val api: LLMChatAPI,
    private val db: VectorDB,
    private val extractor: DesktopDataExtractor,
    private val settingsProvider: SettingsProvider,
    private val localModelStore: LocalModelStore = LocalModelStore(),
    private val skillSearch: EmbeddingSkillSearch = EmbeddingSkillSearch(
        api = api,
        settingsProvider = settingsProvider,
        localModelStore = localModelStore,
    ),
) : AgentDesktopInfoRepository {
    private val l = LoggerFactory.getLogger(DesktopInfoRepository::class.java)
    private val refreshMutex = Mutex()

    companion object {
        private const val LAST_RUN_KEY = "rag_repo_last_run"
        private const val INDEX_MODEL_KEY = "rag_repo_index_model"
        private const val REMOTE_EMBEDDINGS_BATCH_SIZE = 500
        private const val LOCAL_EMBEDDINGS_BATCH_SIZE = 64
    }

    /**
     * Extract desktop data and store embeddings at most once per day.
     */
    suspend fun storeDesktopDataDaily() {
        refreshDesktopData(force = false)
    }

    suspend fun rebuildIndexNow() {
        refreshDesktopData(force = true)
    }

    suspend fun storeDesktopInfo(data: List<StorredData>) {
        val shortened = data.map { it.copy(text = it.text.take(500)) } // get 500 symbols of long texts
        val embeddings = when (val resp = api.embeddings(
            LLMRequest.Embeddings(
                input = shortened.map { it.text },
                inputKind = EmbeddingInputKind.DOCUMENT,
            )
        )) {
            is LLMResponse.Embeddings.Ok -> resp.data.map { it.embedding }
            is LLMResponse.Embeddings.Error -> throw IllegalStateException("Embeddings error: ${resp.message}")
        }
        try {
            db.insert(shortened, embeddings)
        } catch (e: Exception) {
            l.error("Can't insert data in vector storage, $e", e)
        }
    }

    @Suppress("unused")
    fun getDesktopData(): List<StorredData> = db.getAllData()

    /**
     * Convert the provided query to an embedding and return the most similar
     * stored texts from the database.
     */
    override suspend fun search(query: String, limit: Int): List<StorredData> {
        if (!isEmbeddingsReady() || !isCurrentIndexReady()) return emptyList()
        val emb = when (val resp = api.embeddings(
            LLMRequest.Embeddings(
                input = listOf(query),
                inputKind = EmbeddingInputKind.QUERY,
            )
        )) {
            is LLMResponse.Embeddings.Ok -> resp.data.first().embedding
            is LLMResponse.Embeddings.Error -> throw IllegalStateException("Embeddings error: ${resp.message}")
        }
        return db.searchSimilar(emb, limit)
    }

    override suspend fun searchSkills(query: String, limit: Int) = skillSearch.searchRelevantSkills(query, limit)

    override suspend fun loadSkill(name: String) = skillSearch.loadSkill(name)

    private suspend fun refreshDesktopData(force: Boolean) = refreshMutex.withLock {
        db.initializeOnce()
        if (!isEmbeddingsReady()) {
            l.info(
                "Skip storeDesktopDataDaily: embeddings model {} is not ready",
                settingsProvider.embeddingsModel.alias,
            )
            return
        }

        val today = LocalDate.now().toString() // returns data like 2023-03-31
        val targetEmbeddingsModel = currentEmbeddingsFingerprint()
        val indexModel = currentIndexModel()
        if (!force && ConfigStore.get(LAST_RUN_KEY, "") == today && indexModel == targetEmbeddingsModel) return

        ConfigStore.rm(INDEX_MODEL_KEY)
        db.clearAllData()
        val data = extractor.all()
        if (data.isEmpty()) {
            l.info("DesktopDataExtractor.all() is empty!")
        } else {
            l.info("About to store data, random sample: {}", data[Random.nextInt(data.size)])
        }

        val batchSize = if (settingsProvider.embeddingsModel.provider == LlmProvider.LOCAL) {
            LOCAL_EMBEDDINGS_BATCH_SIZE
        } else {
            REMOTE_EMBEDDINGS_BATCH_SIZE
        }
        data.chunked(batchSize).forEach { chunk ->
            if (!isEmbeddingsReady() || currentEmbeddingsFingerprint() != targetEmbeddingsModel) {
                l.info("Stop desktop index rebuild: embeddings model changed while rebuilding")
                return@withLock
            }
            storeDesktopInfo(chunk)
        }
        ConfigStore.put(INDEX_MODEL_KEY, targetEmbeddingsModel)
        ConfigStore.put(LAST_RUN_KEY, today)
    }

    private fun isEmbeddingsReady(): Boolean {
        val model = settingsProvider.embeddingsModel
        if (!settingsProvider.hasKey(model.provider)) return false
        if (model.provider != LlmProvider.LOCAL) return true
        val profile = LocalEmbeddingProfiles.forAlias(model.alias) ?: return false
        return localModelStore.isPresent(profile)
    }

    private fun isCurrentIndexReady(): Boolean = currentIndexModel() == currentEmbeddingsFingerprint()

    private fun currentIndexModel(): String = ConfigStore.get(INDEX_MODEL_KEY, "")

    private fun currentEmbeddingsFingerprint(): String = settingsProvider.embeddingsModel.name
}

suspend fun main() {
    ConfigStore.rm("rag_repo_last_run") // to reset
    val di = DI.invoke { import(mainDiModule) }
    val api: LLMChatAPI by di.instance()
    val vectorDB: VectorDB by di.instance()
    val extractor: DesktopDataExtractor by di.instance()
    val settingsProvider: SettingsProvider by di.instance()
    val repo = DesktopInfoRepository(api, vectorDB, extractor, settingsProvider)
    repo.storeDesktopDataDaily()
    println(repo.getDesktopData().size)
}
