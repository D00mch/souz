package ru.souz.plugins

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import ru.souz.llms.LLMRequest
import ru.souz.llms.ToolInvocationMeta
import ru.souz.skilloauth.ApiCallOutcome
import ru.souz.skilloauth.ApiCallRequest

const val PLUGIN_API_VERSION = "1"
const val PLUGIN_MAX_BYTES = 1_048_576

/** Shared stateless handlers. Resources and persistent state belong to the host. */
interface SouzPlugin {
    val tools: List<PluginTool>
}

data class PluginTool(
    val function: LLMRequest.Function,
    val category: String,
    val invoke: suspend (ObjectNode, PluginContext) -> JsonNode,
)

interface PluginContext {
    val meta: ToolInvocationMeta
    val data: PluginData
    val http: PluginHttp
    val oauth: PluginOAuth
    val files: PluginFiles
    val channels: PluginChannels
    fun log(message: String)
}

/** Bound to the authenticated user and plugin; callbacks are pure and non-suspending. */
interface PluginData {
    suspend fun get(key: String): JsonNode?
    suspend fun <R> update(key: String, transform: (JsonNode?) -> DataChange<R>): R
    suspend fun put(key: String, value: JsonNode) = update(key) { DataChange(value, Unit) }
    suspend fun delete(key: String): Boolean = update(key) { DataChange(null, it != null) }
    suspend fun list(after: String? = null, limit: Int = 20): DataPage
}

/** A null value deletes the record. The result is returned only after commit. */
data class DataChange<R>(val value: JsonNode?, val result: R)
data class DataRecord(val key: String, val value: JsonNode)
data class DataPage(val records: List<DataRecord>, val nextCursor: String?)

fun interface PluginHttp {
    suspend fun execute(request: HttpRequest): HttpResponse
}

data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, List<String>> = emptyMap(),
    val body: ByteArray = byteArrayOf(),
)

data class HttpResponse(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray)

fun interface PluginOAuth {
    suspend fun call(provider: String, scopes: Set<String>, request: ApiCallRequest): ApiCallOutcome
}

interface PluginFiles {
    suspend fun readText(path: String): String?
    suspend fun save(extension: String, content: ByteArray): String
}

fun interface PluginChannels {
    /** False, exceptions and cancellation can mean uncertain delivery. */
    suspend fun send(type: String, id: String, text: String): Boolean
}

class PluginException(val code: String, message: String) : Exception(message)
