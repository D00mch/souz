package ru.souz.agent

import com.fasterxml.jackson.databind.JsonNode
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
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
    fun `bounded polls preserve the child and the default wait collects a repeatable result`() = runTest {
        val release = CompletableDeferred<Unit>()
        val tool = tool { release.await(); "answer" }
        withSubagentTasks {
            val id = tool.spawn()
            for (timeout in listOf(0, 5, 30000)) {
                val started = testScheduler.currentTime
                assertEquals("running", tool.call(SubagentTool.WAIT, id, timeout)["status"].asText())
                assertEquals(timeout.toLong(), testScheduler.currentTime - started)
            }
            backgroundScope.launch { delay(15_000); release.complete(Unit) }
            val done = tool.call(SubagentTool.WAIT, id)
            assertEquals("completed", done["status"].asText())
            assertEquals("answer", done["result"].asText())
            assertEquals(done, tool.call(SubagentTool.WAIT, id, 0))
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
            val status = tool.call(SubagentTool.CANCEL, id)["status"].asText()
            assertTrue(status == "cancelling" || status == "cancelled")
            val cancelled = tool.call(SubagentTool.WAIT, id)
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
                coroutineScope { actions.map { async { it() } }.awaitAll() }
                val result = tool.call(SubagentTool.WAIT, id)
                val status = result["status"].asText()
                assertTrue(status == "completed" || status == "cancelled")
                assertEquals(result, tool.call(SubagentTool.CANCEL, id))
                assertEquals(result, tool.call(SubagentTool.WAIT, id, 0))
            }
        }
    }

    @Test
    fun `failures and deadlines retain their outcomes without stopping the parent or sibling`() = runTest {
        var stopped = false
        val tool = tool { ctx ->
            when (ctx.input) {
                "fail" -> error("child failed")
                "slow" -> try { awaitCancellation() } finally { stopped = true }
                else -> "answer"
            }
        }
        withSubagentTasks {
            val success = tool.spawn("success")
            for ((task, code) in listOf("fail" to "subagent_failed", "slow" to "subagent_timeout")) {
                val id = tool.spawn(task)
                if (task == "slow") {
                    runCurrent()
                    advanceTimeBy(SubagentTasks.TASK_TIMEOUT_MS)
                }
                val result = tool.call(SubagentTool.WAIT, id)
                assertEquals("failed", result["status"].asText())
                assertEquals(code, result["error"]["code"].asText())
                if (task == "fail") assertEquals("child failed", result["error"]["message"].asText())
                else assertTrue(result["sideEffectsMayHaveOccurred"].asBoolean())
                assertEquals(result, tool.call(SubagentTool.CANCEL, id))
            }
            assertTrue(stopped)
            assertEquals("answer", tool.call(SubagentTool.WAIT, success)["result"].asText())
        }
    }

    @Test
    fun `oversized success and failure results are bounded without replaying child work`() = runTest {
        var executions = 0
        val large = "Ж".repeat(40_000)
        val tool = tool { ctx ->
            executions++
            if (ctx.input == "fail") error(large)
            large
        }
        withSubagentTasks {
            for (task in listOf("success", "fail")) {
                val id = tool.spawn(task)
                val result = tool.call(SubagentTool.WAIT, id)
                assertEquals("failed", result["status"].asText())
                assertEquals("subagent_result_limit", result["error"]["code"].asText())
                assertTrue(result["sideEffectsMayHaveOccurred"].asBoolean())
                assertTrue(result["resultBytes"].asInt() > 64 * 1024)
                assertTrue(restJsonMapper.writeValueAsBytes(result).size < 64 * 1024)
                assertFalse(result.has("result"))
                assertEquals(result, tool.call(SubagentTool.WAIT, id, 0))
                assertEquals(result, tool.call(SubagentTool.CANCEL, id))
            }
        }
        assertEquals(2, executions)
    }

    @Test
    fun `cancellation stays pending until cleanup releases child resources`() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val tool = tool {
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { cleanup.complete(Unit); release.await() }
            }
        }
        withSubagentTasks {
            val id = tool.spawn()
            runCurrent()
            assertEquals("cancelling", tool.call(SubagentTool.CANCEL, id)["status"].asText())
            cleanup.await()
            assertEquals("cancelling", tool.call(SubagentTool.WAIT, id, 5)["status"].asText())
            assertEquals("cancelling", tool.call(SubagentTool.CANCEL, id)["status"].asText())
            release.complete(Unit)
            val result = tool.call(SubagentTool.WAIT, id)
            assertEquals("cancelled", result["status"].asText())
            assertEquals(result, tool.call(SubagentTool.WAIT, id, 0))
        }
    }

    @Test
    fun `concurrent admission is bounded and capacity is released only after cleanup`() = runTest {
        val release = CompletableDeferred<Unit>()
        val tool = tool {
            try { awaitCancellation() } finally { withContext(NonCancellable) { release.await() } }
        }
        withSubagentTasks {
            val outcomes = coroutineScope {
                List(256) { async { tool.call(SubagentTool.NAME, task = "child") } }.awaitAll()
            }
            val ids = outcomes.filter { it.has("subagentId") }.map { it["subagentId"].asText() }
            assertEquals(8, ids.size)
            assertEquals(248, outcomes.count { it.path("error").path("code").asText() == "subagent_concurrency_limit" })
            runCurrent()
            tool.call(SubagentTool.CANCEL, ids.first())
            assertEquals("subagent_concurrency_limit", tool.call(SubagentTool.NAME, task = "still full")["error"]["code"].asText())
            release.complete(Unit)
            assertEquals("cancelled", tool.call(SubagentTool.WAIT, ids.first())["status"].asText())
            assertTrue(tool.call(SubagentTool.NAME, task = "replacement").has("subagentId"))
        }
    }

    @Test
    fun `total limit retains completed results without allowing unlimited sequential spawns`() = runTest {
        val tool = tool { "answer" }
        withSubagentTasks {
            val ids = List(32) {
                tool.spawn().also { id -> assertEquals("completed", tool.call(SubagentTool.WAIT, id)["status"].asText()) }
            }
            assertEquals("subagent_task_limit", tool.call(SubagentTool.NAME, task = "overflow")["error"]["code"].asText())
            for (id in listOf(ids.first(), ids.last())) {
                assertEquals("answer", tool.call(SubagentTool.WAIT, id, 0)["result"].asText())
            }
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
                    assertEquals("subagent_not_found", tool.call(SubagentTool.WAIT, id, 0)["error"]["code"].asText())
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

    private suspend fun SubagentTool.call(name: String, id: String = "", timeout: Int? = null, task: String? = null): JsonNode {
        val arguments = if (task != null) mapOf("task" to task) else buildMap<String, Any> {
            put("subagentId", id)
            if (timeout != null) put("timeoutMs", timeout)
        }
        val tool = tools.single { it.fn.name == name }
        return restJsonMapper.readTree(tool.invoke(LLMResponse.FunctionCall(name, arguments)).content)
    }
}
