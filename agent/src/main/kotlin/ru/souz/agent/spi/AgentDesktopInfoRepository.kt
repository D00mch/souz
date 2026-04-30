package ru.souz.agent.spi

import ru.souz.db.StorredData

/**
 * Provides desktop-index search results to the agent.
 *
 * The agent uses this to enrich prompts with local context without depending on
 * the concrete desktop indexing implementation in `composeApp`.
 */
interface AgentDesktopInfoRepository {
    /**
     * Returns the most relevant locally indexed facts for the given query.
     */
    suspend fun search(query: String, limit: Int = 5): List<StorredData>
}
