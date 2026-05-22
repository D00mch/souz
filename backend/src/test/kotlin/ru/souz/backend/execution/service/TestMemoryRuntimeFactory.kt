package ru.souz.backend.execution.service

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.memory.BackendUserMemoryRuntimeFactory
import ru.souz.backend.storage.memory.InMemoryBackendMemoryStore
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse

internal fun testMemoryRuntimeFactory(
    settingsProvider: TestSettingsProvider,
): BackendUserMemoryRuntimeFactory =
    BackendUserMemoryRuntimeFactory(
        store = InMemoryBackendMemoryStore(),
        settingsProvider = settingsProvider,
        llmApiFactory = { _, _ -> NoopEmbeddingsChatApi },
        indexDirResolver = { userId ->
            Files.createTempDirectory("backend-memory-test-index-").resolve(userId)
        },
    )

private object NoopEmbeddingsChatApi : LLMChatAPI {
    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat =
        error("Chat is not used in this test helper.")

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> =
        error("Streaming is not used in this test helper.")

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        LLMResponse.Embeddings.Ok(
            data = body.input.mapIndexed { index, _ ->
                LLMResponse.Embedding(
                    embedding = listOf(0.0, 1.0, 0.0),
                    index = index,
                    objectType = "embedding",
                )
            },
            model = "test-embeddings",
            objectType = "list",
        )

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        error("File upload is not used in this test helper.")

    override suspend fun downloadFile(fileId: String): String? =
        error("File download is not used in this test helper.")

    override suspend fun balance(): LLMResponse.Balance =
        error("Balance is not used in this test helper.")
}
