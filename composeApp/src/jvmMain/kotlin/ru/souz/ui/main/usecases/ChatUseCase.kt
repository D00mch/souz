package ru.souz.ui.main.usecases

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import ru.souz.service.telemetry.TelemetryRequestSource
import ru.souz.service.telemetry.TelemetryRequestStatus
import ru.souz.service.telemetry.TelemetryService
import ru.souz.ui.main.ChatAgentActionFormatter
import ru.souz.ui.main.ChatAttachedFile
import ru.souz.ui.main.ChatMessage
import ru.souz.ui.main.MainState
import ru.souz.ui.main.ToolModifyReviewItemUi
import ru.souz.ui.main.ToolModifyReviewUi
import ru.souz.tool.files.DeferredToolModifyPermissionBroker
import ru.souz.tool.files.ToolModifyApplyResult
import ru.souz.tool.files.ToolModifyApplyStatus
import ru.souz.tool.files.ToolModifySelectionAction
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
    private val deferredToolModifyPermissionBroker: DeferredToolModifyPermissionBroker,
    private val tokenLogging: TokenLogging,
    private val telemetryService: TelemetryService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val l = LoggerFactory.getLogger(ChatUseCase::class.java)
    private val chatAgentActionFormatter = ChatAgentActionFormatter()
    private val taskSideEffectJobs = ArrayList<Job>()
    private val activeChatRequestId = AtomicLong(0L)
    private val activeRequestMessages = AtomicReference<ActiveRequestMessages?>(null)
    private val pendingToolModifyReview = AtomicReference<PendingToolModifyReviewState?>(null)
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
        killTaskSideEffectJobs()
        cancelActiveJob()
        clearPendingToolModifyReview(discardBrokerState = true)

        val userText = chatMessage.trim()
        if (userText.isEmpty()) {
            onResult?.invoke(Result.failure(IllegalArgumentException("Empty message")))
            return
        }

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
        var telemetryStatus = TelemetryRequestStatus.SUCCESS
        var telemetryResponseLength: Int? = null
        var telemetryErrorMessage: String? = null
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
        activeRequestMessages.set(
            ActiveRequestMessages(
                requestId = requestId,
                userMessageId = userMessage.id,
                pendingMessageId = pendingBotMessage.id,
            )
        )

        var sideEffectsJob: Job? = null

        try {
            emitState {
                copy(
                    chatMessages = chatMessages + userMessage,
                    chatStartTip = "",

                    isProcessing = true,
                    statusMessage = "",
                    agentActions = emptyList(),
                )
            }

            sideEffectsJob = subscribeOnTaskSideEffects(scope, pendingBotMessage)
            l.info("About to execute agent with user input {}", userText)

            val response = withContext(
                ioDispatcher +
                    telemetryService.requestContextElement(requestContext) +
                    tokenLogging.requestContextElement(requestContext.requestId)
            ) {
                agentFacade.execute(userText)
            }

            val extractedFinderPaths = extractFinderPaths(response)
            val botAttachments = chatAttachmentsUseCase.buildAttachmentsFromPaths(
                extractedFinderPaths.map { it.path }
            )
            if (activeChatRequestId.get() != requestId) {
                l.info("Skipping stale chat response for request {}", requestId)
                telemetryStatus = TelemetryRequestStatus.CANCELLED
                telemetryErrorMessage = "StaleRequest"
                onResult?.invoke(Result.failure(CancellationException("Stale request")))
                return
            }

            val pendingReview = deferredToolModifyPermissionBroker.snapshotPendingReview()
            val finalBotText = if (pendingReview != null) {
                val reviewMessage = pendingBotMessage.copy(
                    toolModifyReview = ToolModifyReviewUi(
                        items = pendingReview.items.map { item ->
                            ToolModifyReviewItemUi(
                                id = item.id,
                                path = item.path,
                                patchPreview = item.patchPreview,
                            )
                        }
                    )
                )
                val reviewDeferred = CompletableDeferred<ToolModifyReviewDecision>()
                pendingToolModifyReview.set(
                    PendingToolModifyReviewState(
                        requestId = requestId,
                        messageId = reviewMessage.id,
                        decision = reviewDeferred,
                    )
                )
                activeRequestMessages.set(
                    ActiveRequestMessages(
                        requestId = requestId,
                        userMessageId = userMessage.id,
                        pendingMessageId = reviewMessage.id,
                    )
                )
                emitState {
                    copy(
                        chatMessages = upsertMessage(reviewMessage, fallbackMessageId = pendingBotMessage.id),
                        isProcessing = false,
                        isAwaitingToolReview = true,
                        agentActions = emptyList(),
                    )
                }

                val decision = reviewDeferred.await()
                val applyResult = deferredToolModifyPermissionBroker.applySelection(
                    selectedIds = decision.selectedIds,
                    action = decision.action,
                )
                val resolvedReview = reviewMessage.toolModifyReview?.copy(
                    items = reviewMessage.toolModifyReview.items.map { item ->
                        val outcome = applyResult.items.firstOrNull { it.id == item.id }
                        item.copy(
                            selected = item.id in decision.selectedIds,
                            status = outcome?.status,
                            warning = outcome?.warning,
                        )
                    },
                    isResolved = true,
                    summary = formatToolModifyReviewSummary(applyResult),
                )
                emitState {
                    copy(
                        chatMessages = chatMessages.map { message ->
                            if (message.id == reviewMessage.id) {
                                message.copy(toolModifyReview = resolvedReview)
                            } else {
                                message
                            }
                        },
                        isAwaitingToolReview = false,
                    )
                }
                pendingToolModifyReview.set(null)
                appendToolModifySummary(response, applyResult)
            } else {
                response
            }

            val botMessage = if (pendingReview != null) {
                ChatMessage(
                    text = finalBotText,
                    isUser = false,
                    isVoice = isVoice,
                    attachedFiles = botAttachments,
                    finderPaths = extractedFinderPaths,
                )
            } else {
                pendingBotMessage.copy(
                    text = finalBotText,
                    finderPaths = extractedFinderPaths,
                    attachedFiles = botAttachments,
                )
            }

            if (settingsProvider.notificationSoundEnabled) {
                speechUseCase.playMacPingMsgSafely(scope)
            }

            emitState {
                val completedBotMessage = botMessage.copy(agentActions = agentActions)
                copy(
                    chatMessages = if (pendingReview != null) {
                        chatMessages + completedBotMessage
                    } else {
                        upsertMessage(completedBotMessage)
                    },
                    isProcessing = false,
                    isAwaitingToolReview = false,
                    agentActions = emptyList(),
                )
            }

            if (isVoice && !settingsProvider.useStreaming) {
                speechUseCase.queuePrepared(botMessage.text)
            }
            telemetryResponseLength = botMessage.text.length
            onResult?.invoke(Result.success(botMessage.text))
        } catch (e: CancellationException) {
            telemetryStatus = TelemetryRequestStatus.CANCELLED
            telemetryErrorMessage = telemetryErrorLabel(e)
            l.info("Chat message cancelled: {}", e.message)
            val isCurrentRequest = activeChatRequestId.get() == requestId
            clearPendingToolModifyReview(discardBrokerState = true)
            withContext(NonCancellable) {
                emitState {
                    val idsToDrop = activeRequestMessages.get()
                        ?.takeIf { it.requestId == requestId }
                        ?.let { arrayOf(it.userMessageId, it.pendingMessageId) }
                        ?: arrayOf(userMessage.id, pendingBotMessage.id)
                    copy(
                        chatMessages = chatMessages.filterNot { it.id in idsToDrop },
                        isProcessing = if (isCurrentRequest) false else isProcessing,
                        isAwaitingToolReview = if (isCurrentRequest) false else isAwaitingToolReview,
                        agentActions = if (isCurrentRequest) emptyList() else agentActions,
                    )
                }
            }
            onResult?.invoke(Result.failure(e))
        } catch (e: Exception) {
            telemetryStatus = TelemetryRequestStatus.ERROR
            telemetryErrorMessage = telemetryErrorLabel(e)
            if (activeChatRequestId.get() != requestId) {
                l.info("Ignoring stale chat failure for request {}: {}", requestId, e.message)
                onResult?.invoke(Result.failure(e))
                return
            }

            l.error("Chat message failed: {}", e.message, e)
            val errorMessage = ChatMessage(
                text = "Ошибка: ${e.message}",
                isUser = false,
                isVoice = isVoice,
            )

            emitState {
                copy(
                    chatMessages = chatMessages + errorMessage,
                    isProcessing = false,
                    isAwaitingToolReview = false,
                    agentActions = emptyList(),
                )
            }
            onResult?.invoke(Result.failure(e))
        } finally {
            telemetryService.finishRequest(
                context = requestContext,
                status = telemetryStatus,
                responseLengthChars = telemetryResponseLength,
                errorMessage = telemetryErrorMessage,
                requestTokenUsage = tokenLogging.currentRequestTokenUsage(requestContext.requestId),
                sessionTokenUsage = tokenLogging.sessionTokenUsage(),
            )
            tokenLogging.finishRequest(requestContext.requestId)
            finishPendingConversationIfNeeded(requestContext.conversationId)
            sideEffectsJob?.cancel()
            sideEffectsJob?.let { taskSideEffectJobs.remove(it) }
            val currentActiveRequest = activeRequestMessages.get()
            if (currentActiveRequest?.requestId == requestId) {
                activeRequestMessages.compareAndSet(currentActiveRequest, null)
            }
        }
    }

    fun cancelActiveJob() {
        agentFacade.cancelActiveJob()
    }

    suspend fun stopCurrentExecution() {
        val nextRequestId = activeChatRequestId.incrementAndGet()
        val inFlightMessages = activeRequestMessages.getAndSet(null)

        killTaskSideEffectJobs()
        cancelActiveJob()
        clearPendingToolModifyReview(discardBrokerState = true)

        emitState {
            val idsToDrop = inFlightMessages?.let { arrayOf(it.userMessageId, it.pendingMessageId) } ?: emptyArray()
            copy(
                chatMessages = if (idsToDrop.isEmpty()) chatMessages else chatMessages.filterNot { it.id in idsToDrop },
                isProcessing = false,
                isAwaitingToolReview = false,
                agentActions = emptyList(),
            )
        }
        l.info("Stop requested: invalidated request {}", nextRequestId)
    }

    fun stopSpeechAndSideEffects() {
        killTaskSideEffectJobs()
    }

    fun clearContext() {
        clearPendingToolModifyReview(discardBrokerState = true)
        agentFacade.clearContext()
    }

    suspend fun toggleToolModifyReviewSelection(messageId: String, itemId: Long) {
        val pendingReviewState = pendingToolModifyReview.get() ?: return
        if (pendingReviewState.messageId != messageId) return
        emitState {
            copy(
                chatMessages = chatMessages.map { message ->
                    if (message.id != messageId) return@map message
                    val review = message.toolModifyReview ?: return@map message
                    message.copy(
                        toolModifyReview = review.copy(
                            items = review.items.map { item ->
                                if (item.id == itemId && !review.isResolved) {
                                    item.copy(selected = !item.selected)
                                } else {
                                    item
                                }
                            }
                        )
                    )
                }
            )
        }
    }

    fun resolveToolModifyReview(
        messageId: String,
        action: ToolModifySelectionAction,
        selectedIds: Set<Long>,
    ) {
        val pendingReviewState = pendingToolModifyReview.get() ?: return
        if (pendingReviewState.messageId != messageId) return
        pendingReviewState.decision.complete(
            ToolModifyReviewDecision(
                action = action,
                selectedIds = selectedIds,
            )
        )
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
        clearPendingToolModifyReview(discardBrokerState = true)
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
                        if (deferredToolModifyPermissionBroker.hasPendingEdits()) {
                            return@collect
                        }
                        val text = effect.v
                        accumulatedText += text
                        emitState {
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
                            copy(agentActions = (agentActions + action).takeLast(MAX_AGENT_ACTIONS))
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

    private suspend fun emitState(reduce: MainState.() -> MainState) {
        _outputs.emit(MainUseCaseOutput.State(reduce))
    }

    private fun clearPendingToolModifyReview(discardBrokerState: Boolean) {
        pendingToolModifyReview.getAndSet(null)?.decision?.cancel()
        if (discardBrokerState) {
            kotlinx.coroutines.runBlocking {
                deferredToolModifyPermissionBroker.clearPending()
            }
        }
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

    private fun appendToolModifySummary(
        response: String,
        applyResult: ToolModifyApplyResult,
    ): String {
        val summary = buildString {
            appendLine()
            appendLine()
            appendLine("Applied changes summary:")
            appendLine("- Applied: ${applyResult.appliedCount}")
            appendLine("- Discarded: ${applyResult.discardedCount}")
            appendLine("- Skipped: ${applyResult.skippedCount}")
            applyResult.items
                .filter { it.status == ToolModifyApplyStatus.SKIPPED_CONFLICT || it.status == ToolModifyApplyStatus.SKIPPED_EXTERNAL_CONFLICT }
                .forEach { item ->
                    appendLine("- Warning: ${item.path}: ${item.warning ?: "Can't apply the staged change."}")
                }
        }
        return response + summary
    }

    private fun formatToolModifyReviewSummary(applyResult: ToolModifyApplyResult): String =
        "Applied ${applyResult.appliedCount}, discarded ${applyResult.discardedCount}, skipped ${applyResult.skippedCount}"

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

    private data class PendingToolModifyReviewState(
        val requestId: Long,
        val messageId: String,
        val decision: CompletableDeferred<ToolModifyReviewDecision>,
    )

    private data class ToolModifyReviewDecision(
        val action: ToolModifySelectionAction,
        val selectedIds: Set<Long>,
    )
}
