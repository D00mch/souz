package ru.souz.backend.agent.runtime

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import ru.souz.backend.TestSettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.runtime.ImageGenerationGateway
import ru.souz.llms.runtime.VisionGateway
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.tool.LLM_BACKED_TOOL_NAMES
import ru.souz.tool.LlmBackedToolCatalog
import ru.souz.tool.ToolCategory
import ru.souz.tool.files.ToolGenerateImage
import ru.souz.tool.files.ToolViewImage
import ru.souz.tool.web.ToolInternetResearch
import ru.souz.tool.web.ToolInternetSearch
import ru.souz.tool.web.internal.WebResearchClient

class LlmBackedToolCatalogTest {
    @Test
    fun `catalog contains only execution-bound LLM tools`() {
        val catalog = LlmBackedToolCatalog(
            llmApi = UnusedLlmApi,
            settingsProvider = TestSettingsProvider(),
            filesToolUtil = FilesToolUtil(ToolInvocationRuntimeSandboxResolver { error("not invoked") }),
            webResearchClient = WebResearchClient(),
            visionGateway = VisionGateway { error("not invoked") },
            imageGenerationGateway = ImageGenerationGateway { error("not invoked") },
        )

        assertEquals(
            setOf(ToolInternetSearch.NAME, ToolInternetResearch.NAME),
            catalog.toolsByCategory.getValue(ToolCategory.WEB_SEARCH).keys,
        )
        assertEquals(
            setOf(ToolViewImage.NAME),
            catalog.toolsByCategory.getValue(ToolCategory.IMAGE).keys,
        )
        assertEquals(
            setOf(ToolGenerateImage.NAME),
            catalog.toolsByCategory.getValue(ToolCategory.IMAGE_GENERATION).keys,
        )
        assertEquals(
            LLM_BACKED_TOOL_NAMES,
            catalog.toolsByCategory.values.flatMapTo(linkedSetOf()) { it.keys },
        )
    }
}

private object UnusedLlmApi : LLMChatAPI {
    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat = error("not invoked")
    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = emptyFlow()
    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings = error("not invoked")
    override suspend fun uploadFile(file: File): LLMResponse.UploadFile = error("not invoked")
    override suspend fun downloadFile(fileId: String): String? = error("not invoked")
    override suspend fun balance(): LLMResponse.Balance = error("not invoked")
}
