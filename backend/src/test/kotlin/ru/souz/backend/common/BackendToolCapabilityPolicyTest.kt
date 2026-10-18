package ru.souz.backend.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import ru.souz.backend.personality.PersonalityTool
import ru.souz.backend.testutil.TestToolCatalog
import ru.souz.tool.ToolCategory
import ru.souz.tool.web.ToolWebImageSearch

class BackendToolCapabilityPolicyTest {
    @Test
    fun `advertised names exclude unsafe tools and include execution-bound tools`() {
        val names = BackendToolCapabilityPolicy.advertisedToolNames(TestToolCatalog(
            ToolCategory.FILES to listOf("ReadFile"),
            ToolCategory.WEB_SEARCH to listOf("WebPageText", ToolWebImageSearch.NAME, "FutureWebTool"),
            ToolCategory.CHANNEL_MESSAGING to listOf("ListActiveChannels", "SendMessageToChannel"),
            ToolCategory.BROWSER to listOf("ControlBrowser"),
        ))

        assertFalse(ToolCategory.WEB_SEARCH in BackendToolCapabilityPolicy.safeCategories)
        assertEquals(
            setOf("ReadFile", "ListActiveChannels", "SendMessageToChannel", "ViewImage", "GenerateImage", PersonalityTool.NAME),
            names,
        )
    }
}
