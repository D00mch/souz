package com.dumch.agent.engine

import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

fun <I, O> subgraph(
    name: String? = null,
    maxSteps: Int = 100,
    configure: SubgraphBuilder<I, O>.() -> Unit
): ReadOnlyProperty<Any?, Node<I, O>> = SubgraphDelegate(name, maxSteps, configure)

class SubgraphBuilder<I, O> internal constructor(
    private val delegate: GraphBuilder<I, O>
) {
    val input: Node<I, I> = delegate.input
    val nodeFinish: Node<O, O> get() = delegate.nodeFinish

    internal fun build(): Node<I, O> = delegate.build()
}

private class SubgraphDelegate<I, O>(
    private val nameHint: String?,
    private val maxSteps: Int,
    private val configure: SubgraphBuilder<I, O>.() -> Unit
) : ReadOnlyProperty<Any?, Node<I, O>> {
    private var cached: Node<I, O>? = null

    override fun getValue(thisRef: Any?, property: KProperty<*>): Node<I, O> {
        return cached ?: build(property.name).also { cached = it }
    }

    private fun build(propertyName: String): Node<I, O> {
        val name = nameHint ?: propertyName
        val builder = GraphBuilder<I, O>(name, maxSteps, RetryPolicy())
        val wrapper = SubgraphBuilder(builder)
        wrapper.configure()
        return wrapper.build()
    }
}
