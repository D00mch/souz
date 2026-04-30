package ru.souz.backend.security

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import ru.souz.backend.http.BackendV1Exception

data class RequestIdentity(
    val userId: String,
)

class RequestIdentityPluginConfig {
    var trustedProxyToken: () -> String? = { null }
    var protectedPathPrefix: String = "/v1/"
}

val RequestIdentityPlugin = createApplicationPlugin(
    name = "RequestIdentityPlugin",
    createConfiguration = ::RequestIdentityPluginConfig,
) {
    onCall { call ->
        if (!call.request.path().startsWith(pluginConfig.protectedPathPrefix)) {
            return@onCall
        }
        val identity = resolveRequestIdentity(call, pluginConfig.trustedProxyToken())
        call.attributes.put(RequestIdentityAttributeKey, identity)
    }
}

fun ApplicationCall.requestIdentity(): RequestIdentity =
    attributes.getOrNull(RequestIdentityAttributeKey)
        ?: throw BackendV1Exception(
            status = HttpStatusCode.InternalServerError,
            code = "internal_error",
            message = "Trusted request identity is unavailable.",
        )

private fun resolveRequestIdentity(
    call: ApplicationCall,
    trustedProxyToken: String?,
): RequestIdentity {
    val configuredToken = trustedProxyToken?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw BackendV1Exception(
            status = HttpStatusCode.InternalServerError,
            code = "backend_misconfigured",
            message = "Trusted proxy token is not configured for /v1 routes.",
        )
    val actualProxyToken = call.request.header(PROXY_AUTH_HEADER)?.trim()
    if (actualProxyToken != configuredToken) {
        throw BackendV1Exception(
            status = HttpStatusCode.Unauthorized,
            code = "untrusted_proxy",
            message = "Trusted proxy authentication is required.",
        )
    }
    val userId = call.request.header(USER_ID_HEADER)?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: throw BackendV1Exception(
            status = HttpStatusCode.Unauthorized,
            code = "missing_user_identity",
            message = "Trusted user identity is required.",
        )
    return RequestIdentity(userId = userId)
}

private val RequestIdentityAttributeKey = AttributeKey<RequestIdentity>("requestIdentity")

private const val USER_ID_HEADER = "X-User-Id"
private const val PROXY_AUTH_HEADER = "X-Souz-Proxy-Auth"
