package ru.souz.plugins

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

const val PLUGIN_API_VERSION: String = "1"
const val PLUGIN_MAX_BYTES: Int = 1024 * 1024

/** Trusted, shared instance. Never retain invocation contexts or user data. */
interface SouzPlugin {
    fun tools(): List<PluginTool>
    suspend fun start() {}
    suspend fun stop() {}
}

interface PluginTool {
    val definition: PluginToolDefinition
    suspend fun invoke(arguments: ObjectNode, context: PluginCallContext): JsonNode
}

/** Tool names are local; the host advertises `<pluginId>__<name>`. */
data class PluginToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: ObjectNode,
    val category: String = "NOTES",
)

interface PluginCallContext {
    val data: PluginData
    val http: PluginHttp
    val logger: PluginLogger
}

/** Already bound to the authenticated user and installed plugin; keys are opaque. */
interface PluginData {
    suspend fun get(key: String): JsonNode?
    suspend fun put(key: String, value: JsonNode)
    suspend fun delete(key: String): Boolean
    suspend fun list(after: String? = null, limit: Int = 20): PluginDataPage
}

data class PluginDataRecord(val key: String, val value: JsonNode)
data class PluginDataPage(val records: List<PluginDataRecord>, val nextCursor: String?)

fun interface PluginHttp {
    suspend fun execute(request: PluginHttpRequest): PluginHttpResponse
}

data class PluginHttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, List<String>> = emptyMap(),
    val body: ByteArray = byteArrayOf(),
)

data class PluginHttpResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
)

/** Log operational messages only; never credentials, arguments, HTTP bodies, or user data. */
fun interface PluginLogger { fun info(message: String) }

/** Expected, safe-to-display tool failure. Coroutine cancellation must be rethrown separately. */
class PluginException(val code: String, message: String) : Exception(message)
