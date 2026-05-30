@file:OptIn(ExperimentalCoroutinesApi::class)

package ru.souz.ui.main.usecases

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import ru.souz.agent.AgentFacade
import ru.souz.agent.AgentSideEffect
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMResponse
import ru.souz.llms.TokenLogging
import ru.souz.service.observability.ChatConversationCloseReason
import ru.souz.service.observability.ChatConversationMetrics
import ru.souz.service.observability.ChatObservabilityTracker
import ru.souz.service.observability.ChatRequestSource
import ru.souz.service.observability.DesktopStructuredLogger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatUseCaseTest {
    @Test
    fun `onCleared emits pending conversation finish after active request is cancelled`() = runTest {
        val executeStarted = CompletableDeferred<Unit>()
        val executeResult = CompletableDeferred<String>()
        val finished = mutableListOf<Triple<String, ChatConversationMetrics, ChatConversationCloseReason>>()
        val agentFacade = mockk<AgentFacade>(relaxed = true)
        every { agentFacade.sideEffects } returns MutableSharedFlow<AgentSideEffect>()
        every { agentFacade.cancelActiveJob() } answers {
            executeResult.completeExceptionally(CancellationException("view model cleared"))
        }
        coEvery { agentFacade.execute("hello") } coAnswers {
            executeStarted.complete(Unit)
            executeResult.await()
        }
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.gigaModel } returns LLMModel.Max
        val tokenLogging = mockk<TokenLogging>(relaxed = true)
        every { tokenLogging.requestContextElement(any()) } returns EmptyCoroutineContext
        every { tokenLogging.currentRequestTokenUsage(any()) } returns LLMResponse.Usage(1, 2, 3, 0)
        every { tokenLogging.sessionTokenUsage() } returns LLMResponse.Usage(1, 2, 3, 0)
        val tracker = ChatObservabilityTracker(
            onConversationStarted = { _, _ -> },
            onConversationFinished = { conversationId, metrics, reason ->
                finished += Triple(conversationId, metrics, reason)
            },
        )
        val useCase = ChatUseCase(
            agentFacade = agentFacade,
            settingsProvider = settingsProvider,
            speechUseCase = mockk(relaxed = true),
            finderPathExtractor = mockk(relaxed = true),
            chatAttachmentsUseCase = ChatAttachmentsUseCase(UnconfinedTestDispatcher(testScheduler)),
            toolModifyReviewUseCase = mockk(relaxed = true),
            observabilityTracker = tracker,
            log = DesktopStructuredLogger(),
            tokenLogging = tokenLogging,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val requestJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase.sendChatMessage(
                scope = this,
                isVoice = false,
                chatMessage = "hello",
                requestSource = ChatRequestSource.CHAT_UI,
            )
        }
        executeStarted.await()

        useCase.onCleared()
        requestJob.join()

        val event = finished.single()
        assertEquals(ChatConversationCloseReason.VIEW_MODEL_CLEARED, event.third)
        assertEquals(1, event.second.requestCount)
        assertEquals(3, event.second.tokenUsage.totalTokens)
    }
}
