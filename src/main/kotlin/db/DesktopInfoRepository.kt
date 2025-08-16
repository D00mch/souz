package com.dumch.db

import com.dumch.giga.GigaAuth
import com.dumch.giga.GigaRequest
import com.dumch.giga.GigaResponse
import com.dumch.giga.GigaRestChatAPI
import com.dumch.tool.config.ConfigStore
import java.time.LocalDate

/**
 * Orchestrates extraction of desktop data, embedding via LLM and persistence
 * into a Lucene index for similarity search.
 */
class DesktopInfoRepository(
    private val api: GigaRestChatAPI,
    private val db: VectorDB,
) {

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

        val data = DesktopDataExtractor.extract()
        storeDesktopInfo(data)
        ConfigStore.put(LAST_RUN_KEY, today)
    }

    suspend fun storeDesktopInfo(data: List<String>) {
        val embeddings = when (val resp = api.embeddings(GigaRequest.Embeddings(input = data))) {
            is GigaResponse.Embeddings.Ok -> resp.data.map { it.embedding }
            is GigaResponse.Embeddings.Error -> throw IllegalStateException("Embeddings error: ${resp.message}")
        }
        db.insert(data, embeddings)
    }

    fun getDesktopTexts(): List<String> = db.getAllTexts()

    /**
     * Convert the provided query to an embedding and return the most similar
     * stored texts from the database.
     */
    suspend fun search(query: String, limit: Int = 5): List<String> {
        val emb = when (val resp = api.embeddings(GigaRequest.Embeddings(input = listOf(query)))) {
            is GigaResponse.Embeddings.Ok -> resp.data.first().embedding
            is GigaResponse.Embeddings.Error -> throw IllegalStateException("Embeddings error: ${resp.message}")
        }
        return db.searchSimilar(emb, limit)
    }
}

suspend fun main() {
    val api = GigaRestChatAPI(GigaAuth)
    val repo = DesktopInfoRepository(api, VectorDB)
    println(repo.search("жена"))
}
