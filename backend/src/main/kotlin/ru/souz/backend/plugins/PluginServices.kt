package ru.souz.backend.plugins

import com.fasterxml.jackson.core.JsonProcessingException
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.URLParserException
import io.ktor.http.Url
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.util.UUID
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import ru.souz.backend.attachments.*
import ru.souz.backend.channels.ChannelProviderRegistry
import ru.souz.backend.channels.ChannelSendResult
import ru.souz.backend.crypto.sha256Hex
import ru.souz.backend.http.BackendV1Exception
import ru.souz.llms.ToolInvocationMeta
import ru.souz.plugins.api.*
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.skilloauth.*
import ru.souz.tool.BadInputException

/** Services and transport are process-owned; contexts and all identities are invocation-bound. */
internal class PluginServices(
    private val repository: PostgresPluginRepository,
    private val oauth: SkillOAuthGateway?,
    private val channels: ChannelProviderRegistry,
    private val sandboxes: ToolInvocationRuntimeSandboxResolver,
    private val storage: AttachmentStorage?,
) : AutoCloseable {
    private val http = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 5_000; socketTimeoutMillis = 15_000 }
    }
    fun context(lease: PluginCallLease, identity: PluginInvocationMeta, checkActive: suspend () -> Unit): PluginContext {
        require(identity.userId == lease.ownerId)
        val partition = repository.state(lease)
        val invocationMeta = ToolInvocationMeta(identity.userId, identity.conversationId, identity.requestId, identity.locale, identity.timeZone)
        suspend fun active() { checkActive(); repository.assertOwned(lease); checkActive() }
        return object : PluginContext {
            override val meta = identity
            override val state = object : PluginState {
                override suspend fun get(key: String): String? = service { checkActive(); partition.get(key) }
                override suspend fun put(key: String, json: String) = service { checkActive(); partition.put(key, json) }
                override suspend fun delete(key: String): Boolean = service { checkActive(); partition.delete(key) }
                override suspend fun list(after: String?, limit: Int): PluginStatePage = service { checkActive(); partition.list(after, limit) }
            }
            override val http = PluginHttp { request -> service { active(); fetch(request) } }
            override val oauth = PluginOAuth { provider, scopes, request -> service {
                active()
                require((request.body?.toByteArray()?.size ?: 0) <= PluginApi.MAX_JSON_BYTES &&
                    request.url.length <= 8192 && (request.maxResponseBytes ?: OAUTH_BYTES) > 0 && request.headers.size <= 64 &&
                    request.headers.entries.sumOf { it.key.length + it.value.length } <= 16_384)
                when (val reply = (this@PluginServices.oauth ?: missing("OAuth")).call(lease.ownerId, provider, scopes,
                    ApiCallRequest(request.method, request.url, request.body, request.headers.toMap(),
                        minOf(request.maxResponseBytes ?: OAUTH_BYTES, OAUTH_BYTES)))) {
                    is ApiCallResponse -> PluginOAuthResponse(reply.statusCode, reply.body, reply.headers.toMap())
                    is ApiCallReconnectRequired -> PluginReconnectRequired(reply.authorizationUrl, reply.message)
                }
            } }
            override val channels = object : PluginChannels {
                override suspend fun send(type: String, id: String, text: String, fileIds: List<String>): Boolean = service {
                    active()
                    require(type.isNotBlank() && id.isNotBlank() && text.toByteArray().size <= PluginApi.MAX_JSON_BYTES && fileIds.size <= 32)
                    this@PluginServices.channels.send(lease.ownerId, type, id, text, fileIds.toList()) is ChannelSendResult.Delivered
                }
            }
            override val files = object : PluginFiles {
                override suspend fun readWorkspaceText(path: String): String? = service {
                    active()
                    runInterruptible(Dispatchers.IO) {
                        val fs = sandboxes.resolve(invocationMeta).fileSystem
                        val file = fs.resolvePath(path)
                        require(fs.isPathSafe(file) && !file.isSymbolicLink)
                        if (!file.exists) return@runInterruptible null
                        require(file.isRegularFile && (file.sizeBytes ?: Long.MAX_VALUE) <= PluginApi.MAX_JSON_BYTES)
                        val bytes = fs.openInputStream(file).use { it.readNBytes(PluginApi.MAX_JSON_BYTES + 1) }
                        require(bytes.size <= PluginApi.MAX_JSON_BYTES)
                        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                    }
                }
                override suspend fun saveWorkspace(extension: String, content: ByteArray): String = service {
                    active()
                    require(extension.matches(Regex("[a-z0-9]{1,12}")) && content.size <= 25 * PluginApi.MAX_JSON_BYTES)
                    var created: Pair<SandboxFileSystem, SandboxPathInfo>? = null
                    var returned = false
                    try {
                        val path = runInterruptible(Dispatchers.IO) {
                            val fs = sandboxes.resolve(invocationMeta).fileSystem
                            val root = fs.resolvePath(fs.runtimePaths.homePath.trimEnd('/') + "/Documents/souz/plugin-files/" + UUID.randomUUID())
                            require(fs.isPathSafe(root) && !root.exists)
                            created = fs to root
                            fs.createDirectory(root)
                            val file = fs.resolvePath(root.path + "/attachment." + extension)
                            fs.writeBytes(file, content)
                            file.path
                        }
                        returned = true
                        path
                    } finally {
                        if (!returned) withContext(NonCancellable + Dispatchers.IO) {
                            created?.let { (fs, path) ->
                                try { fs.delete(fs.resolvePath(path.path), recursively = true) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { log.warn("Plugin workspace cleanup failed plugin={}", lease.binding.id) }
                            }
                        }
                    }
                }
                override suspend fun loadStored(fileId: String): PluginFileContent = service {
                    active()
                    val (file, bytes) = (storage ?: missing("File storage")).load(lease.ownerId, attachmentId(fileId))
                    PluginFileContent(file.descriptor(), bytes)
                }
                override suspend fun saveStored(name: String, content: ByteArray): PluginFile = service {
                    active()
                    (storage ?: missing("File storage")).save(lease.ownerId, name, content.inputStream()) { hash ->
                        "plugin:" + lease.token + ":" + name.toByteArray().sha256Hex() + ":" + hash
                    }.descriptor()
                }
            }
            override suspend fun log(message: String) {
                active()
                log.atInfo().addKeyValue("plugin", lease.binding.id).addKeyValue("invocation", lease.token)
                    .addKeyValue("request_id", identity.requestId?.take(100)).log("Plugin: {}", message.replace(Regex("[\\r\\n]"), " ").take(1000))
            }
        }
    }

    private suspend fun fetch(request: PluginHttpRequest): PluginHttpResponse {
        require(request.url.length <= 8192 && request.body.size <= PluginApi.MAX_JSON_BYTES && request.method in methods)
        val url = Url(request.url)
        require((url.protocol.name == "http" || url.protocol.name == "https") && url.user.isNullOrEmpty() && url.password.isNullOrEmpty())
        require(request.headers.keys.none { it.lowercase() in reservedHeaders })
        requireHeaders(request.headers)
        require((request.method != "GET" && request.method != "HEAD") || request.body.isEmpty())
        return http.prepareRequest(url) {
            method = HttpMethod.parse(request.method)
            request.headers.forEach { (name, values) -> headers.appendAll(name, values) }
            if (request.body.isNotEmpty()) setBody(request.body.copyOf())
        }.execute { response ->
            val headers = response.headers.entries().associate { it.key to it.value.toList() }
            requireHeaders(headers)
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            val input = response.bodyAsChannel()
            while (true) {
                val count = input.readAvailable(buffer)
                if (count < 0) break
                require(count <= PluginApi.MAX_JSON_BYTES - bytes.size())
                bytes.write(buffer, 0, count)
            }
            PluginHttpResponse(response.status.value, headers, bytes.toByteArray())
        }
    }
    private fun requireHeaders(headers: Map<String, List<String>>) {
        require(headers.size <= 64 && headers.entries.sumOf { it.key.length + it.value.sumOf(String::length) } <= 16_384)
    }
    private fun StoredAttachment.descriptor() = PluginFile(id.toString(), name, mimeType, sizeBytes)
    private suspend fun <T> service(block: suspend () -> T): T = try { block() }
    catch (_: IllegalArgumentException) { invalid() }
    catch (_: URLParserException) { invalid() }
    catch (_: JsonProcessingException) { invalid() }
    catch (_: CharacterCodingException) { invalid() }
    catch (_: BadInputException) { invalid() }
    catch (_: SkillOAuthValidationException) { invalid() }
    catch (failure: AttachmentException) {
        if (failure.code in attachmentInputErrors) throw PluginException(failure.code, "Plugin file request was rejected.")
        throw failure
    }
    catch (failure: BackendV1Exception) {
        if (failure.status.value >= 500) throw failure
        throw PluginException(failure.code, "Plugin service request was rejected.",
            if (failure.status.value == 401 || failure.status.value == 403) PluginFailureKind.AUTHORIZATION else PluginFailureKind.BUSINESS)
    }
    override fun close() = http.close()
    private companion object {
        val log = LoggerFactory.getLogger(PluginServices::class.java)
        const val OAUTH_BYTES = 36 * 1024 * 1024
        val methods = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        val reservedHeaders = setOf("host", "connection", "content-length", "transfer-encoding", "proxy-authorization")
        val attachmentInputErrors = setOf("attachment_not_found", "attachment_expired", "attachment_not_ready",
            "attachment_too_large", "attachment_idempotency_conflict", "invalid_attachment_object_key")
        fun invalid(): Nothing = throw PluginException("plugin_invalid_service_request", "Invalid plugin service input.")
        fun missing(name: String): Nothing = throw PluginException("plugin_service_unavailable", "$name is not configured.")
    }
}
