package ru.souz.agent.session

/** Host-owned persistence for graph execution traces. */
interface GraphSessionRepository {
    fun save(session: GraphSession)
    /** Fetches sessions, newest first. */
    fun loadAll(): List<GraphSession>
    fun loadById(sessionId: String): GraphSession?
    fun delete(sessionId: String): Boolean
    fun count(): Int
}
