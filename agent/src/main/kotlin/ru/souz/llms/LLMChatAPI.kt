package ru.souz.llms

import java.io.File
import kotlinx.coroutines.flow.Flow

interface LLMChatAPI {
    suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat
    suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat>
    suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings
    suspend fun uploadFile(file: File): LLMResponse.UploadFile
    suspend fun downloadFile(fileId: String): String?
    suspend fun balance(): LLMResponse.Balance
}

