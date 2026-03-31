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
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.DefaultBrowserProvider
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.db.StorredData
import ru.souz.db.StorredType
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.toSystemPromptMessage
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NodesCommonTest {
    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `local model keeps static additional context but skips desktop search`() = runTest {
        val desktopInfoRepository = mockk<AgentDesktopInfoRepository>()
        coEvery { desktopInfoRepository.search(any(), any()) } returns listOf()
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

        assertTrue(injectedContext.content.contains("Календарь по умолчанию: Work"))
        assertTrue(injectedContext.content.contains("Текущие дата и время:"))
        coVerify(exactly = 0) { desktopInfoRepository.search(any(), any()) }
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
}
