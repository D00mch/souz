package ru.souz.agent.spi

import ru.souz.llms.LLMRequest
import ru.souz.llms.LlmProvider

/** A host-resolved execution model. */
data class RoutedModel(val model: String, val provider: LlmProvider)

/** Execution models for simple and complex turns; hosts set it only for turns that may be routed. */
data class ModelRouting(val simple: RoutedModel, val complex: RoutedModel)

/** Whether a turn needs the complex model, and the host waiting-phrase category when the classifier picked one. */
data class TaskClassification(val complex: Boolean, val waitCategory: String? = null)

/** Classifies a turn for routing; throws when it cannot decide. */
fun interface TaskComplexityClassifier {
    suspend fun classify(request: LLMRequest.Chat, capabilities: Map<String, String>): TaskClassification
}
