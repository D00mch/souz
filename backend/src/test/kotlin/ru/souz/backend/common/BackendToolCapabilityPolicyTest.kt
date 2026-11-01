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
    fun `advertised names exclude unsafe tools and include execution-bound tools`() {
        val names = BackendToolCapabilityPolicy.advertisedToolNames(processCatalog())

        assertFalse(ToolCategory.WEB_SEARCH in BackendToolCapabilityPolicy.safeCategories)
        assertEquals(
            setOf("ReadFile", "ListActiveChannels", "SendMessageToChannel", "ViewImage", "GenerateImage", "personality.update"),
            names,
        )
    }

    @Test
    fun `only the applied plugin catalog supplies reserved capabilities and selection still applies`() {
        val pluginName = "plugin.reference.report"
        val compiled = TestToolCatalog(ToolCategory.FILES to listOf("ReadFile", pluginName))
        val plugins = TestToolCatalog(ToolCategory.PLUGINS to listOf(pluginName, "UnrelatedTool"))
        assertFalse(pluginName in BackendToolCapabilityPolicy.advertisedToolNames(compiled))
        val names = BackendToolCapabilityPolicy.advertisedToolNames(compiled, plugins)
        assertFalse("UnrelatedTool" in names)
        assertEquals(true, pluginName in names)
        val selected = BackendToolCapabilityPolicy.selectExecutionTools(compiled, TestToolCatalog(), setOf(pluginName), plugins)
        assertEquals(setOf(pluginName), selected.toolsByCategory.values.flatMap { it.keys }.toSet())
        val disabled = BackendToolCapabilityPolicy.selectExecutionTools(compiled, TestToolCatalog(), emptySet(), plugins)
        assertEquals(emptyList(), disabled.toolsByCategory.values.flatMap { it.keys })
    }

    private fun processCatalog(): AgentToolCatalog = TestToolCatalog(
        ToolCategory.FILES to listOf("ReadFile"),
        ToolCategory.WEB_SEARCH to listOf("WebPageText", ToolWebImageSearch.NAME, "FutureWebTool"),
        ToolCategory.CHANNEL_MESSAGING to listOf("ListActiveChannels", "SendMessageToChannel"),
        ToolCategory.BROWSER to listOf("ControlBrowser"),
    )

}
