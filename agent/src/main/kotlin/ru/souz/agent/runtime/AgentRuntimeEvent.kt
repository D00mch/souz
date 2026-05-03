package ru.souz.agent.runtime

/**
 * Execution-scoped runtime event sink.
 *
 * Implementations are expected to receive events sequentially for a single
 * execution. Concurrent calls are not part of the contract unless an
 * implementation explicitly documents support for them.
 */
interface AgentRuntimeEventSink {
    suspend fun emit(event: AgentRuntimeEvent)

    companion object {
        val NONE: AgentRuntimeEventSink = object : AgentRuntimeEventSink {
            override suspend fun emit(event: AgentRuntimeEvent) = Unit
        }
    }
}

sealed interface AgentRuntimeEvent {
    data class LlmMessageDelta(
        val text: String,
    ) : AgentRuntimeEvent

    data class ToolCallStarted(
        val toolCallId: String,
        val name: String,
        val arguments: Map<String, Any?>,
    ) : AgentRuntimeEvent

    data class ToolCallFinished(
        val toolCallId: String,
        val name: String,
        val result: Any?,
        val durationMs: Long,
    ) : AgentRuntimeEvent

    data class ToolCallFailed(
        val toolCallId: String,
        val name: String,
        val error: Throwable,
        val durationMs: Long,
    ) : AgentRuntimeEvent

    data class ChoiceRequested(
        val choiceId: String,
        val kind: String,
        val title: String?,
        val options: List<ChoiceOption>,
        val selectionMode: String,
    ) : AgentRuntimeEvent {
        data class ChoiceOption(
            val id: String,
            val label: String,
            val content: String? = null,
        )
    }
}
