package ru.souz.db

import org.kodein.di.DI
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.di.mainDiModule
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
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
) : AgentDesktopInfoRepository {
    private val l = LoggerFactory.getLogger(DesktopInfoRepository::class.java)
    private val refreshMutex = Mutex()

    companion object {
        private const val LAST_RUN_KEY = "rag_repo_last_run"
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
        val embeddings = when (val resp = api.embeddings(LLMRequest.Embeddings(input = shortened.map { it.text }))) {
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
        if (!hasEmbeddingsKeyConfigured()) return emptyList()
        val emb = when (val resp = api.embeddings(LLMRequest.Embeddings(input = listOf(query)))) {
            is LLMResponse.Embeddings.Ok -> resp.data.first().embedding
            is LLMResponse.Embeddings.Error -> throw IllegalStateException("Embeddings error: ${resp.message}")
        }
        return db.searchSimilar(emb, limit)
    }

    private suspend fun refreshDesktopData(force: Boolean) = refreshMutex.withLock {
        db.initializeOnce()
        if (!hasEmbeddingsKeyConfigured()) {
            l.info(
                "Skip storeDesktopDataDaily: embeddings provider {} has no configured API key",
                settingsProvider.embeddingsModel.provider
            )
            return
        }

        val today = LocalDate.now().toString() // returns data like 2023-03-31
        if (!force && ConfigStore.get(LAST_RUN_KEY, "") == today) return

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
            storeDesktopInfo(chunk)
        }
        ConfigStore.put(LAST_RUN_KEY, today)
    }

    private fun hasEmbeddingsKeyConfigured(): Boolean = settingsProvider.hasKey(settingsProvider.embeddingsModel.provider)
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
