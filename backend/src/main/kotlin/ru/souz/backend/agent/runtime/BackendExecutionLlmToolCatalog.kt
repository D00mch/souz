package ru.souz.backend.agent.runtime

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.giga.toGiga
import ru.souz.llms.runtime.ImageGenerationGateway
import ru.souz.llms.runtime.VisionGateway
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.tool.ToolCategory
import ru.souz.tool.files.ToolGenerateImage
import ru.souz.tool.files.ToolViewImage
import ru.souz.tool.web.ToolInternetResearch
import ru.souz.tool.web.ToolInternetSearch
import ru.souz.tool.web.internal.WebResearchClient

/** LLM-dependent tools bound to the current backend execution API and settings. */
internal class BackendExecutionLlmToolCatalog(
    llmApi: LLMChatAPI,
    settingsProvider: SettingsProvider,
    filesToolUtil: FilesToolUtil,
    webResearchClient: WebResearchClient,
    visionGateway: VisionGateway,
    imageGenerationGateway: ImageGenerationGateway,
) : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> = mapOf(
        ToolCategory.WEB_SEARCH to listOf(
            ToolInternetSearch(llmApi, settingsProvider, filesToolUtil, webResearchClient).toGiga(),
            ToolInternetResearch(llmApi, settingsProvider, filesToolUtil, webResearchClient).toGiga(),
        ).associateBy { it.fn.name },
        ToolCategory.IMAGE to listOf(
            ToolViewImage(filesToolUtil, visionGateway).toGiga(),
        ).associateBy { it.fn.name },
        ToolCategory.IMAGE_GENERATION to listOf(
            ToolGenerateImage(filesToolUtil, imageGenerationGateway).toGiga(),
        ).associateBy { it.fn.name },
    )
}
