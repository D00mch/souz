package ru.souz.agent.nodes

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import ru.souz.agent.graph.GraphRuntime
import ru.souz.agent.graph.RetryPolicy
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.agent.spi.AgentRuntimeEnvironment
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.DefaultBrowserProvider
import ru.souz.agent.spi.SystemAgentRuntimeEnvironment
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.db.StorredData
import ru.souz.db.StorredType
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.toSystemPromptMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NodesCommonTest {
    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `local model includes desktop search in additional context`() = runTest {
        val desktopInfoRepository = mockk<AgentDesktopInfoRepository>()
        coEvery { desktopInfoRepository.search(any(), any()) } returns listOf(
            StorredData("Найден локальный факт", StorredType.GENERAL_FACT)
        )
        val settingsProvider = mockk<AgentSettingsProvider> {
            every { defaultCalendar } returns "Work"
        }
        val nodesCommon = NodesCommon(
            desktopInfoRepository = desktopInfoRepository,
            settingsProvider = settingsProvider,
            agentToolExecutor = mockk<AgentToolExecutor>(relaxed = true),
            defaultBrowserProvider = mockk<DefaultBrowserProvider> {
                every { defaultBrowserDisplayName() } returns null
            },
            runtimeEnvironment = SystemAgentRuntimeEnvironment,
        )
        val context = AgentContext(
            input = "Проверь Telegram",
            settings = AgentSettings(
                model = LLMModel.LocalQwen3_4B_Instruct_2507.alias,
                temperature = 0.2f,
                toolsByCategory = emptyMap(),
            ),
            history = listOf(
                "system".toSystemPromptMessage(),
                LLMRequest.Message(LLMMessageRole.user, "Проверь Telegram"),
            ),
            activeTools = emptyList(),
            systemPrompt = "system",
        )

        val result = nodesCommon.nodeAppendAdditionalData().execute(
            ctx = context,
            runtime = GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10),
        )
        val injectedContext = assertNotNull(result.history.firstOrNull { it.content.contains("<context>") })

        assertTrue(injectedContext.content.contains("Найден локальный факт"))
        assertTrue(injectedContext.content.contains("Календарь по умолчанию: Work"))
        assertTrue(injectedContext.content.contains("Текущие дата и время:"))
        coVerify(exactly = 1) { desktopInfoRepository.search(any(), any()) }
    }

    @Test
    fun `cloud model includes desktop search in additional context`() = runTest {
        val desktopInfoRepository = mockk<AgentDesktopInfoRepository>()
        coEvery { desktopInfoRepository.search(any(), any()) } returns listOf(
            StorredData("Найден локальный факт", StorredType.GENERAL_FACT)
        )
        val settingsProvider = mockk<AgentSettingsProvider> {
            every { defaultCalendar } returns null
        }
        val nodesCommon = NodesCommon(
            desktopInfoRepository = desktopInfoRepository,
            settingsProvider = settingsProvider,
            agentToolExecutor = mockk<AgentToolExecutor>(relaxed = true),
            defaultBrowserProvider = mockk<DefaultBrowserProvider> {
                every { defaultBrowserDisplayName() } returns null
            },
            runtimeEnvironment = SystemAgentRuntimeEnvironment,
        )
        val context = AgentContext(
            input = "Найди локальные данные",
            settings = AgentSettings(
                model = "gpt-5-nano",
                temperature = 0.2f,
                toolsByCategory = emptyMap(),
            ),
            history = listOf(
                "system".toSystemPromptMessage(),
                LLMRequest.Message(LLMMessageRole.user, "Найди локальные данные"),
            ),
            activeTools = emptyList(),
            systemPrompt = "system",
        )

        val result = nodesCommon.nodeAppendAdditionalData().execute(
            ctx = context,
            runtime = GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10),
        )
        val injectedContext = assertNotNull(result.history.firstOrNull { it.content.contains("<context>") })

        assertTrue(injectedContext.content.contains("Найден локальный факт"))
        coVerify(exactly = 1) { desktopInfoRepository.search(any(), any()) }
    }

    @Test
    fun `tool use forwards context tool invocation metadata to executor`() = runTest {
        val desktopInfoRepository = mockk<AgentDesktopInfoRepository>(relaxed = true)
        val settingsProvider = mockk<AgentSettingsProvider> {
            every { defaultCalendar } returns null
        }
        val agentToolExecutor = mockk<AgentToolExecutor>()
        val functionCall = LLMResponse.FunctionCall(
            name = "tool.read_file",
            arguments = mapOf("path" to "/tmp/file.txt"),
        )
        val meta = ToolInvocationMeta(
            userId = "user-1",
            conversationId = "conversation-1",
            requestId = "request-1",
        )
        coEvery {
            agentToolExecutor.execute(
                settings = any(),
                functionCall = functionCall,
                meta = meta,
                toolCallId = "call-1",
                eventSink = any(),
            )
        } returns LLMRequest.Message(
            role = LLMMessageRole.function,
            content = """{"ok":true}""",
            functionsStateId = null,
            name = functionCall.name,
        )
        val nodesCommon = NodesCommon(
            desktopInfoRepository = desktopInfoRepository,
            settingsProvider = settingsProvider,
            agentToolExecutor = agentToolExecutor,
            defaultBrowserProvider = mockk<DefaultBrowserProvider> {
                every { defaultBrowserDisplayName() } returns null
            },
            runtimeEnvironment = SystemAgentRuntimeEnvironment,
        )
        val eventSink = object : AgentRuntimeEventSink {
            override suspend fun emit(event: ru.souz.agent.runtime.AgentRuntimeEvent) = Unit
        }
        val context = AgentContext(
            input = LLMResponse.Chat.Ok(
                choices = listOf(
                    LLMResponse.Choice(
                        message = LLMResponse.Message(
                            content = "",
                            role = LLMMessageRole.assistant,
                            functionCall = functionCall,
                            functionsStateId = "call-1",
                        ),
                        index = 0,
                        finishReason = LLMResponse.FinishReason.function_call,
                    )
                ),
                created = 1L,
                model = "gpt-5-nano",
                usage = LLMResponse.Usage(1, 1, 2, 0),
            ),
            settings = AgentSettings(
                model = "gpt-5-nano",
                temperature = 0.2f,
                toolsByCategory = emptyMap(),
            ),
            history = listOf(
                "system".toSystemPromptMessage(),
                LLMRequest.Message(LLMMessageRole.user, "read file"),
            ),
            activeTools = emptyList(),
            systemPrompt = "system",
            toolInvocationMeta = meta,
            runtimeEventSink = eventSink,
        )

        val result = nodesCommon.toolUse().execute(
            ctx = context,
            runtime = GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10),
        )

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
}
