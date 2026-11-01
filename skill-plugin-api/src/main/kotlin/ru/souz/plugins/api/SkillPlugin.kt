package ru.souz.plugins.api

import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta

/** Compile-only ABI shared with the host. Changing any exposed dependency requires a new ABI. */
object SkillPluginApi {
    const val ABI = "1;kotlin=2.4.10;coroutines=1.11.0;jackson=2.21.3"
}

/**
 * One side-effect-free public no-argument SPI provider per flat JAR.
 * initialize may acquire resources; shutdown must release even partially initialized resources.
 * All returned schemas and messages contain host DTOs and plain JSON values only.
 * Work is invocation-scoped: await structured children before returning. Do not retain invocation
 * contexts or start persistent/background tasks. initialize/shutdown acquire and release resources.
 */
interface SkillPlugin {
    val tools: List<LLMRequest.Function>
    suspend fun initialize()
    suspend fun invoke(call: LLMResponse.FunctionCall, context: PluginInvocation): LLMRequest.Message
    suspend fun shutdown()
}

/** Valid only for this invocation. Identity and service partitions are supplied by the host. */
data class PluginInvocation(
    val meta: ToolInvocationMeta,
    val state: PluginState,
    val http: PluginHttp,
    val log: PluginLog,
)

data class PluginStateValue(val json: String, val dataSchemaVersion: Int, val revision: Long)

interface PluginState {
    suspend fun get(key: String): PluginStateValue?

    /**
     * null revision: unconditional write; 0: insert only; positive: compare-and-set.
     * Returns null on a revision conflict. JSON and data format compatibility belong to the author.
     */
    suspend fun put(key: String, json: String, dataSchemaVersion: Int, expectedRevision: Long?): PluginStateValue?
}

/** Basic bounded HTTP facility, without backend credentials, default headers or cookies. */
interface PluginHttp {
    suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?): PluginHttpResponse
}

data class PluginHttpResponse(val status: Int, val body: String)

fun interface PluginLog {
    fun info(message: String)
}
