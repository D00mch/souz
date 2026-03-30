package ru.souz.agent.spi

/**
 * Supplies user-facing error strings for agent flows.
 *
 * The agent module owns error handling logic, while the host application keeps
 * ownership of localization and resource lookup.
 */
interface AgentErrorMessages {
    /** Message shown when the agent clears history because the context is too large. */
    suspend fun contextReset(): String

    /** Message shown when an LLM request times out. */
    suspend fun timeout(): String

    /** Message shown when the configured provider has no balance left. */
    suspend fun noMoney(): String
}
