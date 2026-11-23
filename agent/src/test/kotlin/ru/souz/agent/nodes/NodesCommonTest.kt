package ru.souz.agent.nodes

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.souz.agent.graph.GraphRuntime
import ru.souz.agent.graph.RetryPolicy
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.spi.AgentRuntimeEnvironment
import ru.souz.agent.spi.SystemAgentRuntimeEnvironment
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LlmProvider
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.toSystemPromptMessage
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodesCommonTest {
    @Test
    fun `host context refreshes the localized date and time without geolocation`() = runTest {
        val zones = listOf("Europe/Moscow", "Asia/Tokyo", "UTC").map(ZoneId::of)
        val environment = object : AgentRuntimeEnvironment {
            override val locale = Locale.FRANCE
            override var zoneId: ZoneId = zones.first()
        }
        val nodesCommon = nodesCommon(environment = environment)
        val dateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd HH:mm:ss", environment.locale)
        val dateTimePrefix = "- [General fact]: Текущие дата и время: "
        var context = stringContext(
            input = "Проверь Telegram",
            model = LLMModel.OpenAIGpt5Mini.alias,
        )

        for ((turn, zoneId) in zones.withIndex()) {
            environment.zoneId = zoneId
            val exchangeStart = if (turn == 0) {
                1
            } else {
                context = NodesPlain.inputToHistory().execute(context, graphRuntime())
                context.activeExchangeStartIndex - 1
            }

            val beforeExecution = Instant.now().truncatedTo(ChronoUnit.SECONDS)
            context = nodesCommon.nodeAppendAdditionalData().execute(context, graphRuntime())
            val afterExecution = Instant.now()

            val injectedContext = context.history.single { it.isInjectedContextMessage() }
            assertEquals(exchangeStart, context.activeExchangeStartIndex)
            assertEquals(injectedContext, context.history[exchangeStart])
            assertFalse(injectedContext.content.contains("User geo:"), injectedContext.content)
            val facts = injectedContext.content.substringAfter("\n---\n").substringBeforeLast("\n</context>").lines()
            assertEquals(1, facts.size, injectedContext.content)
            val fact = facts.single()
            assertTrue(fact.startsWith(dateTimePrefix), fact)
            val dateTime = LocalDateTime.parse(fact.removePrefix(dateTimePrefix), dateTimeFormatter)
            assertTrue(dateTime.atZone(zoneId).toInstant() in beforeExecution..afterExecution, "$zoneId: $dateTime")
        }
    }

    @Test
    fun `ordinary context replacement does not remove a user prompt containing context tags`() = runTest {
        val prompt = """
            <context>
            Background information. Use ONLY if strictly relevant to the user query. If irrelevant (e.g. chitchat), IGNORE completely. Do NOT reference this data in output.
            ---
            user-authored content
            </context>
        """.trimIndent()
        val nodesCommon = nodesCommon()
        val context = stringContext(input = prompt, model = "gpt-model")

        val result = nodesCommon.nodeAppendAdditionalData().execute(context, graphRuntime())

        assertEquals(3, result.history.size)
        assertEquals(LLMMessageRole.system, result.history[0].role)
        assertTrue(result.history[1].isInjectedContextMessage())
        assertEquals(prompt, result.history[2].content)
    }

    @Test
    fun `tool use forwards invocation metadata to executor`() = runTest {
        val functionCall = LLMResponse.FunctionCall(
            name = "tool.read_file",
            arguments = mapOf("path" to "/tmp/file.txt"),
        )
        val meta = ToolInvocationMeta(
            userId = "user-1",
            conversationId = "conversation-1",
            requestId = "request-1",
        )
        val eventSink = object : AgentRuntimeEventSink {
            override suspend fun emit(event: ru.souz.agent.runtime.AgentRuntimeEvent) = Unit
        }
        val agentToolExecutor = mockk<AgentToolExecutor>()
        coEvery {
            agentToolExecutor.execute(
                settings = any(),
                functionCall = functionCall,
                meta = meta,
                toolCallId = "call-1",
                eventSink = eventSink,
            )
        } returns LLMRequest.Message(
            role = LLMMessageRole.function,
            content = """{"ok":true}""",
            name = functionCall.name,
        )
        val context = stringContext("read file", "gpt-5-mini").copy(
            toolInvocationMeta = meta,
            runtimeEventSink = eventSink,
        ).map {
            LLMResponse.Chat.Ok(
                choices = listOf(LLMResponse.Choice(
                    message = LLMResponse.Message("", LLMMessageRole.assistant,
                        functionCall = functionCall, functionsStateId = "call-1"),
                    index = 0, finishReason = LLMResponse.FinishReason.function_call,
                )),
                created = 1L, model = "test-model", usage = LLMResponse.Usage(1, 1, 2, 0),
            )
        }

        val result = NodesPlain.toolUse(agentToolExecutor).execute(context, graphRuntime())

        coVerify(exactly = 1) {
            agentToolExecutor.execute(
                settings = context.settings,
                functionCall = functionCall,
                meta = meta,
                toolCallId = "call-1",
                eventSink = eventSink,
            )
        }
        assertEquals("""{"ok":true}""", result.history.last().content)
        assertEquals("call-1", result.history.last().functionsStateId)
    }

    private fun nodesCommon(
        environment: AgentRuntimeEnvironment = SystemAgentRuntimeEnvironment,
    ): NodesCommon = NodesCommon(
        runtimeEnvironment = environment,
    )

    private fun stringContext(input: String, model: String): AgentContext<String> = AgentContext(
        input = input,
        settings = settings(model),
        history = listOf(
            "system".toSystemPromptMessage(),
            LLMRequest.Message(LLMMessageRole.user, input),
        ),
        activeTools = emptyList(),
        systemPrompt = "system",
        activeExchangeStartIndex = 1,
    )

    private fun settings(model: String): AgentSettings = AgentSettings(
        model = model,
        provider = LlmProvider.OPENAI,
        temperature = 0.2f,
        toolsByCategory = emptyMap(),
    )

    private fun graphRuntime(): GraphRuntime = GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 20)
}
