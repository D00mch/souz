package ru.souz.backend.execution.service

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.runtime.BackendNoopAgentToolCatalog
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.options.model.Option
import ru.souz.backend.options.model.OptionAnswer
import ru.souz.backend.options.model.OptionItem
import ru.souz.backend.options.model.OptionKind
import ru.souz.backend.options.model.OptionStatus
import ru.souz.backend.settings.service.EffectiveSettingsResolver
import ru.souz.backend.settings.service.UserSettingsOverrides
import ru.souz.backend.storage.memory.MemoryUserProviderKeyRepository
import ru.souz.backend.storage.memory.MemoryUserSettingsRepository
import ru.souz.llms.LLMModel
import ru.souz.llms.LocalModelAvailability
import ru.souz.llms.restJsonMapper

class AgentExecutionRequestFactoryTest {
    @Test
    fun `prepare chat turn resolves settings builds execution metadata and runtime request`() = runTest {
        val featureFlags = BackendFeatureFlags(
            wsEvents = true,
            streamingMessages = true,
            toolEvents = true,
        )
        val settingsProvider = TestSettingsProvider().apply {
            gigaChatKey = "giga-key"
            gigaModel = LLMModel.Pro
            contextSize = 24_000
            temperature = 0.7f
            useStreaming = false
        }
        val factory = AgentExecutionRequestFactory(
            effectiveSettingsResolver = EffectiveSettingsResolver(
                baseSettingsProvider = settingsProvider,
                userSettingsRepository = MemoryUserSettingsRepository(),
                userProviderKeyRepository = MemoryUserProviderKeyRepository(),
                featureFlags = featureFlags,
                toolCatalog = BackendNoopAgentToolCatalog,
                localModelAvailability = unavailableLocalModels(),
            ),
            featureFlags = featureFlags,
        )
        val chatId = UUID.randomUUID()

        val prepared = factory.prepareChatTurn(
            userId = "user-a",
            chatId = chatId,
            content = "Summarize this chat.",
            clientMessageId = "  client-42  ",
            requestOverrides = UserSettingsOverrides(
                contextSize = 32_000,
                temperature = 0.2f,
                locale = Locale.forLanguageTag("en-US"),
                timeZone = ZoneId.of("UTC"),
                systemPrompt = "Use terse answers.",
                showToolEvents = false,
                streamingMessages = true,
            ),
        )

        assertEquals("client-42", prepared.normalizedClientMessageId)
        assertEquals(
            AgentConversationKey(userId = "user-a", conversationId = chatId.toString()),
            prepared.conversationKey,
        )
        assertEquals(mapOf("clientMessageId" to "client-42"), prepared.userMessageMetadata)
        assertTrue(prepared.shouldReturnRunning)

        val effectiveSettings = prepared.effectiveSettings
        assertEquals(LLMModel.Pro, effectiveSettings.defaultModel)
        assertEquals(32_000, effectiveSettings.contextSize)
        assertEquals(0.2f, effectiveSettings.temperature)
        assertEquals(Locale.forLanguageTag("en-US"), effectiveSettings.locale)
        assertEquals(ZoneId.of("UTC"), effectiveSettings.timeZone)
        assertEquals("Use terse answers.", effectiveSettings.systemPrompt)
        assertFalse(effectiveSettings.showToolEvents)
        assertTrue(effectiveSettings.streamingMessages)

        val execution = prepared.execution
        assertEquals(AgentExecutionStatus.QUEUED, execution.status)
        assertEquals("client-42", execution.clientMessageId)
        assertEquals(LLMModel.Pro, execution.model)
        assertEquals(LLMModel.Pro.provider, execution.provider)
        assertEquals("32000", execution.metadata.getValue("contextSize"))
        assertEquals("0.2", execution.metadata.getValue("temperature"))
        assertEquals("en-US", execution.metadata.getValue("locale"))
        assertEquals("UTC", execution.metadata.getValue("timeZone"))
        assertEquals("Use terse answers.", execution.metadata.getValue("systemPrompt"))
        assertEquals("true", execution.metadata.getValue("streamingMessages"))
        assertEquals("false", execution.metadata.getValue("showToolEvents"))

        val runtimeRequest = prepared.runtimeRequest
        assertEquals("Summarize this chat.", runtimeRequest.prompt)
        assertEquals(LLMModel.Pro.alias, runtimeRequest.model)
        assertEquals(32_000, runtimeRequest.contextSize)
        assertEquals("en-US", runtimeRequest.locale)
        assertEquals("UTC", runtimeRequest.timeZone)
        assertEquals(execution.id.toString(), runtimeRequest.executionId)
        assertEquals(0.2f, runtimeRequest.temperature)
        assertEquals("Use terse answers.", runtimeRequest.systemPrompt)
        assertEquals(true, runtimeRequest.streamingMessages)
    }

    @Test
    fun `continuation turn request preserves option answer payload and execution metadata`() {
        val factory = AgentExecutionRequestFactory(
            effectiveSettingsResolver = stubResolver(),
            featureFlags = BackendFeatureFlags(),
        )
        val executionId = UUID.randomUUID()
        val optionId = UUID.randomUUID()
        val execution = AgentExecution(
            id = executionId,
            userId = "user-a",
            chatId = UUID.randomUUID(),
            userMessageId = UUID.randomUUID(),
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
            metadata = mapOf(
                "contextSize" to "24000",
                "temperature" to "0.6",
                "locale" to "ru-RU",
                "timeZone" to "Europe/Moscow",
                "systemPrompt" to "Stay precise.",
                "streamingMessages" to "true",
                "showToolEvents" to "true",
            ),
        )
        val option = Option(
            id = optionId,
            userId = execution.userId,
            chatId = execution.chatId,
            executionId = execution.id,
            kind = OptionKind.GENERIC_SELECTION,
            title = "Pick one",
            selectionMode = "single",
            options = listOf(
                OptionItem(id = "a", label = "Alpha", content = "Alpha content"),
                OptionItem(id = "b", label = "Beta", content = null),
            ),
            payload = emptyMap(),
            status = OptionStatus.ANSWERED,
            answer = OptionAnswer(
                selectedOptionIds = linkedSetOf("a"),
                freeText = "because alpha",
                metadata = mapOf("source" to "web-ui"),
            ),
            createdAt = Instant.parse("2026-05-02T09:01:00Z"),
            expiresAt = null,
            answeredAt = Instant.parse("2026-05-02T09:02:00Z"),
        )

        val request = factory.createContinuationTurnRequest(execution, option)

        assertEquals(LLMModel.Max.alias, request.model)
        assertEquals(24_000, request.contextSize)
        assertEquals("ru-RU", request.locale)
        assertEquals("Europe/Moscow", request.timeZone)
        assertEquals(executionId.toString(), request.executionId)
        assertEquals(0.6f, request.temperature)
        assertEquals("Stay precise.", request.systemPrompt)
        assertEquals(true, request.streamingMessages)
        assertTrue(request.prompt.startsWith("__option_answer__ "))

        val payload = restJsonMapper.readTree(request.prompt.removePrefix("__option_answer__ "))
        assertEquals("option_answer", payload["type"].asText())
        assertEquals(optionId.toString(), payload["optionId"].asText())
        assertEquals("generic_selection", payload["kind"].asText())
        assertEquals("single", payload["selectionMode"].asText())
        assertEquals(listOf("a"), payload["selectedOptionIds"].map { it.asText() })
        val selectedOptions = payload["selectedOptions"]
        assertEquals(1, selectedOptions.size())
        assertEquals("a", selectedOptions[0]["id"].asText())
        assertEquals("Alpha", selectedOptions[0]["label"].asText())
        assertEquals("Alpha content", selectedOptions[0]["content"].asText())
        assertEquals("because alpha", payload["freeText"].asText())
        assertEquals("web-ui", payload["metadata"]["source"].asText())
    }

    private fun stubResolver(): EffectiveSettingsResolver =
        EffectiveSettingsResolver(
            baseSettingsProvider = TestSettingsProvider().apply { gigaChatKey = "giga-key" },
            userSettingsRepository = MemoryUserSettingsRepository(),
            userProviderKeyRepository = MemoryUserProviderKeyRepository(),
            featureFlags = BackendFeatureFlags(),
            toolCatalog = BackendNoopAgentToolCatalog,
            localModelAvailability = unavailableLocalModels(),
        )

    private fun unavailableLocalModels(): LocalModelAvailability =
        object : LocalModelAvailability {
            override fun isProviderAvailable(): Boolean = false

            override fun availableGigaModels() = emptyList<LLMModel>()

            override fun defaultGigaModel(): LLMModel? = null
        }
}
