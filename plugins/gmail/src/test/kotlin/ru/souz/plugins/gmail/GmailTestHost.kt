package ru.souz.plugins.gmail

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.plugins.*

internal class GmailTestHost : PluginCallContext {
    val records = mutableMapOf<String, JsonNode>()
    val texts = mutableMapOf<String, String>()
    val saved = mutableListOf<Pair<String, ByteArray>>()
    val requests = mutableListOf<PluginHttpRequest>()
    val sent = mutableListOf<Triple<String, String, String>>()
    var respond: suspend (PluginHttpRequest) -> PluginOAuthResponse = { request ->
        PluginOAuthResponse(200, when {
            request.url.endsWith("/profile") -> """{"emailAddress":"owner@example.com","historyId":"100"}"""
            "/history?" in request.url -> """{"historyId":"101","history":[{"messagesAdded":[{"message":{"id":"m1"}}]}]}"""
            request.url.endsWith("/watch") -> """{"historyId":"999","expiration":"9999999999999"}"""
            else -> mail().toString()
        })
    }
    var send: suspend () -> Boolean = { true }
    var updateFailure: RuntimeException? = null
    override val data = object : PluginData {
        private val mutex = Mutex()
        override suspend fun get(key: String) = mutex.withLock { records[key]?.deepCopy<JsonNode>() }
        override suspend fun put(key: String, value: JsonNode) { update(key) { value } }
        override suspend fun update(key: String, transform: (JsonNode?) -> JsonNode): JsonNode = mutex.withLock {
            updateFailure?.let { throw it }
            transform(records[key]?.deepCopy<JsonNode>()).also { records[key] = it.deepCopy<JsonNode>() }.deepCopy()
        }
        override suspend fun delete(key: String) = mutex.withLock { records.remove(key) != null }
        override suspend fun list(after: String?, limit: Int) = PluginDataPage(emptyList(), null)
    }
    override val oauth = PluginOAuth { provider, scopes, request ->
        check(provider == "google" && scopes == setOf("https://www.googleapis.com/auth/gmail.readonly"))
        requests += request
        respond(request)
    }
    override val http = PluginHttp { error("Gmail must use host OAuth") }
    override val logger = PluginLogger { }
    override val files = object : PluginFiles {
        override suspend fun readText(path: String) = texts[path]
        override suspend fun save(extension: String, content: ByteArray): String {
            saved += extension to content
            return "/sandbox/attachment.$extension"
        }
    }
    override val channels = PluginChannels { type, id, text -> sent += Triple(type, id, text); send() }

    suspend fun call(name: String, vararg values: Pair<String, Any>): JsonNode = GmailPlugin().tools()
        .single { it.definition.name == name }.invoke(json.valueToTree<ObjectNode>(values.toMap()), this)

    suspend fun configure(mode: String = "assistant") = call("configure", "config" to "personal", "mailboxEmail" to "owner@example.com",
        "topicName" to "projects/demo/topics/gmail", "channelType" to "telegram", "channelId" to "owned", "notificationMode" to mode)

    fun state(): ObjectNode = records.getValue("connection:personal")["state"] as ObjectNode
    suspend fun claim(): JsonNode = call("sync", "config" to "personal")["message"]
    suspend fun deliver(claim: JsonNode, channel: String = "owned") = call("deliver", "config" to "personal",
        "message" to claim["message"].asText(), "claim" to claim["claim"].asText(), "channelType" to "telegram", "channelId" to channel, "text" to "A notification")
}

internal fun mail(body: String = "Plain email"): ObjectNode = json.readTree("""{
  "id":"m1","labelIds":["INBOX"],"internalDate":"9999999999999","snippet":"snippet",
  "payload":{"mimeType":"text/plain","headers":[{"name":"From","value":"Sender"},{"name":"Subject","value":"Subject"}],
    "body":{"data":"${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray())}"}}
}""") as ObjectNode
