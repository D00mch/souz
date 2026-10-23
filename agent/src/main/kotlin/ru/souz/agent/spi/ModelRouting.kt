package ru.souz.agent.spi

import ru.souz.llms.LLMRequest
import ru.souz.llms.LlmProvider

/** A host-resolved execution model. */
data class RoutedModel(val model: String, val provider: LlmProvider)

/** Execution models for simple and complex turns; hosts set it only for turns that may be routed. */
data class ModelRouting(val simple: RoutedModel, val complex: RoutedModel)

/** Independent decisions made before memory and model preparation. */
data class TurnClassification(val complex: Boolean, val recallMemory: Boolean) {
    val expectedFast: Boolean get() = !complex && !recallMemory
}

fun interface TurnClassifier {
    suspend fun classify(request: LLMRequest.Chat): TurnClassification
}
