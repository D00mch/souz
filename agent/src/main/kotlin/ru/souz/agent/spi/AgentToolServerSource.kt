package ru.souz.agent.spi

/** Execution-bound host integration; protocol clients and connection settings stay outside :agent. */
interface AgentToolServerSource {
    val servers: Map<String, String>
    val loadedTools: Map<String, String>
    val discoveryToolName: String
    suspend fun preload(serverIds: Set<String>)
}
