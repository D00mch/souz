package ru.souz.db

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse

class DesktopInfoRepositoryTest {

    @AfterTest
    fun tearDown() {
        ConfigStore.rm("rag_repo_last_run")
        unmockkAll()
    }

    @Test
    fun `rebuildIndexNow ignores same day guard and rebuilds immediately`() {
        mockkObject(VectorDB)
        every { VectorDB.initializeOnce() } just runs
        every { VectorDB.clearAllData() } just runs
        every { VectorDB.insert(any(), any()) } just runs

        val api = mockk<LLMChatAPI>()
        coEvery { api.embeddings(any()) } returns LLMResponse.Embeddings.Ok(
            data = listOf(LLMResponse.Embedding(listOf(0.1, 0.2), 0, "embedding")),
            model = "Embeddings",
            objectType = "list",
        )

        val extractor = mockk<DesktopDataExtractor>()
        every { extractor.all() } returns listOf(
            StorredData("sample text", StorredType.GENERAL_FACT),
        )

        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.embeddingsModel } returns EmbeddingsModel.GigaEmbeddings
        every { settingsProvider.gigaChatKey } returns "giga-key"

        val repository = DesktopInfoRepository(api, VectorDB, extractor, settingsProvider)
        ConfigStore.put("rag_repo_last_run", LocalDate.now().toString())

        kotlinx.coroutines.runBlocking {
            repository.storeDesktopDataDaily()
            repository.rebuildIndexNow()
        }

        coVerify(exactly = 1) { api.embeddings(any()) }
    }
}
