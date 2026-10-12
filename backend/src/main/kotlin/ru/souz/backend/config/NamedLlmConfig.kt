package ru.souz.backend.config

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import ru.souz.db.NamedLlmModels
import ru.souz.db.OpenAiConnection
import ru.souz.db.NamedLlmModel
import ru.souz.llms.ModelResolution
import ru.souz.llms.resolveChatModel
import ru.souz.llms.restJsonMapper

/** Load once at host startup; malformed routes and missing secrets fail before serving requests. */
internal fun loadNamedLlmModels(source: BackendConfigSource): NamedLlmModels {
    val path = source.value("SOUZ_LLM_CONFIG_FILE", "SOUZ_LLM_CONFIG_FILE") ?: return NamedLlmModels.EMPTY
    val root = restJsonMapper.factory.createParser(Files.readString(Path.of(path))).use { parser ->
        parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        val node = restJsonMapper.readTree<JsonNode>(parser)
        require(node != null && node.isObject && parser.nextToken() == null) { "Expected one LLM configuration object." }
        node
    }
    root.fieldsOnly("openaiConnections", "models", "summarization", "subagentModels")
    val connections = root.objectEntries("openaiConnections").associate { (id, node) ->
        require(id.isNotBlank() && id == id.trim()) { "Connection IDs must be nonblank and trimmed." }
        node.fieldsOnly("baseUrl", "apiKeyEnv")
        val url = node.text("baseUrl")
        val uri = URI(url)
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null) { "Invalid baseUrl for connection $id." }
        val keyEnv = node.text("apiKeyEnv")
        val key = source.env(keyEnv)?.trim()?.takeIf(String::isNotEmpty)
        requireNotNull(key) { "Missing environment variable $keyEnv for connection $id." }
        id to OpenAiConnection(url, key)
    }
    val models = root.objectEntries("models").associate { (name, node) ->
        require(name.isNotBlank() && name == name.trim()) { "Model names must be nonblank and trimmed." }
        require(resolveChatModel(name) is ModelResolution.Unknown) { "Model name $name conflicts with a built-in model." }
        node.fieldsOnly("connection", "modelId")
        val connection = node.text("connection")
        require(connection in connections) { "Unknown connection $connection for model $name." }
        name to NamedLlmModel(node.text("modelId"), connection)
    }
    fun model(name: String): NamedLlmModel = requireNotNull(models[name]) { "Unknown configured model $name." }
    val subagents = root["subagentModels"]?.let { node ->
        require(node.isArray && node.all { it.isTextual }) { "subagentModels must be an array of model names." }
        node.associate { it.textValue().let { name -> name to model(name) } }
    }.orEmpty()
    val summary = root["summarization"]
    summary?.fieldsOnly("model", "contextSize")
    val contextSize = summary?.get("contextSize")?.let {
        require(it.isIntegralNumber && it.canConvertToInt() && it.intValue() > 0) { "summarization.contextSize must be positive." }
        it.intValue()
    }
    return NamedLlmModels(connections, models, subagents, summary?.text("model")?.let(::model), contextSize)
}

private fun JsonNode.fieldsOnly(vararg names: String) {
    require(isObject && properties().all { it.key in names }) { "Unexpected fields in LLM configuration; expected ${names.toList()}." }
}

private fun JsonNode.objectEntries(name: String): Set<Map.Entry<String, JsonNode>> {
    val node = get(name) ?: return emptySet()
    require(node.isObject) { "$name must be an object." }
    return node.properties()
}

private fun JsonNode.text(name: String): String {
    val node = get(name)
    require(node != null && node.isTextual && node.textValue().isNotBlank() && node.textValue() == node.textValue().trim()) {
        "$name must be a nonblank, trimmed string."
    }
    return node.textValue()
}
