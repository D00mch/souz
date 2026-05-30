package ru.souz.llms.runtime

import kotlinx.coroutines.flow.Flow
import ru.souz.db.SettingsProvider
import ru.souz.llms.EmbeddingInputKind
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.openai.OpenAIChatAPI
import java.io.File

class LLMFactory(
    private val settingsProvider: SettingsProvider,
    private val openAiApi: OpenAIChatAPI,
) : LLMChatAPI {

    fun current(): LLMChatAPI = when (settingsProvider.gigaModel.provider) {
        LlmProvider.OPENAI -> openAiApi
        else -> UnsupportedProviderApi(settingsProvider.gigaModel.provider)
    }

    private fun currentEmbeddings(): LLMChatAPI = when (settingsProvider.embeddingsModel.provider) {
        LlmProvider.OPENAI -> openAiApi
        else -> UnsupportedProviderApi(settingsProvider.embeddingsModel.provider)
    }

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat =
        current().message(body)

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> =
        current().messageStream(body)

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings {
        val request = if (body.model.equals(EmbeddingsModel.GigaEmbeddings.alias, ignoreCase = true)) {
            body.copy(model = settingsProvider.embeddingsModel.alias, inputKind = EmbeddingInputKind.QUERY)
        } else {
            body
        }
        return currentEmbeddings().embeddings(request)
    }

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        current().uploadFile(file)

    override suspend fun downloadFile(fileId: String): String? =
        current().downloadFile(fileId)

    override suspend fun balance(): LLMResponse.Balance =
        current().balance()
}

private class UnsupportedProviderApi(
    private val provider: LlmProvider,
) : LLMChatAPI {
    private fun messageText(): String =
        "Provider $provider is not available in the Android chat-agent runtime yet."

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat =
        LLMResponse.Chat.Error(-1, messageText())

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> =
        kotlinx.coroutines.flow.flowOf(message(body))

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        LLMResponse.Embeddings.Error(-1, messageText())

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        error(messageText())

    override suspend fun downloadFile(fileId: String): String? = null

    override suspend fun balance(): LLMResponse.Balance =
        LLMResponse.Balance.Error(-1, messageText())
}
