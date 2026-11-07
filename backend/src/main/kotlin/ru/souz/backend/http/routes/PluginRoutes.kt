package ru.souz.backend.http.routes

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.DeserializationFeature
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.openapi.JsonSchema
import io.ktor.openapi.JsonType
import io.ktor.openapi.Operation
import io.ktor.openapi.ReferenceOr
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readRemaining
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import org.slf4j.LoggerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import ru.souz.backend.http.describePublic
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.http.jsonBody
import ru.souz.backend.http.jsonResponse
import ru.souz.backend.http.v1ErrorResponses
import ru.souz.backend.plugins.PluginAdminStatus
import ru.souz.backend.plugins.PluginAdministration
import ru.souz.backend.plugins.PLUGIN_JAR_BYTES
import ru.souz.backend.plugins.PluginMutationAccepted
import ru.souz.backend.plugins.pluginHttpError
import ru.souz.llms.restJsonMapper

internal const val PLUGIN_ADMIN_SECURITY = "pluginAdminBearer"
internal const val PLUGIN_ADMIN_PATH = "/admin/v1/plugins"
private data class PluginRevisionRequest(val expectedRevision: Long)
private data class PluginSelectionRequest(val version: String, val expectedRevision: Long)

internal fun Route.pluginRoutes(service: PluginAdministration?) {
    get(PLUGIN_ADMIN_PATH) {
        call.respond(call.admin(service).status())
    }.describePublic("listPlugins", "Plugins", "List plugin registry and replica status") {
        adminSecurity()
        responses { jsonResponse<PluginAdminStatus>(HttpStatusCode.OK, "Desired state and this replica's applied state."); adminErrors() }
    }
    get("$PLUGIN_ADMIN_PATH/{id}") {
        call.respond(call.admin(service).status(call.parameters["id"].orEmpty()))
    }.describePublic("getPlugin", "Plugins", "Inspect a plugin") {
        adminSecurity(); pluginParameters()
        responses { jsonResponse<PluginAdminStatus>(HttpStatusCode.OK, "Desired state and this replica's applied state."); adminErrors() }
    }
    post("$PLUGIN_ADMIN_PATH/install") {
        val admin = call.admin(service)
        withPluginUploadFile { temporary ->
            val revision = withTimeoutOrNull(admin.config.uploadMillis) {
                try { call.uploadPlugin(temporary) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (known: BackendV1Exception) { throw known }
                catch (_: Exception) { throw pluginHttpError(400, "plugin_invalid_upload", "Malformed or interrupted multipart upload.") }
            }
                ?: throw pluginHttpError(408, "plugin_upload_timeout", "Plugin upload deadline exceeded.")
            call.respond(HttpStatusCode.Accepted, admin.install(temporary, revision))
        }
    }.describePublic("installPlugin", "Plugins", "Upload and select a plugin JAR",
        "Authenticates before receiving bytes. Stores an immutable JAR in S3 and commits desired state; replicas apply it asynchronously.") {
        adminSecurity()
        requestBody {
            required = true
            ContentType.MultiPart.FormData {
                schema = JsonSchema(type = JsonType.OBJECT, required = listOf("jar", "expectedRevision"), properties = mapOf(
                    "jar" to ReferenceOr.Value(JsonSchema(type = JsonType.STRING, format = "binary", description = "JAR, at most 64 MiB.")),
                    "expectedRevision" to ReferenceOr.Value(JsonSchema(type = JsonType.INTEGER, format = "int64", minimum = 0.0,
                        description = "Current plugin revision; zero for a new plugin.")),
                ))
            }
        }
        acceptedResponse()
    }
    put("$PLUGIN_ADMIN_PATH/{id}/selection") {
        val admin = call.admin(service)
        val body = call.pluginBody()
        val version = body["version"]?.takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()
            ?: throw pluginHttpError(400, "plugin_invalid_request", "version is required.")
        call.respond(HttpStatusCode.Accepted, admin.select(call.parameters["id"].orEmpty(), version, body.revision()))
    }.describePublic("selectPluginVersion", "Plugins", "Select or roll back a plugin version") {
        adminSecurity(); pluginParameters()
        requestBody { jsonBody<PluginSelectionRequest>() }
        acceptedResponse()
    }
    post("$PLUGIN_ADMIN_PATH/{id}/disable") {
        val admin = call.admin(service)
        call.respond(HttpStatusCode.Accepted, admin.disable(call.parameters["id"].orEmpty(), call.pluginBody().revision()))
    }.describePublic("disablePlugin", "Plugins", "Disable calls to all versions of a plugin") {
        adminSecurity(); pluginParameters()
        requestBody { jsonBody<PluginRevisionRequest>() }; acceptedResponse()
    }
    post("$PLUGIN_ADMIN_PATH/{id}/versions/{version}/retire") {
        val admin = call.admin(service)
        call.respond(HttpStatusCode.Accepted, admin.retire(call.parameters["id"].orEmpty(), call.parameters["version"].orEmpty(), call.pluginBody().revision()))
    }.describePublic("retirePluginVersion", "Plugins", "Retire a version while preserving pinned continuations") {
        adminSecurity(); pluginParameters(version = true)
        requestBody { jsonBody<PluginRevisionRequest>() }; acceptedResponse()
    }
    post("$PLUGIN_ADMIN_PATH/{id}/versions/{version}/reenable") {
        val admin = call.admin(service)
        call.respond(HttpStatusCode.Accepted, admin.reenable(call.parameters["id"].orEmpty(), call.parameters["version"].orEmpty(), call.pluginBody().revision()))
    }.describePublic("reenablePluginVersion", "Plugins", "Clear version quarantine and severe-incident count") {
        adminSecurity(); pluginParameters(version = true)
        requestBody { jsonBody<PluginRevisionRequest>() }; acceptedResponse()
    }
}

internal suspend fun <T> withPluginUploadFile(
    create: () -> Path = { staging { Files.createTempFile("souz-plugin-upload-", ".jar") } },
    block: suspend (Path) -> T,
): T {
    var temporary: Path? = null
    try {
        withContext(Dispatchers.IO) { temporary = create() }
        return block(requireNotNull(temporary))
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            temporary?.let { path ->
                try { Files.deleteIfExists(path) }
                catch (_: IOException) { LoggerFactory.getLogger("PluginUpload").warn("Temporary plugin upload cleanup failed.") }
            }
        }
    }
}

private fun ApplicationCall.admin(service: PluginAdministration?): PluginAdministration {
    val enabled = service ?: throw pluginHttpError(404, "plugin_admin_disabled", "Plugin administration is disabled.")
    enabled.authenticate(request.headers.getAll("Authorization")?.singleOrNull())
    return enabled
}

private suspend fun ApplicationCall.uploadPlugin(path: Path): Long {
    if (!request.contentType().match(ContentType.MultiPart.FormData)) throw pluginHttpError(400, "plugin_multipart_required", "Multipart upload is required.")
    if ((request.contentLength() ?: 0) > PLUGIN_JAR_BYTES.toLong() + 65_536) throw tooLarge()
    var foundJar = false
    var revision: Long? = null
    var parts = 0
    receiveMultipart(formFieldLimit = PLUGIN_JAR_BYTES.toLong() + 65_536).forEachPart { part ->
        try {
            if (++parts > 2) throw pluginHttpError(400, "plugin_invalid_upload", "Expected one jar and one expectedRevision field.")
            when {
                part is PartData.FileItem && part.name == "jar" && !foundJar -> {
                    foundJar = true
                    withContext(Dispatchers.IO) {
                        val output = staging { Files.newOutputStream(path) }
                        try {
                            val input = part.provider()
                            val buffer = ByteArray(8192)
                            var size = 0L
                            while (true) {
                                val count = input.readAvailable(buffer)
                                if (count < 0) break
                                size += count
                                if (size > PLUGIN_JAR_BYTES) throw tooLarge()
                                staging { output.write(buffer, 0, count) }
                            }
                        } finally { staging { output.close() } }
                    }
                }
                part is PartData.FormItem && part.name == "expectedRevision" && revision == null -> {
                    revision = part.value.takeIf { it.length <= 20 }?.toLongOrNull()?.takeIf { it >= 0 }
                        ?: throw pluginHttpError(400, "plugin_invalid_revision", "expectedRevision must be a nonnegative integer.")
                }
                else -> throw pluginHttpError(400, "plugin_invalid_upload", "Unexpected or duplicate multipart field.")
            }
        } finally { part.release() }
    }
    if (!foundJar || revision == null) throw pluginHttpError(400, "plugin_invalid_upload", "jar and expectedRevision are required.")
    return requireNotNull(revision)
}

private suspend fun ApplicationCall.pluginBody(): JsonNode {
    val bytes = withTimeoutOrNull(5_000) { receiveChannel().readRemaining(16_385).use { it.readByteArray() } }
        ?: throw pluginHttpError(408, "plugin_request_timeout", "Request body deadline exceeded.")
    if (bytes.size > 16_384) throw tooLarge()
    return try { restJsonMapper.readerFor(JsonNode::class.java).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .readValue<JsonNode>(bytes)?.takeIf(JsonNode::isObject) ?: throw IllegalArgumentException() }
    catch (_: Exception) { throw pluginHttpError(400, "plugin_invalid_request", "Expected a JSON object.") }
}

private fun JsonNode.revision(): Long = get("expectedRevision")?.takeIf { it.isIntegralNumber && it.canConvertToLong() }
    ?.asLong()?.takeIf { it >= 0 } ?: throw pluginHttpError(400, "plugin_invalid_revision", "expectedRevision must be a nonnegative integer.")

private fun tooLarge() = pluginHttpError(413, "plugin_upload_too_large", "Plugin request exceeds its size limit.")
private fun <T> staging(block: () -> T): T = try { block() }
catch (_: IOException) { throw pluginHttpError(503, "plugin_storage_unavailable", "Plugin staging storage is unavailable.") }
private fun Operation.Builder.adminSecurity() { security { requirement(PLUGIN_ADMIN_SECURITY) } }
private fun Operation.Builder.pluginParameters(version: Boolean = false) {
    parameters {
        path("id") { schema = JsonSchema(type = JsonType.STRING) }
        if (version) path("version") { schema = JsonSchema(type = JsonType.STRING) }
    }
}
private fun Operation.Builder.acceptedResponse() {
    responses { jsonResponse<PluginMutationAccepted>(HttpStatusCode.Accepted, "Desired state committed; rollout is asynchronous."); adminErrors() }
}
private fun io.ktor.openapi.Responses.Builder.adminErrors() = v1ErrorResponses(
    HttpStatusCode.BadRequest, HttpStatusCode.NotFound, HttpStatusCode.Conflict, HttpStatusCode.RequestTimeout,
    HttpStatusCode.PayloadTooLarge, HttpStatusCode.ServiceUnavailable,
)
