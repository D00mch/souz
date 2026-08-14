package ru.souz.tool

import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.runtime.ImageGenerationGateway
import ru.souz.llms.runtime.VisionGateway
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.tool.web.internal.WebResearchClient
import ru.souz.tool.web.ToolInternetSearch

class ToolsFactoryTest {
    @Test
    fun `desktop catalog composes runtime LLM-backed and desktop sources`() {
        val settingsProvider = mockk<SettingsProvider>() {
            every { useFewShotExamples } returns true
        }
        val runtimeTool = tool("RuntimeTool")
        val desktopTool = tool("DesktopTool")
        val filesToolUtil = FilesToolUtil(ToolInvocationRuntimeSandboxResolver { error("not invoked") })
        val catalog = ToolsFactory(
            runtimeToolCatalog = catalog(ToolCategory.FILES, runtimeTool),
            llmBackedToolCatalog = LlmBackedToolCatalog(
                llmApi = DesktopUnusedLlmApi,
                settingsProvider = settingsProvider,
                filesToolUtil = filesToolUtil,
                webResearchClient = WebResearchClient(),
                visionGateway = VisionGateway { error("not invoked") },
                imageGenerationGateway = ImageGenerationGateway { error("not invoked") },
            ),
            desktopToolCatalog = catalog(ToolCategory.DESKTOP, desktopTool),
            settingsProvider = settingsProvider,
        )

        val names = catalog.toolsByCategory.values.flatMapTo(linkedSetOf()) { it.keys }
        assertTrue("RuntimeTool" in names)
        assertTrue("DesktopTool" in names)
        assertTrue(ToolInternetSearch.NAME in names)
        assertEquals(ToolCategory.entries, catalog.toolsByCategory.keys.toList())
    }
}

private fun catalog(category: ToolCategory, tool: LLMToolSetup): AgentToolCatalog =
    immutableToolCatalogFromLists(mapOf(category to listOf(tool)))

private fun tool(name: String): LLMToolSetup = object : LLMToolSetup {
    override val fn: LLMRequest.Function = LLMRequest.Function(name)
    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        LLMRequest.Message(LLMMessageRole.function, "ok", name = name)
}

private object DesktopUnusedLlmApi : LLMChatAPI {
    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat = error("not invoked")
    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = emptyFlow()
    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings = error("not invoked")
    override suspend fun uploadFile(file: File): LLMResponse.UploadFile = error("not invoked")
    override suspend fun downloadFile(fileId: String): String? = error("not invoked")
    override suspend fun balance(): LLMResponse.Balance = error("not invoked")
}
