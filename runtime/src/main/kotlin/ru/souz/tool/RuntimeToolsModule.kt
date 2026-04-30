package ru.souz.tool

import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.tool.config.ToolSoundConfig
import ru.souz.tool.config.ToolSoundConfigDiff
import ru.souz.tool.dataAnalytics.ToolCreatePlotFromCsv
import ru.souz.tool.dataAnalytics.excel.ExcelRead
import ru.souz.tool.dataAnalytics.excel.ExcelReport
import ru.souz.tool.files.FilesToolUtil
import ru.souz.tool.files.ToolDeleteFile
import ru.souz.tool.files.ToolExtractText
import ru.souz.tool.files.ToolFindFilesByName
import ru.souz.tool.files.ToolFindFolders
import ru.souz.tool.files.ToolFindInFiles
import ru.souz.tool.files.ToolListFiles
import ru.souz.tool.files.ToolModifyFile
import ru.souz.tool.files.ToolMoveFile
import ru.souz.tool.files.ToolNewFile
import ru.souz.tool.files.ToolReadPdfPages
import ru.souz.tool.math.ToolCalculator
import ru.souz.tool.web.ToolInternetResearch
import ru.souz.tool.web.ToolInternetSearch
import ru.souz.tool.web.ToolWebImageSearch
import ru.souz.tool.web.ToolWebPageText
import ru.souz.tool.web.internal.WebImageDownloader
import ru.souz.tool.web.internal.WebResearchClient
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.giga.toGiga

fun runtimeToolsDiModule(
    includeWebImageSearch: Boolean = true,
): DI.Module = DI.Module("runtimeTools") {
    bindSingleton { FilesToolUtil(instance()) }
    bindSingleton { ToolListFiles(instance()) }
    bindSingleton { ToolFindInFiles(instance()) }
    bindSingleton { ToolNewFile(instance()) }
    bindSingleton { ToolDeleteFile(instance()) }
    bindSingleton { ToolModifyFile(instance()) }
    bindSingleton { ToolMoveFile(instance()) }
    bindSingleton { ToolExtractText(instance()) }
    bindSingleton { ToolFindFilesByName(instance()) }
    bindSingleton { ToolReadPdfPages(instance()) }
    bindSingleton { ToolFindFolders(instance()) }

    bindSingleton { ToolSoundConfig(instance()) }
    bindSingleton { ToolSoundConfigDiff(instance()) }
    bindSingleton { ToolCalculator() }

    bindSingleton { ToolCreatePlotFromCsv(instance()) }
    bindSingleton { ExcelRead(instance()) }
    bindSingleton { ExcelReport(instance()) }

    bindSingleton { WebResearchClient() }
    bindSingleton { ToolInternetSearch(api = instance(), settingsProvider = instance(), filesToolUtil = instance(), webResearchClient = instance()) }
    bindSingleton { ToolInternetResearch(api = instance(), settingsProvider = instance(), filesToolUtil = instance(), webResearchClient = instance()) }
    if (includeWebImageSearch) {
        bindSingleton { WebImageDownloader(instance()) }
        bindSingleton { ToolWebImageSearch(filesToolUtil = instance(), webResearchClient = instance(), webImageDownloader = instance()) }
    }
    bindSingleton { ToolWebPageText(webResearchClient = instance()) }

    bindSingleton {
        RuntimeToolsFactory(
            toolListFiles = instance(),
            toolFindInFiles = instance(),
            toolNewFile = instance(),
            toolDeleteFile = instance(),
            toolModifyFile = instance(),
            toolMoveFile = instance(),
            toolExtractText = instance(),
            toolFindFilesByName = instance(),
            toolReadPdfPages = instance(),
            toolFindFolders = instance(),
            toolSoundConfig = instance(),
            toolSoundConfigDiff = instance(),
            toolCalculator = instance(),
            toolCreatePlotFromCsv = instance(),
            excelRead = instance(),
            excelReport = instance(),
            toolInternetSearch = instance(),
            toolInternetResearch = instance(),
            toolWebImageSearch = if (includeWebImageSearch) instance() else null,
            toolWebPageText = instance(),
        )
    }
    bindSingleton<AgentToolCatalog> { instance<RuntimeToolsFactory>() }
    bindSingleton<AgentToolsFilter> { RuntimePassThroughToolsFilter }
}

object RuntimePassThroughToolsFilter : AgentToolsFilter {
    override fun applyFilter(
        toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
    ): Map<ToolCategory, Map<String, LLMToolSetup>> = toolsByCategory
}

class RuntimeToolsFactory(
    private val toolListFiles: ToolListFiles,
    private val toolFindInFiles: ToolFindInFiles,
    private val toolNewFile: ToolNewFile,
    private val toolDeleteFile: ToolDeleteFile,
    private val toolModifyFile: ToolModifyFile,
    private val toolMoveFile: ToolMoveFile,
    private val toolExtractText: ToolExtractText,
    private val toolFindFilesByName: ToolFindFilesByName,
    private val toolReadPdfPages: ToolReadPdfPages,
    private val toolFindFolders: ToolFindFolders,
    private val toolSoundConfig: ToolSoundConfig,
    private val toolSoundConfigDiff: ToolSoundConfigDiff,
    private val toolCalculator: ToolCalculator,
    private val toolCreatePlotFromCsv: ToolCreatePlotFromCsv,
    private val excelRead: ExcelRead,
    private val excelReport: ExcelReport,
    private val toolInternetSearch: ToolInternetSearch,
    private val toolInternetResearch: ToolInternetResearch,
    private val toolWebImageSearch: ToolWebImageSearch?,
    private val toolWebPageText: ToolWebPageText,
) : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> by lazy {
        ToolCategory.entries.associateWith { category ->
            category.tools().associateBy { it.fn.name }
        }
    }

    private fun ToolCategory.tools(): List<LLMToolSetup> = when (this) {
        ToolCategory.FILES -> listOf(
            toolListFiles.toGiga(),
            toolFindInFiles.toGiga(),
            toolNewFile.toGiga(),
            toolDeleteFile.toGiga(),
            toolModifyFile.toGiga(),
            toolMoveFile.toGiga(),
            toolExtractText.toGiga(),
            toolFindFilesByName.toGiga(),
            toolReadPdfPages.toGiga(),
            toolFindFolders.toGiga(),
        )

        ToolCategory.WEB_SEARCH -> buildList {
            add(toolInternetSearch.toGiga())
            add(toolInternetResearch.toGiga())
            toolWebImageSearch?.let { add(it.toGiga()) }
            add(toolWebPageText.toGiga())
        }

        ToolCategory.CONFIG -> listOf(
            toolSoundConfig.toGiga(),
            toolSoundConfigDiff.toGiga(),
        )

        ToolCategory.DATA_ANALYTICS -> listOf(
            toolCreatePlotFromCsv.toGiga(),
            excelRead.toGiga(),
            excelReport.toGiga(),
        )

        ToolCategory.CALCULATOR -> listOf(
            toolCalculator.toGiga(),
        )

        ToolCategory.BROWSER,
        ToolCategory.NOTES,
        ToolCategory.APPLICATIONS,
        ToolCategory.CALENDAR,
        ToolCategory.MAIL,
        ToolCategory.TEXT_REPLACE,
        ToolCategory.CHAT,
        ToolCategory.TELEGRAM,
        ToolCategory.DESKTOP,
        ToolCategory.PRESENTATION,
        ToolCategory.HELP -> emptyList()
    }
}
