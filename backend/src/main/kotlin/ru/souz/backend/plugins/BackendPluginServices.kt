package ru.souz.backend.plugins

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.souz.backend.channels.ChannelProviderRegistry
import ru.souz.backend.channels.ChannelSendResult
import ru.souz.llms.ToolInvocationMeta
import ru.souz.plugins.*
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.skilloauth.ApiCallReconnectRequired
import ru.souz.skilloauth.ApiCallRequest
import ru.souz.skilloauth.ApiCallResponse
import ru.souz.skilloauth.SkillOAuthGateway

internal class BackendPluginServices(
    private val gateway: SkillOAuthGateway?,
    private val channelRegistry: ChannelProviderRegistry,
    private val sandboxes: ToolInvocationRuntimeSandboxResolver,
) {
    fun oauth(meta: ToolInvocationMeta) = PluginOAuth { provider, scopes, request ->
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

    fun channels(meta: ToolInvocationMeta) = PluginChannels { type, id, text ->
        require(type.isNotBlank() && id.isNotBlank() && text.isNotBlank() && text.toByteArray().size <= PLUGIN_MAX_BYTES)
        channelRegistry.send(meta.userId, type, id, text) is ChannelSendResult.Delivered
    }

    fun files(meta: ToolInvocationMeta, pluginId: String) = object : PluginFiles {
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
