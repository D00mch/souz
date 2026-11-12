package ru.souz.tool.skills

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.souz.llms.LLMRequest
import ru.souz.llms.restJsonMapper

internal const val DISCOVERY_PAGE_BYTES = 16_384

/** Shared strict inputs and byte-bounded pages for category and remote-server discovery. */
internal class SkillDiscoveryPage(arguments: Map<String, Any?>) {
    private val offset = integer(arguments, "offset", 0, 0..Int.MAX_VALUE)
    private val limit = integer(arguments, "limit", 20, 1..50)
    var nextOffset: Int? = null
        private set

    suspend fun <T, R> select(
        source: List<T>,
        oversized: (T) -> R,
        render: (T) -> R,
    ): List<R> {
        val result = mutableListOf<R>()
        var position = offset.coerceAtMost(source.size)
        var bytes = 512 // Reserve the response envelope and commas.
        while (position < source.size && result.size < limit) {
            currentCoroutineContext().ensureActive()
            val item = source[position]
            val rendered = render(item)
            val size = restJsonMapper.writeValueAsBytes(rendered).size + 1
            if (bytes + size > DISCOVERY_PAGE_BYTES) {
                if (result.isEmpty()) {
                    result += oversized(item)
                    position++
                }
                break
            }
            result += rendered
            bytes += size
            position++
        }
        nextOffset = position.takeIf { it < source.size }
        return result
    }

    companion object {
        val parameters = mapOf(
            "offset" to LLMRequest.Property("integer", "Start position; default 0."),
            "limit" to LLMRequest.Property("integer", "Maximum entries; default 20, maximum 50."),
        )

        private fun integer(args: Map<String, Any?>, name: String, default: Int, range: IntRange): Int {
            if (name !in args) return default
            val number = (args[name] as? Number)?.toString()?.toBigDecimalOrNull()
            val value = try {
                number?.intValueExact()
            } catch (_: ArithmeticException) {
                null
            }
            require(value != null && value in range) { "Invalid discovery $name: expected an integer in $range." }
            return value
        }
    }
}
