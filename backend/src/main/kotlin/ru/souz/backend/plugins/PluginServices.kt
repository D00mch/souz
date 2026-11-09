package ru.souz.backend.plugins

import com.fasterxml.jackson.core.JsonProcessingException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.souz.backend.attachments.*
import ru.souz.backend.channels.ChannelProviderRegistry
import ru.souz.backend.channels.ChannelSendResult
import ru.souz.backend.crypto.sha256Hex
import ru.souz.backend.http.BackendV1Exception
import ru.souz.plugins.api.*
import ru.souz.skilloauth.*
import ru.souz.tool.BadInputException

/** Services and transport are process-owned; contexts and all identities are invocation-bound. */
internal class PluginServices(
    private val repository: PostgresPluginRepository,
    private val oauth: SkillOAuthGateway?,
    private val channels: ChannelProviderRegistry,
    private val storage: AttachmentStorage?,
) : AutoCloseable {
    private val http = PluginHttpClient()
    fun context(lease: PluginCallLease, identity: PluginInvocationMeta, checkActive: suspend () -> Unit): PluginContext {
        require(identity.userId == lease.ownerId)
        val partition = repository.state(lease)
        suspend fun active() { checkActive(); repository.assertOwned(lease); checkActive() }
        return object : PluginContext {
            override val meta = identity
            override val state = object : PluginState {
                override suspend fun get(key: String): String? = service { checkActive(); partition.get(key) }
                override suspend fun put(key: String, json: String) = service { checkActive(); partition.put(key, json) }
                override suspend fun delete(key: String): Boolean = service { checkActive(); partition.delete(key) }
                override suspend fun list(after: String?, limit: Int): PluginStatePage = service { checkActive(); partition.list(after, limit) }
            }
            override val http = PluginHttp { request -> service { active(); this@PluginServices.http.execute(request) } }
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

    private fun StoredAttachment.descriptor() = PluginFile(id.toString(), name, mimeType, sizeBytes)
    private suspend fun <T> service(block: suspend () -> T): T = try { block() }
    catch (_: IllegalArgumentException) { invalid() }
    catch (_: JsonProcessingException) { invalid() }
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
        val log: Logger = LoggerFactory.getLogger(PluginServices::class.java)
        const val OAUTH_BYTES = 36 * 1024 * 1024
        val attachmentInputErrors = setOf("attachment_not_found", "attachment_expired", "attachment_not_ready",
            "attachment_too_large", "attachment_idempotency_conflict", "invalid_attachment_object_key")
        fun invalid(): Nothing = throw PluginException("plugin_invalid_service_request", "Invalid plugin service input.")
        fun missing(name: String): Nothing = throw PluginException("plugin_service_unavailable", "$name is not configured.")
    }
}
