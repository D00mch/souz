package ru.souz.agent.nodes

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import ru.souz.agent.ActiveRunInput
import ru.souz.agent.ActiveRunMailbox
import ru.souz.agent.ActiveRunMailbox.NextLlmStep
import ru.souz.agent.graph.GraphRuntime
import ru.souz.agent.graph.Node
import ru.souz.agent.state.AgentContext
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.toMessage

/** Main steerable chat node that owns each cancellable LLM attempt and replans around queued input. */
internal class SteerableChat(
    private val nodesLLM: NodesLLM,
    private val mailbox: ActiveRunMailbox,
) : Node<String, LLMResponse.Chat> {
    override val name: String = "LLM"
    private var trailingToolExchangeStart: Int? = null

    override suspend fun execute(
        ctx: AgentContext<String>,
        runtime: GraphRuntime,
    ): AgentContext<LLMResponse.Chat> {
        var current = ctx
        var carriedInputs = emptyList<ActiveRunInput>()

        while (true) {
            val prepared = prepareLlmRequest(current, carriedInputs)
            carriedInputs = emptyList()
            when (val attempt = runLlmAttempt(prepared.context, runtime, prepared.request)) {
                is LlmAttempt.Replan -> {
                    current = prepared.context
                    carriedInputs = attempt.queuedInputs
                }

                is LlmAttempt.Completed -> {
                    val responseContext = attempt.context
                    val response = responseContext.input
                    if (response is LLMResponse.Chat.Ok && response.isToolUse) {
                        // An empty drain accepts this tool batch. Later input waits for its results.
                        mailbox.drain()?.let { queued ->
                            current = prepared.context
                            carriedInputs = queued
                            continue
                        }

                        val passiveHistory = mailbox.loadHistoryAtBoundary()
                        mailbox.drain()?.let { queued ->
                            current = prepared.context.applyBoundary(
                                beforeInputs = emptyList(),
                                passiveHistory = passiveHistory,
                                afterInputs = queued,
                                toolExchangeStart = null,
                            )
                            continue
                        }

                        val historyBeforeToolExchange = responseContext.history + passiveHistory
                        trailingToolExchangeStart = historyBeforeToolExchange.size
                        return responseContext.copy(
                            history = historyBeforeToolExchange + response.choices.mapNotNull { it.toMessage() },
                        )
                    }

                    mailbox.drainOrSeal()?.let { queued ->
                        current = prepared.context
                        carriedInputs = queued
                        continue
                    }

                    return if (response is LLMResponse.Chat.Ok) {
                        responseContext.copy(
                            history = responseContext.history + response.choices.mapNotNull { it.toMessage() },
                        )
                    } else {
                        responseContext
                    }
                }
            }
        }
    }

    /** Prepares exactly one safe boundary before starting the next provider request. */
    private suspend fun prepareLlmRequest(
        context: AgentContext<String>,
        carriedInputs: List<ActiveRunInput>,
    ): PreparedLlmRequest {
        var current = context
        var activeInputs = carriedInputs
        var toolExchangeStart = trailingToolExchangeStart

        while (true) {
            val beforeInputs = activeInputs + mailbox.drain().orEmpty()
            val passiveHistory = mailbox.loadHistoryAtBoundary()
            val afterInputs = mailbox.drain().orEmpty()
            current = current.applyBoundary(
                beforeInputs = beforeInputs,
                passiveHistory = passiveHistory,
                afterInputs = afterInputs,
                toolExchangeStart = toolExchangeStart,
            )
            activeInputs = emptyList()
            toolExchangeStart = null
            trailingToolExchangeStart = null

            when (val next = mailbox.nextLlmStep()) {
                is NextLlmStep.QueuedInput -> activeInputs = next.inputs
                is NextLlmStep.Request -> return PreparedLlmRequest(current, next)
            }
        }
    }

    private suspend fun runLlmAttempt(
        context: AgentContext<String>,
        runtime: GraphRuntime,
        request: NextLlmStep.Request,
    ): LlmAttempt = supervisorScope {
        val llm = async(start = CoroutineStart.LAZY) {
            nodesLLM.provisionalChat("LLM request", request.streamRevision).execute(context, runtime)
        }

        if (request.inputAvailable.isCompleted) {
            llm.cancelAndJoin()
            return@supervisorScope LlmAttempt.Replan(mailbox.requireQueuedInputs())
        }

        llm.start()
        select<LlmAttempt> {
            // Completion wins when both clauses are ready, so provider cancellation always propagates.
            llm.onAwait { LlmAttempt.Completed(it) }
            request.inputAvailable.onAwait {
                if (!llm.isActive) {
                    LlmAttempt.Completed(llm.await())
                } else {
                    llm.cancelAndJoin()
                    LlmAttempt.Replan(mailbox.requireQueuedInputs())
                }
            }
        }
    }

    private suspend fun ActiveRunMailbox.requireQueuedInputs(): List<ActiveRunInput> =
        checkNotNull(drain()) { "An input notification must have queued user input" }

    private val LLMResponse.Chat.Ok.isToolUse: Boolean
        get() = choices.any { it.message.functionCall != null }

    private fun AgentContext<String>.applyBoundary(
        beforeInputs: List<ActiveRunInput>,
        passiveHistory: List<LLMRequest.Message>,
        afterInputs: List<ActiveRunInput>,
        toolExchangeStart: Int?,
    ): AgentContext<String> {
        if (beforeInputs.isEmpty() && passiveHistory.isEmpty() && afterInputs.isEmpty()) return this

        val activeInputs = beforeInputs + afterInputs
        val nextHistory = if (toolExchangeStart == null) {
            buildList {
                addAll(history)
                addActiveInputs(beforeInputs)
                addAll(passiveHistory)
                addActiveInputs(afterInputs)
            }
        } else {
            require(toolExchangeStart in 0..history.size) { "Invalid tool exchange boundary" }
            buildList {
                addAll(history.subList(0, toolExchangeStart))
                addAll(beforeInputs.flatMap { it.history })
                addAll(passiveHistory)
                addAll(afterInputs.flatMap { it.history })
                addAll(history.subList(toolExchangeStart, history.size))
                addAll(activeInputs.map { LLMRequest.Message(LLMMessageRole.user, it.input) })
            }
        }
        val nextInput = activeInputs.lastOrNull()?.input ?: input
        return map(history = nextHistory) { nextInput }
    }

    private fun MutableList<LLMRequest.Message>.addActiveInputs(inputs: List<ActiveRunInput>) {
        inputs.forEach { input ->
            addAll(input.history)
            add(LLMRequest.Message(LLMMessageRole.user, input.input))
        }
    }

    private data class PreparedLlmRequest(
        val context: AgentContext<String>,
        val request: NextLlmStep.Request,
    )

    private sealed interface LlmAttempt {
        data class Completed(val context: AgentContext<LLMResponse.Chat>) : LlmAttempt
        data class Replan(val queuedInputs: List<ActiveRunInput>) : LlmAttempt
    }
}
