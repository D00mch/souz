package ru.souz.backend.client

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import ru.souz.backend.http.routeTestContext
import ru.souz.tool.ToolCategory

class BackendClientToolCatalogTest {
    @Test
    fun `catalog projects bundled client Skills`() = runTest {
        val context = routeTestContext()

        val catalog = BackendClientToolCatalogFactory(
            registry = context.clientThreadRegistry,
            toolCallRepository = context.toolCallRepository,
            eventService = context.eventService,
        ).create()

        val ask = catalog.toolsByCategory.getValue(ToolCategory.CHAT).getValue("user.ask")
        val openMedia = catalog.toolsByCategory.getValue(ToolCategory.APPLICATIONS).getValue("device.media.open")
        assertEquals(setOf("user.ask", "device.media.open"), catalog.toolsByCategory.values.flatMap { it.keys }.toSet())
        assertContains(ask.fn.description, "Ask the user")
        assertContains(openMedia.fn.description, "Open media")
    }
}
