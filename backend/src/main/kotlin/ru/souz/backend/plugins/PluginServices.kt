package ru.souz.backend.plugins

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.util.UUID
import com.fasterxml.jackson.core.JsonProcessingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.backend.attachments.AttachmentStorage
import ru.souz.backend.attachments.AttachmentException
import ru.souz.backend.attachments.StoredAttachment
import ru.souz.backend.attachments.attachmentId
import ru.souz.backend.channels.ChannelProviderRegistry
import ru.souz.backend.channels.ChannelSendResult
import ru.souz.backend.crypto.sha256Hex
import ru.souz.backend.http.BackendV1Exception
import ru.souz.llms.ToolInvocationMeta
import ru.souz.plugins.api.*
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.skilloauth.ApiCallReconnectRequired
import ru.souz.skilloauth.ApiCallRequest
import ru.souz.skilloauth.ApiCallResponse
import ru.souz.skilloauth.SkillOAuthGateway
import ru.souz.skilloauth.SkillOAuthValidationException
import ru.souz.tool.BadInputException

/** Host adapters never let plugin arguments choose an owner, OAuth identity or state partition. */
internal class PluginServices(
    private val repository: PostgresPluginRepository,
    private val http: PluginHttpClient,
    private val oauth: SkillOAuthGateway?,
    private val channels: ChannelProviderRegistry,
    private val sandboxes: ToolInvocationRuntimeSandboxResolver,
    private val storage: AttachmentStorage?,
) {
    fun context(lease: PluginCallLease, identity: PluginInvocationMeta, checkActive: suspend () -> Unit): PluginContext {
        require(identity.userId == lease.ownerId)
        suspend fun active() {
            checkActive()
            repository.assertOwned(lease)
            checkActive()
        }
        val partition = repository.state(lease)
        val invocation = ToolInvocationMeta(identity.userId, identity.conversationId, identity.requestId, identity.locale, identity.timeZone)
        return object : PluginContext {
            override val meta = identity
            override val state = object : PluginState {
                override suspend fun get(key: String): String? = serviceCall { checkActive(); partition.get(key) }
                override suspend fun put(key: String, json: String) = serviceCall { checkActive(); partition.put(key, json) }
                override suspend fun delete(key: String): Boolean = serviceCall { checkActive(); partition.delete(key) }
                override suspend fun list(after: String?, limit: Int): PluginStatePage = serviceCall { checkActive(); partition.list(after, limit) }
            }
            override val http = PluginHttp { request -> serviceCall { active(); this@PluginServices.http.execute(request) } }
            override val oauth = PluginOAuth { provider, scopes, request -> serviceCall {
                active()
                require((request.body?.toByteArray()?.size ?: 0) <= JSON_BYTES)
                require(request.url.length <= 8192 && request.headers.size <= 64 &&
                    request.headers.entries.sumOf { (key, value) -> key.length + value.length } <= 16_384)
                val gateway = this@PluginServices.oauth ?: unavailable("OAuth")
                when (val result = gateway.call(lease.ownerId, provider, scopes, ApiCallRequest(
                    request.method, request.url, request.body, request.headers.toMap(),
                    maxResponseBytes = minOf(request.maxResponseBytes ?: OAUTH_BYTES, OAUTH_BYTES),
                ))) {
                    is ApiCallResponse -> PluginOAuthResponse(result.statusCode, result.body, result.headers.toMap())
                    is ApiCallReconnectRequired -> PluginReconnectRequired(result.authorizationUrl, result.message)
                }
            } }
            override val channels = object : PluginChannels {
                override suspend fun send(type: String, id: String, text: String, fileIds: List<String>): Boolean = serviceCall {
                    active()
                    require(type.isNotBlank() && id.isNotBlank() && text.toByteArray().size <= JSON_BYTES && fileIds.size <= 32)
                    this@PluginServices.channels.send(lease.ownerId, type, id, text, fileIds.toList()) is ChannelSendResult.Delivered
                }
            }
            override val files = object : PluginFiles {
                override suspend fun readWorkspaceText(path: String): String? = serviceCall {
                    active()
                    runInterruptible(Dispatchers.IO) {
                        val fs = sandboxes.resolve(invocation).fileSystem
                        val file = fs.resolvePath(path)
                        require(fs.isPathSafe(file) && !file.isSymbolicLink) { "Unsafe plugin input path." }
                        if (!file.exists) return@runInterruptible null
                        require(file.isRegularFile && (file.sizeBytes ?: Long.MAX_VALUE) <= JSON_BYTES)
                        val bytes = fs.openInputStream(file).use { it.readNBytes(JSON_BYTES + 1) }
                        require(bytes.size <= JSON_BYTES)
                        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                    }
                }
                override suspend fun saveWorkspace(extension: String, content: ByteArray): String = serviceCall {
                    active()
                    require(extension.matches(Regex("[a-z0-9]{1,12}")) && content.size <= 25 * JSON_BYTES)
                    var created: Pair<SandboxFileSystem, SandboxPathInfo>? = null
                    var delivered = false
                    try {
                        val result = runInterruptible(Dispatchers.IO) {
                            val fs = sandboxes.resolve(invocation).fileSystem
                            val root = fs.resolvePath("${fs.runtimePaths.homePath.trimEnd('/')}/Documents/souz/plugin-files/${lease.pluginId}/${UUID.randomUUID()}")
                            require(fs.isPathSafe(root) && !root.exists)
                            created = fs to root
                            fs.createDirectory(root)
                            val file = fs.resolvePath("${root.path}/attachment.$extension")
                            fs.writeBytes(file, content)
                            file.path
                        }
                        delivered = true
                        result
                    } finally {
                        if (!delivered) withContext(NonCancellable + Dispatchers.IO) {
                            created?.let { (fs, root) ->
                                try { fs.delete(fs.resolvePath(root.path), recursively = true) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { logger.warn("Plugin workspace file cleanup failed plugin={}", lease.pluginId) }
                            }
                        }
                    }
                }
                override suspend fun loadStored(fileId: String): PluginFileContent = serviceCall {
                    active()
                    val (file, bytes) = (storage ?: unavailable("File storage")).load(lease.ownerId, attachmentId(fileId))
                    PluginFileContent(file.descriptor(), bytes)
                }
                override suspend fun saveStored(name: String, content: ByteArray): PluginFile = serviceCall {
                    active()
                    val nameHash = name.toByteArray().sha256Hex()
                    (storage ?: unavailable("File storage")).save(lease.ownerId, name, content.inputStream()) { hash ->
                        "plugin:${lease.pluginId}:${lease.token}:$nameHash:$hash"
                    }.descriptor()
                }
            }
            override suspend fun log(message: String) {
                active()
                logger.atInfo().addKeyValue("plugin", lease.pluginId)
                    .addKeyValue("request_id", identity.requestId?.take(100))
                    .addKeyValue("invocation", lease.token.toString())
                    .log("Plugin: {}", message.replace(Regex("[\\r\\n]"), " ").take(1_000))
            }
        }
    }

    private fun StoredAttachment.descriptor() = PluginFile(id.toString(), name, mimeType, sizeBytes)
    private suspend fun <T> serviceCall(block: suspend () -> T): T = try { block() }
    catch (_: IllegalArgumentException) { invalidServiceInput() }
    catch (_: JsonProcessingException) { invalidServiceInput() }
    catch (_: CharacterCodingException) { invalidServiceInput() }
    catch (_: BadInputException) { invalidServiceInput() }
    catch (_: SkillOAuthValidationException) { invalidServiceInput() }
    catch (known: AttachmentException) {
        when (known.code) {
            "attachment_not_found", "attachment_expired", "attachment_not_ready", "attachment_too_large",
            "attachment_idempotency_conflict", "invalid_attachment_object_key" ->
                throw PluginException(known.code, "Plugin file request was rejected.")
            else -> throw known
        }
    }
    catch (known: BackendV1Exception) {
        if (known.status.value >= 500) throw known
        throw PluginException(known.code, "Plugin service request was rejected.",
            if (known.status.value == 401 || known.status.value == 403) PluginFailureKind.AUTHORIZATION else PluginFailureKind.BUSINESS)
    }
    private fun invalidServiceInput(): Nothing = throw PluginException("plugin_invalid_service_request", "Invalid plugin service input.")
    private fun unavailable(service: String): Nothing = throw PluginException("plugin_service_unavailable", "$service is not configured.")

    private companion object {
        const val JSON_BYTES = 1_048_576
        const val OAUTH_BYTES = 36 * JSON_BYTES
        val logger = LoggerFactory.getLogger(PluginServices::class.java)
    }
}
