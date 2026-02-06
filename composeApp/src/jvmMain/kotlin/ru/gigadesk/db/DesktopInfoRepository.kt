package ru.gigadesk.db

import org.kodein.di.DI
import org.kodein.di.instance
import org.slf4j.LoggerFactory
import ru.gigadesk.di.mainDiModule
import ru.gigadesk.giga.GigaChatAPI
import ru.gigadesk.giga.GigaRequest
import ru.gigadesk.giga.GigaResponse
import ru.gigadesk.giga.GigaRestChatAPI
import java.time.LocalDate
import kotlin.random.Random

/**
 * Orchestrates extraction of desktop data, embedding via LLM and persistence
 * into a Lucene index for similarity search.
 */
open class DesktopInfoRepository(
    private val api: GigaChatAPI,
    private val db: VectorDB,
    private val extractor: DesktopDataExtractor,
) {
    private val l = LoggerFactory.getLogger(DesktopInfoRepository::class.java)

    companion object {
        private const val LAST_RUN_KEY = "rag_repo_last_run"
    }

    /**
     * Extract desktop data and store embeddings at most once per day.
     */
    suspend fun storeDesktopDataDaily() {
        db.initializeOnce()
        val today = LocalDate.now().toString() // returns data like 2023-03-31
        if (ConfigStore.get(LAST_RUN_KEY, "") == today) return
        db.clearAllData()
        val data = extractor.all()
        if (data.isEmpty()) {
            l.info("DesktopDataExtractor.all() is empty!")
        } else {
            l.info("About to store data, random sample: {}", data[Random.nextInt(data.size)])
        }
        data.chunked(500).forEach { chunk ->
            storeDesktopInfo(chunk)
        }
        ConfigStore.put(LAST_RUN_KEY, today)
    }

    suspend fun storeDesktopInfo(data: List<StorredData>) {
        val shortened = data.map { it.copy(text = it.text.take(500)) } // get 500 symbols of long texts
        val embeddings = when (val resp = api.embeddings(GigaRequest.Embeddings(input = shortened.map { it.text }))) {
            is GigaResponse.Embeddings.Ok -> resp.data.map { it.embedding }
            is GigaResponse.Embeddings.Error -> throw IllegalStateException("Embeddings error: ${resp.message}")
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
    open suspend fun search(query: String, limit: Int = 5): List<StorredData> {
        val emb = when (val resp = api.embeddings(GigaRequest.Embeddings(input = listOf(query)))) {
            is GigaResponse.Embeddings.Ok -> resp.data.first().embedding
            is GigaResponse.Embeddings.Error -> throw IllegalStateException("Embeddings error: ${resp.message}")
        }
        return db.searchSimilar(emb, limit)
    }
}

suspend fun main() {
 //   ConfigStore.rm("rag_repo_last_run") // to reset
    val di = DI.invoke { import(mainDiModule) }
    val api: GigaRestChatAPI by di.instance()
    val vectorDB: VectorDB by di.instance()
    val extractor: DesktopDataExtractor by di.instance()
    val repo = DesktopInfoRepository(api, vectorDB, extractor)
    repo.storeDesktopDataDaily()
    println(repo.getDesktopData().size)
}
