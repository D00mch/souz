package ru.souz.backend.agent.runtime.conversation

import io.mockk.mockk
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.state.AgentSettings
import ru.souz.backend.metrics.BackendMetrics
import ru.souz.backend.testutil.TestToolCatalog
import ru.souz.backend.testutil.TestToolSetup
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.LlmProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.RuntimePassThroughToolsFilter
import ru.souz.tool.ToolCategory
import ru.souz.tool.skills.ToolInvokeSkill
import ru.souz.tool.web.ToolWebImageSearch

class BackendExecutionToolCatalogTest {
    @Test
    fun `compiled tool outcomes are recorded through Skill delegation and counted once for direct calls`() = runTest {
        val failures = mapOf(
            "success" to null,
            "error" to IllegalStateException("failed"),
            "timeout" to SocketTimeoutException("timed out"),
            "cancelled" to CancellationException("cancelled"),
        )
        val call = LLMResponse.FunctionCall("ReadFile", mapOf("path" to "file.txt"))
        val expectedMeta = ToolInvocationMeta("user", conversationId = "chat")
        for ((outcome, failure) in failures) {
            BackendMetrics().use { metrics ->
                val delegate = TestToolSetup(call.name)
                val tool = object : LLMToolSetup by delegate {
                    override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message {
                        assertEquals(call, functionCall)
                        assertSame(expectedMeta, meta)
                        assertEquals(1.0, metrics.registry.get("souz.pending.tool.calls").gauge().value())
                        if (failure != null) throw failure
                        return delegate.invoke(functionCall)
                    }
                }
                val catalog = backendExecutionToolCatalog(
                    compiledToolCatalog = TestToolCatalog(mapOf(ToolCategory.FILES to mapOf(call.name to tool))),
                    executionLlmToolCatalog = TestToolCatalog(),
                    enabledCompiledToolNames = null,
                    clientToolCatalog = TestToolCatalog(),
                    includeFewShotExamples = false,
                    metrics = metrics,
                )
                val helper = ToolInvokeSkill(catalog, RuntimePassThroughToolsFilter, { _, _ -> null }, mockk())
                val executor = AgentToolExecutor(metrics.toolTelemetry(toolNames(catalog)))
                val settings = AgentSettings("test", LlmProvider.OPENAI, 0f, catalog.toolsByCategory)
                for ((index, delegated) in listOf(true, false).withIndex()) {
                    suspend fun invoke() = if (delegated) helper.invoke(
                        LLMResponse.FunctionCall(ToolInvokeSkill.NAME, mapOf("skillId" to call.name, "arguments" to call.arguments)),
                        expectedMeta,
                    ) else executor.execute(settings, call, expectedMeta)

                    if (failure != null && (!delegated || failure is CancellationException)) {
                        assertSame(failure, assertFailsWith<Exception> { invoke() })
                    } else {
                        val result = invoke()
                        if (failure == null) assertEquals("ok", result.content)
                        else assertTrue(result.content.contains("skill_invocation_failed"))
                    }
                    assertEquals(0.0, metrics.registry.get("souz.pending.tool.calls").gauge().value())
                    val tags = arrayOf("category", "files", "outcome", outcome)
                    assertEquals(index + 1.0, metrics.registry.get("souz.tool.calls").tags(*tags).counter().count())
                    val timer = metrics.registry.get("souz.tool.duration").tags(*tags).timer()
                    assertEquals(index + 1L, timer.count())
                    assertTrue(timer.totalTime(TimeUnit.NANOSECONDS) > 0)
                }
            }
        }
    }

    @Test
    fun `catalog exposes only client web search for every compiled tool selection`() {
        val selections = mapOf(
            null to setOf("ReadFile", "ViewImage", "GenerateImage"),
            setOf("ReadFile") to setOf("ReadFile"),
            setOf("InternetSearch", "InternetResearch", "WebPageText", "FutureWebTool") to emptySet(),
            emptySet<String>() to emptySet(),
        )
        for ((enabled, compiled) in selections) {
            val catalog = executionCatalog(enabled)
            assertEquals(compiled + setOf("ClientAsk", "web.search"), toolNames(catalog), "enabled=$enabled")
            assertEquals(setOf("web.search"), catalog.toolsByCategory.getValue(ToolCategory.WEB_SEARCH).keys)
        }
    }

    @Test
    fun `client tools win name collisions with compiled tools`() {
        val catalog = backendExecutionToolCatalog(
            compiledToolCatalog = TestToolCatalog(ToolCategory.FILES to listOf("ReadFile")),
            executionLlmToolCatalog = TestToolCatalog(),
            enabledCompiledToolNames = null,
            clientToolCatalog = TestToolCatalog(
                mapOf(
                    ToolCategory.FILES to mapOf(
                        "ReadFile" to TestToolSetup("ReadFile", description = "client owned"),
                    ),
                )
            ),
            includeFewShotExamples = true,
        )

        assertEquals(
            "client owned",
            catalog.toolsByCategory.getValue(ToolCategory.FILES).getValue("ReadFile").fn.description,
        )
    }

    @Test
    fun `few-shot examples are stripped when the execution disables them`() {
        val catalog = backendExecutionToolCatalog(
            compiledToolCatalog = TestToolCatalog(
                mapOf(
                    ToolCategory.FILES to mapOf(
                        "ReadFile" to TestToolSetup(
                            name = "ReadFile",
                            fewShotExamples = listOf(LLMRequest.FewShotExample("read it", emptyMap())),
                        ),
                    ),
                )
            ),
            executionLlmToolCatalog = TestToolCatalog(),
            enabledCompiledToolNames = null,
            clientToolCatalog = TestToolCatalog(),
            includeFewShotExamples = false,
        )

        assertEquals(
            emptyList(),
            catalog.toolsByCategory.getValue(ToolCategory.FILES).getValue("ReadFile").fn.fewShotExamples,
        )
    }

    private fun executionCatalog(enabledCompiledToolNames: Set<String>?): AgentToolCatalog =
        backendExecutionToolCatalog(
            compiledToolCatalog = TestToolCatalog(
                ToolCategory.FILES to listOf("ReadFile"),
                ToolCategory.WEB_SEARCH to listOf("WebPageText", ToolWebImageSearch.NAME, "FutureWebTool"),
                ToolCategory.BROWSER to listOf("ControlBrowser"),
            ),
            executionLlmToolCatalog = TestToolCatalog(
                ToolCategory.WEB_SEARCH to listOf("InternetSearch", "InternetResearch"),
                ToolCategory.IMAGE to listOf("ViewImage"),
                ToolCategory.IMAGE_GENERATION to listOf("GenerateImage"),
            ),
            enabledCompiledToolNames = enabledCompiledToolNames,
            clientToolCatalog = TestToolCatalog(
                ToolCategory.CHAT to listOf("ClientAsk"),
                ToolCategory.WEB_SEARCH to listOf("web.search"),
            ),
            includeFewShotExamples = true,
        )

    private fun toolNames(catalog: AgentToolCatalog): Set<String> =
        catalog.toolsByCategory.values.flatMapTo(linkedSetOf()) { tools -> tools.keys }
}
