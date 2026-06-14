package ru.souz.giga

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.openai.OpenAIChatAPI
import ru.souz.llms.tunnel.AiTunnelChatAPI
import ru.souz.llms.runtime.LLMFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LLMFactoryEmbeddingsTest {

    @Test
    fun `embeddings injects selected alias when request uses default marker`() = runTest {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.embeddingsModel } returns EmbeddingsModel.AiTunnelEmbeddingAda

        val aiTunnelApi = mockk<AiTunnelChatAPI>()

        val requestSlot = slot<LLMRequest.Embeddings>()
        coEvery { aiTunnelApi.embeddings(capture(requestSlot)) } returns LLMResponse.Embeddings.Ok(
            data = emptyList(),
            model = EmbeddingsModel.AiTunnelEmbeddingAda.alias,
            objectType = "list",
        )

        val factory = LLMFactory(
            settingsProvider = settingsProvider,
            apisByProvider = mapOf(LlmProvider.AI_TUNNEL to aiTunnelApi),
        )

        factory.embeddings(
            LLMRequest.Embeddings(
                input = listOf("hello"),
            )
        )

        assertEquals(EmbeddingsModel.AiTunnelEmbeddingAda.alias, requestSlot.captured.model)
    }

    @Test
    fun `embeddings keeps explicit request model`() = runTest {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.embeddingsModel } returns EmbeddingsModel.AiTunnelEmbeddingAda

        val aiTunnelApi = mockk<AiTunnelChatAPI>()

        val requestSlot = slot<LLMRequest.Embeddings>()
        coEvery { aiTunnelApi.embeddings(capture(requestSlot)) } returns LLMResponse.Embeddings.Ok(
            data = emptyList(),
            model = EmbeddingsModel.AiTunnelEmbeddingAda.alias,
            objectType = "list",
        )

        val factory = LLMFactory(
            settingsProvider = settingsProvider,
            apisByProvider = mapOf(LlmProvider.AI_TUNNEL to aiTunnelApi),
        )

        factory.embeddings(
            LLMRequest.Embeddings(
                model = EmbeddingsModel.QwenEmbeddings.alias,
                input = listOf("hello"),
            )
        )

        assertEquals(EmbeddingsModel.QwenEmbeddings.alias, requestSlot.captured.model)
    }

    @Test
    fun `message routes to selected chat provider`() = runTest {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.gigaModel } returns LLMModel.OpenAIGpt5Nano

        val openAiApi = mockk<OpenAIChatAPI>()
        val request = LLMRequest.Chat(
            model = LLMModel.OpenAIGpt5Nano.alias,
            messages = emptyList(),
        )
        coEvery { openAiApi.message(request) } returns LLMResponse.Chat.Ok(
            choices = emptyList(),
            created = 1L,
            model = LLMModel.OpenAIGpt5Nano.alias,
            usage = LLMResponse.Usage(0, 0, 0, 0),
        )

        val factory = LLMFactory(
            settingsProvider = settingsProvider,
            apisByProvider = mapOf(LlmProvider.OPENAI to openAiApi),
        )

        val response = factory.message(request)

        assertIs<LLMResponse.Chat.Ok>(response)
        assertEquals(LLMModel.OpenAIGpt5Nano.alias, response.model)
    }

    @Test
    fun `message returns unsupported error for missing selected provider`() = runTest {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.gigaModel } returns LLMModel.QwenFlash

        val factory = LLMFactory(
            settingsProvider = settingsProvider,
            apisByProvider = emptyMap<LlmProvider, LLMChatAPI>(),
        )

        val response = factory.message(
            LLMRequest.Chat(
                model = LLMModel.QwenFlash.alias,
                messages = emptyList(),
            )
        )

        val error = assertIs<LLMResponse.Chat.Error>(response)
        assertEquals(-1, error.status)
    }
}
