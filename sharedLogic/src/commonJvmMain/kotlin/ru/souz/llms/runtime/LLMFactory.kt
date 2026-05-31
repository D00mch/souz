package ru.souz.llms.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import ru.souz.db.SettingsProvider
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import java.io.File

class LLMFactory(
    private val settingsProvider: SettingsProvider,
    private val apisByProvider: Map<LlmProvider, LLMChatAPI>,
) : LLMChatAPI {

    fun current(): LLMChatAPI =
        apiFor(settingsProvider.gigaModel.provider)

    private fun currentEmbeddings(): LLMChatAPI =
        apiFor(settingsProvider.embeddingsModel.provider)

    private fun apiFor(provider: LlmProvider): LLMChatAPI =
        apisByProvider[provider] ?: UnsupportedProviderApi(provider)

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat =
        current().message(body)

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> =
        current().messageStream(body)

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings {
        val request = if (body.model.equals(EmbeddingsModel.GigaEmbeddings.alias, ignoreCase = true)) {
            body.copy(model = settingsProvider.embeddingsModel.alias)
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
        "Provider $provider is not available in this runtime."

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat =
        LLMResponse.Chat.Error(-1, messageText())

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> =
        flowOf(message(body))

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        LLMResponse.Embeddings.Error(-1, messageText())

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        error(messageText())

    override suspend fun downloadFile(fileId: String): String? = null

    override suspend fun balance(): LLMResponse.Balance =
        LLMResponse.Balance.Error(-1, messageText())
}
