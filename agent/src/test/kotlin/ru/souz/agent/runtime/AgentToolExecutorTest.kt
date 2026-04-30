package ru.souz.agent.runtime

import kotlinx.coroutines.test.runTest
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentToolExecutionEvent
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AgentToolExecutorTest {
    @Test
    fun `reports failures through injected telemetry sink`() = runTest {
        val events = mutableListOf<AgentToolExecutionEvent>()
        val executor = AgentToolExecutor(
            telemetry = AgentTelemetry { event -> events += event },
        )
        val settings = AgentSettings(
            model = "test-model",
            temperature = 0f,
            toolsByCategory = mapOf(
                ToolCategory.FILES to mapOf(
                    "tool.read_file" to object : LLMToolSetup {
                        override val fn: LLMRequest.Function = LLMRequest.Function(
                            name = "tool.read_file",
                            description = "Read file",
                            parameters = LLMRequest.Parameters(
                                type = "object",
                                properties = emptyMap(),
                            ),
                        )

                        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message {
                            error("secret path: /tmp/private.txt")
                        }
                    }
                )
            )
        )

        val failure = assertFailsWith<IllegalStateException> {
            executor.execute(
                settings = settings,
                functionCall = LLMResponse.FunctionCall(
                    name = "tool.read_file",
                    arguments = mapOf("path" to "/tmp/private.txt"),
                ),
            )
        }

        assertEquals("secret path: /tmp/private.txt", failure.message)
        val event = events.single()
        assertEquals("tool.read_file", event.functionName)
        assertEquals(ToolCategory.FILES.name, event.toolCategory)
        assertEquals(listOf("path"), event.argumentKeys)
        assertEquals("IllegalStateException", event.errorType)
        assertFalse(event.success)
    }
}
