package ru.souz.agent.spi

import ru.souz.llms.LLMRequest
import ru.souz.llms.LlmProvider

/** A host-resolved execution model. */
data class RoutedModel(val model: String, val provider: LlmProvider)

/** Execution models for simple and complex turns; hosts set it only for turns that may be routed. */
data class ModelRouting(val simple: RoutedModel, val complex: RoutedModel)

/** Says whether a turn needs the complex model; throws when it cannot decide. */
fun interface TaskComplexityClassifier {
    suspend fun isComplex(request: LLMRequest.Chat): Boolean
}
