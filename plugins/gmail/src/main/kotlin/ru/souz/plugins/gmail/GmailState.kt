package ru.souz.plugins.gmail

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

internal fun key(config: String): String {
    require(config.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))) { "Invalid connection name" }
    return "connection:$config"
}

internal fun validateConnection(connection: ObjectNode) {
    require(connection.text("mailboxEmail").length <= 320 && '@' in connection.text("mailboxEmail"))
    require(connection.text("topicName").matches(Regex("projects/[^/\\s]{1,128}/topics/[^/\\s]{1,128}")))
    require(connection.path("notificationMode").asText("signal") in setOf("signal", "subject", "summary", "assistant"))
    val destination = connection["destination"] as? ObjectNode ?: error("Missing destination")
    require(destination.text("channelType").length <= 64 && destination.text("channelId").length <= 256)
}

internal fun entry(connection: ObjectNode, state: ObjectNode): ObjectNode = json.createObjectNode().apply {
    set<JsonNode>("connection", json.createObjectNode().apply {
        put("mailboxEmail", connection.text("mailboxEmail"))
        put("topicName", connection.text("topicName"))
        put("notificationMode", connection.path("notificationMode").asText("signal"))
        set<JsonNode>("destination", connection["destination"].deepCopy<JsonNode>())
    })
    set<JsonNode>("state", state)
}

internal fun newState(mailbox: String): ObjectNode = json.createObjectNode().apply {
    put("schemaVersion", 1); put("revision", 0); put("mailboxEmail", mailbox); put("status", "needs_profile")
    putObject("pending"); putArray("done")
}

internal fun validateState(state: ObjectNode, connection: ObjectNode) {
    require(state.path("schemaVersion").asInt() == 1 && state.text("mailboxEmail").equals(connection.text("mailboxEmail"), ignoreCase = true))
    require(state.text("status") in setOf("needs_profile", "ready", "account_mismatch", "resync_required", "reconnect_required"))
    require(state.path("revision").isIntegralNumber && state.path("revision").asLong() >= 0)
    require(state.path("pending").isObject && state.path("pending").size() <= 500)
    require(state.path("done").isArray)
    state.path("historyCursor").takeUnless { it.isMissingNode || it.isNull }?.let {
        historyId(it.asText()); require(state.path("startedAtMillis").asLong() > 0)
    }
    require(state.text("status") != "ready" || state.path("historyCursor").isTextual)
    state.path("pending").properties().forEach { (id, item) ->
        messageId(id)
        require(item.isObject && item.path("status").asText() in setOf("pending", "claimed", "delivering", "uncertain"))
        if (item.path("status").asText() != "pending") require(item.path("claim").isTextual && item.path("expiresAt").isNumber)
    }
    state.path("done").forEach { messageId(it.asText()) }
}

internal fun claimed(state: ObjectNode, message: String, claim: String): ObjectNode {
    if (state.text("status") != "ready") throw GmailResult(result(state.text("status")))
    val item = state.path("pending").path(message)
    require(item is ObjectNode && item.path("status").asText() == "claimed" && item.path("claim").asText() == claim) { "Claim is not active" }
    return item
}

internal fun finish(state: ObjectNode, message: String) {
    (state["pending"] as ObjectNode).remove(message)
    val done = state["done"] as ArrayNode
    done.add(message)
}

internal fun messageQuery(full: Boolean): List<Pair<String, String>> = if (full) listOf("format" to "full")
    else listOf("format" to "metadata") + listOf("From", "Subject", "Date").map { "metadataHeaders" to it }
