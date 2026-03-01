package ru.souz.ui.main.usecases

import androidx.compose.ui.text.input.TextFieldValue
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
import ru.souz.agent.GraphBasedAgent
import ru.souz.agent.engine.AgentContext
import ru.souz.db.SettingsProvider
import ru.souz.giga.GigaModel
import ru.souz.giga.TokenLogging
import ru.souz.telemetry.TelemetryConversationEndReason
import ru.souz.telemetry.TelemetryConversationStartReason
import ru.souz.telemetry.TelemetryRequestContext
import ru.souz.telemetry.TelemetryRequestSource
import ru.souz.telemetry.TelemetryRequestStatus
import ru.souz.telemetry.TelemetryService
import ru.souz.ui.main.ChatAttachedFile
import ru.souz.ui.main.ChatMessage
import ru.souz.ui.main.MainState
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.plus

class ChatUseCase(
    private val graphAgent: GraphBasedAgent,
    private val settingsProvider: SettingsProvider,
    private val speechUseCase: SpeechUseCase,
    private val finderPathExtractor: FinderPathExtractor,
    private val chatAttachmentsUseCase: ChatAttachmentsUseCase,
    private val tokenLogging: TokenLogging,
    private val telemetryService: TelemetryService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val l = LoggerFactory.getLogger(ChatUseCase::class.java)
    private val taskSideEffectJobs = ArrayList<Job>()
    private val activeChatRequestId = AtomicLong(0L)
    private var agentRef: AtomicReference<GraphBasedAgent?> = AtomicReference(graphAgent)
    private val currentConversationId = AtomicReference<String?>(null)
    private val activeTelemetryRequest = AtomicReference<TelemetryRequestContext?>(null)
    private val pendingConversationClosures =
        Collections.synchronizedMap(mutableMapOf<String, TelemetryConversationEndReason>())

    private val _outputs = MutableSharedFlow<MainUseCaseOutput>(replay = 1, extraBufferCapacity = 64)
    val outputs: Flow<MainUseCaseOutput> = _outputs.asSharedFlow()

    fun bindAgentRef(agentRef: AtomicReference<GraphBasedAgent?>) {
        this.agentRef = agentRef
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            activeAgent().currentContext.collect { ctx ->
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
        activeTelemetryRequest.set(requestContext)
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

        emitState {
            copy(
                chatMessages = chatMessages + userMessage,
                chatStartTip = "",
                chatInputText = TextFieldValue(""),
                isProcessing = true,
                statusMessage = "",
            )
        }

        val pendingBotMessage = ChatMessage(
            text = "",
            isUser = false,
            isVoice = isVoice,
        )

        var sideEffectsJob: Job? = null

        try {
            sideEffectsJob = subscribeOnTaskSideEffects(scope, pendingBotMessage)
            l.info("About to execute agent with user input {}", userText)

            val response = withContext(ioDispatcher) {
                activeAgent().execute(userText)
            }

            val extractedFinderPaths = extractFinderPaths(response)
            val botAttachments = chatAttachmentsUseCase.buildAttachmentsFromPaths(
                extractedFinderPaths.map { it.path }
            )
            val botMessage = pendingBotMessage.copy(
                text = response,
                finderPaths = extractedFinderPaths,
                attachedFiles = botAttachments,
            )
            if (activeChatRequestId.get() != requestId) {
                l.info("Skipping stale chat response for request {}", requestId)
                telemetryStatus = TelemetryRequestStatus.CANCELLED
                telemetryErrorMessage = "Stale request"
                onResult?.invoke(Result.failure(CancellationException("Stale request")))
                return
            }

            if (settingsProvider.notificationSoundEnabled) {
                speechUseCase.playMacPingMsgSafely(scope)
            }

            emitState {
                copy(
                    chatMessages = if (chatMessages.lastOrNull()?.id == botMessage.id) {
                        chatMessages.mapLast { botMessage }
                    } else {
                        chatMessages + botMessage
                    },
                    isProcessing = false,
                )
            }

            if (isVoice && !settingsProvider.useStreaming) {
                speechUseCase.queuePrepared(botMessage.text)
            }
            telemetryResponseLength = botMessage.text.length
            onResult?.invoke(Result.success(botMessage.text))
        } catch (e: CancellationException) {
            telemetryStatus = TelemetryRequestStatus.CANCELLED
            telemetryErrorMessage = e.message
            l.info("Chat message cancelled: {}", e.message)
            val isCurrentRequest = activeChatRequestId.get() == requestId
            withContext(NonCancellable) {
                emitState {
                    val idsToDrop = arrayOf(userMessage.id, pendingBotMessage.id)
                    copy(
                        chatMessages = chatMessages.filterNot { it.id in idsToDrop },
                        isProcessing = if (isCurrentRequest) false else isProcessing,
                    )
                }
            }
            onResult?.invoke(Result.failure(e))
        } catch (e: Exception) {
            telemetryStatus = TelemetryRequestStatus.ERROR
            telemetryErrorMessage = e.message
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
                )
            }
            onResult?.invoke(Result.failure(e))
        } finally {
            val requestTokenUsage = tokenLogging.currentRequestTokenUsage()
            telemetryService.finishRequest(
                context = requestContext,
                status = telemetryStatus,
                responseLengthChars = telemetryResponseLength,
                errorMessage = telemetryErrorMessage,
                requestTokenUsage = requestTokenUsage,
                sessionTokenUsage = tokenLogging.sessionTokenUsage(),
            )
            tokenLogging.finishRequest(requestContext.requestId)
            activeTelemetryRequest.compareAndSet(requestContext, null)
            pendingConversationClosures.remove(requestContext.conversationId)
                ?.let { reason -> telemetryService.finishConversation(requestContext.conversationId, reason) }
            sideEffectsJob?.cancel()
            sideEffectsJob?.let { taskSideEffectJobs.remove(it) }
        }
    }

    fun cancelActiveJob() {
        activeAgent().cancelActiveJob()
    }

    fun stopSpeechAndSideEffects() {
        killTaskSideEffectJobs()
    }

    fun clearContext() {
        activeAgent().clearContext()
    }

    fun finishCurrentConversation(reason: TelemetryConversationEndReason) {
        val conversationId = currentConversationId.getAndSet(null) ?: return
        if (activeTelemetryRequest.get()?.conversationId == conversationId) {
            pendingConversationClosures[conversationId] = reason
        } else {
            telemetryService.finishConversation(conversationId, reason)
        }
    }

    fun setContext(ctx: AgentContext<String>) {
        activeAgent().setContext(ctx)
    }

    fun snapshotContext(): AgentContext<String>? = activeAgent().currentContext.value

    fun updateModel(model: GigaModel) {
        activeAgent().updateModel(model)
    }

    fun updateContextSize(size: Int) {
        activeAgent().updateContextSize(size)
    }

    fun onCleared() {
        finishCurrentConversation(TelemetryConversationEndReason.VIEW_MODEL_CLEARED)
        killTaskSideEffectJobs()
        cancelActiveJob()
    }

    private fun ensureConversation(source: TelemetryRequestSource): String {
        currentConversationId.get()?.let { return it }

        val conversationId = telemetryService.startConversation(
            reason = when (source) {
                TelemetryRequestSource.CHAT_UI -> TelemetryConversationStartReason.CHAT_UI
                TelemetryRequestSource.VOICE_INPUT -> TelemetryConversationStartReason.VOICE_INPUT
                TelemetryRequestSource.TELEGRAM_BOT -> TelemetryConversationStartReason.TELEGRAM_BOT
            }
        )
        currentConversationId.compareAndSet(null, conversationId)
        return currentConversationId.get() ?: conversationId
    }

    private fun subscribeOnTaskSideEffects(scope: CoroutineScope, msg: ChatMessage): Job {
        val job = scope.launch {
            val isCodeBlockStarted = AtomicBoolean(false)
            var accumulatedText = ""
            activeAgent().sideEffects.collect { text ->
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
        }
        taskSideEffectJobs.add(job)
        return job
    }

    private fun activeAgent(): GraphBasedAgent = agentRef.get() ?: graphAgent

    private fun killTaskSideEffectJobs() {
        speechUseCase.clearQueue()
        taskSideEffectJobs.forEach { it.cancel() }
        taskSideEffectJobs.clear()
    }

    private suspend fun emitState(reduce: MainState.() -> MainState) {
        _outputs.emit(MainUseCaseOutput.State(reduce))
    }


    private suspend fun extractFinderPaths(text: String) =
        withContext(ioDispatcher) {
            finderPathExtractor.extract(text)
        }

    private inline fun <T> List<T>.mapLast(transform: (T) -> T): List<T> =
        mapIndexed { index, value -> if (index == lastIndex) transform(value) else value }

    private companion object {
        const val CODE_BLOCK = "```"
    }
}
