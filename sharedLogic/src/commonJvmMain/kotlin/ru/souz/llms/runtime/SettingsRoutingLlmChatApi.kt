package ru.souz.llms.runtime

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import ru.souz.db.SettingsProvider
import ru.souz.llms.DEFAULT_EMBEDDINGS_MODEL
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider

/** Routes interactive-host calls using the providers currently selected in mutable settings. */
class SettingsRoutingLlmChatApi(
    private val settingsProvider: SettingsProvider,
    private val apisByProvider: Map<LlmProvider, LLMChatAPI>,
) : LLMChatAPI {
    private fun currentChatApi(): LLMChatAPI =
        apiFor(settingsProvider.gigaModel.provider)

    private fun currentEmbeddingsApi(): LLMChatAPI =
        apiFor(settingsProvider.embeddingsModel.provider)

    private fun apiFor(provider: LlmProvider): LLMChatAPI =
        apisByProvider[provider] ?: UnsupportedProviderApi(provider)

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        val (api, request) = chatRoute(body)
        return api.message(request)
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> {
        val (api, request) = chatRoute(body)
        return api.messageStream(request)
    }

    private fun chatRoute(body: LLMRequest.Chat): Pair<LLMChatAPI, LLMRequest.Chat> {
        val provider = body.provider ?: settingsProvider.gigaModel.provider
        val request = if (body.provider != null) body else body.copy(
            model = settingsProvider.resolveLegacyChatModel(provider, body.model),
        )
        return apiFor(provider) to request
    }

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings {
        val normalizedModel = body.model.trim()
        val request = if (normalizedModel.equals(DEFAULT_EMBEDDINGS_MODEL, ignoreCase = true)) {
            body.copy(model = settingsProvider.embeddingsModel.alias)
        } else {
            body.copy(model = normalizedModel)
        }
        return currentEmbeddingsApi().embeddings(request)
    }

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        currentChatApi().uploadFile(file)

    override suspend fun downloadFile(fileId: String): String? =
        currentChatApi().downloadFile(fileId)

    override suspend fun balance(): LLMResponse.Balance =
        currentChatApi().balance()
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
