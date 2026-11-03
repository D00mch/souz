package ru.souz.plugins.gmail

import com.fasterxml.jackson.databind.JsonNode
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import ru.souz.plugins.api.PluginOAuth
import ru.souz.plugins.api.PluginOAuthRequest
import ru.souz.plugins.api.PluginOAuthResponse
import ru.souz.plugins.api.PluginReconnectRequired

internal class GmailApiFailure(val status: Int? = null, val authorizationUrl: String? = null) : RuntimeException()

internal class GmailApi(private val oauth: PluginOAuth) {
    suspend fun request(path: String, query: List<Pair<String, String>> = emptyList(), body: JsonNode? = null): JsonNode {
        val suffix = if (query.isEmpty()) "" else query.joinToString("&", prefix = "?") {
            "${URLEncoder.encode(it.first, UTF_8)}=${URLEncoder.encode(it.second, UTF_8)}"
        }
        return when (val response = oauth.call("google", setOf("https://www.googleapis.com/auth/gmail.readonly"), PluginOAuthRequest(
            if (body == null) "GET" else "POST", "https://www.googleapis.com/gmail/v1/users/me$path$suffix", body?.toString(),
            maxResponseBytes = 36 * 1024 * 1024,
        ))) {
            is PluginReconnectRequired -> throw GmailApiFailure(authorizationUrl = response.authorizationUrl)
            is PluginOAuthResponse -> {
                if (response.statusCode != 200) throw GmailApiFailure(status = response.statusCode)
                requireNotNull(json.readTree(response.body))
            }
        }
    }

    suspend fun message(id: String, full: Boolean): JsonNode = request("/messages/${messageId(id)}",
        if (full) listOf("format" to "full") else listOf("format" to "metadata") + listOf("From", "Subject", "Date").map { "metadataHeaders" to it })
}
