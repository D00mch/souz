package agent

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.luaj.vm2.LuaError
import org.kodein.di.DI
import org.kodein.di.instance
import ru.souz.agent.AgentToolExecutor
import ru.souz.agent.engine.AgentSettings
import ru.souz.agent.lua.LuaExecutionException
import ru.souz.agent.lua.LuaScriptRuntime
import ru.souz.db.SettingsProvider
import ru.souz.di.mainDiModule
import ru.souz.giga.toGiga
import ru.souz.telemetry.TelemetryService
import ru.souz.tool.ToolCategory
import ru.souz.tool.ToolsFactory
import ru.souz.tool.math.ToolCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LuaScriptRuntimeTest {
    @Test
    fun `lua runtime can call registered tool`() = runBlocking {
        val calculator = ToolCalculator().toGiga()
        val settings = AgentSettings(
            model = "test-model",
            temperature = 0f,
            toolsByCategory = mapOf(
                ToolCategory.CALCULATOR to mapOf(calculator.fn.name to calculator)
            ),
        )
        val runtime = LuaScriptRuntime(
            toolExecutor = AgentToolExecutor(mockk<TelemetryService>(relaxed = true))
        )

        val result = runtime.execute(
            code = """
                local sum = Calculator({ expression = "1 + 2 * 3" })
                return "sum=" .. sum
            """.trimIndent(),
            settings = settings,
            activeTools = listOf(calculator.fn),
        )

        assertEquals("sum=7", result)
    }

    @Test
    fun `invalid lua is wrapped with source preview`() = runBlocking {
        val runtime = LuaScriptRuntime(
            toolExecutor = AgentToolExecutor(mockk<TelemetryService>(relaxed = true))
        )
        val settings = AgentSettings(
            model = "test-model",
            temperature = 0f,
            toolsByCategory = emptyMap(),
        )

        val error = assertFailsWith<LuaExecutionException> {
            runtime.execute(
                code = """
                    local broken = "hello
                    return broken
                """.trimIndent(),
                settings = settings,
                activeTools = emptyList(),
            )
        }

        assertTrue(error.message?.contains("unfinished string") == true)
        assertTrue(error.message?.contains("local broken") == true)
    }

    @Test
    fun `lua IO is prohibited`() = runTest {
        val code = """
local path = "/Users/dumch/work/souz/README.md"

local file = io.open(path, "r")
return file:read("*a")
    """.trimIndent()

        val di = DI.invoke { import(mainDiModule) }
        val toolExecutor: AgentToolExecutor by di.instance()
        val settingsProvider: SettingsProvider by di.instance()
        val toolsFactory: ToolsFactory by di.instance()

        val runtime = LuaScriptRuntime(toolExecutor)
        val error = assertFailsWith<LuaExecutionException> {
            runtime.execute(
                code = code,
                settings = AgentSettings(
                    model = settingsProvider.gigaModel.alias,
                    temperature = settingsProvider.temperature,
                    toolsByCategory = toolsFactory.toolsByCategory,
                    contextSize = settingsProvider.contextSize,
                ),
                activeTools = emptyList(),
            )
        }
        assertTrue(error.cause is LuaError)
        assertTrue(error.message?.contains("nil value") == true)
        assertTrue(error.message?.contains("io.open") == true)
    }

    @Test
    fun `lua basic code execution`() = runTest {
        val code = """
local total = 40 + 2
return "answer=" .. total
    """.trimIndent()

        val di = DI.invoke { import(mainDiModule) }
        val toolExecutor: AgentToolExecutor by di.instance()
        val settingsProvider: SettingsProvider by di.instance()
        val toolsFactory: ToolsFactory by di.instance()

        val runtime = LuaScriptRuntime(toolExecutor)
        val result = runtime.execute(
            code = code,
            settings = AgentSettings(
                model = settingsProvider.gigaModel.alias,
                temperature = settingsProvider.temperature,
                toolsByCategory = toolsFactory.toolsByCategory,
                contextSize = settingsProvider.contextSize,
            ),
            activeTools = emptyList(),
        )
        assertEquals("answer=42" ,result)
    }
}
