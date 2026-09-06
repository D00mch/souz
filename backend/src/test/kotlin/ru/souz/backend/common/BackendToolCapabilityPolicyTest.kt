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
    fun `advertised names exclude unsafe tools and match default execution selection`() {
        val names = BackendToolCapabilityPolicy.advertisedToolNames(processCatalog())

        assertTrue(ToolCategory.WEB_SEARCH in BackendToolCapabilityPolicy.safeCategories)
        assertEquals(
            setOf("ReadFile", "WebPageText", "ListActiveChannels", "SendMessageToChannel") +
                LLM_BACKED_TOOL_NAMES,
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
    fun `client search replaces InternetSearch with default and explicit selections`() {
        val advertised = BackendToolCapabilityPolicy.advertisedToolNames(processCatalog())
        val webTools = setOf("InternetSearch", "InternetResearch", "WebPageText")
        listOf(null to advertised, webTools to webTools, emptySet<String>() to emptySet()).forEach { (enabled, expected) ->
            assertEquals(expected, executionToolNames(enabledToolNames = enabled), "enabled=$enabled")
            assertEquals(
                expected - "InternetSearch",
                executionToolNames(enabledToolNames = enabled, clientSearchEnabled = true),
                "client search with enabled=$enabled",
            )
        }
    }

    private fun executionToolNames(
        enabledToolNames: Set<String>?,
        clientSearchEnabled: Boolean = false,
    ): Set<String> =
        BackendToolCapabilityPolicy.selectExecutionTools(
            processToolCatalog = processCatalog(),
            executionLlmToolCatalog = TestToolCatalog(
                ToolCategory.WEB_SEARCH to BackendToolCapabilityPolicy.executionBoundToolNames.toList(),
            ),
            enabledToolNames = enabledToolNames,
            clientSearchEnabled = clientSearchEnabled,
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
