package ru.souz.db

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import ru.souz.llms.EmbeddingInputKind
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.local.LocalEmbeddingProfiles
import ru.souz.llms.local.LocalModelStore

class DesktopInfoRepositoryTest {
    private var previousLastRun: String? = null
    private var previousIndexModel: String? = null

    @BeforeTest
    fun setUp() {
        previousLastRun = ConfigStore.get(LAST_RUN_KEY)
        previousIndexModel = ConfigStore.get(INDEX_MODEL_KEY)
    }

    @AfterTest
    fun tearDown() {
        restoreKey(LAST_RUN_KEY, previousLastRun)
        restoreKey(INDEX_MODEL_KEY, previousIndexModel)
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
        ConfigStore.put(LAST_RUN_KEY, LocalDate.now().toString())
        ConfigStore.put(INDEX_MODEL_KEY, EmbeddingsModel.GigaEmbeddings.name)

        kotlinx.coroutines.runBlocking {
            repository.storeDesktopDataDaily()
            repository.rebuildIndexNow()
        }

        coVerify(exactly = 1) { api.embeddings(any()) }
    }

    @Test
    fun `storeDesktopDataDaily skips local embeddings until linked asset exists`() {
        mockkObject(VectorDB)
        every { VectorDB.initializeOnce() } just runs
        every { VectorDB.clearAllData() } just runs

        val api = mockk<LLMChatAPI>(relaxed = true)
        val extractor = mockk<DesktopDataExtractor>(relaxed = true)
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.embeddingsModel } returns LocalEmbeddingProfiles.default().embeddingsModel

        val localModelStore = mockk<LocalModelStore>()
        every { localModelStore.isPresent(LocalEmbeddingProfiles.default()) } returns false

        val repository = DesktopInfoRepository(api, VectorDB, extractor, settingsProvider, localModelStore)

        kotlinx.coroutines.runBlocking {
            repository.storeDesktopDataDaily()
            assertEquals(emptyList(), repository.search("query", 5))
        }

        verify(exactly = 0) { VectorDB.clearAllData() }
        coVerify(exactly = 0) { api.embeddings(any()) }
    }

    @Test
    fun `search skips stale index after embeddings model switch`() {
        mockkObject(VectorDB)
        every { VectorDB.initializeOnce() } just runs
        every { VectorDB.clearAllData() } just runs
        every { VectorDB.insert(any(), any()) } just runs
        every { VectorDB.searchSimilar(any(), any()) } returns listOf(StorredData("stale", StorredType.GENERAL_FACT))

        val api = mockk<LLMChatAPI>()
        coEvery { api.embeddings(any()) } returns LLMResponse.Embeddings.Ok(
            data = listOf(LLMResponse.Embedding(listOf(0.1, 0.2), 0, "embedding")),
            model = "Embeddings",
            objectType = "list",
        )

        val extractor = mockk<DesktopDataExtractor>()
        every { extractor.all() } returns listOf(StorredData("sample text", StorredType.GENERAL_FACT))

        var embeddingsModel = EmbeddingsModel.GigaEmbeddings
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.embeddingsModel } answers { embeddingsModel }
        every { settingsProvider.gigaChatKey } returns "giga-key"
        every { settingsProvider.qwenChatKey } returns "qwen-key"

        val repository = DesktopInfoRepository(api, VectorDB, extractor, settingsProvider)

        kotlinx.coroutines.runBlocking {
            repository.rebuildIndexNow()
            embeddingsModel = EmbeddingsModel.QwenEmbeddings
            assertEquals(emptyList(), repository.search("query", 5))
        }

        coVerify(exactly = 1) { api.embeddings(any()) }
        verify(exactly = 0) { VectorDB.searchSimilar(any(), any()) }
    }

    @Test
    fun `storeDesktopInfo marks indexed texts as document embeddings`() {
        mockkObject(VectorDB)
        every { VectorDB.insert(any(), any()) } just runs

        val requestSlot = slot<LLMRequest.Embeddings>()
        val api = mockk<LLMChatAPI>()
        coEvery { api.embeddings(capture(requestSlot)) } returns LLMResponse.Embeddings.Ok(
            data = listOf(LLMResponse.Embedding(listOf(0.1, 0.2), 0, "embedding")),
            model = "Embeddings",
            objectType = "list",
        )

        val repository = DesktopInfoRepository(
            api = api,
            db = VectorDB,
            extractor = mockk(relaxed = true),
            settingsProvider = mockk(relaxed = true),
        )

        kotlinx.coroutines.runBlocking {
            repository.storeDesktopInfo(listOf(StorredData("single doc", StorredType.GENERAL_FACT)))
        }

        assertEquals(EmbeddingInputKind.DOCUMENT, requestSlot.captured.inputKind)
        assertEquals(listOf("single doc"), requestSlot.captured.input)
    }

    private fun restoreKey(key: String, value: String?) {
        if (value == null) {
            ConfigStore.rm(key)
        } else {
            ConfigStore.put(key, value)
        }
    }

    private companion object {
        const val LAST_RUN_KEY = "rag_repo_last_run"
        const val INDEX_MODEL_KEY = "rag_repo_index_model"
    }
}
