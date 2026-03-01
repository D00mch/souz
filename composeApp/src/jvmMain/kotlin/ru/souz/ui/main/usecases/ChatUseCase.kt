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
import ru.souz.agent.GraphBasedAgent
import ru.souz.agent.engine.AgentContext
import ru.souz.db.SettingsProvider
import ru.souz.giga.GigaModel
import ru.souz.ui.main.ChatAttachedFile
import ru.souz.ui.main.ChatMessage
import ru.souz.ui.main.MainState
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val l = LoggerFactory.getLogger(ChatUseCase::class.java)
    private val taskSideEffectJobs = ArrayList<Job>()
    private val activeChatRequestId = AtomicLong(0L)
    private val activeRequestMessages = AtomicReference<ActiveRequestMessages?>(null)
    private var agentRef: AtomicReference<GraphBasedAgent?> = AtomicReference(graphAgent)

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
                isProcessing = true,
                statusMessage = "",
            )
        }

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
            onResult?.invoke(Result.success(botMessage.text))
        } catch (e: CancellationException) {
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
            sideEffectsJob?.cancel()
            sideEffectsJob?.let { taskSideEffectJobs.remove(it) }
            val currentActiveRequest = activeRequestMessages.get()
            if (currentActiveRequest?.requestId == requestId) {
                activeRequestMessages.compareAndSet(currentActiveRequest, null)
            }
        }
    }

    fun cancelActiveJob() {
        activeAgent().cancelActiveJob()
    }

    suspend fun stopCurrentExecution() {
        val nextRequestId = activeChatRequestId.incrementAndGet()
        val inFlightMessages = activeRequestMessages.getAndSet(null)

        killTaskSideEffectJobs()
        cancelActiveJob()

        emitState {
            val idsToDrop = inFlightMessages?.let { arrayOf(it.userMessageId, it.pendingMessageId) } ?: emptyArray()
            copy(
                chatMessages = if (idsToDrop.isEmpty()) chatMessages else chatMessages.filterNot { it.id in idsToDrop },
                isProcessing = false,
            )
        }
        l.info("Stop requested: invalidated request {}", nextRequestId)
    }

    fun stopSpeechAndSideEffects() {
        killTaskSideEffectJobs()
    }

    fun clearContext() {
        activeAgent().clearContext()
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
        killTaskSideEffectJobs()
        cancelActiveJob()
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

    private data class ActiveRequestMessages(
        val requestId: Long,
        val userMessageId: String,
        val pendingMessageId: String,
    )
}
