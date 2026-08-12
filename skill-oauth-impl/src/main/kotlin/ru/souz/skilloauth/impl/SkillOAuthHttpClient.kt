package ru.souz.skilloauth.impl

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout

private const val SKILL_OAUTH_HTTP_CALL_TIMEOUT_MILLIS = 30_000L

/** Mirrors `McpHttpSession`'s HttpTimeout setup — without it, a stalled OAuth provider token
 *  endpoint or third-party API host hangs the calling coroutine indefinitely. Shared factory for
 *  [SkillOAuthGatewayImpl] and [AuthorizationCodeOAuthClient]'s default clients so the timeout config
 *  lives in exactly one place. */
fun defaultSkillOAuthHttpClient(): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = SKILL_OAUTH_HTTP_CALL_TIMEOUT_MILLIS
        connectTimeoutMillis = SKILL_OAUTH_HTTP_CALL_TIMEOUT_MILLIS
        socketTimeoutMillis = SKILL_OAUTH_HTTP_CALL_TIMEOUT_MILLIS
    }
}
