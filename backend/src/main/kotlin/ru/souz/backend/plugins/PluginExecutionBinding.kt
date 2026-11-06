package ru.souz.backend.plugins

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper

internal class PluginExecutionBinding(
    private val runtime: BackendPluginRuntime, val owner: String,
    val versions: List<BackendPluginRuntime.Version>, selectedNames: Set<String>,
) {
    var released = false
    val catalog = AgentToolCatalog(versions.flatMap { version ->
        version.functions.filter { it.schema.name in selectedNames }.map { function -> function.category to object : LLMToolSetup {
            override val fn = function.schema
            override suspend fun invoke(functionCall: LLMResponse.FunctionCall) = pluginError(fn.name, "plugin_identity_required")
            override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta) =
                runtime.invoke(this@PluginExecutionBinding, version, function, functionCall, meta)
        } }
    }.groupBy({ it.first }, { it.second }))
    suspend fun release() = withContext(NonCancellable) { runtime.release(this@PluginExecutionBinding) }
}

internal fun pluginError(name: String, code: String, message: String = "Plugin operation is unavailable.") =
    LLMRequest.Message(LLMMessageRole.function, restJsonMapper.writeValueAsString(mapOf("error" to mapOf("code" to code, "message" to message))), name = name)
