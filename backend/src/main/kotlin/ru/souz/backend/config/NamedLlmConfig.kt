package ru.souz.backend.config

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import ru.souz.agent.spi.ModelRouting
import ru.souz.agent.spi.RoutedModel
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider
import ru.souz.llms.ModelResolution
import ru.souz.llms.findLLMModel
import ru.souz.llms.resolveChatModel
import ru.souz.llms.restJsonMapper

/** Backend deployment selections; credentials are deliberately excluded from generated toString methods. */
class NamedLlmModel(val modelId: String, val baseUrl: String, val apiKey: String, val parameters: String? = null)

class NamedLlmConfig(
    val models: Map<String, NamedLlmModel> = emptyMap(),
    val subagentModels: Set<String> = emptySet(),
    val summarization: NamedLlmModel? = null,
    val routing: ModelRouting? = null,
    val builtinReasoningEfforts: Map<String, String> = emptyMap(),
)

/** Resolve and validate deployment routes once, before accepting requests. */
internal fun BackendConfigSource.loadNamedModels(): NamedLlmConfig {
    val path = value("SOUZ_LLM_CONFIG_FILE", "SOUZ_LLM_CONFIG_FILE") ?: return NamedLlmConfig()
    val root = restJsonMapper.factory.createParser(Files.readString(Path.of(path))).use { parser ->
        parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        val node = restJsonMapper.readTree<JsonNode>(parser)
        require(node != null && parser.nextToken() == null) { "Expected one LLM configuration object." }
        node
    }
    root.fieldsOnly("openaiConnections", "models", "subagentModels", "summarization", "routing")
    val connections = root.entries("openaiConnections").mapValues { (id, node) ->
        node.fieldsOnly("baseUrl", "apiKeyEnv")
        val url = node.text("baseUrl")
        val uri = URI(url)
        require(when (uri.scheme) { "http", "https" -> true; else -> false } && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.port == -1 || uri.port in 1..65535)) { "Invalid baseUrl for connection $id." }
        val keyEnv = node.text("apiKeyEnv")
        url to requireNotNull(env(keyEnv)?.trim()?.takeIf(String::isNotEmpty)) {
            "Missing environment variable $keyEnv for connection $id."
        }
    }
    val builtinReasoningEfforts = linkedMapOf<String, String>()
    val models = root.entries("models").mapNotNull { (name, node) ->
        findLLMModel(name)?.let { builtin ->
            node.fieldsOnly("parameters")
            require((builtin.provider == LlmProvider.CODEX || builtin.provider == LlmProvider.OPENAI) && builtin != LLMModel.OpenAICompatibleCustom) {
                "Model $name does not support configured reasoning effort."
            }
            val parameters = requireNotNull(node["parameters"]) { "parameters for model $name are required." }
            parameters.fieldsOnly("reasoning_effort")
            val effort = parameters.text("reasoning_effort")
            require(builtinReasoningEfforts.putIfAbsent(builtin.alias, effort) == null) {
                "Duplicate parameter configuration for model ${builtin.alias}."
            }
            return@mapNotNull null
        }
        require(resolveChatModel(name) is ModelResolution.Unknown) { "Model name $name conflicts with a built-in model." }
        node.fieldsOnly("connection", "modelId", "parameters")
        require(name != value("OPENAI_MODEL", "OPENAI_MODEL")?.trim()) { "OPENAI_MODEL must not overlap named model selections." }
        val (url, key) = requireNotNull(connections[node.text("connection")]) { "Unknown connection for model $name." }
        val parameters = node["parameters"]?.also {
            require(it.isObject) { "parameters for model $name must be an object." }
            require(it.properties().none { field -> field.key in RESERVED_MODEL_PARAMETERS }) {
                "parameters for model $name must not override request structure or runtime token limits."
            }
        }?.toString()
        name to NamedLlmModel(node.text("modelId"), url, key, parameters)
    }.toMap()
    fun model(name: String): NamedLlmModel = requireNotNull(models[name]) { "Unknown configured model $name." }
    val subagents = root["subagentModels"]?.let { node ->
        require(node.isArray && node.all { it.isTextual }) { "subagentModels must be an array of model names." }
        node.mapTo(linkedSetOf()) { it.textValue().also(::model) }
    }.orEmpty()
    val summary = root["summarization"]?.also { it.fieldsOnly("model") }
    val routing = root["routing"]?.let { node ->
        node.fieldsOnly("simple", "complex")
        fun target(name: String): RoutedModel {
            val target = requireNotNull(node[name]) { "routing.$name is required." }
            target.fieldsOnly("model")
            val id = target.text("model")
            val builtin = if (id in models) null else requireNotNull(findLLMModel(id)) { "Unknown routing.$name model $id." }
            // A routed call sends the model name as is, so the custom selector would never reach OPENAI_MODEL.
            require(builtin != LLMModel.OpenAICompatibleCustom) { "routing.$name cannot use $id; name a configured model instead." }
            return RoutedModel(builtin?.alias ?: id, builtin?.provider ?: LlmProvider.OPENAI)
        }
        ModelRouting(target("simple"), target("complex"))
    }
    return NamedLlmConfig(models, subagents, summary?.text("model")?.let(::model), routing, builtinReasoningEfforts)
}

private val RESERVED_MODEL_PARAMETERS = setOf(
    "model", "messages", "stream", "tools", "tool_choice", "max_tokens", "max_completion_tokens", "context_size",
)

private fun JsonNode.fieldsOnly(vararg names: String) {
    require(isObject && properties().all { it.key in names }) { "Expected LLM configuration fields ${names.toList()}." }
}

private fun JsonNode.entries(name: String): Map<String, JsonNode> = get(name)?.let { node ->
    require(node.isObject && node.properties().all { it.key.isNotBlank() && it.key == it.key.trim() }) {
        "$name must be an object with nonblank, trimmed names."
    }
    node.properties().associate { it.key to it.value }
}.orEmpty()

private fun JsonNode.text(name: String): String {
    val node = get(name)
    require(node != null && node.isTextual && node.textValue().isNotBlank() && node.textValue() == node.textValue().trim()) {
        "$name must be a nonblank, trimmed string."
    }
    return node.textValue()
}
