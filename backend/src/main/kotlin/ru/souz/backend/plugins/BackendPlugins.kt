package ru.souz.backend.plugins

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Path
import javax.sql.DataSource
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.plugins.PluginCallContext
import ru.souz.plugins.PluginException
import ru.souz.plugins.PluginLogger
import ru.souz.plugins.runtime.LoadedPluginTool
import ru.souz.plugins.runtime.PluginBinding
import ru.souz.plugins.runtime.PluginLease
import ru.souz.plugins.runtime.PluginRegistry
import ru.souz.tool.ToolCategory
import ru.souz.tool.immutableToolCatalogSnapshot

internal class BackendPlugins(
    directory: Path?,
    cache: Path?,
    private val source: DataSource,
    private val http: BackendPluginHttp,
    reservedNames: Set<String>,
) {
    private val logger = LoggerFactory.getLogger(BackendPlugins::class.java)
    val registry = PluginRegistry(directory, cache, reservedNames,
        validateDefinition = { ToolCategory.valueOf(it.category) },
        reportFailure = { id, error -> logger.warn("Plugin {} lifecycle failure: {}", id, error.javaClass.simpleName) },
    )

    fun catalog(lease: PluginLease, userId: String): AgentToolCatalog = immutableToolCatalogSnapshot(
        lease.tools.groupBy { ToolCategory.valueOf(it.definition.category) }.mapValues { (_, tools) ->
            tools.associate { tool -> tool.name to adapter(tool, userId) }
        },
    )

    private fun adapter(tool: LoadedPluginTool, owner: String): LLMToolSetup = object : LLMToolSetup {
        override val fn = LLMRequest.Function(
            name = tool.name,
            description = tool.definition.description,
            parameters = restJsonMapper.treeToValue(tool.definition.inputSchema, LLMRequest.Parameters::class.java),
        )

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
            failure("plugin_identity_missing", "Plugin invocation requires authenticated identity")

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message {
            if (meta.userId != owner) return failure("plugin_identity_mismatch", "Plugin invocation owner does not match execution")
            return try {
                val arguments = restJsonMapper.valueToTree<ObjectNode>(functionCall.arguments)
                validateArguments(arguments, tool.definition.inputSchema)
                val context = object : PluginCallContext {
                    override val data = PostgresPluginData(source, meta.userId, tool.pluginId)
                    override val http = this@BackendPlugins.http
                    override val logger = PluginLogger { message -> this@BackendPlugins.logger.info("Plugin {}: {}", tool.pluginId, message) }
                }
                val result = tool.handler.invoke(arguments, context)
                LLMRequest.Message(LLMMessageRole.function, restJsonMapper.writeValueAsString(result), name = fn.name)
            } catch (e: CancellationException) { throw e }
            catch (e: PluginException) { failure(e.code, e.message ?: "Plugin call failed") }
            catch (_: IllegalArgumentException) { failure("plugin_invalid_arguments", "Invalid plugin arguments") }
            catch (_: Exception) { failure("plugin_failed", "Plugin call failed") }
        }

        private fun failure(code: String, message: String) = LLMRequest.Message(
            LLMMessageRole.function,
            restJsonMapper.writeValueAsString(mapOf("error" to mapOf("code" to code, "message" to message))),
            name = fn.name,
        )
    }
}

internal const val PLUGIN_BINDINGS_METADATA = "pluginBindings"
internal fun encodePluginBindings(bindings: List<PluginBinding>): String = restJsonMapper.writeValueAsString(bindings)
internal fun decodePluginBindings(raw: String?): List<PluginBinding> =
    if (raw == null) emptyList() else restJsonMapper.readValue(raw, Array<PluginBinding>::class.java).toList()

private fun validateArguments(value: JsonNode, schema: JsonNode) {
    require(when (schema.path("type").asText()) {
        "object" -> value.isObject
        "array" -> value.isArray
        "string" -> value.isTextual
        "number" -> value.isNumber
        "integer" -> value.isIntegralNumber
        "boolean" -> value.isBoolean
        else -> false
    })
    schema.get("enum")?.let { require(value in it) }
    if (value.isObject) {
        schema.path("required").forEach { require(value.has(it.asText())) }
        value.properties().forEach { (key, item) ->
            val property = schema.path("properties").get(key)
            require(property != null) { "Unknown tool argument" }
            validateArguments(item, property)
        }
    }
    if (value.isArray) value.forEach { validateArguments(it, schema.path("items")) }
}
