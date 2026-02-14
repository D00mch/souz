package ru.gigadesk.ui.main.interactors

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
import ru.gigadesk.agent.GraphBasedAgent
import ru.gigadesk.agent.engine.AgentContext
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.giga.GigaModel
import ru.gigadesk.ui.main.ChatMessage
import ru.gigadesk.ui.main.MainState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.plus

class ChatInteractor(
    private val graphAgent: GraphBasedAgent,
    private val settingsProvider: SettingsProvider,
    private val speechInteractor: SpeechInteractor,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val l = LoggerFactory.getLogger(ChatInteractor::class.java)
    private val taskSideEffectJobs = ArrayList<Job>()
    private val activeChatRequestId = AtomicLong(0L)
    private var agentRef: AtomicReference<GraphBasedAgent?> = AtomicReference(graphAgent)

    private val _outputs = MutableSharedFlow<MainInteractorOutput>(replay = 1, extraBufferCapacity = 64)
    val outputs: Flow<MainInteractorOutput> = _outputs.asSharedFlow()

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

    suspend fun sendChatMessage(scope: CoroutineScope, isVoice: Boolean, chatMessage: String) {
        killTaskSideEffectJobs()
        cancelActiveJob()

        val userText = chatMessage.trim()
        if (userText.isEmpty()) return

        val requestId = activeChatRequestId.incrementAndGet()
        val userMessage = ChatMessage(
            text = userText,
            isUser = true,
            isVoice = isVoice,
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

            val botMessage = pendingBotMessage.copy(text = response)
            if (activeChatRequestId.get() != requestId) {
                l.info("Skipping stale chat response for request {}", requestId)
                return
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
                speechInteractor.queuePrepared(botMessage.text)
            }
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
        } catch (e: Exception) {
            if (activeChatRequestId.get() != requestId) {
                l.info("Ignoring stale chat failure for request {}: {}", requestId, e.message)
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
        } finally {
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
            activeAgent().sideEffects.collect { text ->
                emitState {
                    val updatedMessages = if (msg.id == chatMessages.lastOrNull()?.id) {
                        chatMessages.mapLast { last -> last.copy(text = last.text + text) }
                    } else {
                        chatMessages + msg.copy(text = text)
                    }
                    copy(chatMessages = updatedMessages)
                }

                if (!msg.isVoice) return@collect

                if (text.contains(CODE_BLOCK)) {
                    isCodeBlockStarted.set(!isCodeBlockStarted.get())
                    if (isCodeBlockStarted.get()) {
                        speechInteractor.queuePrepared(text.substringBefore(CODE_BLOCK))
                    }
                }

                if (!isCodeBlockStarted.get()) {
                    speechInteractor.queuePrepared(text.substringAfter(CODE_BLOCK))
                }
            }
        }
        taskSideEffectJobs.add(job)
        return job
    }

    private fun activeAgent(): GraphBasedAgent = agentRef.get() ?: graphAgent

    private fun killTaskSideEffectJobs() {
        speechInteractor.clearQueue()
        taskSideEffectJobs.forEach { it.cancel() }
        taskSideEffectJobs.clear()
    }

    private suspend fun emitState(reduce: MainState.() -> MainState) {
        _outputs.emit(MainInteractorOutput.State(reduce))
    }

    private inline fun <T> List<T>.mapLast(transform: (T) -> T): List<T> =
        mapIndexed { index, value -> if (index == lastIndex) transform(value) else value }

    private companion object {
        const val CODE_BLOCK = "```"
    }
}
