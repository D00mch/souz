package ru.souz.tool

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.giga.toGiga
import ru.souz.llms.runtime.ImageGenerationGateway
import ru.souz.llms.runtime.VisionGateway
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.tool.files.ToolGenerateImage
import ru.souz.tool.files.ToolViewImage
import ru.souz.tool.web.ToolInternetResearch
import ru.souz.tool.web.ToolInternetSearch
import ru.souz.tool.web.internal.WebResearchClient

val LLM_BACKED_TOOL_NAMES: Set<String> = setOf(
    ToolInternetSearch.NAME,
    ToolInternetResearch.NAME,
    ToolViewImage.NAME,
    ToolGenerateImage.NAME,
)

class LlmBackedToolCatalog(
    llmApi: LLMChatAPI,
    settingsProvider: SettingsProvider,
    filesToolUtil: FilesToolUtil,
    webResearchClient: WebResearchClient,
    visionGateway: VisionGateway,
    imageGenerationGateway: ImageGenerationGateway,
) {
    val catalog = AgentToolCatalog(
        mapOf(
            ToolCategory.WEB_SEARCH to listOf(
                ToolInternetSearch(llmApi, settingsProvider, filesToolUtil, webResearchClient).toGiga(),
                ToolInternetResearch(llmApi, settingsProvider, filesToolUtil, webResearchClient).toGiga(),
            ),
            ToolCategory.IMAGE to listOf(
                ToolViewImage(filesToolUtil, visionGateway).toGiga(),
            ),
            ToolCategory.IMAGE_GENERATION to listOf(
                ToolGenerateImage(filesToolUtil, imageGenerationGateway).toGiga(),
            ),
        )
    )

    init {
        val concreteNames = catalog.toolsByCategory.values.flatMapTo(linkedSetOf()) { it.keys }
        check(concreteNames == LLM_BACKED_TOOL_NAMES) {
            "LLM-backed catalog must contain exactly $LLM_BACKED_TOOL_NAMES, but was $concreteNames."
        }
    }
}

fun LLMToolSetup.withoutFewShotExamples(): LLMToolSetup {
    val delegate = this
    return object : LLMToolSetup {
        override val fn: LLMRequest.Function = delegate.fn.copy(fewShotExamples = emptyList())

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
            delegate.invoke(functionCall)

        override suspend fun invoke(
            functionCall: LLMResponse.FunctionCall,
            meta: ToolInvocationMeta,
        ): LLMRequest.Message = delegate.invoke(functionCall, meta)
    }
}
