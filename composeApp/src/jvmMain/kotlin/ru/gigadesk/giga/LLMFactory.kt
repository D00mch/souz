package ru.gigadesk.giga

import kotlinx.coroutines.flow.Flow
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.llms.AiTunnelChatAPI
import ru.gigadesk.llms.AnthropicChatAPI
import ru.gigadesk.llms.QwenChatAPI
import java.io.File

class LLMFactory(
    private val settingsProvider: SettingsProvider,
    private val restApi: GigaRestChatAPI,
    private val grpcApi: GigaGRPCChatApi,
    private val qwenApi: QwenChatAPI,
    private val aiTunnelApi: AiTunnelChatAPI,
    private val anthropicApi: AnthropicChatAPI,
) : GigaChatAPI {

    fun current(): GigaChatAPI {
        val model = settingsProvider.gigaModel
        return when (model.provider) {
            LlmProvider.QWEN -> qwenApi
            LlmProvider.AI_TUNNEL -> aiTunnelApi
            LlmProvider.ANTHROPIC -> anthropicApi
            LlmProvider.GIGA -> if (settingsProvider.useStreaming) grpcApi else restApi
        }
    }

    private fun currentEmbeddings(): GigaChatAPI {
        return when (settingsProvider.embeddingsModel.provider) {
            EmbeddingsProvider.QWEN -> qwenApi
            EmbeddingsProvider.AI_TUNNEL -> aiTunnelApi
            EmbeddingsProvider.GIGA -> restApi
        }
    }

    override suspend fun message(body: GigaRequest.Chat): GigaResponse.Chat = current().message(body)

    override suspend fun messageStream(body: GigaRequest.Chat): Flow<GigaResponse.Chat> = current().messageStream(body)

    override suspend fun embeddings(body: GigaRequest.Embeddings): GigaResponse.Embeddings {
        // TODO: Update all the related data on model change if you want to use different models for embeddings
        return restApi.embeddings(body)
    }

    override suspend fun uploadFile(file: File): GigaResponse.UploadFile = current().uploadFile(file)

    override suspend fun downloadFile(fileId: String): String? = current().downloadFile(fileId)

    override suspend fun balance(): GigaResponse.Balance = current().balance()
}
