package ru.souz.giga

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.tunnel.AiTunnelChatAPI
import ru.souz.llms.anthropic.AnthropicChatAPI
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.giga.GigaRestChatAPI
import ru.souz.llms.LLMFactory
import ru.souz.llms.openai.OpenAIChatAPI
import ru.souz.llms.qwen.QwenChatAPI
import ru.souz.llms.local.LocalChatAPI
import kotlin.test.Test
import kotlin.test.assertEquals

class LLMFactoryEmbeddingsTest {

    @Test
    fun `embeddings injects selected alias when request uses default marker`() = runTest {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.embeddingsModel } returns EmbeddingsModel.AiTunnelEmbeddingAda

        val restApi = mockk<GigaRestChatAPI>()
        val qwenApi = mockk<QwenChatAPI>()
        val aiTunnelApi = mockk<AiTunnelChatAPI>()
        val anthropicApi = mockk<AnthropicChatAPI>()
        val openAiApi = mockk<OpenAIChatAPI>()
        val localApi = mockk<LocalChatAPI>()

        val requestSlot = slot<LLMRequest.Embeddings>()
        coEvery { aiTunnelApi.embeddings(capture(requestSlot)) } returns LLMResponse.Embeddings.Ok(
            data = emptyList(),
            model = EmbeddingsModel.AiTunnelEmbeddingAda.alias,
            objectType = "list",
        )

        val factory = LLMFactory(
            settingsProvider = settingsProvider,
            restApi = restApi,
            qwenApi = qwenApi,
            aiTunnelApi = aiTunnelApi,
            anthropicApi = anthropicApi,
            openAiApi = openAiApi,
            localApi = localApi,
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

        val restApi = mockk<GigaRestChatAPI>()
        val qwenApi = mockk<QwenChatAPI>()
        val aiTunnelApi = mockk<AiTunnelChatAPI>()
        val anthropicApi = mockk<AnthropicChatAPI>()
        val openAiApi = mockk<OpenAIChatAPI>()
        val localApi = mockk<LocalChatAPI>()

        val requestSlot = slot<LLMRequest.Embeddings>()
        coEvery { aiTunnelApi.embeddings(capture(requestSlot)) } returns LLMResponse.Embeddings.Ok(
            data = emptyList(),
            model = EmbeddingsModel.AiTunnelEmbeddingAda.alias,
            objectType = "list",
        )

        val factory = LLMFactory(
            settingsProvider = settingsProvider,
            restApi = restApi,
            qwenApi = qwenApi,
            aiTunnelApi = aiTunnelApi,
            anthropicApi = anthropicApi,
            openAiApi = openAiApi,
            localApi = localApi,
        )

        factory.embeddings(
            LLMRequest.Embeddings(
                model = EmbeddingsModel.QwenEmbeddings.alias,
                input = listOf("hello"),
            )
        )

        assertEquals(EmbeddingsModel.QwenEmbeddings.alias, requestSlot.captured.model)
    }
}
