package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import kotlin.test.assertEquals

internal suspend fun BackendE2eScope.subscribeClientChannels(socket: DefaultClientWebSocketSession, vararg channels: String) {
    for (channel in channels) {
        socket.send(Frame.Text("""{"kind":"chat.subscribe","chatId":"$channel","requestId":"subscribe-$channel","afterSeq":9223372036854775807}"""))
        assertEquals("accepted", readJson(socket)["status"].asText())
    }
}

internal suspend fun BackendE2eScope.submitClientToolTurn(
    socket: DefaultClientWebSocketSession, chat: String, owner: String, prompt: String,
    deviceId: String = "history-device",
): JsonNode {
    socket.send(Frame.Text(messageFrame(chat, owner, prompt, text = prompt, deviceId = deviceId)))
    assertEquals("accepted", readJson(socket)["status"].asText())
    assertEquals("thread.status", readJson(socket)["type"].asText())
    return readJson(socket)
}

internal suspend fun BackendE2eScope.answerClientTool(
    socket: DefaultClientWebSocketSession, started: JsonNode, result: String = """{"ok":true}""",
    errorCode: String? = null,
): JsonNode {
    assertEquals("tool.call.started", started["type"].asText(), started.toString())
    val outcome = if (errorCode == null) """"status":"succeeded","result":$result"""
        else """"status":"failed","error":{"code":"$errorCode","message":"Device failed"}"""
    socket.send(Frame.Text("""{"kind":"tool.result","chatId":${started["chatId"]},"threadId":${started["threadId"]},"toolCallId":${started["payload"]["toolCallId"]},$outcome}"""))
    assertEquals("accepted", readJson(socket)["status"].asText())
    return readJson(socket)
}

internal fun BackendE2eScope.clientToolOutput(): JsonNode =
    json.readTree(llm.requests.last().messages.last { it.name == "RunSkillCommand" }.content)
