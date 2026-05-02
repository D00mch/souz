package ru.souz.backend.http

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import ru.souz.agent.AgentId
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.runtime.BackendConversationRuntimeFactory
import ru.souz.backend.agent.session.AgentConversationState
import ru.souz.backend.agent.session.AgentStateBackedSessionRepository
import ru.souz.backend.bootstrap.BackendBootstrapService
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.chat.service.ChatService
import ru.souz.backend.chat.service.MessageService
import ru.souz.backend.options.repository.OptionRepository
import ru.souz.backend.options.service.OptionService
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.agent.runtime.BackendConversationRuntimeTurnRunner
import ru.souz.backend.agent.runtime.BackendConversationTurnRunner
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.service.AgentExecutionFinalizer
import ru.souz.backend.execution.service.AgentExecutionLauncher
import ru.souz.backend.execution.service.AgentExecutionRequestFactory
import ru.souz.backend.execution.service.AgentExecutionService
import ru.souz.backend.keys.service.UserProviderKeyService
import ru.souz.backend.settings.model.UserSettings
import ru.souz.backend.settings.repository.UserSettingsRepository
import ru.souz.backend.settings.service.EffectiveSettingsResolver
import ru.souz.backend.settings.service.UserSettingsService
import ru.souz.backend.storage.StorageMode
import ru.souz.backend.storage.memory.MemoryAgentExecutionRepository
import ru.souz.backend.storage.memory.MemoryAgentEventRepository
import ru.souz.backend.storage.memory.MemoryAgentStateRepository
import ru.souz.backend.storage.memory.MemoryChatRepository
import ru.souz.backend.storage.memory.MemoryOptionRepository
import ru.souz.backend.storage.memory.MemoryMessageRepository
import ru.souz.backend.storage.memory.MemoryToolCallRepository
import ru.souz.backend.storage.memory.MemoryUserRepository
import ru.souz.backend.storage.memory.MemoryUserProviderKeyRepository
import ru.souz.backend.storage.memory.MemoryUserSettingsRepository
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.LocalModelAvailability
import ru.souz.llms.VoiceRecognitionModel
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolCategory
import ru.souz.tool.ToolSetup

class BackendStage3RouteTest {
    private val json = jacksonObjectMapper()

    @Test
    fun `settings routes require trusted proxy headers`() = testApplication {
        val context = routeTestContext()
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
            )
        }

        val response = client.get(BackendHttpRoutes.SETTINGS)
        val payload = json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("untrusted_proxy", payload["error"]["code"].asText())
    }

    @Test
    fun `get me settings resolves effective settings for opaque user identity`() = testApplication {
        val context = routeTestContext()
        runBlocking {
            context.userSettingsRepository.save(
                UserSettings(
                    userId = "user-opaque-42",
                    defaultModel = LLMModel.QwenMax,
                    contextSize = 12_000,
                    temperature = 0.15f,
                    locale = Locale.forLanguageTag("en-US"),
                    timeZone = ZoneId.of("Europe/Amsterdam"),
                    systemPrompt = "be brief",
                    enabledTools = setOf("ListFiles"),
                    showToolEvents = false,
                    streamingMessages = false,
                )
            )
        }
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
            )
        }

        val response = client.get(BackendHttpRoutes.SETTINGS) {
            trustedHeaders("user-opaque-42")
        }
        val settings = json.readTree(response.bodyAsText())["settings"]

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(LLMModel.QwenMax.alias, settings["defaultModel"].asText())
        assertEquals(12_000, settings["contextSize"].asInt())
        assertEquals(0.15, settings["temperature"].asDouble())
        assertEquals("en-US", settings["locale"].asText())
        assertEquals("Europe/Amsterdam", settings["timeZone"].asText())
        assertEquals("be brief", settings["systemPrompt"].asText())
        assertEquals(listOf("ListFiles"), settings["enabledTools"].map { it.asText() })
        assertEquals(false, settings["showToolEvents"].asBoolean())
        assertEquals(false, settings["streamingMessages"].asBoolean())
    }

    @Test
    fun `patch me settings stores user intent and returns normalized effective settings`() = testApplication {
        val context = routeTestContext()
        context.settingsProvider.qwenChatKey = null
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
            )
        }

        val response = client.patch(BackendHttpRoutes.SETTINGS) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "defaultModel": "${LLMModel.QwenMax.alias}",
                  "contextSize": 12000,
                  "temperature": 0.15,
                  "locale": "en-US",
                  "timeZone": "Europe/Amsterdam",
                  "systemPrompt": "be brief",
                  "enabledTools": ["ListFiles", "OpenBrowser"],
                  "showToolEvents": false,
                  "streamingMessages": false
                }
                """.trimIndent()
            )
        }
        val payload = json.readTree(response.bodyAsText())
        val settings = payload["settings"]
        val storedIntent = runBlocking { context.userSettingsRepository.get("user-a") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(settings["defaultModel"].asText() != LLMModel.QwenMax.alias)
        assertEquals(12_000, settings["contextSize"].asInt())
        assertEquals(0.15, settings["temperature"].asDouble())
        assertEquals("en-US", settings["locale"].asText())
        assertEquals("Europe/Amsterdam", settings["timeZone"].asText())
        assertEquals("be brief", settings["systemPrompt"].asText())
        assertEquals(listOf("ListFiles"), settings["enabledTools"].map { it.asText() })
        assertEquals(false, settings["showToolEvents"].asBoolean())
        assertEquals(false, settings["streamingMessages"].asBoolean())
        assertEquals(LLMModel.QwenMax, storedIntent?.defaultModel)
        assertEquals(setOf("ListFiles", "OpenBrowser"), storedIntent?.enabledTools)
    }

    @Test
    fun `get chats lists only current user chats and previews`() = testApplication {
        val context = routeTestContext()
        val olderChat = chat(
            userId = "user-a",
            title = "Older",
            createdAt = Instant.parse("2026-04-30T07:00:00Z"),
            updatedAt = Instant.parse("2026-04-30T08:00:00Z"),
        )
        val newerChat = chat(
            userId = "user-a",
            title = "Newer",
            createdAt = Instant.parse("2026-04-30T07:10:00Z"),
            updatedAt = Instant.parse("2026-04-30T09:00:00Z"),
        )
        val archivedChat = chat(
            userId = "user-a",
            title = "Archived",
            archived = true,
            createdAt = Instant.parse("2026-04-30T07:20:00Z"),
            updatedAt = Instant.parse("2026-04-30T10:00:00Z"),
        )
        val foreignChat = chat(
            userId = "user-b",
            title = "Foreign",
            createdAt = Instant.parse("2026-04-30T07:30:00Z"),
            updatedAt = Instant.parse("2026-04-30T11:00:00Z"),
        )
        runBlocking {
            context.chatRepository.create(olderChat)
            context.chatRepository.create(newerChat)
            context.chatRepository.create(archivedChat)
            context.chatRepository.create(foreignChat)
            context.messageRepository.append("user-a", olderChat.id, ChatRole.USER, "older preview")
            context.messageRepository.append("user-a", newerChat.id, ChatRole.ASSISTANT, "newer preview")
            context.messageRepository.append("user-b", foreignChat.id, ChatRole.USER, "foreign preview")
        }
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
            )
        }

        val defaultResponse = client.get("${BackendHttpRoutes.CHATS}?limit=10") {
            trustedHeaders("user-a")
        }
        val includeArchivedResponse = client.get("${BackendHttpRoutes.CHATS}?includeArchived=true") {
            trustedHeaders("user-a")
        }
        val defaultPayload = json.readTree(defaultResponse.bodyAsText())
        val includeArchivedPayload = json.readTree(includeArchivedResponse.bodyAsText())

        assertEquals(HttpStatusCode.OK, defaultResponse.status)
        assertEquals(listOf("Newer", "Older"), defaultPayload["items"].map { it["title"].asText() })
        assertEquals("newer preview", defaultPayload["items"][0]["lastMessagePreview"].asText())
        assertNull(defaultPayload["nextCursor"].textValue())

        assertEquals(HttpStatusCode.OK, includeArchivedResponse.status)
        assertEquals(
            listOf("Archived", "Newer", "Older"),
            includeArchivedPayload["items"].map { it["title"].asText() }
        )
    }

    @Test
    fun `get chats route defaults clamps and validates limit`() = testApplication {
        val context = routeTestContext()
        val baseTime = Instant.parse("2026-04-30T12:00:00Z")
        runBlocking {
            repeat(120) { index ->
                context.chatRepository.create(
                    chat(
                        userId = "user-a",
                        title = "Chat $index",
                        createdAt = baseTime.plusSeconds(index.toLong()),
                        updatedAt = baseTime.plusSeconds(index.toLong()),
                    )
                )
            }
        }
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
            )
        }

        val defaultResponse = client.get(BackendHttpRoutes.CHATS) {
            trustedHeaders("user-a")
        }
        val clampedResponse = client.get("${BackendHttpRoutes.CHATS}?limit=9999") {
            trustedHeaders("user-a")
        }
        val zeroResponse = client.get("${BackendHttpRoutes.CHATS}?limit=0") {
            trustedHeaders("user-a")
        }
        val negativeResponse = client.get("${BackendHttpRoutes.CHATS}?limit=-1") {
            trustedHeaders("user-a")
        }
        val invalidBooleanResponse = client.get("${BackendHttpRoutes.CHATS}?includeArchived=nope") {
            trustedHeaders("user-a")
        }

        assertEquals(HttpStatusCode.OK, defaultResponse.status)
        assertEquals(50, json.readTree(defaultResponse.bodyAsText())["items"].size())

        assertEquals(HttpStatusCode.OK, clampedResponse.status)
        assertEquals(100, json.readTree(clampedResponse.bodyAsText())["items"].size())

        assertEquals(HttpStatusCode.BadRequest, zeroResponse.status)
        assertEquals("invalid_request", json.readTree(zeroResponse.bodyAsText())["error"]["code"].asText())

        assertEquals(HttpStatusCode.BadRequest, negativeResponse.status)
        assertEquals("invalid_request", json.readTree(negativeResponse.bodyAsText())["error"]["code"].asText())

        assertEquals(HttpStatusCode.BadRequest, invalidBooleanResponse.status)
        assertEquals("invalid_request", json.readTree(invalidBooleanResponse.bodyAsText())["error"]["code"].asText())
        assertEquals(
            "includeArchived must be true or false.",
            json.readTree(invalidBooleanResponse.bodyAsText())["error"]["message"].asText(),
        )
    }

    @Test
    fun `post chats creates chat for current user`() = testApplication {
        val context = routeTestContext()
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
            )
        }

        val response = client.post(BackendHttpRoutes.CHATS) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Новый чат"}""")
        }
        val chat = json.readTree(response.bodyAsText())["chat"]
        val chatId = UUID.fromString(chat["id"].asText())
        val storedChat = runBlocking { context.chatRepository.get("user-a", chatId) }

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("Новый чат", chat["title"].asText())
        assertEquals(false, chat["archived"].asBoolean())
        assertNotNull(storedChat)
        assertEquals("user-a", storedChat.userId)
    }

    @Test
    fun `patch chat title trims updates timestamp and enforces ownership`() = testApplication {
        val context = routeTestContext()
        val ownedChat = chat(
            userId = "user-a",
            title = "Original",
            updatedAt = Instant.parse("2026-04-30T08:00:00Z"),
        )
        val foreignChat = chat(userId = "user-b", title = "Foreign")
        runBlocking {
            context.chatRepository.create(ownedChat)
            context.chatRepository.create(foreignChat)
        }
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
            )
        }

        val response = client.patch(BackendHttpRoutes.chatTitle(ownedChat.id)) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"  Переименованный чат  "}""")
        }
        val payload = json.readTree(response.bodyAsText())
        val storedChat = runBlocking { context.chatRepository.get("user-a", ownedChat.id) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ownedChat.id.toString(), payload["id"].asText())
        assertEquals("Переименованный чат", payload["title"].asText())
        assertEquals(false, payload["archived"].asBoolean())
        assertTrue(Instant.parse(payload["updatedAt"].asText()).isAfter(ownedChat.updatedAt))
        assertEquals("Переименованный чат", storedChat?.title)
        assertTrue(storedChat!!.updatedAt.isAfter(ownedChat.updatedAt))

        val foreignResponse = client.patch(BackendHttpRoutes.chatTitle(foreignChat.id)) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Nope"}""")
        }
        val foreignPayload = json.readTree(foreignResponse.bodyAsText())

        assertEquals(HttpStatusCode.NotFound, foreignResponse.status)
        assertEquals("chat_not_found", foreignPayload["error"]["code"].asText())
    }

    @Test
    fun `patch chat title rejects blank title`() = testApplication {
        val context = routeTestContext()
        val ownedChat = chat(userId = "user-a", title = "Original")
        runBlocking {
            context.chatRepository.create(ownedChat)
        }
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
            )
        }

        val response = client.patch(BackendHttpRoutes.chatTitle(ownedChat.id)) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"   "}""")
        }
        val payload = json.readTree(response.bodyAsText())
        val storedChat = runBlocking { context.chatRepository.get("user-a", ownedChat.id) }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_request", payload["error"]["code"].asText())
        assertEquals("title must not be empty.", payload["error"]["message"].asText())
        assertEquals("Original", storedChat?.title)
    }

    @Test
    fun `archive and unarchive routes toggle archived flag and update timestamp`() = testApplication {
        val context = routeTestContext()
        val ownedChat = chat(
            userId = "user-a",
            title = "Archivable",
            archived = false,
            updatedAt = Instant.parse("2026-04-30T08:00:00Z"),
        )
        val foreignChat = chat(userId = "user-b", title = "Foreign")
        runBlocking {
            context.chatRepository.create(ownedChat)
            context.chatRepository.create(foreignChat)
        }
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
            )
        }

        val archiveResponse = client.post(BackendHttpRoutes.archiveChat(ownedChat.id)) {
            trustedHeaders("user-a")
        }
        val archivePayload = json.readTree(archiveResponse.bodyAsText())
        val archivedChat = runBlocking { context.chatRepository.get("user-a", ownedChat.id) }

        assertEquals(HttpStatusCode.OK, archiveResponse.status)
        assertEquals(true, archivePayload["archived"].asBoolean())
        assertTrue(Instant.parse(archivePayload["updatedAt"].asText()).isAfter(ownedChat.updatedAt))
        assertEquals(true, archivedChat?.archived)
        assertTrue(archivedChat!!.updatedAt.isAfter(ownedChat.updatedAt))

        val unarchiveResponse = client.post(BackendHttpRoutes.unarchiveChat(ownedChat.id)) {
            trustedHeaders("user-a")
        }
        val unarchivePayload = json.readTree(unarchiveResponse.bodyAsText())
        val unarchivedChat = runBlocking { context.chatRepository.get("user-a", ownedChat.id) }

        assertEquals(HttpStatusCode.OK, unarchiveResponse.status)
        assertEquals(false, unarchivePayload["archived"].asBoolean())
        assertEquals(false, unarchivedChat?.archived)
        assertTrue(unarchivedChat!!.updatedAt.isAfter(archivedChat.updatedAt))

        val foreignResponse = client.post(BackendHttpRoutes.archiveChat(foreignChat.id)) {
            trustedHeaders("user-a")
        }
        val foreignPayload = json.readTree(foreignResponse.bodyAsText())

        assertEquals(HttpStatusCode.NotFound, foreignResponse.status)
        assertEquals("chat_not_found", foreignPayload["error"]["code"].asText())
    }

    @Test
    fun `first trusted user request provisions namespace before chats messages and settings`() = testApplication {
        val context = routeTestContext()
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                ensureTrustedUser = context.userRepository::ensureUser,
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
            )
        }

        val createChatResponse = client.post(BackendHttpRoutes.CHATS) {
            trustedHeaders("fresh-user")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Provisioned"}""")
        }
        val createChatPayload = json.readTree(createChatResponse.bodyAsText())
        val chatId = UUID.fromString(createChatPayload["chat"]["id"].asText())
        val createMessageResponse = client.post(BackendHttpRoutes.chatMessages(chatId)) {
            trustedHeaders("fresh-user")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"hello"}""")
        }
        val settingsResponse = client.get(BackendHttpRoutes.SETTINGS) {
            trustedHeaders("fresh-user")
        }

        val userRecord = runBlocking { context.userRepository.get("fresh-user") }
        val storedChat = runBlocking { context.chatRepository.get("fresh-user", chatId) }
        val storedMessages = runBlocking { context.messageRepository.list("fresh-user", chatId) }
        val storedSettings = runBlocking { context.userSettingsRepository.get("fresh-user") }

        assertEquals(HttpStatusCode.Created, createChatResponse.status)
        assertEquals(HttpStatusCode.OK, createMessageResponse.status)
        assertEquals(HttpStatusCode.OK, settingsResponse.status)
        assertNotNull(userRecord)
        assertNotNull(storedSettings)
        assertEquals("fresh-user", storedChat?.userId)
        assertEquals(listOf("fresh-user"), storedMessages.map { it.userId }.distinct())
        assertEquals(1, runBlocking { context.userRepository.count() })
    }

    @Test
    fun `message routes enforce ownership and keep agent state separate from visible messages`() = testApplication {
        val context = routeTestContext()
        val ownedChat = chat(userId = "user-a", title = "Owned")
        val foreignChat = chat(userId = "user-b", title = "Foreign")
        runBlocking {
            context.chatRepository.create(ownedChat)
            context.chatRepository.create(foreignChat)
            context.messageRepository.append("user-a", ownedChat.id, ChatRole.USER, "visible message")
            context.stateRepository.save(
                agentState(
                    userId = "user-a",
                    chatId = ownedChat.id,
                    basedOnMessageSeq = 0,
                    history = listOf(
                        LLMRequest.Message(
                            role = LLMMessageRole.system,
                            content = "runtime-only state",
                        )
                    ),
                )
            )
        }
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
            )
        }

        val ownedResponse = client.get(BackendHttpRoutes.chatMessages(ownedChat.id)) {
            trustedHeaders("user-a")
        }
        val foreignGet = client.get(BackendHttpRoutes.chatMessages(foreignChat.id)) {
            trustedHeaders("user-a")
        }
        val foreignPost = client.post(BackendHttpRoutes.chatMessages(foreignChat.id)) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"nope"}""")
        }

        assertEquals(HttpStatusCode.OK, ownedResponse.status)
        assertEquals(
            listOf("visible message"),
            json.readTree(ownedResponse.bodyAsText())["items"].map { it["content"].asText() }
        )
        assertEquals(HttpStatusCode.NotFound, foreignGet.status)
        assertEquals("chat_not_found", json.readTree(foreignGet.bodyAsText())["error"]["code"].asText())
        assertEquals(HttpStatusCode.NotFound, foreignPost.status)
        assertEquals("chat_not_found", json.readTree(foreignPost.bodyAsText())["error"]["code"].asText())
    }

    @Test
    fun `get chat messages route defaults clamps and keeps afterSeq pagination`() = testApplication {
        val context = routeTestContext()
        val chat = chat(userId = "user-a", title = "Many messages")
        runBlocking {
            context.chatRepository.create(chat)
            repeat(520) { index ->
                context.messageRepository.append(
                    userId = "user-a",
                    chatId = chat.id,
                    role = ChatRole.USER,
                    content = "message-$index",
                )
            }
        }
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
            )
        }

        val defaultResponse = client.get(BackendHttpRoutes.chatMessages(chat.id)) {
            trustedHeaders("user-a")
        }
        val clampedResponse = client.get("${BackendHttpRoutes.chatMessages(chat.id)}?limit=9999") {
            trustedHeaders("user-a")
        }
        val pagedResponse = client.get("${BackendHttpRoutes.chatMessages(chat.id)}?afterSeq=500&limit=9999") {
            trustedHeaders("user-a")
        }

        val defaultItems = json.readTree(defaultResponse.bodyAsText())["items"]
        val clampedItems = json.readTree(clampedResponse.bodyAsText())["items"]
        val pagedItems = json.readTree(pagedResponse.bodyAsText())["items"]

        assertEquals(HttpStatusCode.OK, defaultResponse.status)
        assertEquals(100, defaultItems.size())
        assertEquals(100L, defaultItems.last()["seq"].asLong())

        assertEquals(HttpStatusCode.OK, clampedResponse.status)
        assertEquals(500, clampedItems.size())
        assertEquals(500L, clampedItems.last()["seq"].asLong())

        assertEquals(HttpStatusCode.OK, pagedResponse.status)
        assertEquals(20, pagedItems.size())
        assertEquals(501L, pagedItems.first()["seq"].asLong())
        assertEquals(520L, pagedItems.last()["seq"].asLong())
    }

    @Test
    fun `post chat message creates completed execution links messages and updates state separately`() = testApplication {
        val context = routeTestContext(llmApi = CapturingChatApi())
        val chat = chat(
            userId = "user-a",
            title = "Conversation",
            createdAt = Instant.parse("2026-04-30T10:00:00Z"),
            updatedAt = Instant.parse("2026-04-30T10:00:00Z"),
        )
        runBlocking {
            context.chatRepository.create(chat)
        }
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
            )
        }

        val response = client.post(BackendHttpRoutes.chatMessages(chat.id)) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"Напиши ответ","clientMessageId":"client-42"}""")
        }
        val payload = json.readTree(response.bodyAsText())
        val storedMessages = runBlocking { context.messageRepository.list("user-a", chat.id) }
        val storedState = runBlocking { context.stateRepository.get("user-a", chat.id) }
        val updatedChat = runBlocking { context.chatRepository.get("user-a", chat.id) }
        val storedExecution = runBlocking { context.executionRepository.listByChat("user-a", chat.id).single() }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("user", payload["message"]["role"].asText())
        assertEquals("Напиши ответ", payload["message"]["content"].asText())
        assertEquals("assistant", payload["assistantMessage"]["role"].asText())
        assertEquals("assistant reply to Напиши ответ", payload["assistantMessage"]["content"].asText())
        assertEquals("completed", payload["execution"]["status"].asText())
        assertEquals(payload["message"]["id"].asText(), payload["execution"]["userMessageId"].asText())
        assertEquals(payload["assistantMessage"]["id"].asText(), payload["execution"]["assistantMessageId"].asText())
        assertEquals("client-42", payload["execution"]["clientMessageId"].asText())
        assertEquals(context.settingsProvider.gigaModel.alias, payload["execution"]["model"].asText())
        assertEquals(context.settingsProvider.gigaModel.provider.name, payload["execution"]["provider"].asText())
        assertEquals(10, payload["execution"]["usage"]["totalTokens"].asInt())
        assertEquals(listOf("Напиши ответ", "assistant reply to Напиши ответ"), storedMessages.map { it.content })
        assertEquals(2L, storedState?.basedOnMessageSeq)
        assertTrue(storedState?.history.orEmpty().any { it.content.contains("assistant reply to Напиши ответ") })
        assertTrue(updatedChat!!.updatedAt > chat.updatedAt)
        assertEquals(AgentExecutionStatus.COMPLETED, storedExecution.status)
        assertEquals(payload["message"]["id"].asText(), storedExecution.userMessageId?.toString())
        assertEquals(payload["assistantMessage"]["id"].asText(), storedExecution.assistantMessageId?.toString())
        assertEquals("client-42", storedExecution.clientMessageId)
        assertEquals(context.settingsProvider.gigaModel, storedExecution.model)
        assertNotNull(storedExecution.startedAt)
        assertNotNull(storedExecution.finishedAt)
    }

    @Test
    fun `patched settings affect next chat execution`() = testApplication {
        val api = CapturingChatApi()
        val context = routeTestContext(llmApi = api)
        val chat = chat(userId = "user-a", title = "Configured chat")
        runBlocking {
            context.chatRepository.create(chat)
        }
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
            )
        }

        val patchResponse = client.patch(BackendHttpRoutes.SETTINGS) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "defaultModel": "${LLMModel.QwenMax.alias}",
                  "contextSize": 12000,
                  "temperature": 0.15,
                  "locale": "en-US",
                  "timeZone": "Europe/Amsterdam",
                  "systemPrompt": "be brief",
                  "showToolEvents": false,
                  "streamingMessages": false
                }
                """.trimIndent()
            )
        }
        val messageResponse = client.post(BackendHttpRoutes.chatMessages(chat.id)) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"Hello"}""")
        }
        val finalRequest = api.finalRequests.last()
        val storedState = runBlocking { context.stateRepository.get("user-a", chat.id) }

        assertEquals(HttpStatusCode.OK, patchResponse.status)
        assertEquals(HttpStatusCode.OK, messageResponse.status)
        assertEquals(LLMModel.QwenMax.alias, finalRequest.model)
        assertEquals(12_000, finalRequest.maxTokens)
        assertEquals(0.15f, finalRequest.temperature)
        assertEquals("be brief", finalRequest.messages.first { it.role == LLMMessageRole.system }.content)
        assertEquals(Locale.forLanguageTag("en-US"), storedState?.locale)
        assertEquals(ZoneId.of("Europe/Amsterdam"), storedState?.timeZone)
    }

    @Test
    fun `failed chat execution does not create assistant message or overwrite persisted state`() = testApplication {
        val context = routeTestContext(llmApi = FailingChatApi())
        val chat = chat(userId = "user-a", title = "Failure case")
        val originalState = agentState(
            userId = "user-a",
            chatId = chat.id,
            basedOnMessageSeq = 0,
            history = listOf(
                LLMRequest.Message(
                    role = LLMMessageRole.assistant,
                    content = "existing state",
                )
            ),
        )
        runBlocking {
            context.chatRepository.create(chat)
            context.stateRepository.save(originalState)
        }
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
            )
        }

        val response = client.post(BackendHttpRoutes.chatMessages(chat.id)) {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"trigger failure"}""")
        }
        val payload = json.readTree(response.bodyAsText())
        val storedMessages = runBlocking { context.messageRepository.list("user-a", chat.id) }
        val storedState = runBlocking { context.stateRepository.get("user-a", chat.id) }
        val storedExecution = runBlocking { context.executionRepository.listByChat("user-a", chat.id).single() }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("agent_execution_failed", payload["error"]["code"].asText())
        assertEquals(listOf("trigger failure"), storedMessages.map { it.content })
        assertEquals(originalState, storedState)
        assertEquals(AgentExecutionStatus.FAILED, storedExecution.status)
        assertEquals("agent_execution_failed", storedExecution.errorCode)
        assertNull(storedExecution.assistantMessageId)
        assertNotNull(storedExecution.finishedAt)
    }

    @Test
    fun `parallel post messages in same chat return 409 structured error`() = testApplication {
        val api = GateControlledChatApi()
        val context = routeTestContext(llmApi = api)
        val chat = chat(userId = "user-a", title = "Busy chat")
        runBlocking {
            context.chatRepository.create(chat)
        }
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
            )
        }

        runBlocking {
            val firstResponse = async {
                client.post(BackendHttpRoutes.chatMessages(chat.id)) {
                    trustedHeaders("user-a")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"first"}""")
                }
            }
            api.awaitStarted("first")

            assertNotNull(context.executionRepository.findActive("user-a", chat.id))

            val secondResponse = client.post(BackendHttpRoutes.chatMessages(chat.id)) {
                trustedHeaders("user-a")
                contentType(ContentType.Application.Json)
                setBody("""{"content":"second"}""")
            }
            val secondPayload = json.readTree(secondResponse.bodyAsText())

            assertEquals(HttpStatusCode.Conflict, secondResponse.status)
            assertEquals("chat_already_has_active_execution", secondPayload["error"]["code"].asText())

            api.release()
            assertEquals(HttpStatusCode.OK, firstResponse.await().status)
        }
    }

    @Test
    fun `parallel post messages in different chats and users do not block each other`() = testApplication {
        val api = GateControlledChatApi()
        val context = routeTestContext(llmApi = api)
        val userAChat = chat(userId = "user-a", title = "Chat A")
        val userBChat = chat(userId = "user-b", title = "Chat B")
        runBlocking {
            context.chatRepository.create(userAChat)
            context.chatRepository.create(userBChat)
        }
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
            )
        }

        runBlocking {
            val firstResponse = async {
                client.post(BackendHttpRoutes.chatMessages(userAChat.id)) {
                    trustedHeaders("user-a")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"A1"}""")
                }
            }
            val secondResponse = async {
                client.post(BackendHttpRoutes.chatMessages(userBChat.id)) {
                    trustedHeaders("user-b")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"B1"}""")
                }
            }

            api.awaitStarted("A1")
            api.awaitStarted("B1")

            assertNotNull(context.executionRepository.findActive("user-a", userAChat.id))
            assertNotNull(context.executionRepository.findActive("user-b", userBChat.id))

            api.release()

            assertEquals(HttpStatusCode.OK, firstResponse.await().status)
            assertEquals(HttpStatusCode.OK, secondResponse.await().status)
        }
    }

    @Test
    fun `cancel active route transitions execution from cancelling to cancelled`() = testApplication {
        val api = CancellableChatApi()
        val context = routeTestContext(llmApi = api)
        val chat = chat(userId = "user-a", title = "Cancellable chat")
        runBlocking {
            context.chatRepository.create(chat)
        }
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
            )
        }

        runBlocking {
            val sendResponse = async {
                client.post(BackendHttpRoutes.chatMessages(chat.id)) {
                    trustedHeaders("user-a")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"cancel me"}""")
                }
            }
            api.awaitStarted("cancel me")
            val activeExecution = assertNotNull(context.executionRepository.findActive("user-a", chat.id))

            val cancelResponse = client.post(BackendHttpRoutes.cancelActive(chat.id)) {
                trustedHeaders("user-a")
            }
            val cancelPayload = json.readTree(cancelResponse.bodyAsText())

            assertEquals(HttpStatusCode.OK, cancelResponse.status)
            assertEquals(activeExecution.id.toString(), cancelPayload["execution"]["id"].asText())
            assertEquals("cancelling", cancelPayload["execution"]["status"].asText())

            val cancelledResponse = sendResponse.await()
            val cancelledPayload = json.readTree(cancelledResponse.bodyAsText())
            val storedExecution = assertNotNull(context.executionRepository.getByChat("user-a", chat.id, activeExecution.id))

            assertEquals(HttpStatusCode.Conflict, cancelledResponse.status)
            assertEquals("agent_execution_cancelled", cancelledPayload["error"]["code"].asText())
            assertEquals(AgentExecutionStatus.CANCELLED, storedExecution.status)
            assertTrue(storedExecution.cancelRequested)
            assertNull(storedExecution.assistantMessageId)
            assertNotNull(storedExecution.finishedAt)
        }
    }

    @Test
    fun `cancel execution route enforces ownership and cancels matching execution`() = testApplication {
        val api = CancellableChatApi()
        val context = routeTestContext(llmApi = api)
        val chat = chat(userId = "user-a", title = "Owned chat")
        runBlocking {
            context.chatRepository.create(chat)
        }
        application {
            backendApplication(
                bootstrapService = context.bootstrapService,
                selectedModel = { context.settingsProvider.gigaModel.alias },
                trustedProxyToken = { "proxy-secret" },
                userSettingsService = context.userSettingsService,
                chatService = context.chatService,
                messageService = context.messageService,
                executionService = context.executionService,
            )
        }

        runBlocking {
            val sendResponse = async {
                client.post(BackendHttpRoutes.chatMessages(chat.id)) {
                    trustedHeaders("user-a")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"cancel exact execution"}""")
                }
            }
            api.awaitStarted("cancel exact execution")
            val activeExecution = assertNotNull(context.executionRepository.findActive("user-a", chat.id))

            val foreignResponse = client.post(BackendHttpRoutes.cancelExecution(chat.id, activeExecution.id)) {
                trustedHeaders("user-b")
            }
            val foreignPayload = json.readTree(foreignResponse.bodyAsText())
            assertEquals(HttpStatusCode.NotFound, foreignResponse.status)
            assertEquals("chat_not_found", foreignPayload["error"]["code"].asText())

            val ownerResponse = client.post(BackendHttpRoutes.cancelExecution(chat.id, activeExecution.id)) {
                trustedHeaders("user-a")
            }
            val ownerPayload = json.readTree(ownerResponse.bodyAsText())
            assertEquals(HttpStatusCode.OK, ownerResponse.status)
            assertEquals(activeExecution.id.toString(), ownerPayload["execution"]["id"].asText())
            assertEquals("cancelling", ownerPayload["execution"]["status"].asText())

            val cancelledResponse = sendResponse.await()
            val storedExecution = assertNotNull(context.executionRepository.getByChat("user-a", chat.id, activeExecution.id))

            assertEquals(HttpStatusCode.Conflict, cancelledResponse.status)
            assertEquals(AgentExecutionStatus.CANCELLED, storedExecution.status)
        }
    }

}

internal data class RouteTestContext(
    val featureFlags: BackendFeatureFlags,
    val settingsProvider: TestSettingsProvider,
    val userRepository: MemoryUserRepository,
    val userSettingsRepository: MemoryUserSettingsRepository,
    val userProviderKeyRepository: MemoryUserProviderKeyRepository,
    val chatRepository: MemoryChatRepository,
    val messageRepository: MemoryMessageRepository,
    val executionRepository: MemoryAgentExecutionRepository,
    val optionRepository: MemoryOptionRepository,
    val eventRepository: MemoryAgentEventRepository,
    val toolCallRepository: MemoryToolCallRepository,
    val eventService: AgentEventService,
    val stateRepository: MemoryAgentStateRepository,
    val bootstrapService: BackendBootstrapService,
    val userSettingsService: UserSettingsService,
    val userProviderKeyService: UserProviderKeyService,
    val chatService: ChatService,
    val executionService: AgentExecutionService,
    val optionService: OptionService,
    val messageService: MessageService,
)

internal fun routeTestContext(
    llmApi: LLMChatAPI = CapturingChatApi(),
    settingsProvider: TestSettingsProvider = TestSettingsProvider().apply {
        gigaChatKey = "giga-key"
        qwenChatKey = "qwen-key"
        contextSize = 24_000
        temperature = 0.6f
        useStreaming = false
    },
    userRepository: MemoryUserRepository = MemoryUserRepository(),
    userSettingsRepository: MemoryUserSettingsRepository = MemoryUserSettingsRepository(),
    userProviderKeyRepository: MemoryUserProviderKeyRepository = MemoryUserProviderKeyRepository(),
    chatRepository: MemoryChatRepository = MemoryChatRepository(),
    messageRepository: MemoryMessageRepository = MemoryMessageRepository(),
    executionRepository: MemoryAgentExecutionRepository = MemoryAgentExecutionRepository(),
    optionRepository: MemoryOptionRepository = MemoryOptionRepository(),
    eventRepository: MemoryAgentEventRepository = MemoryAgentEventRepository(),
    toolCallRepository: MemoryToolCallRepository = MemoryToolCallRepository(),
    stateRepository: MemoryAgentStateRepository = MemoryAgentStateRepository(),
    toolCatalog: AgentToolCatalog = toolCatalog(
        ToolCategory.FILES to fakeTool("ListFiles"),
        ToolCategory.BROWSER to fakeTool("OpenBrowser"),
    ),
    featureFlags: BackendFeatureFlags = BackendFeatureFlags(
        streamingMessages = false,
        toolEvents = true,
    ),
    turnRunner: BackendConversationTurnRunner? = null,
): RouteTestContext {
    val eventService = AgentEventService(
        chatRepository = chatRepository,
        eventRepository = eventRepository,
        eventBus = AgentEventBus(),
    )
    val effectiveSettingsResolver = EffectiveSettingsResolver(
        baseSettingsProvider = settingsProvider,
        userSettingsRepository = userSettingsRepository,
        userProviderKeyRepository = userProviderKeyRepository,
        featureFlags = featureFlags,
        toolCatalog = toolCatalog,
        localModelAvailability = unavailableLocalModels(),
    )
    val runtimeFactory = BackendConversationRuntimeFactory(
        baseSettingsProvider = settingsProvider,
        llmApiFactory = { llmApi },
        sessionRepository = AgentStateBackedSessionRepository(stateRepository),
        logObjectMapper = jacksonObjectMapper(),
        systemPrompt = "global backend prompt",
        toolCatalog = toolCatalog,
    )
    val conversationTurnRunner = turnRunner ?: BackendConversationRuntimeTurnRunner(runtimeFactory)
    val requestFactory = AgentExecutionRequestFactory(
        effectiveSettingsResolver = effectiveSettingsResolver,
        featureFlags = featureFlags,
    )
    val finalizer = AgentExecutionFinalizer(
        agentStateRepository = stateRepository,
        chatRepository = chatRepository,
        executionRepository = executionRepository,
        turnRunner = conversationTurnRunner,
    )
    val executionService = AgentExecutionService(
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        executionRepository = executionRepository,
        optionRepository = optionRepository,
        eventService = eventService,
        toolCallRepository = toolCallRepository,
        requestFactory = requestFactory,
        finalizer = finalizer,
        launcher = AgentExecutionLauncher(
            executionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            finalizer = finalizer,
        ),
    )
    val optionService = OptionService(
        optionRepository = optionRepository,
        executionService = executionService,
        featureFlags = featureFlags,
    )
    return RouteTestContext(
        featureFlags = featureFlags,
        settingsProvider = settingsProvider,
        userRepository = userRepository,
        userSettingsRepository = userSettingsRepository,
        userProviderKeyRepository = userProviderKeyRepository,
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        executionRepository = executionRepository,
        optionRepository = optionRepository,
        eventRepository = eventRepository,
        toolCallRepository = toolCallRepository,
        eventService = eventService,
        stateRepository = stateRepository,
        bootstrapService = BackendBootstrapService(
            settingsProvider = settingsProvider,
            effectiveSettingsResolver = effectiveSettingsResolver,
            toolCatalog = toolCatalog,
            featureFlags = featureFlags,
            storageMode = StorageMode.MEMORY,
            localModelAvailability = unavailableLocalModels(),
            userProviderKeyRepository = userProviderKeyRepository,
        ),
        userSettingsService = UserSettingsService(
            userSettingsRepository = userSettingsRepository,
            effectiveSettingsResolver = effectiveSettingsResolver,
        ),
        userProviderKeyService = UserProviderKeyService(
            repository = userProviderKeyRepository,
            masterKey = "test-master-key",
        ),
        chatService = ChatService(
            chatRepository = chatRepository,
            messageRepository = messageRepository,
        ),
        executionService = executionService,
        optionService = optionService,
        messageService = MessageService(
            chatRepository = chatRepository,
            messageRepository = messageRepository,
            executionService = executionService,
        ),
    )
}

internal fun io.ktor.client.request.HttpRequestBuilder.trustedHeaders(userId: String) {
    header("X-User-Id", userId)
    header("X-Souz-Proxy-Auth", "proxy-secret")
}

internal fun chat(
    userId: String,
    title: String,
    archived: Boolean = false,
    createdAt: Instant = Instant.parse("2026-04-30T07:00:00Z"),
    updatedAt: Instant = Instant.parse("2026-04-30T07:00:00Z"),
): Chat =
    Chat(
        id = UUID.randomUUID(),
        userId = userId,
        title = title,
        archived = archived,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun agentState(
    userId: String,
    chatId: UUID,
    basedOnMessageSeq: Long,
    history: List<LLMRequest.Message>,
): AgentConversationState =
    AgentConversationState(
        userId = userId,
        chatId = chatId,
        schemaVersion = 1,
        activeAgentId = AgentId.default,
        history = history,
        temperature = 0.3f,
        locale = Locale.forLanguageTag("ru-RU"),
        timeZone = ZoneId.of("Europe/Moscow"),
        basedOnMessageSeq = basedOnMessageSeq,
        updatedAt = Instant.parse("2026-04-30T08:15:00Z"),
        rowVersion = 0L,
    )

private fun toolCatalog(vararg tools: Pair<ToolCategory, LLMToolSetup>): AgentToolCatalog =
    object : AgentToolCatalog {
        override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> =
            tools.groupBy(keySelector = { it.first }, valueTransform = { it.second })
                .mapValues { (_, setups) -> setups.associateBy { it.fn.name } }
    }

private fun fakeTool(name: String): LLMToolSetup =
    object : LLMToolSetup {
        override val fn: LLMRequest.Function = LLMRequest.Function(
            name = name,
            description = "test",
            parameters = LLMRequest.Parameters(type = "object", properties = emptyMap()),
        )

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
            LLMRequest.Message(role = LLMMessageRole.function, content = "ok", name = functionCall.name)
    }

private fun unavailableLocalModels(): LocalModelAvailability =
    object : LocalModelAvailability {
        override fun availableGigaModels(): List<LLMModel> = emptyList()

        override fun defaultGigaModel(): LLMModel? = null

        override fun isProviderAvailable(): Boolean = false
    }

internal class CapturingChatApi : LLMChatAPI {
    val finalRequests = ArrayList<LLMRequest.Chat>()

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        if (body.isClassificationRequest()) {
            return reply(body, "HELP 90")
        }
        finalRequests += body
        return reply(body, "assistant reply to ${body.conversationPrompt()}")
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> =
        error("Streaming is not used in stage 3 route tests.")

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        error("Embeddings are not used in stage 3 route tests.")

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        error("File upload is not used in stage 3 route tests.")

    override suspend fun downloadFile(fileId: String): String? =
        error("File download is not used in stage 3 route tests.")

    override suspend fun balance(): LLMResponse.Balance =
        error("Balance is not used in stage 3 route tests.")

    private fun reply(body: LLMRequest.Chat, content: String): LLMResponse.Chat.Ok =
        LLMResponse.Chat.Ok(
            choices = listOf(
                LLMResponse.Choice(
                    message = LLMResponse.Message(
                        content = content,
                        role = LLMMessageRole.assistant,
                        functionCall = null,
                        functionsStateId = null,
                    ),
                    index = 0,
                    finishReason = LLMResponse.FinishReason.stop,
                )
            ),
            created = System.currentTimeMillis(),
            model = body.model,
            usage = LLMResponse.Usage(7, 3, 10, 0),
        )
}

internal class FailingChatApi : LLMChatAPI {
    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        if (body.isClassificationRequest()) {
            return LLMResponse.Chat.Ok(
                choices = listOf(
                    LLMResponse.Choice(
                        message = LLMResponse.Message(
                            content = "HELP 90",
                            role = LLMMessageRole.assistant,
                            functionCall = null,
                            functionsStateId = null,
                        ),
                        index = 0,
                        finishReason = LLMResponse.FinishReason.stop,
                    )
                ),
                created = System.currentTimeMillis(),
                model = body.model,
                usage = LLMResponse.Usage(1, 1, 2, 0),
            )
        }
        error("simulated execution failure")
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> =
        error("Streaming is not used in stage 3 route tests.")

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        error("Embeddings are not used in stage 3 route tests.")

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        error("File upload is not used in stage 3 route tests.")

    override suspend fun downloadFile(fileId: String): String? =
        error("File download is not used in stage 3 route tests.")

    override suspend fun balance(): LLMResponse.Balance =
        error("Balance is not used in stage 3 route tests.")
}

internal class GateControlledChatApi : LLMChatAPI {
    private val startedByPrompt = LinkedHashMap<String, CompletableDeferred<Unit>>()
    private val release = CompletableDeferred<Unit>()

    suspend fun awaitStarted(prompt: String) {
        startedByPrompt.getOrPut(prompt) { CompletableDeferred() }.await()
    }

    fun release() {
        release.complete(Unit)
    }

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        if (body.isClassificationRequest()) {
            return reply(body, "HELP 90")
        }
        startedByPrompt.getOrPut(body.conversationPrompt()) { CompletableDeferred() }.complete(Unit)
        release.await()
        return reply(body, "assistant reply to ${body.conversationPrompt()}")
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> =
        error("Streaming is not used in stage 4 route tests.")

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        error("Embeddings are not used in stage 4 route tests.")

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        error("File upload is not used in stage 4 route tests.")

    override suspend fun downloadFile(fileId: String): String? =
        error("File download is not used in stage 4 route tests.")

    override suspend fun balance(): LLMResponse.Balance =
        error("Balance is not used in stage 4 route tests.")
}

internal class CancellableChatApi : LLMChatAPI {
    private val startedByPrompt = LinkedHashMap<String, CompletableDeferred<Unit>>()

    suspend fun awaitStarted(prompt: String) {
        startedByPrompt.getOrPut(prompt) { CompletableDeferred() }.await()
    }

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        if (body.isClassificationRequest()) {
            return reply(body, "HELP 90")
        }
        startedByPrompt.getOrPut(body.conversationPrompt()) { CompletableDeferred() }.complete(Unit)
        awaitCancellation()
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> =
        error("Streaming is not used in stage 4 route tests.")

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        error("Embeddings are not used in stage 4 route tests.")

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        error("File upload is not used in stage 4 route tests.")

    override suspend fun downloadFile(fileId: String): String? =
        error("File download is not used in stage 4 route tests.")

    override suspend fun balance(): LLMResponse.Balance =
        error("Balance is not used in stage 4 route tests.")
}

internal fun reply(body: LLMRequest.Chat, content: String): LLMResponse.Chat.Ok =
    LLMResponse.Chat.Ok(
        choices = listOf(
            LLMResponse.Choice(
                message = LLMResponse.Message(
                    content = content,
                    role = LLMMessageRole.assistant,
                    functionCall = null,
                    functionsStateId = null,
                ),
                index = 0,
                finishReason = LLMResponse.FinishReason.stop,
            )
        ),
        created = System.currentTimeMillis(),
        model = body.model,
        usage = LLMResponse.Usage(7, 3, 10, 0),
    )

internal fun LLMRequest.Chat.conversationPrompt(): String =
    messages.lastOrNull { message ->
        message.role == LLMMessageRole.user && !message.content.contains("<context>")
    }?.content.orEmpty()

internal fun LLMRequest.Chat.isClassificationRequest(): Boolean =
    messages.any { message ->
        message.role == LLMMessageRole.system &&
            message.content.contains("Твоя задача — выбрать минимальный, но достаточный набор категорий")
    }

private class ThrowingTool : ToolSetup<ThrowingTool.Input> {
    data class Input(
        @InputParamDescription("Any payload that should trigger the tool")
        val payload: String,
    )

    override val name: String = "ExplodeTool"
    override val description: String = "Always fails so tests can verify tool error handling."
    override val fewShotExamples: List<FewShotExample> = emptyList()
    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf("result" to ReturnProperty("string"))
    )

    override fun invoke(input: Input): String = error(input.payload)
}
