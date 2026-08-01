package ru.souz.ui.main.usecases

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.agent.AgentFacade
import ru.souz.agent.AgentExecutionResult
import ru.souz.agent.AgentId
import ru.souz.agent.AgentSideEffect
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.state.AgentContext
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMResponse
import ru.souz.llms.TokenLogging
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.plus
import ru.souz.service.observability.ChatObservabilityTracker
import ru.souz.service.observability.ChatConversationCloseReason
import ru.souz.service.observability.ChatRequestLogContext
import ru.souz.service.observability.ChatRequestSource
import ru.souz.service.observability.ChatRequestStatus
import ru.souz.service.observability.DesktopStructuredLogger
import ru.souz.ui.main.ChatAgentActionFormatter
import ru.souz.ui.main.ChatAttachedFile
import ru.souz.ui.main.ChatMessage
import ru.souz.ui.main.MainState
import kotlin.collections.plus

class ChatUseCase internal constructor(
    private val agentFacade: AgentFacade,
    private val settingsProvider: SettingsProvider,
    private val speechUseCase: SpeechUseCase,
    private val finderPathExtractor: FinderPathExtractor,
    private val chatAttachmentsUseCase: ChatAttachmentsUseCase,
    private val toolModifyReviewUseCase: ToolModifyReviewUseCase,
    private val observabilityTracker: ChatObservabilityTracker,
    private val log: DesktopStructuredLogger,
    private val tokenLogging: TokenLogging,
    private val memoryConversationCleanup: MemoryConversationCleanup = NoopMemoryConversationCleanup,
    private val conversationKnowledgeStore: ConversationKnowledgeStore,
    private val chatAgentActionFormatter: ChatAgentActionFormatter = ChatAgentActionFormatter(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val l = LoggerFactory.getLogger(ChatUseCase::class.java)
    private val taskSideEffectJobs = ArrayList<Job>()
    private val activeRequestMutex = Mutex()
    private var activeChatRequestId: Long = 0L
    private var activeRequestSession: ChatRequestSession? = null
    private var activeConversationMeta: ToolInvocationMeta? = null

    private val _outputs = MutableSharedFlow<MainUseCaseOutput>(replay = 1, extraBufferCapacity = 64)
    val outputs: Flow<MainUseCaseOutput> = _outputs.asSharedFlow()

    fun start(scope: CoroutineScope) {
        scope.launch {
            agentFacade.currentContext.collect { ctx ->
                emitState { copy(agentHistory = ctx.history) }
            }
        }
    }

    suspend fun sendChatMessage(
        scope: CoroutineScope,
        isVoice: Boolean,
        chatMessage: String,
        displayMessage: String = chatMessage,
        attachedFiles: List<ChatAttachedFile> = emptyList(),
        requestSource: ChatRequestSource = ChatRequestSource.CHAT_UI,
        onResult: ((Result<String>) -> Unit)? = null,
    ) {
        resetBeforeSendingMessage()

        val userText = chatMessage.trim()
        if (userText.isEmpty()) {
            onResult?.invoke(Result.failure(IllegalArgumentException("Empty message")))
            return
        }

        val session = createChatRequestSession(
            userText = userText,
            displayMessage = displayMessage,
            isVoice = isVoice,
            attachedFiles = attachedFiles,
            requestSource = requestSource,
        )

        try {
            emitRequestStarted(session)
            session.sideEffectsJob = subscribeOnTaskSideEffects(scope, session)
            l.info(
                "About to execute agent request: source={} chars={}",
                requestSource,
                userText.length,
            )

            val response = executeAgentRequest(session, userText)
            val completedResponse = buildCompletedResponse(session, response.output, onResult)
                ?: return

            handleRequestSuccess(
                scope = scope,
                session = session,
                response = completedResponse,
                onResult = onResult,
            )
        } catch (e: CancellationException) {
            session.requestStatus = ChatRequestStatus.CANCELLED
            session.requestErrorType = requestErrorType(e)
            handleRequestCancellation(session, e, onResult)
        } catch (e: Exception) {
            session.requestStatus = ChatRequestStatus.ERROR
            session.requestErrorType = requestErrorType(e)
            if (!handleRequestFailure(session, e, onResult)) {
                return
            }
        } finally {
            finalizeRequestSession(session)
        }
    }

    /** Adds text to the open Skills run without starting or cancelling a chat request session. */
    suspend fun submitToActiveRun(
        chatMessage: String,
    ): Boolean = submitToActiveRunInternal(chatMessage, expectedRequestId = null)

    internal suspend fun captureActiveSkillsRequestId(): Long? = activeRequestMutex.withLock {
        if (agentFacade.activeAgentId.value != AgentId.SKILLS_GRAPH) return@withLock null
        val session = activeRequestSession ?: return@withLock null
        session.requestId.takeIf { it == activeChatRequestId }
    }

    internal suspend fun submitToCapturedActiveRun(
        expectedRequestId: Long,
        chatMessage: String,
    ): Boolean = submitToActiveRunInternal(chatMessage, expectedRequestId)

    private suspend fun submitToActiveRunInternal(
        chatMessage: String,
        expectedRequestId: Long?,
    ): Boolean {
        val userText = chatMessage.trim()
        if (userText.isEmpty()) return false

        return activeRequestMutex.withLock {
            val session = activeRequestSession ?: return@withLock false
            if (session.requestId != activeChatRequestId) return@withLock false
            if (expectedRequestId != null && session.requestId != expectedRequestId) return@withLock false
            if (!agentFacade.submitToActiveRun(userText)) return@withLock false

            val continuationMessage = ChatMessage(text = userText, isUser = true)
            session.userMessageIds += continuationMessage.id
            session.sideEffectRevision += 1
            speechUseCase.clearQueue()
            emitState(refreshChatSearch = true) {
                val pendingIds = setOf(session.pendingBotMessage.id, session.currentPendingMessageId)
                copy(
                    chatMessages = chatMessages.filterNot { it.id in pendingIds } + continuationMessage,
                    agentActions = emptyList(),
                    statusMessage = "",
                )
            }
            l.info("Submitted input to active agent run: chars={}", userText.length)
            true
        }
    }

    /**
     * Stops only the currently running agent execution without directly mutating chat UI state.
     */
    private suspend fun cancelActiveJob() {
        agentFacade.cancelActiveJob()
    }

    /**
     * Cancels the active request, drops any in-flight chat messages, and clears pending approvals.
     */
    suspend fun abortActiveRequest() {
        val (nextRequestId, inFlightMessageIds) = invalidateActiveRequest()

        killTaskSideEffectJobs()
        cancelActiveJob()
        toolModifyReviewUseCase.clearPendingReview(discardBrokerState = true)

        emitState(refreshChatSearch = true) {
            copy(
                chatMessages = if (inFlightMessageIds.isEmpty()) {
                    chatMessages
                } else {
                    chatMessages.filterNot { it.id in inFlightMessageIds }
                },
                isProcessing = false,
                isAwaitingToolReview = false,
                agentActions = emptyList(),
            )
        }
        l.info("Active request reset: invalidated request {}", nextRequestId)
    }

    /**
     * Stops synthesized speech and streamed side effects while leaving chat history intact.
     */
    fun stopAssistantOutput() {
        killTaskSideEffectJobs()
    }

    /**
     * Clears the agent context after resetting any in-flight request state.
     */
    suspend fun clearConversationContext() {
        abortActiveRequest()
        agentFacade.clearContext()
    }

    suspend fun finishCurrentConversation(reason: ChatConversationCloseReason): ToolInvocationMeta? {
        return closeAndCaptureConversation(reason)
    }

    private fun closeCurrentConversation(reason: ChatConversationCloseReason): String? {
        return observabilityTracker.finishCurrentConversation(reason)
    }

    suspend fun cleanupConversation(meta: ToolInvocationMeta) {
        val conversationId = meta.conversationId?.takeIf(String::isNotBlank) ?: return
        try {
            memoryConversationCleanup.cleanupConversation(conversationId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            l.warn("Memory conversation cleanup failed for conversationId={}", conversationId, error)
        }

        try {
            conversationKnowledgeStore.clearConversation(meta)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            l.warn(
                "Knowledge conversation cleanup failed for userId={} conversationId={}",
                meta.userId,
                conversationId,
                error,
            )
        }
    }

    suspend fun setContext(ctx: AgentContext<String>) {
        agentFacade.setContext(ctx)
    }

    fun snapshotContext(): AgentContext<String>? = agentFacade.currentContext.value

    fun updateModel(model: LLMModel) {
        agentFacade.setModel(model)
    }

    fun updateContextSize(size: Int) {
        agentFacade.setContextSize(size)
    }

    /** Finishes UI-owned state after the ViewModel request scope has been cancelled. */
    fun onCleared(): ToolInvocationMeta? {
        val conversationMeta = closeAndCaptureConversation(ChatConversationCloseReason.VIEW_MODEL_CLEARED)
        killTaskSideEffectJobs()
        toolModifyReviewUseCase.clearPendingReviewBlocking(discardBrokerState = true)
        return conversationMeta
    }

    private fun closeAndCaptureConversation(reason: ChatConversationCloseReason): ToolInvocationMeta? {
        val conversationId = closeCurrentConversation(reason) ?: return null
        val meta = activeConversationMeta
            ?.takeIf { it.conversationId == conversationId }
            ?: agentFacade.currentContext.value.toolInvocationMeta.copy(conversationId = conversationId)
        activeConversationMeta = null
        return meta
    }

    private fun subscribeOnTaskSideEffects(scope: CoroutineScope, session: ChatRequestSession): Job {
        val agentId = agentFacade.activeAgentId.value
        val msg = session.pendingBotMessage
        val job = scope.launch {
            var isCodeBlockStarted = false
            var accumulatedText = ""
            var observedRevision = session.sideEffectRevision
            agentFacade.sideEffects.collect { effect ->
                when (effect) {
                    is AgentSideEffect.Text -> {
                        activeRequestMutex.withLock textEffect@{
                            if (effect.streamRevision != session.sideEffectRevision) {
                                return@textEffect
                            }
                            if (observedRevision != effect.streamRevision) {
                                observedRevision = effect.streamRevision
                                accumulatedText = ""
                                isCodeBlockStarted = false
                            }
                            if (toolModifyReviewUseCase.hasPendingEdits()) {
                                return@textEffect
                            }
                            val text = effect.v
                            accumulatedText += text
                            emitState(refreshChatSearch = true) {
                                val updatedMessage = msg.copy(
                                    text = accumulatedText,
                                )
                                val updatedMessages = if (msg.id == chatMessages.lastOrNull()?.id) {
                                    chatMessages.mapLast { updatedMessage }
                                } else {
                                    chatMessages + updatedMessage
                                }
                                copy(chatMessages = updatedMessages)
                            }

                            if (!msg.isVoice) return@textEffect

                            if (text.contains(CODE_BLOCK)) {
                                isCodeBlockStarted = !isCodeBlockStarted
                                if (isCodeBlockStarted) {
                                    speechUseCase.queuePrepared(text.substringBefore(CODE_BLOCK))
                                }
                            }

                            if (!isCodeBlockStarted) {
                                speechUseCase.queuePrepared(text.substringAfter(CODE_BLOCK))
                            }
                        }
                    }
                    is AgentSideEffect.Fn -> {
                        val action = chatAgentActionFormatter.format(agentId, effect.call)
                            ?: return@collect
                        emitState {
                            copy(
                                agentActions = (agentActions + action)
                                    .distinct()
                                    .takeLast(MAX_AGENT_ACTIONS)
                            )
                        }
                    }
                }
            }
        }
        taskSideEffectJobs.add(job)
        return job
    }

    private fun killTaskSideEffectJobs() {
        speechUseCase.clearQueue()
        taskSideEffectJobs.forEach { it.cancel() }
        taskSideEffectJobs.clear()
    }

    /**
     * Resets any in-flight chat execution state before a new request starts.
     */
    private suspend fun resetBeforeSendingMessage() {
        killTaskSideEffectJobs()
        cancelActiveJob()
        toolModifyReviewUseCase.clearPendingReview(discardBrokerState = true)
    }

    /**
     * Creates a session object that carries chat request state through execution,
     * observability, approval flow, and final cleanup.
     */
    private suspend fun createChatRequestSession(
        userText: String,
        displayMessage: String,
        isVoice: Boolean,
        attachedFiles: List<ChatAttachedFile>,
        requestSource: ChatRequestSource,
    ): ChatRequestSession {
        val requestId = nextActiveRequestId()
        val conversationId = observabilityTracker.ensureConversation(requestSource)
        val requestContext = log.requestContext(
            conversationId = conversationId,
            source = requestSource,
            model = settingsProvider.gigaModel.alias,
            provider = settingsProvider.gigaModel.provider.name,
            inputLengthChars = userText.length,
            attachedFilesCount = attachedFiles.size,
        )
        log.requestStarted(requestContext)
        observabilityTracker.markConversationRequestStarted(conversationId)
        tokenLogging.startRequest(requestContext.requestId)

        val userMessage = ChatMessage(
            text = displayMessage.trim(),
            isUser = true,
            isVoice = isVoice,
            attachedFiles = attachedFiles,
        )
        val pendingBotMessage = ChatMessage(
            text = "",
            isUser = false,
            isVoice = isVoice,
        )
        val executionMeta = executionMeta(requestContext, userMessage, pendingBotMessage)
        val session = ChatRequestSession(
            requestId = requestId,
            requestContext = requestContext,
            userMessage = userMessage,
            pendingBotMessage = pendingBotMessage,
            executionMeta = executionMeta,
        )
        activeConversationMeta = session.executionMeta
        updateActiveRequestSession(session)
        return session
    }

    /** Keeps the current session's pending assistant message aligned with its UI representation. */
    private suspend fun updateActiveRequestSession(
        session: ChatRequestSession,
        pendingMessageId: String = session.pendingBotMessage.id,
    ) {
        activeRequestMutex.withLock {
            if (activeChatRequestId != session.requestId) return@withLock
            session.currentPendingMessageId = pendingMessageId
            activeRequestSession = session
        }
    }

    /** Publishes the user's message and flips the UI into processing mode for this session. */
    private suspend fun emitRequestStarted(session: ChatRequestSession) {
        emitState(refreshChatSearch = true) {
            copy(
                chatMessages = chatMessages + session.userMessage,
                chatStartTip = "",
                isProcessing = true,
                statusMessage = "",
                agentActions = emptyList(),
            )
        }
    }

    /** Executes the agent under the session's structured logging and token logging context. */
    private suspend fun executeAgentRequest(
        session: ChatRequestSession,
        userText: String,
    ): AgentExecutionResult = withContext(
        ioDispatcher +
            session.requestContext.asCoroutineContext() +
            tokenLogging.requestContextElement(session.requestContext.requestId)
    ) {
        agentFacade.executeForResult(
            input = userText,
            toolInvocationMetaOverride = session.executionMeta,
        )
    }

    private fun executionMeta(
        requestContext: ChatRequestLogContext,
        userMessage: ChatMessage,
        pendingBotMessage: ChatMessage,
    ): ToolInvocationMeta {
        val current = agentFacade.currentContext.value.toolInvocationMeta
        return current.copy(
            conversationId = requestContext.conversationId,
            requestId = requestContext.requestId,
            attributes = current.attributes + mapOf(
                "userMessageId" to userMessage.id,
                "assistantMessageId" to pendingBotMessage.id,
            ),
        )
    }

    /**
     * Turns the raw agent response into the final bot message shape, including
     * attachments, finder paths, stale-request detection, and optional tool review.
     */
    private suspend fun buildCompletedResponse(
        session: ChatRequestSession,
        response: String,
        onResult: ((Result<String>) -> Unit)?,
    ): CompletedChatResponse? {
        val extractedFinderPaths = extractFinderPaths(response)
        val botAttachments = chatAttachmentsUseCase.buildAttachmentsFromPaths(
            extractedFinderPaths.map { it.path }
        )
        if (!ensureSessionIsCurrent(session, onResult)) {
            return null
        }

        val toolReviewResult = toolModifyReviewUseCase.resolvePendingReviewIfNeeded(
            requestId = session.requestId,
            pendingBotMessage = session.pendingBotMessage,
            response = response,
            onReviewShown = { reviewMessageId ->
                updateActiveRequestSession(session, pendingMessageId = reviewMessageId)
            },
        )
        val botMessage = if (toolReviewResult.appendAsNewMessage) {
            ChatMessage(
                text = toolReviewResult.text,
                isUser = false,
                isVoice = session.pendingBotMessage.isVoice,
                attachedFiles = botAttachments,
                finderPaths = extractedFinderPaths,
            )
        } else {
            session.pendingBotMessage.copy(
                text = toolReviewResult.text,
                finderPaths = extractedFinderPaths,
                attachedFiles = botAttachments,
            )
        }
        return CompletedChatResponse(
            botMessage = botMessage,
            appendAsNewMessage = toolReviewResult.appendAsNewMessage,
        )
    }

    /**
     * Verifies that the session still owns the latest request slot before UI state
     * is updated with the completed agent response.
     */
    private suspend fun ensureSessionIsCurrent(
        session: ChatRequestSession,
        onResult: ((Result<String>) -> Unit)?,
    ): Boolean {
        if (currentActiveRequestId() == session.requestId) {
            return true
        }

        l.info("Skipping stale chat response for request {}", session.requestId)
        session.requestStatus = ChatRequestStatus.CANCELLED
        session.requestErrorType = "StaleRequest"
        toolModifyReviewUseCase.clearPendingReview(discardBrokerState = true)
        onResult?.invoke(Result.failure(CancellationException("Stale request")))
        return false
    }

    /**
     * Commits a successful request into UI state, triggers completion side effects,
     * and records response metrics on the session for final structured logging.
     */
    private suspend fun handleRequestSuccess(
        scope: CoroutineScope,
        session: ChatRequestSession,
        response: CompletedChatResponse,
        onResult: ((Result<String>) -> Unit)?,
    ) {
        if (settingsProvider.notificationSoundEnabled) {
            speechUseCase.playMacPingMsgSafely(scope)
        }

        activeRequestMutex.withLock {
            emitState(refreshChatSearch = true) {
                val completedBotMessage = response.botMessage.copy(agentActions = agentActions)
                copy(
                    chatMessages = if (response.appendAsNewMessage) {
                        chatMessages + completedBotMessage
                    } else {
                        upsertMessage(completedBotMessage)
                    },
                    isProcessing = false,
                    isAwaitingToolReview = false,
                    agentActions = emptyList(),
                )
            }
        }

        if (response.botMessage.isVoice && !settingsProvider.useStreaming) {
            speechUseCase.queuePrepared(response.botMessage.text)
        }
        session.responseLengthChars = response.botMessage.text.length
        onResult?.invoke(Result.success(response.botMessage.text))
    }

    /**
     * Removes the session's pending UI messages after cancellation and clears any
     * approval state that might still be waiting for user input.
     */
    private suspend fun handleRequestCancellation(
        session: ChatRequestSession,
        error: CancellationException,
        onResult: ((Result<String>) -> Unit)?,
    ) {
        l.info("Chat message cancelled: {}", error.message)
        val isCurrentRequest = currentActiveRequestId() == session.requestId
        val requestMessageIds = requestMessageIds(session)
        toolModifyReviewUseCase.clearPendingReview(discardBrokerState = true)
        withContext(NonCancellable) {
            emitState(refreshChatSearch = true) {
                copy(
                    chatMessages = chatMessages.filterNot { it.id in requestMessageIds },
                    isProcessing = if (isCurrentRequest) false else isProcessing,
                    isAwaitingToolReview = if (isCurrentRequest) false else isAwaitingToolReview,
                    agentActions = if (isCurrentRequest) emptyList() else agentActions,
                )
            }
        }
        onResult?.invoke(Result.failure(error))
    }

    /**
     * Publishes an error message for the current session, unless the failure belongs
     * to a request that has already been superseded by a newer one.
     */
    private suspend fun handleRequestFailure(
        session: ChatRequestSession,
        error: Exception,
        onResult: ((Result<String>) -> Unit)?,
    ): Boolean {
        if (currentActiveRequestId() != session.requestId) {
            l.info("Ignoring stale chat failure for request {}: {}", session.requestId, error.message)
            toolModifyReviewUseCase.clearPendingReview(discardBrokerState = true)
            onResult?.invoke(Result.failure(error))
            return false
        }

        l.error("Chat message failed: {}", error.message, error)
        val errorMessage = ChatMessage(
            text = "Ошибка: ${error.message}",
            isUser = false,
            isVoice = session.userMessage.isVoice,
        )

        emitState(refreshChatSearch = true) {
            copy(
                chatMessages = chatMessages + errorMessage,
                isProcessing = false,
                isAwaitingToolReview = false,
                agentActions = emptyList(),
            )
        }
        onResult?.invoke(Result.failure(error))
        return true
    }

    /**
     * Completes structured logging, stops side-effect streaming, and clears the active request
     * bookkeeping for the finished session.
     */
    private suspend fun finalizeRequestSession(session: ChatRequestSession) {
        val requestTokenUsage = tokenLogging.currentRequestTokenUsage(session.requestContext.requestId)
        observabilityTracker.recordConversationRequestFinished(
            conversationId = session.requestContext.conversationId,
            toolCallCount = session.requestContext.toolExecutionCount,
            requestTokenUsage = requestTokenUsage,
        )
        log.requestFinished(
            context = session.requestContext,
            status = session.requestStatus,
            responseLengthChars = session.responseLengthChars,
            errorType = session.requestErrorType,
            requestTokenUsage = requestTokenUsage,
            sessionTokenUsage = tokenLogging.sessionTokenUsage(),
        )
        tokenLogging.finishRequest(session.requestContext.requestId)
        observabilityTracker.finishPendingConversationIfNeeded(session.requestContext.conversationId)
        session.sideEffectsJob?.cancel()
        session.sideEffectsJob?.let { taskSideEffectJobs.remove(it) }
        activeRequestMutex.withLock {
            if (activeRequestSession === session) {
                activeRequestSession = null
            }
        }
    }

    private suspend fun nextActiveRequestId(): Long = activeRequestMutex.withLock {
        activeChatRequestId += 1
        activeChatRequestId
    }

    private suspend fun invalidateActiveRequest(): Pair<Long, Set<String>> = activeRequestMutex.withLock {
        activeChatRequestId += 1
        val inFlightMessageIds = activeRequestSession?.messageIds().orEmpty()
        activeRequestSession = null
        activeChatRequestId to inFlightMessageIds
    }

    private suspend fun currentActiveRequestId(): Long = activeRequestMutex.withLock {
        activeChatRequestId
    }

    private suspend fun requestMessageIds(session: ChatRequestSession): Set<String> = activeRequestMutex.withLock {
        session.messageIds()
    }

    private suspend fun emitState(
        refreshChatSearch: Boolean = false,
        reduce: MainState.() -> MainState,
    ) {
        _outputs.emit(
            MainUseCaseOutput.State(
                reduce = reduce,
                refreshChatSearch = refreshChatSearch,
            )
        )
    }

    private fun MainState.upsertMessage(
        message: ChatMessage,
        fallbackMessageId: String? = null,
    ): List<ChatMessage> = when {
        chatMessages.lastOrNull()?.id == message.id -> chatMessages.mapLast { message }
        fallbackMessageId != null && chatMessages.lastOrNull()?.id == fallbackMessageId ->
            chatMessages.mapLast { message }
        else -> chatMessages + message
    }

    private suspend fun extractFinderPaths(text: String) =
        withContext(ioDispatcher) {
            finderPathExtractor.extract(text)
        }

    private inline fun <T> List<T>.mapLast(transform: (T) -> T): List<T> =
        mapIndexed { index, value -> if (index == lastIndex) transform(value) else value }

    private fun requestErrorType(error: Throwable): String =
        error::class.simpleName
            ?: error::class.qualifiedName?.substringAfterLast('.')
            ?: "UnknownError"

    private companion object {
        const val CODE_BLOCK = "```"
        const val MAX_AGENT_ACTIONS = 8
    }

    private class ChatRequestSession(
        val requestId: Long,
        val requestContext: ChatRequestLogContext,
        val userMessage: ChatMessage,
        val pendingBotMessage: ChatMessage,
        val executionMeta: ToolInvocationMeta,
    ) {
        val userMessageIds: MutableSet<String> = mutableSetOf(userMessage.id)
        var currentPendingMessageId: String = pendingBotMessage.id
        var requestStatus: ChatRequestStatus = ChatRequestStatus.SUCCESS
        var responseLengthChars: Int? = null
        var requestErrorType: String? = null
        var sideEffectsJob: Job? = null
        var sideEffectRevision: Long = 0L

        fun messageIds(): Set<String> = userMessageIds + currentPendingMessageId
    }

    private data class CompletedChatResponse(
        val botMessage: ChatMessage,
        val appendAsNewMessage: Boolean,
    )
}
