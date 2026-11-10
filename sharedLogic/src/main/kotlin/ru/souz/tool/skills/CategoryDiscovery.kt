package ru.souz.tool.skills

import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.restJsonMapper

internal data class CategoryDiscoveryInput(val category: String = "", val offset: Int = 0, val limit: Int = 20)

internal fun categoryDiscoveryInput(call: LLMResponse.FunctionCall): CategoryDiscoveryInput =
    restJsonMapper.convertValue(call.arguments, CategoryDiscoveryInput::class.java).also {
        require(it.offset in 0..10_000 && it.limit in 1..50) { "Invalid discovery offset or limit." }
    }

internal fun categoryPageResultProperties(): Map<String, LLMRequest.Property> = mapOf(
    "totalSkills" to LLMRequest.Property("integer", "Number of available entries in the category."),
    "nextOffset" to LLMRequest.Property("integer", "Start position for the next page, or null when complete."),
)
