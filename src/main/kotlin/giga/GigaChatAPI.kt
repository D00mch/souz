package com.dumch.giga

import java.io.File

interface GigaChatAPI {
    suspend fun message(body: GigaRequest.Chat): GigaResponse.Chat
    suspend fun uploadImage(file: File): GigaResponse.UploadFile
}

