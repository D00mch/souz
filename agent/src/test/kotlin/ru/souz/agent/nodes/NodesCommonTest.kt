package ru.souz.agent.nodes

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.souz.agent.graph.GraphRuntime
import ru.souz.agent.graph.RetryPolicy
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.agent.spi.AgentRuntimeEnvironment
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.SystemAgentRuntimeEnvironment
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.db.StorredData
import ru.souz.db.StorredType
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LlmProvider
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.toSystemPromptMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NodesCommonTest {
    @Test
    fun `host context includes desktop search and refreshes across turns`() = runTest {
        val desktopInfoRepository = mockk<AgentDesktopInfoRepository>()
        coEvery { desktopInfoRepository.search(any(), any()) } returns listOf(
            StorredData("Найден локальный факт", StorredType.GENERAL_FACT)
        )
        val environment = object : AgentRuntimeEnvironment by SystemAgentRuntimeEnvironment {
            override var defaultBrowserDisplayName: String? = "Safari"
        }
        val nodesCommon = nodesCommon(desktopInfoRepository, calendar = "Work", environment = environment)
        val context = stringContext(
            input = "Проверь Telegram",
            model = LLMModel.LocalQwen3_4B_Instruct_2507.alias,
        )

        val result = nodesCommon.nodeAppendAdditionalData().execute(context, graphRuntime())
        val injectedContext = assertNotNull(result.history.firstOrNull { it.isInjectedContextMessage() })
        assertEquals(1, result.activeExchangeStartIndex)

        assertTrue(injectedContext.content.contains("Найден локальный факт"))
        assertTrue(injectedContext.content.contains("Календарь по умолчанию: Work"))
        assertTrue(injectedContext.content.contains("Текущие дата и время:"))
        assertTrue(injectedContext.content.contains("- [Default browser]: Safari"))
        val simple = nodesCommon.nodeAppendAdditionalData().execute(result.copy(simpleReply = true), graphRuntime())
        assertEquals(context.history, simple.history)
        assertEquals(context.activeExchangeStartIndex, simple.activeExchangeStartIndex)
        coVerify(exactly = 1) { desktopInfoRepository.search(any(), any()) }

        var nextContext = result
        for (browser in listOf("Google Chrome", null)) {
            environment.defaultBrowserDisplayName = browser
            nextContext = NodesPlain.inputToHistory().execute(nextContext, graphRuntime())
            val startBeforeCleanup = nextContext.activeExchangeStartIndex
            nextContext = nodesCommon.nodeAppendAdditionalData().execute(nextContext, graphRuntime())
            assertEquals(startBeforeCleanup - 1, nextContext.activeExchangeStartIndex)
            assertTrue(nextContext.history[nextContext.activeExchangeStartIndex].isInjectedContextMessage())
            val refreshed = nextContext.history.single { it.isInjectedContextMessage() }.content
            assertFalse(refreshed.contains("Safari"))
            if (browser == null) assertFalse(refreshed.contains("[Default browser]"))
            else assertTrue(refreshed.contains("- [Default browser]: $browser"))
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
        val nodesCommon = nodesCommon(mockk(relaxed = true))
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
        desktopInfoRepository: AgentDesktopInfoRepository,
        calendar: String? = null,
        environment: AgentRuntimeEnvironment = SystemAgentRuntimeEnvironment,
    ): NodesCommon = NodesCommon(
        desktopInfoRepository = desktopInfoRepository,
        settingsProvider = mockk<AgentSettingsProvider> { every { defaultCalendar } returns calendar },
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
