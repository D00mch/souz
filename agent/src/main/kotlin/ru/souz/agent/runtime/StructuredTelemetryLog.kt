package ru.souz.agent.runtime

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class AgentExecutionLogContext(
    val appSessionId: String,
    val requestId: String,
    val conversationId: String,
    val requestSource: String,
    val model: String,
    val provider: String,
) {
    var toolExecutionCount: Int = 0
        private set

    fun incrementToolExecutionCount() {
        toolExecutionCount += 1
    }

    fun asCoroutineContext(): CoroutineContext = Element(this)

    class Element(
        val value: AgentExecutionLogContext,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Element>
    }
}
