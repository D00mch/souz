package ru.souz.llms.runtime

import kotlinx.coroutines.flow.Flow
import ru.souz.db.SettingsProvider
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.tunnel.AiTunnelChatAPI
import ru.souz.llms.anthropic.AnthropicChatAPI
import ru.souz.llms.giga.GigaRestChatAPI
import ru.souz.llms.openai.OpenAIChatAPI
import ru.souz.llms.qwen.QwenChatAPI
import ru.souz.llms.local.LocalChatAPI
import java.io.File

class LLMFactory(
    private val settingsProvider: SettingsProvider,
    private val restApi: GigaRestChatAPI,
    private val qwenApi: QwenChatAPI,
    private val aiTunnelApi: AiTunnelChatAPI,
    private val anthropicApi: AnthropicChatAPI,
    private val openAiApi: OpenAIChatAPI,
    private val localApi: LocalChatAPI,
) : LLMChatAPI {

    private fun chatApiFor(provider: LlmProvider): LLMChatAPI = when (provider) {
        LlmProvider.QWEN -> qwenApi
        LlmProvider.AI_TUNNEL -> aiTunnelApi
        LlmProvider.ANTHROPIC -> anthropicApi
        LlmProvider.OPENAI -> openAiApi
        LlmProvider.GIGA -> restApi
        LlmProvider.LOCAL -> localApi
    }

    fun current(): LLMChatAPI {
        val model = settingsProvider.gigaModel
        return chatApiFor(model.provider)
    }

    private fun currentEmbeddings(): LLMChatAPI {
        return chatApiFor(settingsProvider.embeddingsModel.provider)
    }

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat = current().message(body)

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = current().messageStream(body)

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings {
        val request = if (body.model.equals(EmbeddingsModel.GigaEmbeddings.alias, ignoreCase = true)) {
            body.copy(model = settingsProvider.embeddingsModel.alias)
        } else {
            body
        }
        return currentEmbeddings().embeddings(request)
    }

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile = current().uploadFile(file)

    override suspend fun downloadFile(fileId: String): String? = current().downloadFile(fileId)

    override suspend fun balance(): LLMResponse.Balance = current().balance()
}
