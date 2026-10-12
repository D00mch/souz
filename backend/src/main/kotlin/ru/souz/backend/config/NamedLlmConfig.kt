package ru.souz.backend.config

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import ru.souz.llms.ModelResolution
import ru.souz.llms.resolveChatModel
import ru.souz.llms.restJsonMapper

/** Backend deployment selections; credentials are deliberately excluded from generated toString methods. */
class NamedLlmModel(val modelId: String, val baseUrl: String, val apiKey: String)

class NamedLlmConfig(
    val models: Map<String, NamedLlmModel> = emptyMap(),
    val subagentModels: Set<String> = emptySet(),
    val summarization: NamedLlmModel? = null,
    val summarizationContextSize: Int? = null,
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
    root.fieldsOnly("openaiConnections", "models", "subagentModels", "summarization")
    val connections = root.entries("openaiConnections").mapValues { (id, node) ->
        node.fieldsOnly("baseUrl", "apiKeyEnv")
        val url = node.text("baseUrl")
        val uri = URI(url)
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.port == -1 || uri.port in 1..65535)) { "Invalid baseUrl for connection $id." }
        val keyEnv = node.text("apiKeyEnv")
        url to requireNotNull(env(keyEnv)?.trim()?.takeIf(String::isNotEmpty)) {
            "Missing environment variable $keyEnv for connection $id."
        }
    }
    val models = root.entries("models").mapValues { (name, node) ->
        require(resolveChatModel(name) is ModelResolution.Unknown) { "Model name $name conflicts with a built-in model." }
        node.fieldsOnly("connection", "modelId")
        require(name != value("OPENAI_MODEL", "OPENAI_MODEL")?.trim()) { "OPENAI_MODEL must not overlap named model selections." }
        val (url, key) = requireNotNull(connections[node.text("connection")]) { "Unknown connection for model $name." }
        NamedLlmModel(node.text("modelId"), url, key)
    }
    fun model(name: String): NamedLlmModel = requireNotNull(models[name]) { "Unknown configured model $name." }
    val subagents = root["subagentModels"]?.let { node ->
        require(node.isArray && node.all { it.isTextual }) { "subagentModels must be an array of model names." }
        node.mapTo(linkedSetOf()) { it.textValue().also(::model) }
    }.orEmpty()
    val summary = root["summarization"]?.also { it.fieldsOnly("model", "contextSize") }
    val contextSize = summary?.get("contextSize")?.let {
        require(it.isIntegralNumber && it.canConvertToInt() && it.intValue() > 0) { "summarization.contextSize must be positive." }
        it.intValue()
    }
    return NamedLlmConfig(models, subagents, summary?.text("model")?.let(::model), contextSize)
}

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
