package ru.souz.backend.agent.runtime.conversation

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val initialMessages: List<LLMRequest.Message>,
    initialObservedMessageSeq: Long,
) {
    private val cursorMutex = Mutex()
    private var observedMessageSeq = initialObservedMessageSeq

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

        val result = executor.execute(
            agentId = AgentId.SKILLS_GRAPH,
            context = seedContext,
            input = request.prompt,
            eventSink = eventSink,
            onActiveRunReady = onRuntimeReady,
        )
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
     * Serializes the durable execute barrier and publishes the committed row plus every relevant
     * preceding message as one active-run input.
     */
    internal suspend fun commitActiveRunInput(
        commit: suspend (afterSeq: Long) -> ClientRequestResult,
    ): ClientRequestResult? {
        var committed: ClientRequestResult? = null
        val published = executor.submitToActiveRun(AgentId.SKILLS_GRAPH) {
            cursorMutex.withLock {
                when (val result = commit(observedMessageSeq).also { committed = it }) {
                    is ClientRequestResult.Accepted -> {
                        val inputMessage = requireNotNull(result.messageDelta.lastOrNull()) {
                            "An accepted active-run input must include its durable message delta"
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
                        observedMessageSeq = inputMessage.seq
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
