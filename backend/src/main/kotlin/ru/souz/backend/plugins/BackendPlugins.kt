package ru.souz.backend.plugins

import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.channels.ChannelProviderRegistry
import ru.souz.backend.channels.ChannelSendResult
import ru.souz.backend.common.toolArgumentsError
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.plugins.*
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.skilloauth.SkillOAuthGateway
import ru.souz.tool.ToolCategory

internal class BackendPlugins(
    private val dataSource: DataSource,
    private val http: PluginHttp,
    private val oauth: SkillOAuthGateway?,
    private val channels: ChannelProviderRegistry,
    private val sandboxes: ToolInvocationRuntimeSandboxResolver,
) {
    private val logger = LoggerFactory.getLogger(BackendPlugins::class.java)

    fun catalog(lease: PluginLease, userId: String): AgentToolCatalog = AgentToolCatalog(
        lease.tools.groupBy { ToolCategory.valueOf(it.tool.category) }.mapValues { (_, tools) ->
            tools.map { loaded -> object : LLMToolSetup {
                override val fn = loaded.tool.function
                override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
                    failure("plugin_identity_missing", "Authenticated invocation required")

                override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message {
                    if (meta.userId != userId) return failure("plugin_identity_mismatch", "Invocation owner differs from execution")
                    return try {
                        val args = restJsonMapper.valueToTree<ObjectNode>(functionCall.arguments)
                        toolArgumentsError(fn.parameters, args)?.let { return failure("plugin_invalid_arguments", it) }
                        val value = loaded.tool.invoke(args, context(meta, loaded.pluginId))
                        LLMRequest.Message(LLMMessageRole.function, value.toString(), name = fn.name)
                    } catch (e: CancellationException) { throw e }
                    catch (e: PluginException) { failure(e.code, e.message ?: "Plugin call failed") }
                    catch (e: Exception) {
                        val reference = UUID.randomUUID().toString()
                        // Arbitrary exception messages may contain OAuth URLs, SQL values or mail bodies.
                        val trace = generateSequence<Throwable>(e) { it.cause }.take(8).joinToString("\nCaused by: ") {
                            it.javaClass.name + it.stackTrace.joinToString(separator = "\n  at ", prefix = "\n  at ")
                        }
                        logger.warn("Plugin {} tool {} failed [diagnosticId={}]: {}", loaded.pluginId, fn.name, reference, trace)
                        val code = if (e is IllegalArgumentException) "plugin_operation_rejected" else "plugin_failed"
                        failure(code, "Plugin operation failed; diagnostic ID: $reference")
                    }
                }

                private fun failure(code: String, message: String) = LLMRequest.Message(
                    LLMMessageRole.function, restJsonMapper.writeValueAsString(mapOf("error" to mapOf("code" to code, "message" to message))), name = fn.name,
                )
            } }
        },
    )

    private fun context(identity: ToolInvocationMeta, pluginId: String): PluginContext = object : PluginContext {
        override val meta = identity
        override val data = PostgresPluginData(dataSource, meta.userId, pluginId)
        override val http = this@BackendPlugins.http
        override val oauth = PluginOAuth { provider, scopes, request ->
            require((request.body?.toByteArray()?.size ?: 0) <= PLUGIN_MAX_BYTES)
            val gateway = this@BackendPlugins.oauth ?: throw PluginException("plugin_service_unavailable", "OAuth is not configured")
            gateway.call(meta.userId, provider, scopes, request.copy(
                maxResponseBytes = minOf(request.maxResponseBytes ?: Int.MAX_VALUE, 36 * PLUGIN_MAX_BYTES),
            ))
        }
        override val channels = PluginChannels { type, id, text ->
            require(type.isNotBlank() && id.isNotBlank() && text.isNotBlank() && text.toByteArray().size <= PLUGIN_MAX_BYTES)
            this@BackendPlugins.channels.send(meta.userId, type, id, text) is ChannelSendResult.Delivered
        }
        override val files = object : PluginFiles {
            override suspend fun readText(path: String): String? = withContext(Dispatchers.IO) {
                val fs = sandboxes.resolve(meta).fileSystem
                val file = fs.resolvePath(path)
                require(fs.isPathSafe(file) && !file.isSymbolicLink) { "Unsafe plugin input path" }
                if (!file.exists) return@withContext null
                require(file.isRegularFile && (file.sizeBytes ?: Long.MAX_VALUE) <= PLUGIN_MAX_BYTES)
                val bytes = fs.openInputStream(file).use { it.readNBytes(PLUGIN_MAX_BYTES + 1) }
                require(bytes.size <= PLUGIN_MAX_BYTES)
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            }

            override suspend fun save(extension: String, content: ByteArray): String = withContext(Dispatchers.IO) {
                require(extension.matches(Regex("[a-z0-9]{1,12}")) && content.size <= 25 * PLUGIN_MAX_BYTES)
                val fs = sandboxes.resolve(meta).fileSystem
                val directory = fs.resolvePath("${fs.runtimePaths.homePath.trimEnd('/')}/Documents/souz/plugin-files/$pluginId/${UUID.randomUUID()}")
                require(fs.isPathSafe(directory) && !directory.exists)
                fs.createDirectory(directory)
                val file = fs.resolvePath("${directory.path}/attachment.$extension")
                try {
                    fs.writeBytes(file, content)
                    file.path
                } catch (failure: Throwable) {
                    try { fs.delete(directory, recursively = true) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                    throw failure
                }
            }
        }
        override fun log(message: String) { logger.info("Plugin {}: {}", pluginId, message) }
    }
}

internal const val PLUGIN_BINDINGS = "pluginBindings"
