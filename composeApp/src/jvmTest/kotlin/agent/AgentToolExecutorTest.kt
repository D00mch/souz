package agent

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import ru.souz.agent.engine.AgentSettings
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.giga.GigaMessageRole
import ru.souz.giga.GigaRequest
import ru.souz.giga.GigaResponse
import ru.souz.giga.GigaToolSetup
import ru.souz.telemetry.TelemetryService
import ru.souz.tool.ToolActionDescriptor
import ru.souz.tool.ToolActionKind
import ru.souz.tool.ToolActionListener
import ru.souz.tool.ToolCategory
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentToolExecutorTest {
    @Test
    fun `listener failures do not fail tool execution`() = runTest {
        val telemetryService = mockk<TelemetryService>(relaxed = true)
        val tool = object : GigaToolSetup {
            override val fn: GigaRequest.Function = GigaRequest.Function(
                name = "FakeTool",
                description = "Fake tool",
                parameters = GigaRequest.Parameters(type = "object", properties = emptyMap()),
                returnParameters = GigaRequest.Parameters(type = "object", properties = emptyMap()),
            )

            override suspend fun invoke(functionCall: GigaResponse.FunctionCall): GigaRequest.Message =
                GigaRequest.Message(
                    role = GigaMessageRole.function,
                    content = """{"result":"ok"}""",
                    name = functionCall.name,
                )

            override fun describeAction(functionCall: GigaResponse.FunctionCall): ToolActionDescriptor =
                ToolActionDescriptor(
                    kind = ToolActionKind.SEARCH_WEB,
                    primary = "query",
                )
        }
        val settings = AgentSettings(
            model = "test-model",
            temperature = 0f,
            toolsByCategory = mapOf(
                ToolCategory.WEB_SEARCH to mapOf(tool.fn.name to tool)
            ),
        )
        val toolActionListener = object : ToolActionListener {
            override fun onToolStarted(actionId: String, descriptor: ToolActionDescriptor) {
                error("start listener failure")
            }

            override fun onToolFinished(actionId: String, success: Boolean) {
                error("finish listener failure")
            }
        }

        val result = AgentToolExecutor(telemetryService).execute(
            settings = settings,
            functionCall = GigaResponse.FunctionCall(
                name = tool.fn.name,
                arguments = emptyMap(),
            ),
            toolActionListener = toolActionListener,
        )

        assertEquals("""{"result":"ok"}""", result.content)
        verify(exactly = 1) {
            telemetryService.recordToolExecution(
                functionName = tool.fn.name,
                functionArguments = emptyMap(),
                toolCategory = ToolCategory.WEB_SEARCH,
                durationMs = any(),
                success = true,
                errorMessage = null,
            )
        }
    }
}
