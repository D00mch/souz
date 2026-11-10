package ru.souz.tool.skills

import ru.souz.llms.LLMRequest

internal const val DEFAULT_DISCOVERY_LIMIT = 20
internal const val MAX_DISCOVERY_LIMIT = 50
internal const val DISCOVERY_PAGE_BYTES = 16_384
internal const val DISCOVERY_ENVELOPE_BYTES = 512

internal data class DiscoveryPage(val offset: Int, val limit: Int)

internal fun discoveryPage(arguments: Map<String, Any?>): DiscoveryPage = DiscoveryPage(
    offset = discoveryInteger(arguments, "offset", 0, 0..Int.MAX_VALUE),
    limit = discoveryInteger(arguments, "limit", DEFAULT_DISCOVERY_LIMIT, 1..MAX_DISCOVERY_LIMIT),
)

private fun discoveryInteger(arguments: Map<String, Any?>, name: String, default: Int, range: IntRange): Int {
    if (name !in arguments) return default
    val number = arguments[name] as? Number
    val value = number?.toString()?.toBigDecimalOrNull()?.let {
        try {
            it.intValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }
    require(value != null && value in range) { "Invalid discovery $name: expected an integer in $range." }
    return value
}

internal fun discoveryPageParameters(): Map<String, LLMRequest.Property> = mapOf(
    "offset" to LLMRequest.Property("integer", "Start position; default 0."),
    "limit" to LLMRequest.Property(
        "integer",
        "Maximum entries; default $DEFAULT_DISCOVERY_LIMIT, maximum $MAX_DISCOVERY_LIMIT.",
    ),
)

internal fun categoryPageResultProperties(): Map<String, LLMRequest.Property> = mapOf(
    "totalSkills" to LLMRequest.Property("integer", "Number of available entries in the category."),
    "nextOffset" to LLMRequest.Property("integer", "Start position for the next page, or null when complete."),
)
