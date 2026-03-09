package ru.souz.service.telegram

import it.tdlight.client.SimpleTelegramClient
import it.tdlight.jni.TdApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.slf4j.Logger
import ru.souz.db.ConfigStore
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration

private const val BOT_FATHER_STEP_DELAY_MS = 1_500L
private const val BOT_FATHER_POLL_DELAY_MS = 1_000L
private const val BOT_FATHER_POLL_ATTEMPTS = 10
private const val BOT_LOOKUP_TIMEOUT_MS = 5_000L

internal class TelegramBotWorkflow(
    private val logger: Logger,
    private val requireClient: suspend () -> SimpleTelegramClient,
    private val resolveCurrentUser: suspend (SimpleTelegramClient) -> TdApi.User,
    private val extractMessageText: (TdApi.Message) -> String?,
) {

    private val botLookupHttpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(BOT_LOOKUP_TIMEOUT_MS))
        .build()

    suspend fun resumePendingTask(
        authState: StateFlow<TelegramAuthState>,
        onCreateTask: suspend () -> Unit,
        onDeleteTask: suspend () -> Unit,
    ) {
        val taskTypeStr = ConfigStore.get(ConfigStore.TG_BOT_TASK_TYPE, BotTaskType.NONE.name)
        val taskType = runCatching { BotTaskType.valueOf(taskTypeStr) }.getOrDefault(BotTaskType.NONE)
        if (taskType == BotTaskType.NONE) return

        logger.info("Pending bot task found ({}). Waiting for Telegram to be READY...", taskType)
        authState.first { it.step == TelegramAuthStep.READY }

        when (taskType) {
            BotTaskType.CREATE -> {
                logger.info("Resuming pending createControlBot task")
                onCreateTask()
            }

            BotTaskType.DELETE -> {
                logger.info("Resuming pending deleteControlBot task")
                onDeleteTask()
            }

            BotTaskType.NONE -> Unit
        }
    }

    suspend fun createControlBot(step: BotCreationStep = BotCreationStep.NONE, forceNew: Boolean = false) {
        if (forceNew) {
            ConfigStore.put(ConfigStore.TG_BOT_TASK_TYPE, BotTaskType.CREATE.name)
            ConfigStore.put(ConfigStore.TG_BOT_TASK_STEP, BotCreationStep.INIT.name)
            ConfigStore.rm(ConfigStore.TG_BOT_TASK_START_MSG_ID)
            ConfigStore.rm(ConfigStore.TG_BOT_USERNAME)
        }

        val isNewTask = forceNew ||
            (step == BotCreationStep.NONE && ConfigStore.get(ConfigStore.TG_BOT_TASK_TYPE, "") != BotTaskType.CREATE.name)
        val currentStep = if (step == BotCreationStep.NONE) {
            if (forceNew) BotCreationStep.INIT
            else {
                val savedStepStr = ConfigStore.get(ConfigStore.TG_BOT_TASK_STEP, BotCreationStep.INIT.name)
                runCatching { BotCreationStep.valueOf(savedStepStr) }.getOrDefault(BotCreationStep.INIT)
            }
        } else {
            step
        }

        ConfigStore.put(ConfigStore.TG_BOT_TASK_TYPE, BotTaskType.CREATE.name)
        ConfigStore.put(ConfigStore.TG_BOT_TASK_STEP, currentStep.name)

        val tdClient = requireClient()
        val me = resolveCurrentUser(tdClient)
        val botFatherChat = resolveBotFatherChat(tdClient)

        if (isNewTask) {
            val startMsgId = latestMessageId(tdClient, botFatherChat.id)
            ConfigStore.put(ConfigStore.TG_BOT_TASK_START_MSG_ID, startMsgId)
        }

        val cachedUsername = ConfigStore.get<String>(ConfigStore.TG_BOT_USERNAME)
        val botUsername = cachedUsername ?: "souz_control_${me.id}_${System.currentTimeMillis() % 10000}_bot"

        when (currentStep) {
            BotCreationStep.NONE -> Unit

            BotCreationStep.INIT -> {
                logger.info("BotCreationStep.INIT")
                val snapshots = loadBotFatherSnapshots(tdClient, botFatherChat.id)
                val waitingForName = BotFatherReplyParser.isWaitingForName(snapshots)
                logger.info("BotCreationStep.INIT: isWaitingForName={}", waitingForName)

                if (!waitingForName) {
                    logger.info("BotCreationStep.INIT: Sending /newbot")
                    sendTextMessage(tdClient, botFatherChat.id, "/newbot")
                    delay(BOT_FATHER_STEP_DELAY_MS)
                }
                createControlBot(BotCreationStep.NAME)
            }

            BotCreationStep.NAME -> {
                logger.info("BotCreationStep.NAME")
                val snapshots = loadBotFatherSnapshots(tdClient, botFatherChat.id)
                val waitingForUsername = BotFatherReplyParser.isWaitingForUsername(snapshots)
                logger.info("BotCreationStep.NAME: isWaitingForUsername={}", waitingForUsername)

                if (!waitingForUsername) {
                    val botName = "Souz AI"
                    logger.info("BotCreationStep.NAME: Sending bot name: {}", botName)
                    sendTextMessage(tdClient, botFatherChat.id, botName)
                    delay(BOT_FATHER_STEP_DELAY_MS)
                }
                createControlBot(BotCreationStep.USERNAME)
            }

            BotCreationStep.USERNAME -> {
                if (cachedUsername == null) {
                    ConfigStore.put(ConfigStore.TG_BOT_USERNAME, botUsername)
                }

                val tokenExtracted = BotFatherReplyParser.extractToken(
                    loadBotFatherSnapshots(tdClient, botFatherChat.id),
                ) != null

                if (!tokenExtracted) {
                    sendTextMessage(tdClient, botFatherChat.id, botUsername)
                }
                createControlBot(BotCreationStep.WAIT_TOKEN)
            }

            BotCreationStep.WAIT_TOKEN -> {
                val token = waitForNewBotToken(tdClient, botFatherChat.id)
                    ?: throw IllegalStateException("Failed to extract bot token from BotFather replies")

                ConfigStore.put(ConfigStore.TG_BOT_TOKEN, token)
                ConfigStore.put(ConfigStore.TG_BOT_OWNER_ID, me.id)
                logger.info("Control bot created for ownerId={}", me.id)

                createControlBot(BotCreationStep.START)
            }

            BotCreationStep.START -> {
                runCatching {
                    delay(BOT_FATHER_STEP_DELAY_MS)
                    val newBotChat = tdClient.send(TdApi.SearchPublicChat(botUsername)).awaitResult()
                    sendTextMessage(tdClient, newBotChat.id, "/start")
                }.onFailure {
                    logger.warn("Failed to send /start to newly created bot @{}", botUsername)
                }
                createControlBot(BotCreationStep.AVATAR_CMD)
            }

            BotCreationStep.AVATAR_CMD -> {
                runCatching {
                    delay(BOT_FATHER_STEP_DELAY_MS)
                    sendTextMessage(tdClient, botFatherChat.id, "/setuserpic")
                }.onFailure { logger.warn("Failed to set /setuserpic via BotFather: ${it.message}") }
                createControlBot(BotCreationStep.AVATAR_MOCK)
            }

            BotCreationStep.AVATAR_MOCK -> {
                runCatching {
                    delay(BOT_FATHER_STEP_DELAY_MS)
                    sendTextMessage(tdClient, botFatherChat.id, "@$botUsername")
                }.onFailure { logger.warn("Failed to set bot username for avatar via BotFather: ${it.message}") }
                createControlBot(BotCreationStep.AVATAR_PIC)
            }

            BotCreationStep.AVATAR_PIC -> {
                runCatching {
                    val readyForAvatar = waitForAvatarUploadPrompt(tdClient, botFatherChat.id)
                    if (!readyForAvatar) {
                        throw IllegalStateException("BotFather did not request profile photo upload")
                    }
                    uploadBotAvatar(tdClient, botFatherChat.id)
                    delay(BOT_FATHER_STEP_DELAY_MS)
                }.onFailure { logger.warn("Failed to set bot avatar via BotFather: ${it.message}") }
                createControlBot(BotCreationStep.SETCMDS_CMD)
            }

            BotCreationStep.SETCMDS_CMD -> {
                runCatching {
                    delay(BOT_FATHER_STEP_DELAY_MS)
                    sendTextMessage(tdClient, botFatherChat.id, "/setcommands")
                }.onFailure { logger.warn("Failed to send /setcommands via BotFather: ${it.message}") }
                createControlBot(BotCreationStep.SETCMDS_BOT)
            }

            BotCreationStep.SETCMDS_BOT -> {
                runCatching {
                    delay(BOT_FATHER_STEP_DELAY_MS)
                    sendTextMessage(tdClient, botFatherChat.id, "@$botUsername")
                }.onFailure { logger.warn("Failed to select bot for /setcommands: ${it.message}") }
                createControlBot(BotCreationStep.SETCMDS_LIST)
            }

            BotCreationStep.SETCMDS_LIST -> {
                runCatching {
                    delay(BOT_FATHER_STEP_DELAY_MS)
                    sendTextMessage(tdClient, botFatherChat.id, "clean - Clear context")
                }.onFailure { logger.warn("Failed to send commands list to BotFather: ${it.message}") }
                createControlBot(BotCreationStep.FINISHED)
            }

            BotCreationStep.FINISHED -> {
                ConfigStore.put(ConfigStore.TG_BOT_TASK_TYPE, BotTaskType.NONE.name)
                ConfigStore.put(ConfigStore.TG_BOT_TASK_STEP, BotCreationStep.NONE.name)
                ConfigStore.rm(ConfigStore.TG_BOT_TASK_START_MSG_ID)
            }
        }
    }

    suspend fun deleteControlBot(step: BotDeletionStep = BotDeletionStep.NONE, forceNew: Boolean = false) {
        if (forceNew) {
            ConfigStore.put(ConfigStore.TG_BOT_TASK_TYPE, BotTaskType.DELETE.name)
            ConfigStore.put(ConfigStore.TG_BOT_TASK_STEP, BotDeletionStep.INIT.name)
            ConfigStore.rm(ConfigStore.TG_BOT_TASK_START_MSG_ID)
        }

        val isNewTask = forceNew ||
            (step == BotDeletionStep.NONE && ConfigStore.get(ConfigStore.TG_BOT_TASK_TYPE, "") != BotTaskType.DELETE.name)
        val currentStep = if (step == BotDeletionStep.NONE) {
            if (forceNew) BotDeletionStep.INIT
            else {
                val savedStepStr = ConfigStore.get(ConfigStore.TG_BOT_TASK_STEP, BotDeletionStep.INIT.name)
                runCatching { BotDeletionStep.valueOf(savedStepStr) }.getOrDefault(BotDeletionStep.INIT)
            }
        } else {
            step
        }

        ConfigStore.put(ConfigStore.TG_BOT_TASK_TYPE, BotTaskType.DELETE.name)
        ConfigStore.put(ConfigStore.TG_BOT_TASK_STEP, currentStep.name)

        val tdClient = requireClient()
        val botUsername = resolveControlBotUsername()
        if (botUsername == null) {
            clearControlBotCredentials()
            logger.info("Control bot username is unknown, stale credentials were cleared")
            ConfigStore.put(ConfigStore.TG_BOT_TASK_TYPE, BotTaskType.NONE.name)
            ConfigStore.put(ConfigStore.TG_BOT_TASK_STEP, BotDeletionStep.NONE.name)
            ConfigStore.rm(ConfigStore.TG_BOT_TASK_START_MSG_ID)
            return
        }

        val botFatherChat = resolveBotFatherChat(tdClient)
        if (isNewTask) {
            val startMsgId = latestMessageId(tdClient, botFatherChat.id)
            ConfigStore.put(ConfigStore.TG_BOT_TASK_START_MSG_ID, startMsgId)
        }

        when (currentStep) {
            BotDeletionStep.NONE -> Unit

            BotDeletionStep.INIT -> {
                sendTextMessage(tdClient, botFatherChat.id, "/deletebot")
                deleteControlBot(BotDeletionStep.WAIT_NO_BOTS)
            }

            BotDeletionStep.WAIT_NO_BOTS -> {
                if (waitForNoBotsState(tdClient, botFatherChat.id)) {
                    clearControlBotCredentials()
                    logger.info("Control bot credentials cleared because BotFather has no bots for current account")
                    deleteControlBot(BotDeletionStep.FINISHED)
                } else {
                    deleteControlBot(BotDeletionStep.USERNAME)
                }
            }

            BotDeletionStep.USERNAME -> {
                delay(BOT_FATHER_STEP_DELAY_MS)
                sendTextMessage(tdClient, botFatherChat.id, "@$botUsername")
                deleteControlBot(BotDeletionStep.WAIT_DELETION)
            }

            BotDeletionStep.WAIT_DELETION -> {
                val deleted = waitForBotDeletionConfirmation(tdClient, botFatherChat.id, botUsername) ||
                    isBotMissingInMyBotsList(tdClient, botFatherChat.id, botUsername)
                if (!deleted) {
                    throw IllegalStateException("BotFather did not confirm deletion for @$botUsername")
                }

                clearControlBotCredentials()
                logger.info("Control bot @{} deleted and local credentials cleared", botUsername)
                deleteControlBot(BotDeletionStep.FINISHED)
            }

            BotDeletionStep.FINISHED -> {
                ConfigStore.put(ConfigStore.TG_BOT_TASK_TYPE, BotTaskType.NONE.name)
                ConfigStore.put(ConfigStore.TG_BOT_TASK_STEP, BotDeletionStep.NONE.name)
                ConfigStore.rm(ConfigStore.TG_BOT_TASK_START_MSG_ID)
            }
        }
    }

    suspend fun fetchActiveBotUsernameFromBotFather(): String? {
        val tdClient = requireClient()
        val botFatherChat = resolveBotFatherChat(tdClient)
        val localUsername = resolveControlBotUsername() ?: return null

        val sentMsg = sendTextMessage(tdClient, botFatherChat.id, "/mybots")
        val baselineId = sentMsg.id

        repeat(BOT_FATHER_POLL_ATTEMPTS) {
            delay(BOT_FATHER_POLL_DELAY_MS)
            val snapshots = loadBotFatherSnapshots(tdClient, botFatherChat.id).filter { it.id > baselineId }
            if (snapshots.isEmpty()) return@repeat

            if (BotFatherReplyParser.hasNoBots(snapshots)) {
                return null
            }
            val listedUsernames = BotFatherReplyParser.listedBotUsernames(snapshots)
            if (listedUsernames.isNotEmpty()) {
                val normalized = localUsername.removePrefix("@").lowercase()
                return if (listedUsernames.contains(normalized)) {
                    localUsername
                } else {
                    null
                }
            }
        }
        return null
    }

    private suspend fun resolveBotFatherChat(tdClient: SimpleTelegramClient): TdApi.Chat {
        return runCatching {
            tdClient.send(TdApi.SearchPublicChat("botfather")).awaitResult()
        }.getOrElse {
            throw IllegalStateException("Failed to resolve @BotFather. Please check internet connection.")
        }
    }

    private suspend fun latestMessageId(tdClient: SimpleTelegramClient, chatId: Long): Long {
        val history = tdClient.send(TdApi.GetChatHistory(chatId, 0L, 0, 1, false)).awaitResult()
        return history.messages.orEmpty().firstOrNull()?.id ?: 0L
    }

    private suspend fun sendTextMessage(tdClient: SimpleTelegramClient, chatId: Long, text: String): TdApi.Message {
        return tdClient.send(
            TdApi.SendMessage(
                chatId,
                0L,
                null,
                null,
                null,
                TdApi.InputMessageText(
                    TdApi.FormattedText(text, null),
                    null,
                    false,
                ),
            ),
        ).awaitResult()
    }

    private suspend fun waitForNewBotToken(
        tdClient: SimpleTelegramClient,
        chatId: Long,
    ): String? {
        repeat(BOT_FATHER_POLL_ATTEMPTS) {
            delay(BOT_FATHER_POLL_DELAY_MS)
            val snapshots = loadBotFatherSnapshots(tdClient, chatId)
            val token = BotFatherReplyParser.extractToken(snapshots)
            if (!token.isNullOrBlank()) {
                return token
            }
        }
        return null
    }

    private suspend fun waitForNoBotsState(
        tdClient: SimpleTelegramClient,
        chatId: Long,
    ): Boolean {
        repeat(BOT_FATHER_POLL_ATTEMPTS) {
            delay(BOT_FATHER_POLL_DELAY_MS)
            val snapshots = loadBotFatherSnapshots(tdClient, chatId)
            if (BotFatherReplyParser.hasNoBots(snapshots)) {
                return true
            }
            if (snapshots.any { !it.isOutgoing }) {
                return false
            }
        }
        return false
    }

    private suspend fun waitForBotDeletionConfirmation(
        tdClient: SimpleTelegramClient,
        chatId: Long,
        botUsername: String,
    ): Boolean {
        var confirmationSent = false

        repeat(BOT_FATHER_POLL_ATTEMPTS) {
            delay(BOT_FATHER_POLL_DELAY_MS)
            val snapshots = loadBotFatherSnapshots(tdClient, chatId)
            if (BotFatherReplyParser.isDeleteConfirmed(snapshots, botUsername)) {
                return true
            }
            if (!confirmationSent && BotFatherReplyParser.requiresDeleteConfirmationText(snapshots)) {
                sendTextMessage(tdClient, chatId, "Yes, I am totally sure.")
                confirmationSent = true
            }
        }

        return false
    }

    private suspend fun isBotMissingInMyBotsList(
        tdClient: SimpleTelegramClient,
        chatId: Long,
        username: String,
    ): Boolean {
        val normalizedUsername = normalizeBotUsername(username) ?: return false
        sendTextMessage(tdClient, chatId, "/mybots")

        repeat(BOT_FATHER_POLL_ATTEMPTS) {
            delay(BOT_FATHER_POLL_DELAY_MS)
            val snapshots = loadBotFatherSnapshots(tdClient, chatId)
            if (BotFatherReplyParser.hasNoBots(snapshots)) {
                return true
            }
            val listedBots = BotFatherReplyParser.listedBotUsernames(snapshots)
            if (listedBots.isNotEmpty()) {
                return normalizedUsername !in listedBots
            }
        }

        return false
    }

    private suspend fun loadBotFatherSnapshots(
        tdClient: SimpleTelegramClient,
        chatId: Long,
        limit: Int = 20,
    ): List<BotFatherMessageSnapshot> {
        val history = tdClient.send(TdApi.GetChatHistory(chatId, 0L, 0, limit, false)).awaitResult()
        return history.messages.orEmpty().map { message ->
            BotFatherMessageSnapshot(
                id = message.id,
                text = extractMessageText(message),
                isOutgoing = message.isOutgoing,
            )
        }
    }

    private suspend fun uploadBotAvatar(tdClient: SimpleTelegramClient, chatId: Long) {
        val avatarFilePath = copyBotAvatarToTempFile() ?: run {
            logger.warn("Bot avatar resource not found at /bot_avatar.png")
            return
        }

        tdClient.send(
            TdApi.SendMessage(
                chatId,
                0L,
                null,
                null,
                null,
                TdApi.InputMessagePhoto(
                    TdApi.InputFileLocal(avatarFilePath.toAbsolutePath().toString()),
                    null,
                    null,
                    0,
                    0,
                    null,
                    false,
                    null,
                    false,
                ),
            ),
        ).awaitResult()
    }

    private fun copyBotAvatarToTempFile(): Path? {
        val avatarStream = TelegramBotWorkflow::class.java.getResourceAsStream("/bot_avatar.png") ?: return null
        val tempDir = Path.of(System.getProperty("java.io.tmpdir"), "souz")
        Files.createDirectories(tempDir)
        val file = tempDir.resolve("bot_avatar.png")
        avatarStream.use { input ->
            Files.newOutputStream(
                file,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ).use { output ->
                input.copyTo(output)
            }
        }
        return file
    }

    private suspend fun waitForAvatarUploadPrompt(
        tdClient: SimpleTelegramClient,
        chatId: Long,
    ): Boolean {
        repeat(BOT_FATHER_POLL_ATTEMPTS) {
            delay(BOT_FATHER_POLL_DELAY_MS)
            val snapshots = loadBotFatherSnapshots(tdClient, chatId)
            if (BotFatherReplyParser.isWaitingForProfilePhoto(snapshots)) {
                return true
            }
        }
        return false
    }

    private fun resolveControlBotUsername(): String? {
        val configured = normalizeBotUsername(ConfigStore.get(ConfigStore.TG_BOT_USERNAME))
        if (configured != null) {
            return configured
        }

        val token = ConfigStore.get<String>(ConfigStore.TG_BOT_TOKEN) ?: return null
        val resolvedFromApi = normalizeBotUsername(resolveBotUsernameByToken(token))
        if (resolvedFromApi != null) {
            ConfigStore.put(ConfigStore.TG_BOT_USERNAME, resolvedFromApi)
        }
        return resolvedFromApi
    }

    private fun resolveBotUsernameByToken(token: String): String? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.telegram.org/bot$token/getMe"))
            .timeout(Duration.ofMillis(BOT_LOOKUP_TIMEOUT_MS))
            .GET()
            .build()

        val body = runCatching {
            botLookupHttpClient.send(request, HttpResponse.BodyHandlers.ofString()).body()
        }.getOrNull() ?: return null

        val json = runCatching { ru.souz.giga.gigaJsonMapper.readTree(body) }.getOrNull() ?: return null
        if (!json.path("ok").asBoolean(false)) {
            return null
        }
        return json.path("result").path("username").asText(null)
    }

    private fun normalizeBotUsername(value: String?): String? =
        value?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }

    private fun clearControlBotCredentials() {
        ConfigStore.rm(ConfigStore.TG_BOT_TOKEN)
        ConfigStore.rm(ConfigStore.TG_BOT_OWNER_ID)
        ConfigStore.rm(ConfigStore.TG_BOT_USERNAME)
    }
}
