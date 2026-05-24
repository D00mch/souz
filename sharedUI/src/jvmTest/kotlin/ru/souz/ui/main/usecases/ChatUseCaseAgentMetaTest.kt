@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ru.souz.ui.main.usecases

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import ru.souz.agent.AgentFacade
import ru.souz.agent.AgentSideEffect
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.TokenLogging
import ru.souz.llms.ToolInvocationMeta
import ru.souz.service.observability.ChatObservabilityTracker
import ru.souz.service.observability.ChatRequestSource
import ru.souz.service.observability.DesktopStructuredLogger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatUseCaseAgentMetaTest {
    @Test
    fun `sendChatMessage passes conversation and message ids to agent execution meta`() = runTest {
        val executionMeta = mutableListOf<ToolInvocationMeta?>()
        val agentFacade = mockk<AgentFacade>(relaxed = true)
        every { agentFacade.sideEffects } returns MutableSharedFlow<AgentSideEffect>()
        every { agentFacade.currentContext } returns MutableStateFlow(
            AgentContext(
                input = "",
                settings = AgentSettings(
                    model = "model",
                    temperature = 0f,
                    toolsByCategory = emptyMap(),
                ),
                history = listOf(LLMRequest.Message(LLMMessageRole.system, "Base system prompt")),
                activeTools = emptyList(),
                systemPrompt = "Base system prompt",
            )
        )
        coEvery {
            agentFacade.execute(
                input = "hello",
                toolInvocationMetaOverride = any(),
            )
        } coAnswers {
            executionMeta += secondArg<ToolInvocationMeta?>()
            "response"
        }

        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.gigaModel } returns LLMModel.Max
        every { settingsProvider.notificationSoundEnabled } returns false
        every { settingsProvider.useStreaming } returns false

        val tokenLogging = mockk<TokenLogging>(relaxed = true)
        every { tokenLogging.requestContextElement(any()) } returns EmptyCoroutineContext
        every { tokenLogging.currentRequestTokenUsage(any()) } returns LLMResponse.Usage(0, 0, 0, 0)
        every { tokenLogging.sessionTokenUsage() } returns LLMResponse.Usage(0, 0, 0, 0)

        val toolModifyReviewUseCase = mockk<ToolModifyReviewUseCase>(relaxed = true)
        coEvery {
            toolModifyReviewUseCase.resolvePendingReviewIfNeeded(
                requestId = any(),
                pendingBotMessage = any(),
                response = "response",
                onReviewShown = any(),
            )
        } returns ToolModifyReviewUseCase.ToolModifyReviewResult(
            text = "response",
            appendAsNewMessage = false,
        )

        val useCase = ChatUseCase(
            agentFacade = agentFacade,
            settingsProvider = settingsProvider,
            speechUseCase = mockk(relaxed = true),
            finderPathExtractor = mockk(relaxed = true),
            chatAttachmentsUseCase = ChatAttachmentsUseCase(UnconfinedTestDispatcher(testScheduler)),
            toolModifyReviewUseCase = toolModifyReviewUseCase,
            observabilityTracker = ChatObservabilityTracker(log = DesktopStructuredLogger()),
            log = DesktopStructuredLogger(),
            tokenLogging = tokenLogging,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        useCase.sendChatMessage(
            scope = backgroundScope,
            isVoice = false,
            chatMessage = "hello",
            requestSource = ChatRequestSource.CHAT_UI,
        )
        advanceUntilIdle()

        val meta = assertNotNull(executionMeta.single())
        assertTrue(meta.conversationId?.isNotBlank() == true)
        assertTrue(meta.requestId?.isNotBlank() == true)
        assertTrue(meta.attributes["userMessageId"]?.isNotBlank() == true)
        assertTrue(meta.attributes["assistantMessageId"]?.isNotBlank() == true)
    }
}
