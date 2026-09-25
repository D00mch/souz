package ru.souz.backend.http.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.JsonSchema
import io.ktor.openapi.JsonType
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import ru.souz.backend.hooks.*
import ru.souz.backend.http.*

internal fun Route.hookRoutes(service: HookService) {
    val intake = Semaphore(16)
    post("/hooks/{hookId}") {
        if (!intake.tryAcquire()) throw hookError(429, "hook_ingress_limit")
        try {
            val authorization = call.request.headers.getAll("Authorization")?.singleOrNull()
            val definition = service.resolveForRequest(call.parameters["hookId"].orEmpty(), authorization)
            val isBearer = definition.definition.auth != null
            if (isBearer && !call.request.contentType().match(ContentType.Application.Json)) throw hookError(400, "hook_json_required")
            val headers = call.request.headers.entries().associate { it.key.lowercase() to it.value }
            if (headers.size > 32 || headers.entries.sumOf { (name, values) -> name.length + values.sumOf(String::length) } > 16_384) {
                throw hookError(400, "hook_headers_too_large")
            }
            val body = withTimeoutOrNull(5_000) {
                val channel = call.receiveChannel()
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = channel.readAvailable(buffer)
                    if (count == -1) break
                    if (bytes.size() + count > HookDefinitions.MAX_BODY_BYTES) throw hookError(413, "hook_payload_too_large")
                    bytes.write(buffer, 0, count)
                }
                bytes.toByteArray()
            } ?: throw hookError(408, "hook_body_timeout")
            val keys = call.request.headers.getAll("Idempotency-Key")
            if (isBearer && keys != null && keys.size != 1) throw hookError(400, "invalid_idempotency_key")
            val accepted = service.accept(definition, HookRequest(
                call.request.local.method.value, call.request.path(),
                headers.filterKeys { it !in setOf("host", "x-user-id", "x-souz-proxy-auth") }, body,
            ), keys?.singleOrNull())
            call.respond(if (accepted.duplicate) HttpStatusCode.OK else HttpStatusCode.Accepted, accepted)
        } catch (_: java.sql.SQLException) {
            throw hookError(503, "hook_storage_unavailable")
        } finally {
            intake.release()
        }
    }.describePublic("invokeHook", "Hooks", "Invoke a workspace hook", "Authenticates with the configured Bearer secret or verifier in the owner's sandbox before admission. Verifiers receive raw body bytes and supply the event ID. 202 means persisted, not completed.") {
        security { requirement("hookBearer"); requirement(emptyMap()) }
        parameters {
            path("hookId") { schema = JsonSchema(type = JsonType.STRING) }
            header("Idempotency-Key") { required = false; schema = JsonSchema(type = JsonType.STRING, maxLength = 200) }
        }
        requestBody { jsonBody<Map<String, Any?>>("External JSON event, at most 64 KiB; interpreted as untrusted data.") }
        responses {
            jsonResponse<HookAccepted>(HttpStatusCode.Accepted, "Durable receipt.")
            jsonResponse<HookAccepted>(HttpStatusCode.OK, "Previously accepted receipt.")
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.NotFound, HttpStatusCode.Conflict, HttpStatusCode.RequestTimeout, HttpStatusCode.PayloadTooLarge, HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable)
        }
    }
    post("/v1/hooks/reload") {
        call.respond(mapOf("hookIds" to service.reload(call.requireUserIdFromTrustedProxy())))
    }.describeV1("reloadHooks", "Hooks", "Reload owned workspace hooks", "Replaces this allowed owner's snapshot; invalid files disable the owner's hooks. No files are created.") {
        responses {
            jsonResponse<Map<String, List<String>>>(HttpStatusCode.OK, "Enabled, unambiguous hook IDs.")
            v1ErrorResponses(HttpStatusCode.Forbidden)
        }
    }
    get("/v1/hooks/receipts/{receiptId}") {
        val id = runCatching { UUID.fromString(call.parameters["receiptId"]) }.getOrNull()
            ?: throw hookError(400, "invalid_hook_receipt_id")
        call.respond(service.status(call.requireUserIdFromTrustedProxy(), id))
    }.describeV1("getHookReceipt", "Hooks", "Get an owned hook result", "Returns status, execution (including chatId for events, options and cancellation), usage and final response. Delivery is recorded in the execution's tool events.") {
        parameters { uuidPathParameter("receiptId", "Receipt UUID returned by invokeHook.") }
        responses {
            jsonResponse<HookStatus>(HttpStatusCode.OK, "Owned receipt and execution result.")
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.NotFound)
        }
    }
}
