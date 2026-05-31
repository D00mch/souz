package ru.souz.tool

import io.mockk.mockk
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.runtime.GeneratedImage
import ru.souz.llms.runtime.VisionGateway
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.tool.files.ToolDeleteFile
import ru.souz.tool.files.ToolFindFilesByName
import ru.souz.tool.files.ToolFindFolders
import ru.souz.tool.files.ToolFindInFiles
import ru.souz.tool.files.ToolGenerateImage
import ru.souz.tool.files.ToolListFiles
import ru.souz.tool.files.ToolModifyFile
import ru.souz.tool.files.ToolMoveFile
import ru.souz.tool.files.ToolNewFile
import ru.souz.tool.files.ToolViewImage
import ru.souz.tool.math.ToolCalculator
import ru.souz.tool.web.ToolInternetResearch
import ru.souz.tool.web.ToolInternetSearch
import ru.souz.tool.web.ToolWebPageText
import ru.souz.tool.web.internal.WebResearchClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortableRuntimeToolsFactoryTest {
    @Test
    fun `portable catalog exposes android safe tool categories`() {
        val filesToolUtil = mockk<FilesToolUtil>()
        val webResearchClient = WebResearchClient()
        val api = mockk<LLMChatAPI>()
        val settingsProvider = mockk<SettingsProvider>()

        val factory = PortableRuntimeToolsFactory(
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
        )

        val tools = factory.toolsByCategory

        assertTrue("ListFiles" in tools.getValue(ToolCategory.FILES))
        assertTrue("ViewImage" in tools.getValue(ToolCategory.IMAGE))
        assertTrue("GenerateImage" in tools.getValue(ToolCategory.IMAGE_GENERATION))
        assertTrue("InternetSearch" in tools.getValue(ToolCategory.WEB_SEARCH))
        assertTrue("Calculator" in tools.getValue(ToolCategory.CALCULATOR))
        assertEquals(emptyMap(), tools.getValue(ToolCategory.DATA_ANALYTICS))
        assertEquals(emptyMap(), tools.getValue(ToolCategory.DESKTOP))
    }
}
