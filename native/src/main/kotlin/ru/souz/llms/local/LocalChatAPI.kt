package ru.souz.llms.local

import java.io.File
import kotlinx.coroutines.flow.Flow
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse

class LocalChatAPI(
    private val runtime: LocalLlamaRuntime,
) : LLMChatAPI {
    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat = try {
        val result = runtime.chat(body)
        if (result is LLMResponse.Chat.Ok) {
            result
        } else {
            result
        }
    } catch (error: Exception) {
        LLMResponse.Chat.Error(-1, "Local provider error: ${error.message}")
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> =
        runtime.chatStream(body)

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings = try {
        runtime.embeddings(body)
    } catch (error: Exception) {
        LLMResponse.Embeddings.Error(-1, "Local provider error: ${error.message}")
    }

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile {
        throw UnsupportedOperationException("Local provider does not support file upload in this version.")
    }

    override suspend fun downloadFile(fileId: String): String? {
        throw UnsupportedOperationException("Local provider does not support file download in this version.")
    }

    override suspend fun balance(): LLMResponse.Balance =
        LLMResponse.Balance.Error(-1, "Local provider does not expose balance information.")
}
