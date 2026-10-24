package ru.souz.tool

import io.mockk.mockk
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.skilloauth.SkillOAuthGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeToolsFactoryTest {
    @Test
    fun `runtime catalog contains backend file and analytics tools`() {
        val tools = RuntimeToolsFactory(mockk<FilesToolUtil>()).catalog.toolsByCategory

        assertTrue("ListFiles" in tools.getValue(ToolCategory.FILES))
        assertTrue("ExtractTextFromFile" in tools.getValue(ToolCategory.FILES))
        assertTrue("ReadPdfPages" in tools.getValue(ToolCategory.FILES))
        assertTrue("Calculator" in tools.getValue(ToolCategory.CALCULATOR))
        assertEquals(setOf("CreatePlot", "ExcelRead", "ExcelReport"), tools.getValue(ToolCategory.DATA_ANALYTICS).keys)
        assertEquals(emptyMap(), tools.getValue(ToolCategory.IMAGE))
        assertEquals(emptyMap(), tools.getValue(ToolCategory.IMAGE_GENERATION))
        assertEquals(emptyMap(), tools.getValue(ToolCategory.WEB_SEARCH))
    }

    @Test
    fun `runtime catalog includes OAuth tools only with an OAuth service`() {
        val filesToolUtil = mockk<FilesToolUtil>()
        val withoutOAuth = RuntimeToolsFactory(filesToolUtil).catalog.toolsByCategory
        val withOAuth = RuntimeToolsFactory(
            filesToolUtil,
            gateway = mockk<SkillOAuthGateway>(),
            skillRegistry = mockk<SkillRegistryRepository>(),
        ).catalog.toolsByCategory

        assertEquals(emptyMap(), withoutOAuth.getValue(ToolCategory.OAUTH))
        assertEquals(setOf("ConnectOAuthProvider", "SafeApiCall"), withOAuth.getValue(ToolCategory.OAUTH).keys)
    }
}
