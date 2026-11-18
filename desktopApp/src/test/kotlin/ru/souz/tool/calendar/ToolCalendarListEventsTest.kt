package ru.souz.tool.calendar

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.ToolRunBashCommand
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolCalendarListEventsTest {
    @Test
    @EnabledOnOs(OS.MAC)
    fun `cancelling event listing terminates a stalled native query`() = runBlocking {
        supervisorScope {
            // Exercise the production process runner without accessing calendars or requesting consent.
            val stalledQuery = "ObjC.import('Foundation'); $.NSThread.sleepForTimeInterval(60); // ${UUID.randomUUID()}"
            fun queryProcesses(): List<ProcessHandle> = ProcessHandle.current().children().use { children ->
                children.filter { it.isAlive && it.info().arguments().orElse(emptyArray()).contains(stalledQuery) }.toList()
            }
            val bash = mockk<ToolRunBashCommand>()
            every { bash.javaScript(any(), any(), 35_000) } answers {
                ToolRunBashCommand.javaScript(stalledQuery, emptyList(), 35_000)
            }
            // Keep the test's cancellation deadline responsive even with the broken blocking implementation.
            val query = async(Dispatchers.Default) {
                ToolCalendarListEvents(bash).suspendInvoke(
                    ToolCalendarListEvents.Input("Synthetic cancellation fixture", "2026-11-18"),
                    ToolInvocationMeta.localDefault(),
                )
            }
            try {
                val process = withTimeout(5_000) {
                    while (queryProcesses().isEmpty()) delay(10)
                    queryProcesses().single()
                }
                assertFalse(query.isCompleted)
                query.cancel()
                withTimeout(5_000) {
                    query.join()
                    while (process.isAlive) delay(10)
                }
                assertTrue(query.isCancelled)
                assertFalse(process.isAlive)
            } finally {
                // A failing regression must not leave its synthetic child running.
                query.cancel()
                queryProcesses().forEach { it.destroyForcibly() }
                withTimeout(5_000) { query.join() }
            }
        }
    }
}
