package ru.souz.memory

import java.io.File
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMChatAPI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class LlmEmbeddingClientTest {
    @Test
    fun `embedding request uses selected settings model`() = runTest {
        val api = RecordingEmbeddingApi()
        val settingsProvider = mockk<SettingsProvider> {
            every { embeddingsModel } returns EmbeddingsModel.QwenEmbeddings
        }
        val client = LlmEmbeddingClient(api, settingsProvider)

        val embedding = client.embedDocument("hello")

        assertEquals(EmbeddingsModel.QwenEmbeddings.alias, client.model)
        assertEquals(EmbeddingsModel.QwenEmbeddings.alias, api.requests.single().model)
        assertContentEquals(floatArrayOf(0.25f, 0.5f), embedding)
    }

    private class RecordingEmbeddingApi : LLMChatAPI {
        val requests = mutableListOf<LLMRequest.Embeddings>()

        override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings {
            requests += body
            return LLMResponse.Embeddings.Ok(
                data = listOf(LLMResponse.Embedding(listOf(0.25, 0.5), index = 0)),
                model = body.model,
                objectType = "list",
            )
        }

        override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat =
            error("Chat is not used in this test.")

        override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> =
            emptyFlow()

        override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
            error("File upload is not used in this test.")

        override suspend fun downloadFile(fileId: String): String? =
            error("File download is not used in this test.")

        override suspend fun balance(): LLMResponse.Balance =
            error("Balance is not used in this test.")
    }
}
