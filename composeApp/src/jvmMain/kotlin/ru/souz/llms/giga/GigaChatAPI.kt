package ru.souz.llms.giga

import java.io.File
import kotlinx.coroutines.flow.Flow
import ru.souz.llms.GigaRequest
import ru.souz.llms.GigaResponse

interface GigaChatAPI {
    suspend fun message(body: GigaRequest.Chat): GigaResponse.Chat
    suspend fun messageStream(body: GigaRequest.Chat): Flow<GigaResponse.Chat>
    suspend fun embeddings(body: GigaRequest.Embeddings): GigaResponse.Embeddings
    suspend fun uploadFile(file: File): GigaResponse.UploadFile
    suspend fun downloadFile(fileId: String): String?
    suspend fun balance(): GigaResponse.Balance
}

