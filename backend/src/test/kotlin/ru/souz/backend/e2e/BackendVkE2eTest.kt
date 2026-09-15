package ru.souz.backend.e2e

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.vk.VkBotApi
import ru.souz.backend.vk.VkBotApiException
import ru.souz.backend.vk.VkGroup
import ru.souz.backend.vk.VkLongPollResponse
import ru.souz.backend.vk.VkLongPollServer
import ru.souz.backend.vk.VkLongPollUpdate
import ru.souz.backend.vk.VkMessage
import ru.souz.backend.vk.VkMessageObjectWrapper
import ru.souz.backend.vk.VkUser
import ru.souz.llms.LLMMessageRole

class BackendVkE2eTest {
    @Test
    fun `binding API links only a private account and replays turns without repeating execution`() {
        val vk = ScriptedVkApi()
        backendE2eTest("vk_workflow", featureFlags = BackendFeatureFlags(vkBot = true), vkApi = vk) {
            val user = UUID.randomUUID().toString()
            val chat = createPublicChat(user)
            val source = createPublicChat(user, "source")
            client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(user)
                jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}"}""")
            }
            for (token in listOf("", " ", "x".repeat(4097), "invalid")) {
                assertEquals(HttpStatusCode.BadRequest, bind(user, chat, token).status)
            }
            val response = bind(user, chat)
            assertEquals(HttpStatusCode.OK, response.status)
            val secret = response.jsonBody()["pendingLinkCommand"].asText()
            assertTrue(secret.isNotBlank())
            assertFalse(response.bodyAsText().contains("vk-token"))
            assertEquals(HttpStatusCode.Conflict, bind(user, source).status)
            assertEquals(HttpStatusCode.Conflict, bind(user, source, "other-token-for-same-group").status)
            for (method in listOf("GET", "PUT", "DELETE")) {
                val stranger = UUID.randomUUID().toString()
                val denied = when (method) {
                    "GET" -> client.get(BackendHttpRoutes.chatVkBot(chat)) { trusted(stranger) }
                    "PUT" -> bind(stranger, chat)
                    else -> client.delete(BackendHttpRoutes.chatVkBot(chat)) { trusted(stranger) }
                }
                assertEquals(HttpStatusCode.NotFound, denied.status)
            }
            val fetched = client.get(BackendHttpRoutes.chatVkBot(chat)) { trusted(user) }
            assertFalse(fetched.bodyAsText().contains(secret))
            assertFalse(fetched.bodyAsText().contains("vk-token"))
            assertTrue(stored(chat, "group_token_encrypted").startsWith("vkenc:v1:"))
            assertFalse(stored(chat, "link_secret_hash").contains(secret))

            vk.responses.add(VkLongPollResponse("2", listOf(
                update(1, secret, peer = 2_000_000_001),
                update(2, "wrong-secret"),
                update(3, secret, sender = -1),
            )))
            backend.pollVkOnce()
            assertFalse(binding(user, chat)["linked"].asBoolean())
            assertEquals(1, vk.sent.size)
            val link = update(4, secret)
            vk.responses.add(VkLongPollResponse("3", listOf(link)))
            backend.pollVkOnce()
            assertTrue(binding(user, chat)["linked"].asBoolean())
            assertEquals("Test", binding(user, chat)["vkFirstName"].asText())

            val turn = update(5, "VK question")
            vk.failSend = true
            vk.responses.add(VkLongPollResponse("4", listOf(link, turn)))
            backend.pollVkOnce()
            assertEquals("3", stored(chat, "last_ts"))
            vk.responses.add(VkLongPollResponse("4", listOf(link, turn)))
            backend.pollVkOnce()
            assertEquals("4", stored(chat, "last_ts"))
            assertEquals(1, llm.requests.count { it.conversationPrompt() == "VK question" })
            val messages = client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(user) }.jsonBody()["items"]
            assertEquals(listOf("VK question", messages.last()["content"].asText()), messages.map { it["content"].asText() })
            assertEquals(messages.last()["content"].asText(), vk.sent.last().second)

            vk.responses.add(VkLongPollResponse("5", listOf(update(6, "foreign", sender = 999), update(7, "foreign", sender = 999))))
            backend.pollVkOnce()
            assertEquals(1, vk.sent.count { it.first == 999L })
            assertEquals(1, llm.requests.count { it.conversationPrompt() == "VK question" })

            llm.requestSkillForPrompt("discover", "ListActiveChannels", emptyMap())
            runSkill(user, source, "discover")
            val channels = skillResult("discover")["channels"]
            assertEquals(setOf("vk:$chat", "public_client:$source"), channels.map {
                "${it["channelType"].asText()}:${it["channelId"].asText()}"
            }.toSet())
            llm.requestSkillForPrompt("forward", "SendMessageToChannel", mapOf("channelType" to "vk", "channelId" to chat, "text" to "forwarded"))
            runSkill(user, source, "forward")
            assertEquals("forwarded", vk.sent.last().second)
            assertEquals("forwarded", client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(user) }.jsonBody()["items"].last()["content"].asText())

            val replacement = bind(user, chat).jsonBody()["pendingLinkCommand"].asText()
            assertFalse(binding(user, chat)["linked"].asBoolean())
            assertTrue(replacement != secret)
            vk.responses.add(VkLongPollResponse("6", listOf(update(8, secret))))
            backend.pollVkOnce()
            assertFalse(binding(user, chat)["linked"].asBoolean())
            assertEquals(HttpStatusCode.OK, client.delete(BackendHttpRoutes.chatVkBot(chat)) { trusted(user) }.status)
            assertTrue(client.get(BackendHttpRoutes.chatVkBot(chat)) { trusted(user) }.jsonBody()["vkBot"].isNull)
        }
    }

    @Test
    fun `long poll recovers cursors and discards work after lease loss or rebind`() {
        val vk = ScriptedVkApi()
        backendE2eTest("vk_recovery", featureFlags = BackendFeatureFlags(vkBot = true), vkApi = vk) {
            val user = UUID.randomUUID().toString()
            val chat = createPublicChat(user)
            val secret = bind(user, chat).jsonBody()["pendingLinkCommand"].asText()
            for ((failed, expected) in listOf(1 to "7", 2 to "8", 3 to "100")) {
                vk.responses.add(VkLongPollResponse(ts = "7", failed = failed))
                vk.responses.add(VkLongPollResponse("8"))
                backend.pollVkOnce()
                assertEquals(expected, vk.cursors.last())
            }
            assertEquals(3, vk.negotiations)
            vk.onPoll = {
                sql { connection ->
                    connection.prepareStatement("update vk_bot_bindings set poller_owner = 'other' where chat_id = ?").use {
                        it.setObject(1, UUID.fromString(chat))
                        it.executeUpdate()
                    }
                }
            }
            vk.responses.add(VkLongPollResponse("9", listOf(update(1, secret))))
            backend.pollVkOnce()
            assertFalse(binding(user, chat)["linked"].asBoolean())
            assertEquals("8", stored(chat, "last_ts"))
            assertTrue(vk.sent.isEmpty())

            bind(user, chat)
            vk.onPoll = { bind(user, chat) }
            vk.responses.add(VkLongPollResponse("10", listOf(update(2, secret))))
            backend.pollVkOnce()
            assertFalse(binding(user, chat)["linked"].asBoolean())
            assertTrue(vk.sent.isEmpty())
        }
    }

    @Test
    fun `disabled VK routes and schema are absent`() = backendE2eTest("vk_disabled") {
        val path = BackendHttpRoutes.chatVkBot(UUID.randomUUID())
        assertEquals(HttpStatusCode.NotFound, client.get(path) { trusted(UUID.randomUUID().toString()) }.status)
        assertFalse(client.get(BackendHttpRoutes.OPENAPI_DOCUMENT).bodyAsText().contains("vk-bot"))
    }

    private suspend fun BackendE2eScope.bind(user: String, chat: String, token: String = "vk-token") =
        client.put(BackendHttpRoutes.chatVkBot(chat)) { trusted(user); jsonBody("""{"token":"$token"}""") }

    private suspend fun BackendE2eScope.binding(user: String, chat: String) =
        client.get(BackendHttpRoutes.chatVkBot(chat)) { trusted(user) }.jsonBody()["vkBot"]

    private fun BackendE2eScope.stored(chat: String, column: String): String = sql { connection ->
        connection.prepareStatement("select $column from vk_bot_bindings where chat_id = ?").use {
            it.setObject(1, UUID.fromString(chat))
            it.executeQuery().use { rows -> rows.next(); rows.getString(1) }
        }
    }

    private suspend fun BackendE2eScope.runSkill(user: String, chat: String, prompt: String) {
        val response = client.post(BackendHttpRoutes.chatMessages(chat)) {
            trusted(user)
            jsonBody("""{"content":"$prompt","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val thread = response.jsonBody()["execution"]["id"].asText()
        eventually("$prompt completion") {
            client.get("${BackendHttpRoutes.chatThread(chat, thread)}?clientType=backend")
                .jsonBody().takeIf { it["status"]?.asText() == "completed" }
        }
    }

    private fun BackendE2eScope.skillResult(prompt: String) = json.readTree(
        llm.requests.filter { it.conversationPrompt() == prompt }.flatMap { it.messages }
            .last { it.role == LLMMessageRole.function && it.name == "RunSkillCommand" }.content,
    )
}

private fun update(id: Long, text: String, sender: Long = 701, peer: Long = sender) =
    VkLongPollUpdate("message_new", VkMessageObjectWrapper(VkMessage(id, sender, peer, text)))

private class ScriptedVkApi : VkBotApi {
    val responses = ArrayDeque<VkLongPollResponse>()
    val cursors = mutableListOf<String>()
    val sent = mutableListOf<Pair<Long, String>>()
    var negotiations = 0
    var failSend = false
    var onPoll: (suspend () -> Unit)? = null
    override suspend fun getGroupInfo(groupToken: String): VkGroup {
        if (groupToken == "invalid") throw VkBotApiException(5)
        return VkGroup(123, "Test group")
    }
    override suspend fun getUserInfo(groupToken: String, userId: Long) = VkUser(userId, "Test", "User")
    override suspend fun getLongPollServer(groupToken: String, groupId: Long): VkLongPollServer {
        negotiations++
        return VkLongPollServer("key-$negotiations", "https://vk.test", "100")
    }
    override suspend fun pollLongPoll(server: String, key: String, ts: String, waitSeconds: Int): VkLongPollResponse {
        cursors += ts
        onPoll?.also { onPoll = null }?.invoke()
        return responses.removeFirstOrNull() ?: VkLongPollResponse(ts)
    }
    override suspend fun sendMessage(groupToken: String, peerId: Long, text: String) {
        if (failSend) { failSend = false; throw IOException("Failed send") }
        sent += peerId to text
    }
    override suspend fun setActivity(groupToken: String, peerId: Long, groupId: Long) = Unit
}
