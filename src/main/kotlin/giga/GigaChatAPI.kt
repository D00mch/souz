package com.dumch.giga

import java.io.File
import kotlinx.coroutines.flow.Flow

interface GigaChatAPI {
    suspend fun message(body: GigaRequest.Chat): GigaResponse.Chat
    suspend fun messageStream(body: GigaRequest.Chat): Flow<GigaResponse.Chat>
    suspend fun uploadImage(file: File): GigaResponse.UploadFile
}

