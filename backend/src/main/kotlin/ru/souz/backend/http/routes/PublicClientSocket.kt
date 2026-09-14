package ru.souz.backend.http.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.hide
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.ExperimentalKtorApi
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.backend.client.ClientContractException
import ru.souz.backend.client.supportedClientTypes
import ru.souz.backend.common.backendLogContext
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.http.queryNonNegativeLong
import ru.souz.backend.http.requireChatId

@OptIn(ExperimentalKtorApi::class, ExperimentalCoroutinesApi::class)
internal fun Route.publicClientSocket(path: String, deps: BackendHttpDependencies, singleChat: Boolean) {
    get(path) { call.respond(HttpStatusCode.BadRequest) }.hide()
    webSocket(path) {
        val socketId = UUID.randomUUID().toString()
        val clientType = call.request.queryParameters["clientType"]
        withContext(backendLogContext("socketId" to socketId, "clientType" to clientType)) {
            socketLogger.info("WebSocket connected route={}", path)
            try {
                if (!deps.featureFlags.wsEvents) {
                    socketLogger.warn("WebSocket rejected closeCode=1013 reason=feature_disabled")
                    close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "WebSocket feature is disabled."))
                    return@withContext
                }
                val allowedTypes = if (singleChat) supportedClientTypes else setOf("backend")
                if (clientType == null || clientType !in allowedTypes) {
                    socketLogger.warn("WebSocket rejected closeCode=1008 reason=invalid_client_type")
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "clientType must be ${allowedTypes.joinToString(" or ")}."))
                    return@withContext
                }
                val chat = try {
                    if (singleChat) deps.publicClientService.requireChat(call.requireChatId(), clientType) else null
                } catch (error: ClientContractException) {
                    socketLogger.warn("WebSocket rejected closeCode=1008 reason={}", error.code)
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, error.message))
                    return@withContext
                }
                val afterSeq = if (singleChat) call.queryNonNegativeLong("afterSeq") ?: 0L else 0L
                PublicClientConnection(this@webSocket, deps, clientType, chat, socketId).run(afterSeq)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                socketLogger.error("WebSocket failed route=$path", failure)
                throw failure
            } finally {
                val reason = if (closeReason.isCompleted) runCatching { closeReason.getCompleted() }.getOrNull() else null
                socketLogger.info("WebSocket ended closeCode={} active={}", reason?.code, isActive)
            }
        }
    }.hide()
}

private val socketLogger = LoggerFactory.getLogger("SouzClientWebSocket")
