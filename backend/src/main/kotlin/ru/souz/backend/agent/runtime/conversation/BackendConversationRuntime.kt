package ru.souz.backend.agent.runtime.conversation

import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.souz.agent.ActiveRunMailbox
import ru.souz.agent.ActiveRunInput
import ru.souz.agent.AgentContextFactory
import ru.souz.agent.AgentExecutor
import ru.souz.agent.AgentId
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.model.chatId
import ru.souz.backend.agent.runtime.BackendConversationSettingsProvider
import ru.souz.backend.llm.BackendExecutionLlmChatApi
import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.backend.agent.session.AgentSessionRepository
import ru.souz.backend.chat.model.CROSS_CHANNEL_MESSAGE_METADATA_KEY
import ru.souz.backend.chat.model.CLIENT_HISTORY_MESSAGE_METADATA_KEY
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.client.repository.ClientRequestResult
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta

/** Request-scoped backend conversation runtime rebuilt from the stored snapshot. */
internal class BackendConversationRuntime(
    private val key: AgentConversationKey,
    private val sessionRepository: AgentSessionRepository,
    private val settingsProvider: BackendConversationSettingsProvider,
    private val contextFactory: AgentContextFactory,
    private val executor: AgentExecutor,
    private val executionApi: BackendExecutionLlmChatApi,
    private val persistedSession: AgentConversationSession?,
    private val messageRepository: MessageRepository,
    private val initialMessages: List<LLMRequest.Message>,
    initialObservedMessageSeq: Long,
) {
    private val cursorMutex = Mutex()
    private var observedMessageSeq = initialObservedMessageSeq
    private var pendingHistoryThroughSeq: Long? = null
    private var activeRunMailbox: ActiveRunMailbox? = null
    private var acceptsHistoryNotifications = true

    internal suspend fun execute(
        request: BackendConversationTurnRequest,
        persistSession: Boolean = true,
        eventSink: AgentRuntimeEventSink? = null,
        onRuntimeReady: suspend () -> Unit = {},
    ): BackendConversationExecution {
        val seedContext = contextFactory.create(
            agentId = AgentId.SKILLS_GRAPH,
            history = persistedSession?.history.orEmpty() + initialMessages,
            model = settingsProvider.gigaModel,
            contextSize = request.contextSize,
            temperature = settingsProvider.temperature,
            toolInvocationMeta = ToolInvocationMeta(
                userId = key.userId,
                conversationId = key.conversationId,
                requestId = request.executionId,
                locale = request.locale,
                timeZone = request.timeZone,
            ),
        )

        var executionMailbox: ActiveRunMailbox? = null
        val result = try {
            executor.execute(
                agentId = AgentId.SKILLS_GRAPH,
                context = seedContext,
                input = request.prompt,
                eventSink = eventSink,
                loadPendingHistory = ::loadPendingHistory,
                onActiveRunReady = { mailbox ->
                    cursorMutex.withLock {
                        executionMailbox = mailbox
                        activeRunMailbox = mailbox
                    }
                    onRuntimeReady()
                },
            )
        } finally {
            withContext(NonCancellable) {
                cursorMutex.withLock {
                    executionMailbox?.let { mailbox ->
                        if (activeRunMailbox === mailbox) activeRunMailbox = null
                    }
                    acceptsHistoryNotifications = false
                }
            }
        }
        val nextSession = AgentConversationSession(
            history = result.context.history,
            temperature = result.context.settings.temperature,
            locale = request.locale,
            timeZone = request.timeZone,
            basedOnMessageSeq = cursorMutex.withLock { observedMessageSeq },
            rowVersion = persistedSession?.rowVersion ?: 0L,
        )

        if (persistSession) {
            sessionRepository.save(key, nextSession)
        }

        return BackendConversationExecution(
            output = result.output,
            usage = executionApi.cumulativeUsage(),
            session = nextSession,
        )
    }

    internal suspend fun currentUsage(): LLMResponse.Usage = executionApi.cumulativeUsage()

    /**
     * Serializes the durable execute barrier with passive history loading and publishes the
     * committed row plus every relevant preceding message as one active-run input.
     */
    internal suspend fun commitActiveRunInput(
        commit: suspend (afterSeq: Long) -> ClientRequestResult,
    ): ClientRequestResult? {
        val mailbox = cursorMutex.withLock { activeRunMailbox } ?: return null
        var committed: ClientRequestResult? = null
        val published = mailbox.submit {
            cursorMutex.withLock {
                when (val result = commit(observedMessageSeq).also { committed = it }) {
                    is ClientRequestResult.Accepted -> {
                        val inputMessage = requireNotNull(result.message) {
                            "An accepted active-run input must include its durable message"
                        }
                        require(inputMessage.seq > observedMessageSeq) {
                            "Accepted active-run input must advance the durable message cursor"
                        }
                        require(result.messageDelta.all { message ->
                            message.seq > observedMessageSeq && message.seq <= inputMessage.seq
                        }) {
                            "Active-run message delta is outside its durable cursor range"
                        }
                        val input = ActiveRunInput(
                            history = result.messageDelta
                                .asSequence()
                                .filter { it.seq < inputMessage.seq }
                                .mapNotNull(ChatMessage::toAgentHistoryMessage)
                                .toList(),
                            input = inputMessage.content,
                        )
                        // A non-null producer result is guaranteed to publish: sealing waits for this reservation.
                        advanceCursor(inputMessage.seq)
                        input
                    }

                    else -> null
                }
            }
        }
        check(published || committed !is ClientRequestResult.Accepted) {
            "Committed active-run input was not published"
        }
        return committed
    }

    /** Advances the runtime-owned durable watermark without touching the active mailbox. */
    internal suspend fun notifyHistoryPending(throughSeq: Long): Boolean = cursorMutex.withLock {
        if (!acceptsHistoryNotifications) return false
        if (throughSeq > observedMessageSeq) {
            pendingHistoryThroughSeq = maxOf(pendingHistoryThroughSeq ?: 0L, throughSeq)
        }
        true
    }

    private suspend fun loadPendingHistory(): List<LLMRequest.Message> = cursorMutex.withLock {
        val throughSeq = pendingHistoryThroughSeq ?: return emptyList()
        if (throughSeq <= observedMessageSeq) {
            pendingHistoryThroughSeq = null
            return emptyList()
        }
        val messages = messageRepository.listThrough(
            userId = key.userId,
            chatId = key.chatId(),
            afterSeq = observedMessageSeq,
            throughSeq = throughSeq,
        )
        val history = messages.mapNotNull(ChatMessage::toAgentHistoryMessage)
        observedMessageSeq = throughSeq
        pendingHistoryThroughSeq = pendingHistoryThroughSeq?.takeIf { it > throughSeq }
        history
    }

    private fun advanceCursor(throughSeq: Long) {
        observedMessageSeq = throughSeq
        pendingHistoryThroughSeq = pendingHistoryThroughSeq?.takeIf { it > observedMessageSeq }
    }
}

internal data class InitialConversationMessages(
    val messages: List<LLMRequest.Message>,
    val observedThroughSeq: Long,
)

/** Loads the bounded durable prefix represented by one reconstructed runtime. */
internal suspend fun loadInitialConversationMessages(
    messageRepository: MessageRepository,
    key: AgentConversationKey,
    basedOnMessageSeq: Long,
    inputMessageSeq: Long?,
): InitialConversationMessages {
    if (inputMessageSeq != null) {
        val messagesBeforeInput = messageRepository.listThrough(
            userId = key.userId,
            chatId = key.chatId(),
            afterSeq = basedOnMessageSeq,
            throughSeq = inputMessageSeq - 1L,
        )
        return InitialConversationMessages(
            messages = messagesBeforeInput.mapNotNull(ChatMessage::toAgentHistoryMessage),
            observedThroughSeq = maxOf(basedOnMessageSeq, inputMessageSeq),
        )
    }

    val latestSeq = messageRepository.latest(key.userId, key.chatId())?.seq ?: basedOnMessageSeq
    val messages = messageRepository.listThrough(
        userId = key.userId,
        chatId = key.chatId(),
        afterSeq = basedOnMessageSeq,
        throughSeq = latestSeq,
    )
    val context = mutableListOf<LLMRequest.Message>()
    var observedThroughSeq = basedOnMessageSeq
    for (message in messages) {
        if (message.isClientHistory) break
        message.toAgentHistoryMessage()?.let(context::add)
        observedThroughSeq = message.seq
    }
    return InitialConversationMessages(context, observedThroughSeq)
}

private suspend fun MessageRepository.listThrough(
    userId: String,
    chatId: UUID,
    afterSeq: Long,
    throughSeq: Long,
): List<ChatMessage> {
    if (throughSeq <= afterSeq) return emptyList()
    val messages = mutableListOf<ChatMessage>()
    var pageAfterSeq = afterSeq
    while (pageAfterSeq < throughSeq) {
        val page = list(
            userId = userId,
            chatId = chatId,
            afterSeq = pageAfterSeq.takeIf { it > 0L },
            limit = MessageRepository.MAX_LIMIT,
        )
        if (page.isEmpty()) break
        messages += page.takeWhile { it.seq <= throughSeq }
        if (page.last().seq >= throughSeq || page.size < MessageRepository.MAX_LIMIT) break
        check(page.last().seq > pageAfterSeq) { "Message pagination did not advance" }
        pageAfterSeq = page.last().seq
    }
    return messages
}

private val ChatMessage.isClientHistory: Boolean
    get() = metadata[CLIENT_HISTORY_MESSAGE_METADATA_KEY] == "true"

private fun ChatMessage.toAgentHistoryMessage(): LLMRequest.Message? = when {
    isClientHistory -> LLMRequest.Message(
        role = when (role) {
            ChatRole.USER -> LLMMessageRole.user
            ChatRole.ASSISTANT -> LLMMessageRole.assistant
            else -> error("Client history has unsupported role ${role.value}")
        },
        content = content,
    )

    metadata[CROSS_CHANNEL_MESSAGE_METADATA_KEY] == "true" -> LLMRequest.Message(
        role = LLMMessageRole.assistant,
        content = content,
    )

    else -> null
}
