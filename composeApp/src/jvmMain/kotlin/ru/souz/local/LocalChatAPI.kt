package ru.souz.local

import java.io.File
import kotlinx.coroutines.flow.Flow
import ru.souz.giga.GigaChatAPI
import ru.souz.giga.GigaRequest
import ru.souz.giga.GigaResponse

class LocalChatAPI(
    private val runtime: LocalLlamaRuntime,
) : GigaChatAPI {
    override suspend fun message(body: GigaRequest.Chat): GigaResponse.Chat = try {
        val result = runtime.chat(body)
        if (result is GigaResponse.Chat.Ok) {
            result
        } else {
            result
        }
    } catch (error: Exception) {
        GigaResponse.Chat.Error(-1, "Local provider error: ${error.message}")
    }

    override suspend fun messageStream(body: GigaRequest.Chat): Flow<GigaResponse.Chat> =
        runtime.chatStream(body)

    override suspend fun embeddings(body: GigaRequest.Embeddings): GigaResponse.Embeddings =
        GigaResponse.Embeddings.Error(-1, "Local provider does not support embeddings in this version.")

    override suspend fun uploadFile(file: File): GigaResponse.UploadFile {
        throw UnsupportedOperationException("Local provider does not support file upload in this version.")
    }

    override suspend fun downloadFile(fileId: String): String? {
        throw UnsupportedOperationException("Local provider does not support file download in this version.")
    }

    override suspend fun balance(): GigaResponse.Balance =
        GigaResponse.Balance.Error(-1, "Local provider does not expose balance information.")
}
