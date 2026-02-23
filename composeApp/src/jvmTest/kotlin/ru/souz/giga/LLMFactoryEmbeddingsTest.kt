package ru.souz.giga

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.AiTunnelChatAPI
import ru.souz.llms.AnthropicChatAPI
import ru.souz.llms.OpenAIChatAPI
import ru.souz.llms.QwenChatAPI
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

        val requestSlot = slot<GigaRequest.Embeddings>()
        coEvery { aiTunnelApi.embeddings(capture(requestSlot)) } returns GigaResponse.Embeddings.Ok(
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
        )

        factory.embeddings(
            GigaRequest.Embeddings(
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

        val requestSlot = slot<GigaRequest.Embeddings>()
        coEvery { aiTunnelApi.embeddings(capture(requestSlot)) } returns GigaResponse.Embeddings.Ok(
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
        )

        factory.embeddings(
            GigaRequest.Embeddings(
                model = EmbeddingsModel.QwenEmbeddings.alias,
                input = listOf("hello"),
            )
        )

        assertEquals(EmbeddingsModel.QwenEmbeddings.alias, requestSlot.captured.model)
    }
}
