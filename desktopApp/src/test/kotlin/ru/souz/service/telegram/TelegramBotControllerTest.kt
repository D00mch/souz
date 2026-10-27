package ru.souz.service.telegram

import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.souz.agent.AgentFacade
import ru.souz.llms.LLMResponse
import ru.souz.llms.giga.toGiga
import ru.souz.llms.restJsonMapper
import ru.souz.service.speech.SpeechRecognitionProvider
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.mail.ToolMailSendNewMessage
import ru.souz.tool.mail.MailSendApproval
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TelegramBotControllerTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `failed mail preview advances polling without replaying completed commands or approving mail`() = runTest {
        val bash = mockk<ToolRunBashCommand>()
        val body = "x".repeat(8_000)
        val snapshot = restJsonMapper.writeValueAsString(mapOf(
            "sender" to "owner@example.com", "subject" to "Report", "content" to body,
            "to" to listOf("ivan@example.com"), "cc" to emptyList<String>(), "bcc" to emptyList<String>(),
        ))
        every { bash.sh(any(), *anyVararg()) } answers {
            if (firstArg<String>().contains("send newMessage")) "Email sent." else snapshot
        }
        val approval = MailSendApproval(bash)
        val token = approval.request(false, listOf("Ivan", "ivan@example.com", "Report", body))
            .substringAfter("/mail_preview ").substringBefore('.')
        val updates = listOf(
            ownerUpdate(1, "before preview"), ownerUpdate(2, "/mail_preview $token"),
            ownerUpdate(3, "/mail_send $token"), ownerUpdate(4, "after preview"),
        )
        val agentFacade = mockk<AgentFacade>()
        coEvery { agentFacade.execute(any<String>()) } returns "Done"
        val botApi = mockk<TelegramBotApi>(relaxed = true)
        val offsets = mutableListOf<Long>()
        coEvery { botApi.getUpdates("token", any(), any()) } coAnswers {
            val offset = secondArg<Long>()
            offsets += offset
            if (offset >= 5) awaitCancellation()
            TelegramUpdatesResponse(ok = true, result = updates.filter { it.updateId >= offset })
        }
        val delivered = mutableListOf<String>()
        var failPreview = true
        coEvery { botApi.sendMessage("token", 42, any()) } coAnswers {
            val text = thirdArg<String>()
            // The first chunk has headers; fail once while delivering the middle of the body.
            if (text == "x".repeat(3_500) && failPreview) {
                failPreview = false
                error("Telegram unavailable")
            }
            delivered += text
        }
        val config = object : TelegramBotConfigProvider {
            override fun token() = "token"
            override fun ownerId() = 42L
        }
        val controller = TelegramBotController(
            telegramService = mockk(relaxed = true), agentFacade = agentFacade, botApi = botApi,
            configProvider = config, mailSendApproval = approval, scope = backgroundScope,
        )
        try {
            controller.restartPolling()
            runCurrent()
            advanceTimeBy(5_001)
            runCurrent()

            assertEquals(listOf(0L, 5L), offsets)
            coVerify(exactly = 1) { agentFacade.execute("before preview") }
            coVerify(exactly = 1) { agentFacade.execute("after preview") }
            coVerify(exactly = 2) { agentFacade.execute(any<String>()) }
            assertTrue(delivered.any { it.contains("Review the email first") })
            verify(exactly = 0) { bash.sh(match { it.contains("send newMessage") }, *anyVararg()) }

            // A fresh, complete preview still lets the owner explicitly approve the pending email.
            controller.processUpdates("token", 42, listOf(
                ownerUpdate(5, "/mail_preview $token"), ownerUpdate(6, "/mail_send $token"),
            ), currentOffset = 5)
            assertEquals("Email sent.", delivered.last())
            verify(exactly = 1) { bash.sh(match { it.contains("send newMessage") }, *anyVararg()) }
        } finally {
            controller.close()
        }
    }

    @Test
    fun `mail command cancellation propagates without processing later updates`() = runTest {
        val approval = mockk<MailSendApproval>()
        coEvery { approval.handleOwnerCommand(any(), any()) } throws CancellationException("Polling stopped")
        val agentFacade = mockk<AgentFacade>(relaxed = true)
        val controller = TelegramBotController(
            telegramService = mockk(relaxed = true), agentFacade = agentFacade,
            botApi = FakeBotApi(), mailSendApproval = approval,
        )
        try {
            assertFailsWith<CancellationException> {
                controller.processUpdates("token", 42, listOf(
                    ownerUpdate(1, "/mail_preview pending"), ownerUpdate(2, "later command"),
                ))
            }
            coVerify(exactly = 0) { agentFacade.execute(any<String>()) }
        } finally {
            controller.close()
        }
    }

    private fun ownerUpdate(id: Long, text: String) = TelegramUpdate(
        updateId = id,
        message = TelegramMessage(
            messageId = id, from = TelegramUser(id = 42, isBot = false),
            chat = TelegramChat(id = 42, type = "private"), text = text,
        ),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `mail sends only after authenticated owner reviews and approves outside the agent`(subscribed: Boolean) = runTest {
        val bash = mockk<ToolRunBashCommand>()
        val snapshot = """{"sender":"owner@example.com","subject":"Report","content":"Отчёт готов","to":["ivan@example.com"],"cc":[],"bcc":[]}"""
        every { bash.sh(any(), *anyVararg()) } answers {
            if (firstArg<String>().contains("send newMessage")) "Email sent." else snapshot
        }
        val approval = MailSendApproval(bash)
        val mail = ToolMailSendNewMessage(bash, approval).toGiga()
        // Even a model asserting confirmation on its first call cannot send.
        val arguments = mapOf("recipientAddress" to "ivan@example.com", "content" to "Отчёт готов",
            "send" to true, "confirmed" to true)
        val agentFacade = mockk<AgentFacade>()
        coEvery { agentFacade.execute("Отправь отчёт") } coAnswers {
            mail.invoke(LLMResponse.FunctionCall(mail.fn.name, arguments)).content
        }
        val botApi = FakeBotApi()
        val controller = TelegramBotController(
            telegramService = mockk(relaxed = true), agentFacade = agentFacade, botApi = botApi,
            mailSendApproval = approval,
        )
        val subscriber = if (subscribed) launch {
            controller.incomingMessages.collect { incoming ->
                incoming.responseDeferred.complete(agentFacade.execute(incoming.text))
            }
        } else null
        runCurrent()
        fun update(id: Long, text: String, user: Long = 42, chatType: String = "private") = TelegramUpdate(
            updateId = id,
            message = TelegramMessage(
                messageId = id, from = TelegramUser(id = user, isBot = false),
                chat = TelegramChat(id = user, type = chatType), text = text,
            ),
        )

        try {
            controller.processUpdates("token", 42, listOf(update(1, "Отправь отчёт")))
            val token = botApi.sentTexts.last().substringAfter("/mail_preview ").substringBefore('.')
            verify(exactly = 0) { bash.sh(match { it.contains("send newMessage") }, *anyVararg()) }

            // Neither another user nor a group message can present or approve the pending email.
            controller.processUpdates("token", 42, listOf(
                update(2, "/mail_preview $token", user = 7),
                update(3, "/mail_preview $token", chatType = "group"),
                update(4, "/mail_send $token"),
            ))
            assertTrue(botApi.sentTexts.last().contains("Review the email first"))
            controller.processUpdates("token", 42, listOf(update(5, "/mail_preview $token")))
            assertTrue(botApi.sentTexts.any { it.contains("TO: ivan@example.com") && it.contains("Отчёт готов") })
            controller.processUpdates("token", 42, listOf(update(6, "/mail_send $token", user = 7)))
            verify(exactly = 0) { bash.sh(match { it.contains("send newMessage") }, *anyVararg()) }
            controller.processUpdates("token", 42, listOf(update(7, "/mail_send $token")))
            assertEquals("Email sent.", botApi.sentTexts.last())
            // Replayed approval cannot send twice, and approval never passes through model execution.
            controller.processUpdates("token", 42, listOf(update(7, "/mail_send $token")))
            verify(exactly = 1) { bash.sh(match { it.contains("send newMessage") },
                "ivan@example.com", "ivan@example.com", "No Subject", "Отчёт готов", snapshot) }
            coVerify(exactly = 1) { agentFacade.execute(any<String>()) }
        } finally {
            subscriber?.cancel()
            controller.close()
        }
    }

    @AfterTest
    fun clearMocks() {
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun `restJsonMapper deserializes Telegram snake case fields`() {
        val updates = restJsonMapper.readValue<TelegramUpdatesResponse>(
            """
            {
              "ok": true,
              "result": [
                {
                  "update_id": 101,
                  "message": {
                    "message_id": 202,
                    "from": {
                      "id": 303,
                      "is_bot": false,
                      "first_name": "Owner",
                      "username": "owner"
                    },
                    "chat": {
                      "id": 303,
                      "type": "private"
                    },
                    "document": {
                      "file_id": "document-id",
                      "file_name": "report.pdf"
                    },
                    "voice": {
                      "file_id": "voice-id"
                    }
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val update = updates.result.single()
        assertEquals(101L, update.updateId)
        val message = assertNotNull(update.message)
        assertEquals(202L, message.messageId)
        assertEquals("document-id", message.document?.fileId)
        assertEquals("report.pdf", message.document?.fileName)
        assertEquals("voice-id", message.voice?.fileId)
        assertEquals(false, message.from?.isBot)
        assertEquals("Owner", message.from?.firstName)

        val fileResponse = restJsonMapper.readValue<TelegramBotFileResponse>(
            """
            {
              "ok": true,
              "result": {
                "file_id": "document-id",
                "file_path": "documents/report.pdf",
                "file_size": 4096
              }
            }
            """.trimIndent(),
        )

        val file = assertNotNull(fileResponse.result)
        assertEquals("document-id", file.fileId)
        assertEquals("documents/report.pdf", file.filePath)
        assertEquals(4096L, file.fileSize)
    }

    @Test
    fun `processUpdates executes only owner private commands`() = runTest {
        val agentFacade = mockk<AgentFacade>()
        coEvery { agentFacade.execute("ping") } returns "pong"

        val botApi = FakeBotApi()
        val controller = TelegramBotController(
            telegramService = mockk(relaxed = true),
            agentFacade = agentFacade,
            botApi = botApi,
        )

        val nextOffset = controller.processUpdates(
            token = "token",
            ownerId = 42,
            updates = listOf(
                TelegramUpdate(
                    updateId = 5,
                    message = TelegramMessage(
                        messageId = 1,
                        from = TelegramUser(id = 42, isBot = false),
                        chat = TelegramChat(id = 100, type = "private"),
                        text = "ping",
                    ),
                ),
            ),
            currentOffset = 0,
        )

        assertEquals(6, nextOffset)
        assertEquals(listOf("Processing command...", "pong"), botApi.sentTexts)
        coVerify(exactly = 1) { agentFacade.execute("ping") }

        controller.close()
    }

    @Test
    fun `processUpdates rejects owner commands outside private chat`() = runTest {
        val agentFacade = mockk<AgentFacade>(relaxed = true)
        val botApi = FakeBotApi()
        val controller = TelegramBotController(
            telegramService = mockk(relaxed = true),
            agentFacade = agentFacade,
            botApi = botApi,
        )

        val nextOffset = controller.processUpdates(
            token = "token",
            ownerId = 42,
            updates = listOf(
                TelegramUpdate(
                    updateId = 10,
                    message = TelegramMessage(
                        messageId = 1,
                        from = TelegramUser(id = 42, isBot = false),
                        chat = TelegramChat(id = -1001, type = "group"),
                        text = "dangerous",
                    ),
                ),
            ),
            currentOffset = 0,
        )

        assertEquals(11, nextOffset)
        assertTrue(botApi.sentTexts.isEmpty())
        coVerify(exactly = 0) { agentFacade.execute(any()) }

        controller.close()
    }

    @Test
    fun `processUpdates rejects non-owner private commands`() = runTest {
        val agentFacade = mockk<AgentFacade>(relaxed = true)
        val botApi = FakeBotApi()
        val controller = TelegramBotController(
            telegramService = mockk(relaxed = true),
            agentFacade = agentFacade,
            botApi = botApi,
        )

        val nextOffset = controller.processUpdates(
            token = "token",
            ownerId = 42,
            updates = listOf(
                TelegramUpdate(
                    updateId = 20,
                    message = TelegramMessage(
                        messageId = 1,
                        from = TelegramUser(id = 99, isBot = false),
                        chat = TelegramChat(id = 99, type = "private"),
                        text = "ping",
                    ),
                ),
            ),
            currentOffset = 0,
        )

        assertEquals(21, nextOffset)
        assertTrue(botApi.sentTexts.isEmpty())
        coVerify(exactly = 0) { agentFacade.execute(any()) }

        controller.close()
    }

    @Test
    fun `processUpdates downloads document and appends local path to command`() = runTest {
        val commandSlot = slot<String>()
        val agentFacade = mockk<AgentFacade>()
        coEvery { agentFacade.execute(capture(commandSlot)) } returns "ok"

        val tmpDownloads = Files.createTempDirectory("tg-bot-test-downloads")
        val botApi = FakeBotApi(
            filesById = mapOf(
                "doc-file-id" to FakeBotApi.FileEntry(
                    filePath = "documents/report.pdf",
                    bytes = "fake-pdf-content".toByteArray(),
                )
            )
        )
        val controller = TelegramBotController(
            telegramService = mockk(relaxed = true),
            agentFacade = agentFacade,
            botApi = botApi,
            downloadsDirProvider = { tmpDownloads },
        )

        controller.processUpdates(
            token = "token",
            ownerId = 42,
            updates = listOf(
                TelegramUpdate(
                    updateId = 1,
                    message = TelegramMessage(
                        messageId = 11,
                        from = TelegramUser(id = 42, isBot = false),
                        chat = TelegramChat(id = 100, type = "private"),
                        text = "Проанализируй файл",
                        document = TelegramDocument(
                            fileId = "doc-file-id",
                            fileName = "report.pdf",
                        ),
                    ),
                )
            ),
        )

        val command = commandSlot.captured
        assertTrue(command.contains("Проанализируй файл"))
        val attachedPath = command.lineSequence().lastOrNull { it.startsWith(tmpDownloads.toString()) }
        assertNotNull(attachedPath)
        assertTrue(Files.exists(java.nio.file.Path.of(attachedPath)))
        assertTrue(botApi.sentTexts.any { it == "Processing command..." })
        assertTrue(botApi.sentTexts.any { it == "ok" })

        controller.close()
    }

    @Test
    fun `processUpdates uses transcribed voice text as command`() = runTest {
        val commandSlot = slot<String>()
        val agentFacade = mockk<AgentFacade>()
        coEvery { agentFacade.execute(capture(commandSlot)) } returns "ok"

        val voiceProvider = object : SpeechRecognitionProvider {
            override val enabled: Boolean = true
            override val hasRequiredKey: Boolean = true
            override suspend fun recognize(audio: ByteArray): String = "распознанный текст"
        }

        val botApi = FakeBotApi(
            filesById = mapOf(
                "voice-file-id" to FakeBotApi.FileEntry(
                    filePath = "voice/clip.ogg",
                    bytes = "fake-ogg".toByteArray(),
                )
            )
        )

        val controller = TelegramBotController(
            telegramService = mockk(relaxed = true),
            agentFacade = agentFacade,
            speechRecognitionProvider = voiceProvider,
            botApi = botApi,
            voiceToPcmDecoder = { bytes, _ -> bytes },
        )

        controller.processUpdates(
            token = "token",
            ownerId = 42,
            updates = listOf(
                TelegramUpdate(
                    updateId = 2,
                    message = TelegramMessage(
                        messageId = 12,
                        from = TelegramUser(id = 42, isBot = false),
                        chat = TelegramChat(id = 100, type = "private"),
                        voice = TelegramVoice(fileId = "voice-file-id"),
                    ),
                )
            ),
        )

        assertEquals("распознанный текст", commandSlot.captured)
        assertTrue(botApi.sentTexts.any { it == "ok" })
        controller.close()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `typing indicator repeats every four seconds and stops once the turn completes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val agentFacade = mockk<AgentFacade>()
        coEvery { agentFacade.execute("ping") } coAnswers {
            delay(9_000L)
            "pong"
        }
        val botApi = FakeBotApi()
        val controller = TelegramBotController(
            telegramService = mockk(relaxed = true),
            agentFacade = agentFacade,
            botApi = botApi,
        )

        val processing = launch(dispatcher) {
            controller.processUpdates(
                token = "token",
                ownerId = 42,
                updates = listOf(
                    TelegramUpdate(
                        updateId = 5,
                        message = TelegramMessage(
                            messageId = 1,
                            from = TelegramUser(id = 42, isBot = false),
                            chat = TelegramChat(id = 100, type = "private"),
                            text = "ping",
                        ),
                    ),
                ),
                currentOffset = 0,
            )
        }
        runCurrent()
        assertEquals(listOf("typing"), botApi.chatActions)

        advanceTimeBy(4_001L)
        assertEquals(2, botApi.chatActions.size)

        advanceTimeBy(4_000L)
        assertEquals(3, botApi.chatActions.size)

        advanceTimeBy(1_000L)
        processing.join()

        // No further chat action once the turn completes at 9s and cancels the repeating job.
        assertEquals(3, botApi.chatActions.size)
        assertEquals(listOf("Processing command...", "pong"), botApi.sentTexts)

        controller.close()
    }

    private class FakeBotApi(
        private val filesById: Map<String, FileEntry> = emptyMap(),
    ) : TelegramBotApi {
        val sentTexts = mutableListOf<String>()
        val chatActions = mutableListOf<String>()

        data class FileEntry(
            val filePath: String,
            val bytes: ByteArray,
        )

        override suspend fun getUpdates(token: String, offset: Long, timeoutSeconds: Int): TelegramUpdatesResponse {
            error("getUpdates is not used in this test")
        }

        override suspend fun sendMessage(token: String, chatId: Long, text: String) {
            sentTexts += text
        }

        override suspend fun sendChatAction(token: String, chatId: Long, action: String) {
            chatActions += action
        }

        override suspend fun getTelegramFileInfo(token: String, fileId: String): TelegramBotFileResponse {
            val file = filesById[fileId] ?: error("Unknown fileId in test: $fileId")
            return TelegramBotFileResponse(
                ok = true,
                result = TelegramBotFile(fileId = fileId, filePath = file.filePath),
            )
        }

        override suspend fun downloadTelegramFileBytes(token: String, filePath: String): ByteArray {
            val file = filesById.values.firstOrNull { it.filePath == filePath }
                ?: error("Unknown filePath in test: $filePath")
            return file.bytes
        }
    }
}
