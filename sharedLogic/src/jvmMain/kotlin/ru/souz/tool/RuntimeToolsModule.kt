package ru.souz.tool

import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.giga.toGiga
import ru.souz.runtime.sandbox.DefaultRuntimeSandboxFactory
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import ru.souz.runtime.sandbox.ToolInvocationSandboxScopeResolver
import ru.souz.tool.dataAnalytics.ToolCreatePlotFromCsv
import ru.souz.tool.dataAnalytics.excel.ExcelRead
import ru.souz.tool.dataAnalytics.excel.ExcelReport
import ru.souz.tool.files.ToolExtractText
import ru.souz.tool.files.ToolReadPdfPages
import ru.souz.tool.web.ToolWebImageSearch
import ru.souz.tool.web.internal.WebImageDownloader
import ru.souz.tool.web.internal.WebResearchClient

fun runtimeToolsDiModule(
    includeWebImageSearch: Boolean = true,
    includeLlmBackedTools: Boolean = true,
    scopeResolver: ToolInvocationSandboxScopeResolver = defaultToolInvocationSandboxScopeResolver(),
): DI.Module = DI.Module("runtimeTools") {
    bindSingleton<RuntimeSandboxFactory> { DefaultRuntimeSandboxFactory(settingsProvider = instance()) }
    import(
        portableRuntimeToolsDiModule(
            scopeResolver = scopeResolver,
            bindAgentToolCatalog = false,
            includeLlmBackedTools = includeLlmBackedTools,
        )
    )
    bindSingleton { ToolExtractText(instance()) }
    bindSingleton { ToolReadPdfPages(instance()) }

    bindSingleton { ToolCreatePlotFromCsv(instance()) }
    bindSingleton { ExcelRead(instance()) }
    bindSingleton { ExcelReport(instance()) }

    if (includeWebImageSearch) {
        bindSingleton { WebImageDownloader(instance()) }
        bindSingleton { ToolWebImageSearch(filesToolUtil = instance(), webResearchClient = instance(), webImageDownloader = instance()) }
    }

    bindSingleton {
        RuntimeToolsFactory(
            portableToolsFactory = instance(),
            toolExtractText = instance(),
            toolReadPdfPages = instance(),
            toolCreatePlotFromCsv = instance(),
            excelRead = instance(),
            excelReport = instance(),
            toolWebImageSearch = if (includeWebImageSearch) instance() else null,
        )
    }
    bindSingleton<AgentToolCatalog> { instance<RuntimeToolsFactory>() }
}

class RuntimeToolsFactory(
    private val portableToolsFactory: PortableRuntimeToolsFactory,
    private val toolExtractText: ToolExtractText,
    private val toolReadPdfPages: ToolReadPdfPages,
    private val toolCreatePlotFromCsv: ToolCreatePlotFromCsv,
    private val excelRead: ExcelRead,
    private val excelReport: ExcelReport,
    private val toolWebImageSearch: ToolWebImageSearch?,
) : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> by lazy {
        ToolCategory.entries.associateWith { category ->
            portableToolsFactory.toolsByCategory.getValue(category) +
                category.jvmTools().associateBy { it.fn.name }
        }
    }

    private fun ToolCategory.jvmTools(): List<LLMToolSetup> = when (this) {
        ToolCategory.FILES -> listOf(
            toolExtractText.toGiga(),
            toolReadPdfPages.toGiga(),
        )

        ToolCategory.WEB_SEARCH -> listOfNotNull(toolWebImageSearch?.toGiga())

        ToolCategory.DATA_ANALYTICS -> listOf(
            toolCreatePlotFromCsv.toGiga(),
            excelRead.toGiga(),
            excelReport.toGiga(),
        )

        ToolCategory.BROWSER,
        ToolCategory.CONFIG,
        ToolCategory.IMAGE,
        ToolCategory.IMAGE_GENERATION,
        ToolCategory.NOTES,
        ToolCategory.APPLICATIONS,
        ToolCategory.CALENDAR,
        ToolCategory.MAIL,
        ToolCategory.TEXT_REPLACE,
        ToolCategory.CHAT,
        ToolCategory.TELEGRAM,
        ToolCategory.DESKTOP,
        ToolCategory.CALCULATOR,
        ToolCategory.HELP -> emptyList()
    }
}
