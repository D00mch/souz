package ru.souz.plugins.api

/** Binary boundary shared by the host and every plugin; other libraries remain private. */
object PluginApi {
    const val ABI = "2;kotlin=2.4.10;coroutines=1.11.0"
    const val MAX_JSON_BYTES = 1_048_576
}

/** Stateless providers expose immutable metadata and handlers; resources belong to the host. */
interface SouzPlugin {
    val tools: List<PluginTool>
}

data class PluginTool(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    val category: String,
    val invoke: suspend (String, PluginContext) -> String,
)

/** Invocation-bound services must not escape the handler or be used by detached work. */
interface PluginContext {
    val meta: PluginInvocationMeta
    val state: PluginState
    val http: PluginHttp
    val oauth: PluginOAuth
    val files: PluginFiles
    val channels: PluginChannels
    suspend fun log(message: String)
}

data class PluginInvocationMeta(
    val userId: String,
    val conversationId: String? = null,
    val requestId: String? = null,
    val locale: String? = null,
    val timeZone: String? = null,
)

/** Complete invocations are serialized by the host for the same user/plugin across versions. */
interface PluginState {
    suspend fun get(key: String): String?
    suspend fun put(key: String, json: String)
    suspend fun delete(key: String): Boolean
    suspend fun list(after: String? = null, limit: Int = 20): PluginStatePage
}

data class PluginStateRecord(val key: String, val json: String)
data class PluginStatePage(val records: List<PluginStateRecord>, val nextCursor: String?)

fun interface PluginHttp {
    suspend fun execute(request: PluginHttpRequest): PluginHttpResponse
}

data class PluginHttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, List<String>> = emptyMap(),
    val body: ByteArray = byteArrayOf(),
)

data class PluginHttpResponse(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray)

fun interface PluginOAuth {
    suspend fun call(provider: String, scopes: Set<String>, request: PluginOAuthRequest): PluginOAuthOutcome
}

data class PluginOAuthRequest(
    val method: String,
    val url: String,
    val body: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val maxResponseBytes: Int? = null,
)

sealed interface PluginOAuthOutcome
data class PluginOAuthResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
) : PluginOAuthOutcome

data class PluginReconnectRequired(val authorizationUrl: String, val message: String) : PluginOAuthOutcome

interface PluginFiles {
    suspend fun readWorkspaceText(path: String): String?
    suspend fun saveWorkspace(extension: String, content: ByteArray): String
    suspend fun loadStored(fileId: String): PluginFileContent
    suspend fun saveStored(name: String, content: ByteArray): PluginFile
}

data class PluginFile(val id: String, val name: String, val mimeType: String, val sizeBytes: Long)
data class PluginFileContent(val file: PluginFile, val bytes: ByteArray)

interface PluginChannels {
    /** False, exceptions, and cancellation can mean uncertain delivery. */
    suspend fun send(type: String, id: String, text: String, fileIds: List<String> = emptyList()): Boolean
}

enum class PluginFailureKind { BUSINESS, AUTHORIZATION, TECHNICAL }

/** Only safe messages and codes belong in results; never include tokens or private payloads. */
class PluginException(
    val code: String,
    message: String,
    val kind: PluginFailureKind = PluginFailureKind.BUSINESS,
) : Exception(message)
