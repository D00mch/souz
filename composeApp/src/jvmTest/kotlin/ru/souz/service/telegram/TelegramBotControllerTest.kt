package ru.souz.service.telegram

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.clearAllMocks
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import ru.souz.agent.AgentFacade
import ru.souz.ui.main.usecases.SpeechRecognitionProvider
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TelegramBotControllerTest {

    @AfterTest
    fun clearMocks() {
        clearAllMocks()
        unmockkAll()
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
        val attachedPath = command.lineSequence().lastOrNull { it.startsWith("/") }
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

    private class FakeBotApi(
        private val filesById: Map<String, FileEntry> = emptyMap(),
    ) : TelegramBotApi {
        val sentTexts = mutableListOf<String>()

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
