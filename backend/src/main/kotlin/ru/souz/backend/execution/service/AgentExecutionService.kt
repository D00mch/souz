package ru.souz.backend.execution.service

import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.supervisorScope
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.chat.service.SendMessageResult
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.ChoiceAnsweredPayload
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.repository.ActiveAgentExecutionConflictException
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.http.invalidV1Request
import ru.souz.backend.options.model.Option
import ru.souz.backend.options.repository.OptionRepository
import ru.souz.backend.settings.service.UserSettingsOverrides
import ru.souz.backend.toolcall.repository.ToolCallRepository

data class CancelExecutionResult(
    val execution: AgentExecution,
)

class AgentExecutionService internal constructor(
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val executionRepository: AgentExecutionRepository,
    private val optionRepository: OptionRepository,
    private val eventService: AgentEventService,
    private val toolCallRepository: ToolCallRepository,
    private val requestFactory: AgentExecutionRequestFactory,
    private val finalizer: AgentExecutionFinalizer,
    private val launcher: AgentExecutionLauncher,
) {
    suspend fun executeChatTurn(
        userId: String,
        chatId: UUID,
        content: String,
        clientMessageId: String? = null,
        requestOverrides: UserSettingsOverrides = UserSettingsOverrides(),
    ): SendMessageResult = supervisorScope {
        val chat = requireOwnedChat(userId, chatId)
        val prepared = requestFactory.prepareChatTurn(
            userId = userId,
            chatId = chatId,
            content = content,
            clientMessageId = clientMessageId,
            requestOverrides = requestOverrides,
        )
        val queuedExecution = try {
            executionRepository.create(prepared.execution)
        } catch (e: ActiveAgentExecutionConflictException) {
            throw BackendV1Exception(
                status = HttpStatusCode.Conflict,
                code = "chat_already_has_active_execution",
                message = "Chat already has an active execution.",
            )
        }

        try {
            val userMessage = messageRepository.append(
                userId = userId,
                chatId = chatId,
                role = ChatRole.USER,
                content = content,
                metadata = prepared.userMessageMetadata,
            )
            chatRepository.update(chat.copy(updatedAt = userMessage.createdAt))

            val runningExecution = executionRepository.update(
                queuedExecution.copy(
                    userMessageId = userMessage.id,
                    status = AgentExecutionStatus.RUNNING,
                    startedAt = Instant.now(),
                )
            )
            val eventSink = requestFactory.createEventSink(
                userId = userId,
                chatId = chatId,
                execution = runningExecution,
                messageRepository = messageRepository,
                optionRepository = optionRepository,
                executionRepository = executionRepository,
                eventService = eventService,
                toolCallRepository = toolCallRepository,
                streamingMessagesEnabled = prepared.effectiveSettings.streamingMessages,
                toolEventsEnabled = prepared.effectiveSettings.showToolEvents,
            )
            eventSink.emitExecutionStarted(runningExecution)

            if (prepared.shouldReturnRunning) {
                launcher.startBackgroundExecution(
                    execution = runningExecution,
                    eventSink = eventSink,
                ) {
                    finalizer.runExecution(
                        chat = chat,
                        execution = runningExecution,
                        conversationKey = prepared.conversationKey,
                        turnRequest = ru.souz.backend.agent.model.BackendConversationTurnRequest(
                            prompt = when (val input = prepared.turn.input) {
                                is ru.souz.backend.agent.model.BackendAgentTurnInput.UserMessage -> input.content
                                is ru.souz.backend.agent.model.BackendAgentTurnInput.OptionAnswer -> "__option_answer__ ${input.payload}"
                            },
                            model = prepared.turn.config.modelAlias,
                            contextSize = prepared.turn.config.contextSize,
                            locale = prepared.turn.config.locale,
                            timeZone = prepared.turn.config.timeZone,
                            executionId = prepared.turn.executionId.toString(),
                            temperature = prepared.turn.config.temperature,
                            systemPrompt = prepared.turn.config.systemPrompt,
                            streamingMessages = prepared.turn.config.streamingMessages,
                        ),
                        eventSink = eventSink,
                    )
                }
                return@supervisorScope SendMessageResult(
                    userMessage = userMessage,
                    assistantMessage = null,
                    execution = runningExecution,
                )
            }

            val executionResult = try {
                launcher.runTrackedExecution(
                    execution = runningExecution,
                    eventSink = eventSink,
                ) {
                    finalizer.runExecution(
                        chat = chat,
                        execution = runningExecution,
                        conversationKey = prepared.conversationKey,
                        turnRequest = ru.souz.backend.agent.model.BackendConversationTurnRequest(
                            prompt = when (val input = prepared.turn.input) {
                                is ru.souz.backend.agent.model.BackendAgentTurnInput.UserMessage -> input.content
                                is ru.souz.backend.agent.model.BackendAgentTurnInput.OptionAnswer -> "__option_answer__ ${input.payload}"
                            },
                            model = prepared.turn.config.modelAlias,
                            contextSize = prepared.turn.config.contextSize,
                            locale = prepared.turn.config.locale,
                            timeZone = prepared.turn.config.timeZone,
                            executionId = prepared.turn.executionId.toString(),
                            temperature = prepared.turn.config.temperature,
                            systemPrompt = prepared.turn.config.systemPrompt,
                            streamingMessages = prepared.turn.config.streamingMessages,
                        ),
                        eventSink = eventSink,
                    )
                }
            } catch (_: ExecutionCancelledException) {
                throw BackendV1Exception(
                    status = HttpStatusCode.Conflict,
                    code = "agent_execution_cancelled",
                    message = "Agent execution was cancelled.",
                )
            }

            SendMessageResult(
                userMessage = userMessage,
                assistantMessage = executionResult.assistantMessage,
                execution = executionResult.execution,
            )
        } catch (e: BackendV1Exception) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finalizer.markFailed(
                executionId = queuedExecution.id,
                userId = userId,
                chatId = chatId,
                errorCode = "agent_execution_failed",
                errorMessage = e.message ?: "Agent execution failed.",
                usage = queuedExecution.usage,
            )
            throw BackendV1Exception(
                status = HttpStatusCode.InternalServerError,
                code = "agent_execution_failed",
                message = "Agent execution failed.",
            )
        }
    }

    suspend fun resumeOption(option: Option): AgentExecution {
        val currentExecution = finalizer.currentExecution(option.executionId, option.userId, option.chatId)
        if (currentExecution.status != AgentExecutionStatus.WAITING_OPTION) {
            throw invalidV1Request("Execution is not waiting for an option.")
        }
        val chat = requireOwnedChat(option.userId, option.chatId)
        val runningExecution = executionRepository.update(
            currentExecution.copy(
                status = AgentExecutionStatus.RUNNING,
                finishedAt = null,
                cancelRequested = false,
                errorCode = null,
                errorMessage = null,
            )
        )
        eventService.appendDurable(
            userId = option.userId,
            chatId = option.chatId,
            executionId = runningExecution.id,
            type = AgentEventType.OPTION_ANSWERED,
            payload = ChoiceAnsweredPayload(
                optionId = option.id,
                status = option.status.value,
                selectedOptionIds = option.answer?.selectedOptionIds?.toList().orEmpty(),
                freeText = option.answer?.freeText,
                metadata = option.answer?.metadata.orEmpty(),
            ),
        )

        val prepared = requestFactory.prepareContinuationTurn(runningExecution, option)
        val eventSink = requestFactory.createEventSink(
            userId = option.userId,
            chatId = option.chatId,
            execution = runningExecution,
            messageRepository = messageRepository,
            optionRepository = optionRepository,
            executionRepository = executionRepository,
            eventService = eventService,
            toolCallRepository = toolCallRepository,
            streamingMessagesEnabled = prepared.streamingMessagesEnabled,
            toolEventsEnabled = prepared.toolEventsEnabled,
        )
        launcher.startBackgroundExecution(
            execution = runningExecution,
            eventSink = eventSink,
        ) {
            finalizer.runExecution(
                chat = chat,
                execution = runningExecution,
                conversationKey = prepared.conversationKey,
                turnRequest = ru.souz.backend.agent.model.BackendConversationTurnRequest(
                    prompt = when (val input = prepared.turn.input) {
                        is ru.souz.backend.agent.model.BackendAgentTurnInput.UserMessage -> input.content
                        is ru.souz.backend.agent.model.BackendAgentTurnInput.OptionAnswer -> "__option_answer__ ${input.payload}"
                    },
                    model = prepared.turn.config.modelAlias,
                    contextSize = prepared.turn.config.contextSize,
                    locale = prepared.turn.config.locale,
                    timeZone = prepared.turn.config.timeZone,
                    executionId = prepared.turn.executionId.toString(),
                    temperature = prepared.turn.config.temperature,
                    systemPrompt = prepared.turn.config.systemPrompt,
                    streamingMessages = prepared.turn.config.streamingMessages,
                ),
                eventSink = eventSink,
            )
        }
        return runningExecution
    }

    suspend fun cancelActive(
        userId: String,
        chatId: UUID,
    ): CancelExecutionResult {
        requireOwnedChat(userId, chatId)
        val activeExecution = executionRepository.findActive(userId, chatId)
            ?: throw invalidV1Request("Chat has no active execution.")
        return CancelExecutionResult(cancelExecutionInternal(activeExecution))
    }

    suspend fun cancelExecution(
        userId: String,
        chatId: UUID,
        executionId: UUID,
    ): CancelExecutionResult {
        requireOwnedChat(userId, chatId)
        val execution = executionRepository.getByChat(userId, chatId, executionId)
            ?: throw BackendV1Exception(
                status = HttpStatusCode.NotFound,
                code = "execution_not_found",
                message = "Execution not found.",
            )
        return CancelExecutionResult(cancelExecutionInternal(execution))
    }

    private suspend fun cancelExecutionInternal(execution: AgentExecution): AgentExecution {
        if (!execution.status.isActiveForCancellation()) {
            throw invalidV1Request("Execution is not active.")
        }
        val cancellingExecution = executionRepository.update(
            execution.copy(
                status = AgentExecutionStatus.CANCELLING,
                cancelRequested = true,
            )
        )
        if (launcher.cancel(cancellingExecution.id)) {
            return cancellingExecution
        }
        return finalizer.markCancelled(
            executionId = cancellingExecution.id,
            userId = cancellingExecution.userId,
            chatId = cancellingExecution.chatId,
            usage = cancellingExecution.usage,
        )
    }

    private suspend fun requireOwnedChat(userId: String, chatId: UUID): Chat =
        chatRepository.get(userId, chatId)
            ?: throw BackendV1Exception(
                status = HttpStatusCode.NotFound,
                code = "chat_not_found",
                message = "Chat not found.",
            )
}

private fun AgentExecutionStatus.isActiveForCancellation(): Boolean =
    this == AgentExecutionStatus.QUEUED ||
        this == AgentExecutionStatus.RUNNING ||
        this == AgentExecutionStatus.WAITING_OPTION ||
        this == AgentExecutionStatus.CANCELLING
