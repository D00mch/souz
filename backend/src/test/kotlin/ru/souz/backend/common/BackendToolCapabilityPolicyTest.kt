package ru.souz.backend.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.testutil.TestToolCatalog
import ru.souz.tool.ToolCategory
import ru.souz.tool.web.ToolWebImageSearch

class BackendToolCapabilityPolicyTest {
    @Test
    fun `advertised names exclude unsafe tools and match default execution selection`() {
        val names = BackendToolCapabilityPolicy.advertisedToolNames(processCatalog())

        assertFalse(ToolCategory.WEB_SEARCH in BackendToolCapabilityPolicy.safeCategories)
        assertEquals(
            setOf("ReadFile", "ListActiveChannels", "SendMessageToChannel", "ViewImage", "GenerateImage"),
            names,
        )
        assertEquals(names, executionToolNames(enabledToolNames = null))
    }

    @Test
    fun `execution selection narrows compiled and execution-bound tools to the enabled snapshot`() {
        val executionBoundTool = BackendToolCapabilityPolicy.executionBoundToolNames.first()

        val selected = executionToolNames(
            enabledToolNames = setOf("ReadFile", executionBoundTool, "ControlBrowser"),
        )

        assertEquals(setOf("ReadFile", executionBoundTool), selected)
    }

    @Test
    fun `explicitly enabled compiled web skills remain unavailable`() {
        val webTools = setOf("InternetSearch", "InternetResearch", "WebPageText", ToolWebImageSearch.NAME, "FutureWebTool")
        for (enabled in listOf(webTools, webTools + "ReadFile", emptySet())) {
            assertEquals(enabled intersect setOf("ReadFile"), executionToolNames(enabled), "enabled=$enabled")
        }
    }

    private fun executionToolNames(
        enabledToolNames: Set<String>?,
    ): Set<String> =
        BackendToolCapabilityPolicy.selectExecutionTools(
            processToolCatalog = processCatalog(),
            executionLlmToolCatalog = TestToolCatalog(
                ToolCategory.WEB_SEARCH to listOf("InternetSearch", "InternetResearch"),
                ToolCategory.IMAGE to listOf("ViewImage"),
                ToolCategory.IMAGE_GENERATION to listOf("GenerateImage"),
            ),
            enabledToolNames = enabledToolNames,
        ).toolNames()

    private fun processCatalog(): AgentToolCatalog = TestToolCatalog(
        ToolCategory.FILES to listOf("ReadFile"),
        ToolCategory.WEB_SEARCH to listOf("WebPageText", ToolWebImageSearch.NAME, "FutureWebTool"),
        ToolCategory.CHANNEL_MESSAGING to listOf("ListActiveChannels", "SendMessageToChannel"),
        ToolCategory.BROWSER to listOf("ControlBrowser"),
    )

    private fun AgentToolCatalog.toolNames(): Set<String> =
        toolsByCategory.values.flatMapTo(linkedSetOf()) { tools -> tools.keys }
}
