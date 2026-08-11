package ru.souz.tool

import io.mockk.mockk
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.runtime.GeneratedImage
import ru.souz.llms.runtime.VisionGateway
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.tool.dataAnalytics.ToolCreatePlotFromCsv
import ru.souz.tool.dataAnalytics.excel.ExcelRead
import ru.souz.tool.dataAnalytics.excel.ExcelReport
import ru.souz.tool.files.ToolDeleteFile
import ru.souz.tool.files.ToolExtractText
import ru.souz.tool.files.ToolFindFilesByName
import ru.souz.tool.files.ToolFindFolders
import ru.souz.tool.files.ToolFindInFiles
import ru.souz.tool.files.ToolGenerateImage
import ru.souz.tool.files.ToolListFiles
import ru.souz.tool.files.ToolModifyFile
import ru.souz.tool.files.ToolMoveFile
import ru.souz.tool.files.ToolNewFile
import ru.souz.tool.files.ToolReadPdfPages
import ru.souz.tool.files.ToolViewImage
import ru.souz.skilloauth.SkillOAuthGateway
import ru.souz.tool.math.ToolCalculator
import ru.souz.tool.skills.ToolConnectOAuthProvider
import ru.souz.tool.skills.ToolSafeApiCall
import ru.souz.tool.web.ToolInternetResearch
import ru.souz.tool.web.ToolInternetSearch
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
        val factory = portableFactory(filesToolUtil, gateway = mockk<SkillOAuthGateway>())

        val tools = factory.toolsByCategory

        assertTrue("ListFiles" in tools.getValue(ToolCategory.FILES))
        assertTrue("ViewImage" in tools.getValue(ToolCategory.IMAGE))
        assertTrue("GenerateImage" in tools.getValue(ToolCategory.IMAGE_GENERATION))
        assertTrue("InternetSearch" in tools.getValue(ToolCategory.WEB_SEARCH))
        assertTrue("Calculator" in tools.getValue(ToolCategory.CALCULATOR))
        assertTrue("ConnectOAuthProvider" in tools.getValue(ToolCategory.OAUTH))
        assertEquals(emptyMap(), tools.getValue(ToolCategory.DATA_ANALYTICS))
        assertEquals(emptyMap(), tools.getValue(ToolCategory.DESKTOP))
    }

    @Test
    fun `portable catalog omits OAuth tools when no OAuth service is bound`() {
        val filesToolUtil = mockk<FilesToolUtil>()
        val factory = portableFactory(filesToolUtil, gateway = null)

        val tools = factory.toolsByCategory

        assertEquals(emptyMap(), tools.getValue(ToolCategory.OAUTH))
    }

    @Test
    fun `JVM catalog extends the portable catalog`() {
        val filesToolUtil = mockk<FilesToolUtil>()
        val factory = RuntimeToolsFactory(
            portableToolsFactory = portableFactory(filesToolUtil, gateway = mockk<SkillOAuthGateway>()),
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
        assertTrue("InternetSearch" in tools.getValue(ToolCategory.WEB_SEARCH))
        assertTrue("WebImageSearch" in tools.getValue(ToolCategory.WEB_SEARCH))
        assertEquals(
            setOf("CreatePlot", "ExcelRead", "ExcelReport"),
            tools.getValue(ToolCategory.DATA_ANALYTICS).keys,
        )
    }

    private fun portableFactory(filesToolUtil: FilesToolUtil, gateway: SkillOAuthGateway?): PortableRuntimeToolsFactory {
        val webResearchClient = WebResearchClient()
        val api = mockk<LLMChatAPI>()
        val settingsProvider = mockk<SettingsProvider>()
        val skillRegistryRepository = mockk<SkillRegistryRepository>()

        return PortableRuntimeToolsFactory(
            toolListFiles = ToolListFiles(filesToolUtil),
            toolFindInFiles = ToolFindInFiles(filesToolUtil),
            toolNewFile = ToolNewFile(filesToolUtil),
            toolDeleteFile = ToolDeleteFile(filesToolUtil),
            toolModifyFile = ToolModifyFile(filesToolUtil),
            toolMoveFile = ToolMoveFile(filesToolUtil),
            toolFindFilesByName = ToolFindFilesByName(filesToolUtil),
            toolFindFolders = ToolFindFolders(filesToolUtil),
            toolViewImage = ToolViewImage(filesToolUtil, VisionGateway { "ok" }),
            toolGenerateImage = ToolGenerateImage(filesToolUtil) {
                GeneratedImage(
                    bytes = ByteArray(0),
                    mimeType = "image/png",
                    provider = "test",
                )
            },
            toolCalculator = ToolCalculator(),
            toolInternetSearch = ToolInternetSearch(api, settingsProvider, filesToolUtil, webResearchClient),
            toolInternetResearch = ToolInternetResearch(api, settingsProvider, filesToolUtil, webResearchClient),
            toolWebPageText = ToolWebPageText(webResearchClient),
            toolConnectOAuthProvider = gateway?.let { ToolConnectOAuthProvider(skillRegistryRepository, gateway = it) },
            toolSafeApiCall = gateway?.let { ToolSafeApiCall(skillRegistryRepository, gateway = it) },
        )
    }
}
