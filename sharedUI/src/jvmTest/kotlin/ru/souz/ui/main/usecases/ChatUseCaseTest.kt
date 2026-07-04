@file:OptIn(ExperimentalCoroutinesApi::class)

package ru.souz.ui.main.usecases

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import ru.souz.agent.AgentFacade
import ru.souz.agent.AgentExecutionResult
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
import ru.souz.memory.MemoryOwnerId
import ru.souz.memory.MemoryOwnerProvider
import ru.souz.memory.MemoryService
import ru.souz.service.observability.ChatConversationCloseReason
import ru.souz.service.observability.ChatConversationMetrics
import ru.souz.service.observability.ChatObservabilityTracker
import ru.souz.service.observability.ChatRequestSource
import ru.souz.service.observability.DesktopStructuredLogger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatUseCaseTest {
    @Test
    fun `finish conversation does not clean session memory implicitly`() = runTest {
        val cleanup = RecordingMemoryConversationCleanup()
        val tracker = ChatObservabilityTracker(log = DesktopStructuredLogger())
        val useCase = createUseCase(
            tracker = tracker,
            cleanup = cleanup,
        )

        tracker.ensureConversation(ChatRequestSource.CHAT_UI)
        useCase.finishCurrentConversation(ChatConversationCloseReason.NEW_CONVERSATION)
        tracker.ensureConversation(ChatRequestSource.CHAT_UI)
        useCase.finishCurrentConversation(ChatConversationCloseReason.CLEAR_CONTEXT)
        tracker.ensureConversation(ChatRequestSource.CHAT_UI)
        useCase.finishCurrentConversation(ChatConversationCloseReason.DELETED)

        assertTrue(cleanup.cleanedConversationIds.isEmpty())
    }

    @Test
    fun `conversation cleanup reports async failures`() = runTest {
        val service = mockk<MemoryService>()
        coEvery { service.closeScopeForCapture(any(), any()) } returns Unit
        coEvery { service.deleteFactsByScope(any(), any()) } throws IllegalStateException("sql failed")
        val failures = mutableListOf<Pair<String, Throwable>>()
        val cleanup = MemoryServiceConversationCleanup(
            memoryService = service,
            ownerProvider = MemoryOwnerProvider { MemoryOwnerId("desktop-owner") },
            onFailure = { conversationId, error -> failures += conversationId to error },
        )

        cleanup.cleanupConversation("chat-42")

        assertEquals("chat-42", failures.single().first)
        assertEquals("sql failed", failures.single().second.message)
    }

    @Test
    fun `conversation cleanup rethrows cancellation`() = runTest {
        val service = mockk<MemoryService>()
        coEvery { service.closeScopeForCapture(any(), any()) } returns Unit
        coEvery { service.deleteFactsByScope(any(), any()) } throws CancellationException("cancelled")
        val failures = mutableListOf<Pair<String, Throwable>>()
        val cleanup = MemoryServiceConversationCleanup(
            memoryService = service,
            ownerProvider = MemoryOwnerProvider { MemoryOwnerId("desktop-owner") },
            onFailure = { conversationId, error -> failures += conversationId to error },
        )

        assertFailsWith<CancellationException> {
            cleanup.cleanupConversation("chat-42")
        }
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `onCleared emits pending conversation finish after active request is cancelled`() = runTest {
        val executeStarted = CompletableDeferred<Unit>()
        val executeResult = CompletableDeferred<String>()
        val finished = mutableListOf<Triple<String, ChatConversationMetrics, ChatConversationCloseReason>>()
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
        every { agentFacade.cancelActiveJob() } answers {
            executeResult.completeExceptionally(CancellationException("view model cleared"))
        }
        coEvery { agentFacade.executeForResult("hello", any()) } coAnswers {
            executeStarted.complete(Unit)
            AgentExecutionResult(
                output = executeResult.await(),
                context = agentFacade.currentContext.value,
            )
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
            chatAttachmentsUseCase = ChatAttachmentsUseCase(UnconfinedTestDispatcher()),
            toolModifyReviewUseCase = mockk(relaxed = true),
            observabilityTracker = tracker,
            log = DesktopStructuredLogger(),
            tokenLogging = tokenLogging,
            ioDispatcher = UnconfinedTestDispatcher(),
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
            agentFacade.executeForResult(
                input = "hello",
                toolInvocationMetaOverride = any(),
            )
        } coAnswers {
            executionMeta += secondArg<ToolInvocationMeta?>()
            AgentExecutionResult(
                output = "response",
                context = agentFacade.currentContext.value,
            )
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
            chatAttachmentsUseCase = ChatAttachmentsUseCase(UnconfinedTestDispatcher()),
            toolModifyReviewUseCase = toolModifyReviewUseCase,
            observabilityTracker = ChatObservabilityTracker(log = DesktopStructuredLogger()),
            log = DesktopStructuredLogger(),
            tokenLogging = tokenLogging,
            ioDispatcher = UnconfinedTestDispatcher(),
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

    @Test
    fun `fresh completed response invokes memory capture callback after stale check`() = runTest {
        var captureCount = 0
        val useCase = createExecutableUseCase(captureCompletedTurn = { captureCount += 1 })

        useCase.sendChatMessage(
            scope = backgroundScope,
            isVoice = false,
            chatMessage = "hello",
            requestSource = ChatRequestSource.CHAT_UI,
        )
        advanceUntilIdle()

        assertEquals(1, captureCount)
    }

    @Test
    fun `stale completed response does not invoke memory capture callback`() = runTest {
        var captureCount = 0
        val executeStarted = CompletableDeferred<Unit>()
        val executeResult = CompletableDeferred<String>()
        val useCase = createExecutableUseCase(
            captureCompletedTurn = { captureCount += 1 },
            executeAnswer = {
                executeStarted.complete(Unit)
                executeResult.await()
            },
        )

        backgroundScope.launch {
            useCase.sendChatMessage(
                scope = backgroundScope,
                isVoice = false,
                chatMessage = "hello",
                requestSource = ChatRequestSource.CHAT_UI,
            )
        }
        executeStarted.await()
        val abortJob = backgroundScope.launch { useCase.abortActiveRequest() }
        executeResult.complete("response")
        advanceUntilIdle()
        abortJob.join()

        assertEquals(0, captureCount)
    }

    @Test
    fun `abort active request does not wait for hanging agent execution`() = runTest {
        val executeStarted = CompletableDeferred<Unit>()
        val executeResult = CompletableDeferred<String>()
        val useCase = createExecutableUseCase(
            executeAnswer = {
                executeStarted.complete(Unit)
                executeResult.await()
            },
        )

        backgroundScope.launch {
            useCase.sendChatMessage(
                scope = backgroundScope,
                isVoice = false,
                chatMessage = "hello",
                requestSource = ChatRequestSource.CHAT_UI,
            )
        }
        executeStarted.await()

        useCase.abortActiveRequest()

        assertTrue(executeResult.isActive)
        executeResult.completeExceptionally(CancellationException("test cleanup"))
        advanceUntilIdle()
    }

    @Test
    fun `cleanup does not wait for hanging agent execution without memory capture`() = runTest {
        val executeStarted = CompletableDeferred<Unit>()
        val executeResult = CompletableDeferred<String>()
        val cleanup = RecordingMemoryConversationCleanup()
        val useCase = createExecutableUseCase(
            cleanup = cleanup,
            executeAnswer = {
                executeStarted.complete(Unit)
                executeResult.await()
            },
        )

        backgroundScope.launch {
            useCase.sendChatMessage(
                scope = backgroundScope,
                isVoice = false,
                chatMessage = "hello",
                requestSource = ChatRequestSource.CHAT_UI,
            )
        }
        executeStarted.await()
        val conversationId = useCase.finishCurrentConversation(ChatConversationCloseReason.NEW_CONVERSATION)
        assertNotNull(conversationId)

        useCase.clearConversationContext()
        useCase.cleanupConversationMemory(conversationId)

        assertEquals(listOf(conversationId), cleanup.cleanedConversationIds)
        assertTrue(executeResult.isActive)
        executeResult.completeExceptionally(CancellationException("test cleanup"))
        advanceUntilIdle()
    }

    private fun createUseCase(
        tracker: ChatObservabilityTracker,
        cleanup: MemoryConversationCleanup = NoopMemoryConversationCleanup,
    ): ChatUseCase {
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
        return ChatUseCase(
            agentFacade = agentFacade,
            settingsProvider = mockk(relaxed = true),
            speechUseCase = mockk(relaxed = true),
            finderPathExtractor = mockk(relaxed = true),
            chatAttachmentsUseCase = ChatAttachmentsUseCase(UnconfinedTestDispatcher()),
            toolModifyReviewUseCase = mockk(relaxed = true),
            observabilityTracker = tracker,
            log = DesktopStructuredLogger(),
            tokenLogging = mockk(relaxed = true),
            memoryConversationCleanup = cleanup,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    private fun createExecutableUseCase(
        cleanup: MemoryConversationCleanup = NoopMemoryConversationCleanup,
        executeAnswer: suspend () -> String = { "response" },
        captureCompletedTurn: () -> Unit = {},
    ): ChatUseCase {
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
                toolInvocationMeta = ToolInvocationMeta.localDefault(),
            )
        )
        coEvery { agentFacade.executeForResult(any(), any()) } coAnswers {
            AgentExecutionResult(
                output = executeAnswer(),
                context = agentFacade.currentContext.value,
                captureCompletedTurn = captureCompletedTurn,
            )
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

        return ChatUseCase(
            agentFacade = agentFacade,
            settingsProvider = settingsProvider,
            speechUseCase = mockk(relaxed = true),
            finderPathExtractor = mockk(relaxed = true),
            chatAttachmentsUseCase = ChatAttachmentsUseCase(UnconfinedTestDispatcher()),
            toolModifyReviewUseCase = toolModifyReviewUseCase,
            observabilityTracker = ChatObservabilityTracker(log = DesktopStructuredLogger()),
            log = DesktopStructuredLogger(),
            tokenLogging = tokenLogging,
            memoryConversationCleanup = cleanup,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    private class RecordingMemoryConversationCleanup : MemoryConversationCleanup {
        val cleanedConversationIds = mutableListOf<String>()

        override suspend fun cleanupConversation(conversationId: String) {
            cleanedConversationIds += conversationId
        }
    }

}
