package ru.souz.backend.execution.model

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.souz.llms.LLMModel

class AgentExecutionRuntimeConfigTest {
    @Test
    fun `runtime config round trips through metadata key`() {
        val config = AgentExecutionRuntimeConfig(
            modelAlias = LLMModel.Max.alias,
            contextSize = 24_000,
            temperature = 0.6f,
            locale = "ru-RU",
            timeZone = "Europe/Moscow",
            systemPrompt = "Stay precise.",
            streamingMessages = true,
            showToolEvents = false,
        )
        val execution = executionWithMetadata(config.toMetadata())

        assertEquals(config, AgentExecutionRuntimeConfig.fromExecution(execution))
        assertTrue(METADATA_RUNTIME_CONFIG in execution.metadata)
    }

    @Test
    fun `runtime config falls back to legacy metadata keys`() {
        val execution = executionWithMetadata(
            mapOf(
                "contextSize" to "32000",
                "temperature" to "0.2",
                "locale" to "en-US",
                "timeZone" to "UTC",
                "systemPrompt" to "Use terse answers.",
                "streamingMessages" to "true",
                "showToolEvents" to "true",
            )
        )

        assertEquals(
            AgentExecutionRuntimeConfig(
                modelAlias = LLMModel.Max.alias,
                contextSize = 32_000,
                temperature = 0.2f,
                locale = "en-US",
                timeZone = "UTC",
                systemPrompt = "Use terse answers.",
                streamingMessages = true,
                showToolEvents = true,
            ),
            AgentExecutionRuntimeConfig.fromExecution(execution),
        )
    }

    private fun executionWithMetadata(metadata: Map<String, String>): AgentExecution =
        AgentExecution(
            id = UUID.randomUUID(),
            userId = "user-a",
            chatId = UUID.randomUUID(),
            userMessageId = null,
            assistantMessageId = null,
            status = AgentExecutionStatus.WAITING_OPTION,
            requestId = null,
            clientMessageId = null,
            model = LLMModel.Max,
            provider = LLMModel.Max.provider,
            startedAt = Instant.parse("2026-05-02T09:00:00Z"),
            finishedAt = null,
            cancelRequested = false,
            errorCode = null,
            errorMessage = null,
            usage = null,
            metadata = metadata,
        )
}
