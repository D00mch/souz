package ru.souz.tool

import java.util.Collections
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
) : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> =
        immutableToolCatalogFromLists(
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
        ).toolsByCategory

    init {
        val concreteNames = toolsByCategory.values.flatMapTo(linkedSetOf()) { it.keys }
        check(concreteNames == LLM_BACKED_TOOL_NAMES) {
            "LLM-backed catalog must contain exactly $LLM_BACKED_TOOL_NAMES, but was $concreteNames."
        }
    }
}

fun composeToolCatalogs(
    catalogs: List<AgentToolCatalog>,
    allowLaterSourceOverrides: Boolean = false,
): AgentToolCatalog {
    val toolsByCategory = ToolCategory.entries.associateWith { linkedMapOf<String, LLMToolSetup>() }
    val categoryByToolName = linkedMapOf<String, ToolCategory>()

    catalogs.forEach { catalog ->
        val sourceCategoryByToolName = linkedMapOf<String, ToolCategory>()
        ToolCategory.entries.forEach { category ->
            catalog.toolsByCategory[category].orEmpty().forEach { (toolName, tool) ->
                require(toolName == tool.fn.name) {
                    "Tool catalog key '$toolName' does not match function name '${tool.fn.name}'."
                }
                val duplicateSourceCategory = sourceCategoryByToolName.putIfAbsent(toolName, category)
                require(duplicateSourceCategory == null) {
                    "Duplicate tool name '$toolName' in one source across categories " +
                        "$duplicateSourceCategory and $category."
                }
                val previousCategory = categoryByToolName[toolName]
                if (previousCategory != null && !allowLaterSourceOverrides) {
                    error("Duplicate tool name '$toolName' in categories $previousCategory and $category.")
                }
                if (previousCategory != null) {
                    toolsByCategory.getValue(previousCategory).remove(toolName)
                }
                toolsByCategory.getValue(category)[toolName] = tool
                categoryByToolName[toolName] = category
            }
        }
    }

    return immutableToolCatalogSnapshot(toolsByCategory)
}

fun immutableToolCatalogFromLists(
    toolsByCategory: Map<ToolCategory, List<LLMToolSetup>>,
): AgentToolCatalog {
    val catalog = object : AgentToolCatalog {
        override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> =
            ToolCategory.entries.associateWith { category ->
                val tools = toolsByCategory[category].orEmpty()
                val duplicateNames = tools.groupingBy { it.fn.name }.eachCount().filterValues { it > 1 }.keys
                require(duplicateNames.isEmpty()) { "Duplicate tool names: ${duplicateNames.joinToString()}." }
                tools.associateByTo(linkedMapOf()) { it.fn.name }
            }
    }
    return composeToolCatalogs(listOf(catalog))
}

fun immutableToolCatalogSnapshot(
    toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
): AgentToolCatalog {
    val immutableCategories = linkedMapOf<ToolCategory, Map<String, LLMToolSetup>>()
    val categoryByToolName = linkedMapOf<String, ToolCategory>()
    ToolCategory.entries.forEach { category ->
        toolsByCategory[category].orEmpty().forEach { (toolName, tool) ->
            require(toolName == tool.fn.name) {
                "Tool catalog key '$toolName' does not match function name '${tool.fn.name}'."
            }
            val previousCategory = categoryByToolName.putIfAbsent(toolName, category)
            require(previousCategory == null) {
                "Duplicate tool name '$toolName' in categories $previousCategory and $category."
            }
        }
        immutableCategories[category] = Collections.unmodifiableMap(
            LinkedHashMap(toolsByCategory[category].orEmpty())
        )
    }
    val immutableSnapshot = Collections.unmodifiableMap(immutableCategories)
    return object : AgentToolCatalog {
        override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> = immutableSnapshot
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
