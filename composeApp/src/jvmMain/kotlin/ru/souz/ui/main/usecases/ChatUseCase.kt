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
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.agent.AgentFacade
import ru.souz.agent.AgentSideEffect
import ru.souz.agent.state.AgentContext
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMModel
import ru.souz.llms.TokenLogging
import ru.souz.service.telemetry.TelemetryConversationEndReason
import ru.souz.service.telemetry.TelemetryConversationStartReason
import ru.souz.service.telemetry.TelemetryRequestContext
import ru.souz.service.telemetry.TelemetryRequestSource
import ru.souz.service.telemetry.TelemetryRequestStatus
import ru.souz.service.telemetry.TelemetryService
import ru.souz.ui.main.ChatAgentActionFormatter
import ru.souz.ui.main.ChatAttachedFile
import ru.souz.ui.main.ChatMessage
import ru.souz.ui.main.MainState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.plus

class ChatUseCase(
    private val agentFacade: AgentFacade,
    private val settingsProvider: SettingsProvider,
    private val speechUseCase: SpeechUseCase,
    private val finderPathExtractor: FinderPathExtractor,
    private val chatAttachmentsUseCase: ChatAttachmentsUseCase,
    private val toolModifyReviewUseCase: ToolModifyReviewUseCase,
    private val tokenLogging: TokenLogging,
    private val telemetryService: TelemetryService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val l = LoggerFactory.getLogger(ChatUseCase::class.java)
    private val chatAgentActionFormatter = ChatAgentActionFormatter()
    private val taskSideEffectJobs = ArrayList<Job>()
    private val activeChatRequestId = AtomicLong(0L)
    private val activeRequestMessages = AtomicReference<ActiveRequestMessages?>(null)
    private val conversationLock = Any()
    private var currentConversationId: String? = null
    private val pendingConversationClosures = LinkedHashMap<String, TelemetryConversationEndReason>()
    private val activeConversationRequestCounts = LinkedHashMap<String, Int>()

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
        requestSource: TelemetryRequestSource = TelemetryRequestSource.CHAT_UI,
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
            session.sideEffectsJob = subscribeOnTaskSideEffects(scope, session.pendingBotMessage)
            l.info("About to execute agent with user input {}", userText)

            val response = executeAgentRequest(session, userText)
            val completedResponse = buildCompletedResponse(session, response, onResult)
                ?: return

            handleRequestSuccess(
                scope = scope,
                session = session,
                response = completedResponse,
                onResult = onResult,
            )
        } catch (e: CancellationException) {
            session.telemetryStatus = TelemetryRequestStatus.CANCELLED
            session.telemetryErrorMessage = telemetryErrorLabel(e)
            handleRequestCancellation(session, e, onResult)
        } catch (e: Exception) {
            session.telemetryStatus = TelemetryRequestStatus.ERROR
            session.telemetryErrorMessage = telemetryErrorLabel(e)
            if (!handleRequestFailure(session, e, onResult)) {
                return
            }
        } finally {
            finalizeRequestSession(session)
        }
    }

    /**
     * Stops only the currently running agent execution without directly mutating chat UI state.
     */
    private fun cancelActiveJob() {
        agentFacade.cancelActiveJob()
    }

    /**
     * Cancels the active request, drops any in-flight chat messages, and clears pending approvals.
     */
    suspend fun abortActiveRequest() {
        val nextRequestId = activeChatRequestId.incrementAndGet()
        val inFlightMessages = activeRequestMessages.getAndSet(null)

        killTaskSideEffectJobs()
        cancelActiveJob()
        toolModifyReviewUseCase.clearPendingReview(discardBrokerState = true)

        emitState(refreshChatSearch = true) {
            val idsToDrop = inFlightMessages?.let { arrayOf(it.userMessageId, it.pendingMessageId) } ?: emptyArray()
            copy(
                chatMessages = if (idsToDrop.isEmpty()) chatMessages else chatMessages.filterNot { it.id in idsToDrop },
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

    fun finishCurrentConversation(reason: TelemetryConversationEndReason) {
        val conversationIdToFinish = synchronized(conversationLock) {
            val conversationId = currentConversationId ?: return@synchronized null
            currentConversationId = null
            if ((activeConversationRequestCounts[conversationId] ?: 0) > 0) {
                pendingConversationClosures[conversationId] = reason
                null
            } else {
                conversationId
            }
        }
        conversationIdToFinish?.let { telemetryService.finishConversation(it, reason) }
    }

    fun setContext(ctx: AgentContext<String>) {
        agentFacade.setContext(ctx)
    }

    fun snapshotContext(): AgentContext<String>? = agentFacade.currentContext.value

    fun updateModel(model: LLMModel) {
        agentFacade.setModel(model)
    }

    fun updateContextSize(size: Int) {
        agentFacade.setContextSize(size)
    }

    fun onCleared() {
        finishCurrentConversation(TelemetryConversationEndReason.VIEW_MODEL_CLEARED)
        killTaskSideEffectJobs()
        cancelActiveJob()
        toolModifyReviewUseCase.clearPendingReviewBlocking(discardBrokerState = true)
    }

    private fun ensureConversation(source: TelemetryRequestSource): String =
        synchronized(conversationLock) {
            currentConversationId ?: telemetryService.startConversation(
                reason = when (source) {
                    TelemetryRequestSource.CHAT_UI -> TelemetryConversationStartReason.CHAT_UI
                    TelemetryRequestSource.VOICE_INPUT -> TelemetryConversationStartReason.VOICE_INPUT
                    TelemetryRequestSource.TELEGRAM_BOT -> TelemetryConversationStartReason.TELEGRAM_BOT
                }
            ).also { currentConversationId = it }
        }

    private fun markConversationRequestStarted(conversationId: String) {
        synchronized(conversationLock) {
            activeConversationRequestCounts[conversationId] =
                (activeConversationRequestCounts[conversationId] ?: 0) + 1
        }
    }

    private fun finishPendingConversationIfNeeded(conversationId: String) {
        val reason = synchronized(conversationLock) {
            val remaining = (activeConversationRequestCounts[conversationId] ?: 1) - 1
            if (remaining > 0) {
                activeConversationRequestCounts[conversationId] = remaining
                null
            } else {
                activeConversationRequestCounts.remove(conversationId)
                pendingConversationClosures.remove(conversationId)
            }
        }
        reason?.let { telemetryService.finishConversation(conversationId, it) }
    }

    private fun subscribeOnTaskSideEffects(scope: CoroutineScope, msg: ChatMessage): Job {
        val job = scope.launch {
            val isCodeBlockStarted = AtomicBoolean(false)
            var accumulatedText = ""
            agentFacade.sideEffects.collect { effect ->
                when (effect) {
                    is AgentSideEffect.Text -> {
                        if (toolModifyReviewUseCase.hasPendingEdits()) {
                            return@collect
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

                        if (!msg.isVoice) return@collect

                        if (text.contains(CODE_BLOCK)) {
                            isCodeBlockStarted.set(!isCodeBlockStarted.get())
                            if (isCodeBlockStarted.get()) {
                                speechUseCase.queuePrepared(text.substringBefore(CODE_BLOCK))
                            }
                        }

                        if (!isCodeBlockStarted.get()) {
                            speechUseCase.queuePrepared(text.substringAfter(CODE_BLOCK))
                        }
                    }
                    is AgentSideEffect.Fn -> {
                        val action = chatAgentActionFormatter.format(effect.call)
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
     * telemetry, approval flow, and final cleanup.
     */
    private fun createChatRequestSession(
        userText: String,
        displayMessage: String,
        isVoice: Boolean,
        attachedFiles: List<ChatAttachedFile>,
        requestSource: TelemetryRequestSource,
    ): ChatRequestSession {
        val requestId = activeChatRequestId.incrementAndGet()
        val conversationId = ensureConversation(requestSource)
        val requestContext = telemetryService.beginRequest(
            conversationId = conversationId,
            source = requestSource,
            model = settingsProvider.gigaModel.alias,
            provider = settingsProvider.gigaModel.provider.name,
            inputLengthChars = userText.length,
            attachedFilesCount = attachedFiles.size,
        )
        markConversationRequestStarted(conversationId)
        tokenLogging.startRequest(requestContext.requestId)

        val session = ChatRequestSession(
            requestId = requestId,
            requestContext = requestContext,
            userMessage = ChatMessage(
                text = displayMessage.trim(),
                isUser = true,
                isVoice = isVoice,
                attachedFiles = attachedFiles,
            ),
            pendingBotMessage = ChatMessage(
                text = "",
                isUser = false,
                isVoice = isVoice,
            ),
        )
        updateActiveRequestMessages(session)
        return session
    }

    /**
     * Tracks the user message and the current bot placeholder/review message that
     * belong to the active request so later cancellation or cleanup can remove them.
     */
    private fun updateActiveRequestMessages(
        session: ChatRequestSession,
        pendingMessageId: String = session.pendingBotMessage.id,
    ) {
        session.currentPendingMessageId = pendingMessageId
        activeRequestMessages.set(
            ActiveRequestMessages(
                requestId = session.requestId,
                userMessageId = session.userMessage.id,
                pendingMessageId = pendingMessageId,
            )
        )
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

    /** Executes the agent under the session's telemetry and token logging context. */
    private suspend fun executeAgentRequest(
        session: ChatRequestSession,
        userText: String,
    ): String = withContext(
        ioDispatcher +
            telemetryService.requestContextElement(session.requestContext) +
            tokenLogging.requestContextElement(session.requestContext.requestId)
    ) {
        agentFacade.execute(userText)
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
                updateActiveRequestMessages(session, pendingMessageId = reviewMessageId)
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
        if (activeChatRequestId.get() == session.requestId) {
            return true
        }

        l.info("Skipping stale chat response for request {}", session.requestId)
        session.telemetryStatus = TelemetryRequestStatus.CANCELLED
        session.telemetryErrorMessage = "StaleRequest"
        toolModifyReviewUseCase.clearPendingReview(discardBrokerState = true)
        onResult?.invoke(Result.failure(CancellationException("Stale request")))
        return false
    }

    /**
     * Commits a successful request into UI state, triggers completion side effects,
     * and records response metrics on the session for final telemetry reporting.
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

        if (response.botMessage.isVoice && !settingsProvider.useStreaming) {
            speechUseCase.queuePrepared(response.botMessage.text)
        }
        session.telemetryResponseLength = response.botMessage.text.length
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
        val isCurrentRequest = activeChatRequestId.get() == session.requestId
        toolModifyReviewUseCase.clearPendingReview(discardBrokerState = true)
        withContext(NonCancellable) {
            emitState(refreshChatSearch = true) {
                val idsToDrop = activeRequestMessages.get()
                    ?.takeIf { it.requestId == session.requestId }
                    ?.let { arrayOf(it.userMessageId, it.pendingMessageId) }
                    ?: arrayOf(session.userMessage.id, session.currentPendingMessageId)
                copy(
                    chatMessages = chatMessages.filterNot { it.id in idsToDrop },
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
        if (activeChatRequestId.get() != session.requestId) {
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
     * Completes telemetry, stops side-effect streaming, and clears the active request
     * bookkeeping for the finished session.
     */
    private fun finalizeRequestSession(session: ChatRequestSession) {
        telemetryService.finishRequest(
            context = session.requestContext,
            status = session.telemetryStatus,
            responseLengthChars = session.telemetryResponseLength,
            errorMessage = session.telemetryErrorMessage,
            requestTokenUsage = tokenLogging.currentRequestTokenUsage(session.requestContext.requestId),
            sessionTokenUsage = tokenLogging.sessionTokenUsage(),
        )
        tokenLogging.finishRequest(session.requestContext.requestId)
        finishPendingConversationIfNeeded(session.requestContext.conversationId)
        session.sideEffectsJob?.cancel()
        session.sideEffectsJob?.let { taskSideEffectJobs.remove(it) }
        val currentActiveRequest = activeRequestMessages.get()
        if (currentActiveRequest?.requestId == session.requestId) {
            activeRequestMessages.compareAndSet(currentActiveRequest, null)
        }
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

    private fun telemetryErrorLabel(error: Throwable): String =
        error::class.simpleName
            ?: error::class.qualifiedName?.substringAfterLast('.')
            ?: "UnknownError"

    private companion object {
        const val CODE_BLOCK = "```"
        const val MAX_AGENT_ACTIONS = 8
    }

    private data class ActiveRequestMessages(
        val requestId: Long,
        val userMessageId: String,
        val pendingMessageId: String,
    )

    private class ChatRequestSession(
        val requestId: Long,
        val requestContext: TelemetryRequestContext,
        val userMessage: ChatMessage,
        val pendingBotMessage: ChatMessage,
    ) {
        var currentPendingMessageId: String = pendingBotMessage.id
        var telemetryStatus: TelemetryRequestStatus = TelemetryRequestStatus.SUCCESS
        var telemetryResponseLength: Int? = null
        var telemetryErrorMessage: String? = null
        var sideEffectsJob: Job? = null
    }

    private data class CompletedChatResponse(
        val botMessage: ChatMessage,
        val appendAsNewMessage: Boolean,
    )
}
