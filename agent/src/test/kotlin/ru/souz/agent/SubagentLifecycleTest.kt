package ru.souz.agent

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ru.souz.agent.runtime.SubagentTasks
import ru.souz.agent.runtime.withSubagentTasks
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.agent.state.AgentTools
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.restJsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SubagentLifecycleTest {
    @Test
    fun `bounded waits poll without cancelling and terminal results are repeatable`() = runTest {
        val release = CompletableDeferred<Unit>()
        val tool = tool { release.await(); "answer" }
        withSubagentTasks {
            val id = tool.spawn()
            for (timeout in listOf(0, 5, 30000)) {
                assertEquals("running", tool.call(SubagentTool.WAIT, id, timeout)["status"].asText())
            }
            release.complete(Unit)
            val done = tool.call(SubagentTool.WAIT, id)
            assertEquals("completed", done["status"].asText())
            assertEquals("answer", done["result"].asText())
            assertEquals(done, tool.call(SubagentTool.STATUS, id))
            assertEquals(done, tool.call(SubagentTool.CANCEL, id))
            assertEquals(done, tool.call(SubagentTool.WAIT, id))
        }
    }

    @Test
    fun `cancellation before dispatch never executes child and repeated cancellation is stable`() = runTest {
        var executions = 0
        val tool = tool { executions++; "answer" }
        withSubagentTasks {
            val id = tool.spawn()
            val cancelled = tool.call(SubagentTool.CANCEL, id)
            assertEquals("cancelled", cancelled["status"].asText())
            assertEquals(cancelled, tool.call(SubagentTool.CANCEL, id))
            assertEquals(cancelled, tool.call(SubagentTool.WAIT, id))
            assertFalse(cancelled.has("result"))
        }
        assertEquals(0, executions)
    }

    @Test
    fun `completion racing cancellation has one stable terminal outcome`() = runTest {
        repeat(30) { iteration ->
            val release = CompletableDeferred<Unit>()
            val tool = tool { release.await(); "answer" }
            withSubagentTasks {
                val id = tool.spawn()
                runCurrent()
                val finish: suspend () -> Unit = { release.complete(Unit) }
                val cancel: suspend () -> Unit = { tool.call(SubagentTool.CANCEL, id) }
                val actions = if (iteration % 2 == 0) listOf(finish, cancel) else listOf(cancel, finish)
                actions.map { async { it() } }.awaitAll()
                val result = tool.call(SubagentTool.WAIT, id)
                val status = result["status"].asText()
                assertTrue(status == "completed" || status == "cancelled")
                assertEquals(result, tool.call(SubagentTool.CANCEL, id))
                assertEquals(result, tool.call(SubagentTool.STATUS, id))
            }
        }
    }

    @Test
    fun `child failure is retained without cancelling parent or sibling`() = runTest {
        val tool = tool { ctx -> if (ctx.input == "fail") error("child failed") else "answer" }
        withSubagentTasks {
            val failed = tool.spawn("fail")
            val success = tool.spawn("success")
            val result = tool.call(SubagentTool.WAIT, failed)
            assertEquals("failed", result["status"].asText())
            assertEquals("subagent_failed", result["error"]["code"].asText())
            assertEquals("child failed", result["error"]["message"].asText())
            assertEquals(result, tool.call(SubagentTool.CANCEL, failed))
            assertEquals("completed", tool.call(SubagentTool.WAIT, success)["status"].asText())
        }
    }

    @Test
    fun `execution owns registry entries and joins cancellation cleanup`() = runTest {
        val started = CompletableDeferred<Unit>()
        val cleaningUp = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val tool = tool {
            started.complete(Unit)
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { cleaningUp.complete(Unit); releaseCleanup.await() }
            }
        }
        lateinit var registry: SubagentTasks
        var id = ""
        val parent = async {
            withSubagentTasks {
                registry = SubagentTasks.current()
                id = tool.spawn()
                started.await()
                withSubagentTasks {
                    assertEquals("subagent_not_found", tool.call(SubagentTool.STATUS, id)["error"]["code"].asText())
                }
            }
        }
        cleaningUp.await()
        assertFalse(parent.isCompleted)
        releaseCleanup.complete(Unit)
        parent.await()
        assertEquals("subagent_not_found", assertFailsWith<SubagentInputException> { registry.status(id) }.code)
    }

    @Test
    fun `invalid lifecycle arguments and unavailable execution return structured errors`() = runTest {
        val tool = tool { "answer" }
        assertEquals("subagent_unavailable", tool.call(SubagentTool.NAME, task = "task")["error"]["code"].asText())
        withSubagentTasks {
            for (name in SubagentTool.NAMES - SubagentTool.NAME) {
                assertEquals("invalid_subagent_input", tool.call(name)["error"]["code"].asText())
                assertEquals("subagent_not_found", tool.call(name, "unknown")["error"]["code"].asText())
            }
            for (timeout in listOf(-1, 30001)) {
                assertEquals("invalid_subagent_input", tool.call(SubagentTool.WAIT, "unknown", timeout)["error"]["code"].asText())
            }
        }
    }

    private fun tool(execute: suspend (AgentContext<String>) -> String) = SubagentTool({ _ -> mockk<Agent> {
        coEvery { execute(any(), any(), any()) } coAnswers {
            val ctx = firstArg<AgentContext<String>>()
            AgentExecutionResult(execute(ctx), ctx)
        }
    } }) { _, _ -> SubagentTool.Setup(AgentSettings("test", LlmProvider.OPENAI, 0f, AgentTools(emptyList())), "child") }

    private suspend fun SubagentTool.spawn(task: String = "task") = call(SubagentTool.NAME, task = task)["subagentId"].asText()

    private suspend fun SubagentTool.call(name: String, id: String = "", timeout: Int = 1000, task: String? = null) =
        restJsonMapper.readTree(tools.single { it.fn.name == name }.invoke(LLMResponse.FunctionCall(name,
            if (task != null) mapOf("task" to task) else mapOf("subagentId" to id, "timeoutMs" to timeout))).content)
}
