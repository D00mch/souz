package ru.souz.tool

import io.mockk.mockk
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.tool.dataAnalytics.ToolCreatePlotFromCsv
import ru.souz.tool.dataAnalytics.excel.ExcelRead
import ru.souz.tool.dataAnalytics.excel.ExcelReport
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
import ru.souz.tool.web.ToolWebImageSearch
import ru.souz.tool.web.ToolWebPageText
import ru.souz.tool.web.internal.WebResearchClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortableRuntimeToolsFactoryTest {
    @Test
    fun `portable catalog exposes runtime safe tool categories`() {
        val filesToolUtil = mockk<FilesToolUtil>()
        val factory = portableFactory(filesToolUtil)

        val tools = factory.toolsByCategory

        assertTrue("ListFiles" in tools.getValue(ToolCategory.FILES))
        assertEquals(emptyMap(), tools.getValue(ToolCategory.IMAGE))
        assertEquals(emptyMap(), tools.getValue(ToolCategory.IMAGE_GENERATION))
        assertEquals(setOf("WebPageText"), tools.getValue(ToolCategory.WEB_SEARCH).keys)
        assertTrue("Calculator" in tools.getValue(ToolCategory.CALCULATOR))
        assertEquals(emptyMap(), tools.getValue(ToolCategory.DATA_ANALYTICS))
        assertEquals(emptyMap(), tools.getValue(ToolCategory.DESKTOP))
    }

    @Test
    fun `JVM catalog extends the portable catalog`() {
        val filesToolUtil = mockk<FilesToolUtil>()
        val factory = RuntimeToolsFactory(
            portableToolsFactory = portableFactory(filesToolUtil),
            toolExtractText = ToolExtractText(filesToolUtil),
            toolReadPdfPages = ToolReadPdfPages(filesToolUtil),
            toolCreatePlotFromCsv = ToolCreatePlotFromCsv(filesToolUtil),
            excelRead = ExcelRead(filesToolUtil),
            excelReport = ExcelReport(filesToolUtil),
            toolWebImageSearch = ToolWebImageSearch(filesToolUtil),
        )

        val tools = factory.toolsByCategory

        assertTrue("ListFiles" in tools.getValue(ToolCategory.FILES))
        assertTrue("ExtractTextFromFile" in tools.getValue(ToolCategory.FILES))
        assertTrue("ReadPdfPages" in tools.getValue(ToolCategory.FILES))
        assertTrue("WebImageSearch" in tools.getValue(ToolCategory.WEB_SEARCH))
        assertEquals(
            setOf("CreatePlot", "ExcelRead", "ExcelReport"),
            tools.getValue(ToolCategory.DATA_ANALYTICS).keys,
        )
    }

    private fun portableFactory(filesToolUtil: FilesToolUtil): PortableRuntimeToolsFactory {
        val webResearchClient = WebResearchClient()
        return PortableRuntimeToolsFactory(
            toolListFiles = ToolListFiles(filesToolUtil),
            toolFindInFiles = ToolFindInFiles(filesToolUtil),
            toolNewFile = ToolNewFile(filesToolUtil),
            toolDeleteFile = ToolDeleteFile(filesToolUtil),
            toolModifyFile = ToolModifyFile(filesToolUtil),
            toolMoveFile = ToolMoveFile(filesToolUtil),
            toolFindFilesByName = ToolFindFilesByName(filesToolUtil),
            toolFindFolders = ToolFindFolders(filesToolUtil),
            toolCalculator = ToolCalculator(),
            toolWebPageText = ToolWebPageText(webResearchClient),
        )
    }
}
