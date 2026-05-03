package ru.souz.agent.runtime

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import ru.souz.llms.ToolInvocationMeta

class ToolInvocationMetaContext(
    val value: ToolInvocationMeta,
) {
    fun asCoroutineContext(): CoroutineContext = Element(value)

    class Element(
        val value: ToolInvocationMeta,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Element>
    }
}
