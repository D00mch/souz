package ru.souz.db

import com.fasterxml.jackson.core.JsonParser
import ru.souz.llms.LlmProvider
import ru.souz.llms.restJsonMapper

const val SUBAGENT_MODELS_JSON = "SUBAGENT_MODELS_JSON"

/** Parses host configuration without IO; IDs are exact provider request values. */
fun parseSubagentModels(
    json: String?,
    supportedProviders: Set<LlmProvider> = LlmProvider.entries.toSet(),
): Map<String, LlmProvider> {
    if (json == null) return emptyMap()
    try {
        return restJsonMapper.factory.createParser(json).use { parser ->
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            val root = restJsonMapper.readTree<com.fasterxml.jackson.databind.JsonNode>(parser)
            require(root != null && root.isObject && parser.nextToken() == null) { "Expected one provider-to-model-list object." }
            buildMap {
                root.properties().forEach { (key, models) ->
                    val provider = LlmProvider.valueOf(key)
                    require(provider in supportedProviders) { "Unsupported provider: $key." }
                    require(models.isArray) { "$key must contain an array of model IDs." }
                    models.forEach { model ->
                        require(model.isTextual && model.textValue().isNotBlank()) { "$key model IDs must be nonblank strings." }
                        val id = model.textValue()
                        val previous = put(id, provider)
                        require(previous == null || previous == provider) { "Model $id belongs to both $previous and $provider." }
                    }
                }
            }
        }
    } catch (error: Exception) {
        throw IllegalArgumentException("Invalid $SUBAGENT_MODELS_JSON: ${error.message}", error)
    }
}
