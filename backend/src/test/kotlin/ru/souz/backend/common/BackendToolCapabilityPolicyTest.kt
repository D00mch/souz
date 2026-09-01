package ru.souz.backend.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.testutil.TestToolCatalog
import ru.souz.tool.LLM_BACKED_TOOL_NAMES
import ru.souz.tool.ToolCategory
import ru.souz.tool.web.ToolWebImageSearch

class BackendToolCapabilityPolicyTest {
    @Test
    fun `advertised names combine safe process tools and execution-bound LLM tools`() {
        val names = BackendToolCapabilityPolicy.advertisedToolNames(processCatalog())

        assertEquals(
            setOf("ReadFile", "WebPageText", "ListActiveChannels", "SendMessageToChannel") +
                LLM_BACKED_TOOL_NAMES,
            names,
        )
    }

    @Test
    fun `desktop-only categories and denied names reach neither capabilities nor executions`() {
        assertTrue(ToolCategory.WEB_SEARCH in BackendToolCapabilityPolicy.safeCategories)
        val excluded = setOf("ControlBrowser", ToolWebImageSearch.NAME)

        val advertised = BackendToolCapabilityPolicy.advertisedToolNames(processCatalog())
        val selected = executionToolNames(enabledToolNames = null)

        assertEquals(emptySet(), advertised intersect excluded)
        assertEquals(emptySet(), selected intersect excluded)
    }

    @Test
    fun `execution selection without an enabled snapshot matches advertised names`() {
        assertEquals(
            BackendToolCapabilityPolicy.advertisedToolNames(processCatalog()),
            executionToolNames(enabledToolNames = null),
        )
    }

    @Test
    fun `execution selection narrows compiled and execution-bound tools to the enabled snapshot`() {
        val executionBoundTool = BackendToolCapabilityPolicy.executionBoundToolNames.first()

        val selected = executionToolNames(
            enabledToolNames = setOf("ReadFile", executionBoundTool, "ControlBrowser"),
        )

        assertEquals(setOf("ReadFile", executionBoundTool), selected)
    }

    private fun executionToolNames(enabledToolNames: Set<String>?): Set<String> =
        BackendToolCapabilityPolicy.selectExecutionTools(
            processToolCatalog = processCatalog(),
            executionLlmToolCatalog = TestToolCatalog(
                ToolCategory.WEB_SEARCH to BackendToolCapabilityPolicy.executionBoundToolNames.toList(),
            ),
            enabledToolNames = enabledToolNames,
        ).toolNames()

    private fun processCatalog(): AgentToolCatalog = TestToolCatalog(
        ToolCategory.FILES to listOf("ReadFile"),
        ToolCategory.WEB_SEARCH to listOf("WebPageText", ToolWebImageSearch.NAME),
        ToolCategory.CHANNEL_MESSAGING to listOf("ListActiveChannels", "SendMessageToChannel"),
        ToolCategory.BROWSER to listOf("ControlBrowser"),
    )

    private fun AgentToolCatalog.toolNames(): Set<String> =
        toolsByCategory.values.flatMapTo(linkedSetOf()) { tools -> tools.keys }
}
