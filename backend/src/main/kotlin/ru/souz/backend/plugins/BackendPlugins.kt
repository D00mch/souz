package ru.souz.backend.plugins

import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.PluginCallFinishedPayload
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.channels.ChannelProviderRegistry
import ru.souz.backend.channels.ChannelSendResult
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.plugins.runtime.LoadedPluginTool
import ru.souz.plugins.runtime.PluginBinding
import ru.souz.plugins.runtime.PluginLease
import ru.souz.plugins.runtime.PluginRegistry
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.skilloauth.ApiCallReconnectRequired
import ru.souz.skilloauth.ApiCallRequest
import ru.souz.skilloauth.ApiCallResponse
import ru.souz.skilloauth.SkillOAuthGateway
import ru.souz.tool.ToolCategory
import ru.souz.plugins.*

internal class BackendPlugins(
    directory: Path?,
    cache: Path?,
    private val source: DataSource,
    private val http: BackendPluginHttp,
    reservedNames: Set<String>,
    private val gateway: SkillOAuthGateway?,
    private val channelRegistry: ChannelProviderRegistry,
    private val sandboxes: ToolInvocationRuntimeSandboxResolver,
    private val events: AgentEventService,
) {
    private val logger = LoggerFactory.getLogger(BackendPlugins::class.java)
    val registry = PluginRegistry(directory, cache, reservedNames,
        validateDefinition = { ToolCategory.valueOf(it.category) },
        reportFailure = { diagnostic -> logger.warn("Plugin installation failure: {}", diagnostic) },
    )

    fun catalog(lease: PluginLease, userId: String): AgentToolCatalog = AgentToolCatalog(
        lease.tools.groupBy { ToolCategory.valueOf(it.definition.category) }.mapValues { (_, tools) ->
            tools.map { tool -> adapter(tool, userId) }
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

        // Record cancellation as a receipt, then propagate it unchanged.
        @Suppress("SuspendFunSwallowedCancellation")
        override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message {
            if (meta.userId != owner) return failure("plugin_identity_mismatch", "Plugin invocation owner does not match execution")
            val started = System.nanoTime()
            var status = "failed"
            var outcome: String? = null
            var errorCode: String? = null
            return try {
                val arguments = restJsonMapper.valueToTree<ObjectNode>(functionCall.arguments)
                val errors = pluginArgumentErrors(arguments, tool.definition.inputSchema)
                if (errors.isNotEmpty()) {
                    status = "rejected"
                    errorCode = "plugin_invalid_arguments"
                    return failure(errorCode, "Match the selected tool inputSchema and retry.", mapOf(
                        "details" to errors, "inputSchema" to tool.definition.inputSchema,
                    ))
                }
                val context = CallContext(meta, tool.pluginId)
                val result = tool.handler.invoke(arguments, context)
                outcome = context.outcome?.name?.lowercase()
                status = "succeeded"
                LLMRequest.Message(LLMMessageRole.function, restJsonMapper.writeValueAsString(result), name = fn.name)
            } catch (e: CancellationException) { status = "cancelled"; throw e }
            catch (e: PluginException) { errorCode = e.code; failure(e.code, e.message ?: "Plugin call failed") }
            catch (_: IllegalArgumentException) {
                errorCode = "plugin_invalid_arguments"
                failure(errorCode, "Plugin rejected the arguments; inspect the schema and tool constraints.", mapOf("inputSchema" to tool.definition.inputSchema))
            }
            catch (_: Exception) { errorCode = "plugin_failed"; failure(errorCode, "Plugin call failed") }
            finally {
                val chatId = meta.conversationId?.let(UUID::fromString)
                val threadId = meta.requestId?.let(UUID::fromString)
                if (chatId != null && threadId != null) withContext(NonCancellable) {
                    try {
                        events.appendDurable(meta.userId, chatId, threadId, AgentEventType.PLUGIN_CALL_FINISHED,
                            PluginCallFinishedPayload(UUID.randomUUID().toString(), tool.pluginId, tool.name,
                                tool.binding.version, tool.binding.digest, status, outcome, errorCode,
                                (System.nanoTime() - started) / 1_000_000))
                    } catch (error: Exception) {
                        // Diagnostics must not mask cancellation or turn completed side effects into a retryable failure.
                        logger.warn("Plugin receipt failed plugin={} tool={} digest={} reason={}",
                            tool.pluginId, tool.name, tool.binding.digest, error.javaClass.simpleName)
                    }
                }
            }
        }

        private fun failure(code: String, message: String, details: Map<String, Any> = emptyMap()) = LLMRequest.Message(
            LLMMessageRole.function,
            restJsonMapper.writeValueAsString(mapOf("error" to (mapOf("code" to code, "message" to message) + details))),
            name = fn.name,
        )
    }

    private inner class CallContext(meta: ToolInvocationMeta, pluginId: String) : PluginCallContext {
        var outcome: PluginOutcome? = null
        override fun reportOutcome(outcome: PluginOutcome) { this.outcome = outcome }
        override val data = PostgresPluginData(source, meta.userId, pluginId)
        override val http = this@BackendPlugins.http
        override val logger = PluginLogger { message -> this@BackendPlugins.logger.info("Plugin {}: {}", pluginId, message) }

        override val oauth = PluginOAuth { provider, scopes, request ->
            require(request.body.size <= PLUGIN_MAX_BYTES)
            val headers = request.headers.mapValues { (_, values) -> values.joinToString(", ") }
            val oauth = gateway ?: throw PluginException("plugin_service_unavailable", "OAuth is not configured on this host")
            when (val outcome = oauth.call(meta.userId, provider, scopes,
                ApiCallRequest(request.method, request.url, request.body.takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8), headers))) {
                is ApiCallReconnectRequired -> PluginOAuthResponse(authorizationUrl = outcome.authorizationUrl)
                is ApiCallResponse -> {
                    require(outcome.body.toByteArray().size <= 36 * PLUGIN_MAX_BYTES) { "OAuth response exceeds 36 MiB" }
                    PluginOAuthResponse(outcome.statusCode, outcome.body)
                }
            }
        }

        override val channels = PluginChannels { type, id, text ->
            require(type.isNotBlank() && id.isNotBlank() && text.isNotBlank() && text.toByteArray().size <= PLUGIN_MAX_BYTES)
            channelRegistry.send(meta.userId, type, id, text) is ChannelSendResult.Delivered
        }

        override val files = object : PluginFiles {
            override suspend fun readText(path: String): String? = withContext(Dispatchers.IO) {
                val fs = sandboxes.resolve(meta).fileSystem
                val file = fs.resolvePath(path)
                require(fs.isPathSafe(file) && !file.isSymbolicLink) { "Unsafe plugin input file" }
                if (!file.exists) return@withContext null
                require(file.isRegularFile && (file.sizeBytes ?: Long.MAX_VALUE) <= PLUGIN_MAX_BYTES)
                val bytes = fs.openInputStream(file).use { it.readNBytes(PLUGIN_MAX_BYTES + 1) }
                require(bytes.size <= PLUGIN_MAX_BYTES)
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            }

            override suspend fun save(extension: String, content: ByteArray): String = withContext(Dispatchers.IO) {
                require(extension.matches(Regex("[a-z0-9]{1,12}")) && content.size <= 25 * PLUGIN_MAX_BYTES)
                val fs = sandboxes.resolve(meta).fileSystem
                val root = "${fs.runtimePaths.homePath.trimEnd('/', '\\')}/Documents/souz/plugin-files/$pluginId/download-${UUID.randomUUID()}"
                val directory = fs.resolvePath(root)
                require(fs.isPathSafe(directory) && !directory.exists)
                fs.createDirectory(directory)
                val file = fs.resolvePath("$root/attachment.$extension")
                try {
                    fs.writeBytes(file, content)
                    file.path
                } catch (failure: Throwable) {
                    try { fs.delete(fs.resolvePath(root), recursively = true) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                    throw failure
                }
            }
        }
    }
}

internal const val PLUGIN_BINDINGS_METADATA = "pluginBindings"
internal fun encodePluginBindings(bindings: List<PluginBinding>): String = restJsonMapper.writeValueAsString(bindings)
internal fun decodePluginBindings(raw: String?): List<PluginBinding> =
    if (raw == null) emptyList() else restJsonMapper.readValue(raw, Array<PluginBinding>::class.java).toList()
