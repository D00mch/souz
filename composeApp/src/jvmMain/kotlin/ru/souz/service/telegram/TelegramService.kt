package ru.souz.service.telegram

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import it.tdlight.client.APIToken
import it.tdlight.client.AuthenticationData
import it.tdlight.client.AuthenticationSupplier
import it.tdlight.client.ClientInteraction
import it.tdlight.client.InputParameter
import it.tdlight.client.ParameterInfo
import it.tdlight.client.ParameterInfoCode
import it.tdlight.client.ParameterInfoPasswordHint
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.client.SimpleTelegramClientFactory
import it.tdlight.client.TDLibSettings
import it.tdlight.client.TelegramError
import it.tdlight.jni.TdApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import ru.souz.db.ConfigStore
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay

private const val TELEGRAM_MAX_CONTACTS_CACHE = 5_000
private const val TELEGRAM_MAX_CHATS_CACHE = 100
private const val TELEGRAM_DEFAULT_API_ID = 34456605
private const val TELEGRAM_DEFAULT_API_HASH = "04779e90346d857b3f0f313ff8d2aa39"
private const val TELEGRAM_CFG_DEBUG_LOGS = "TELEGRAM_DEBUG_LOGS"
private const val TELEGRAM_ENV_DEBUG_LOGS = "SOUZ_TG_DEBUG_LOGS"
private const val BOT_FATHER_STEP_DELAY_MS = 1_500L
private const val BOT_FATHER_POLL_DELAY_MS = 1_000L
private const val BOT_FATHER_POLL_ATTEMPTS = 10
private const val BOT_LOOKUP_TIMEOUT_MS = 5_000L

enum class TelegramAuthStep {
    INITIALIZING,
    WAIT_PHONE,
    WAIT_CODE,
    WAIT_PASSWORD,
    READY,
    LOGGING_OUT,
    CLOSED,
    ERROR,
}

enum class BotTaskType { NONE, CREATE, DELETE }

enum class BotCreationStep {
    NONE, INIT, NAME, USERNAME, WAIT_TOKEN, START, AVATAR_CMD, AVATAR_MOCK, AVATAR_PIC, FINISHED
}

enum class BotDeletionStep {
    NONE, INIT, WAIT_NO_BOTS, USERNAME, WAIT_DELETION, FINISHED
}

data class TelegramAuthState(
    val step: TelegramAuthStep = TelegramAuthStep.INITIALIZING,
    val activePhoneMasked: String? = null,
    val codeHint: String? = null,
    val passwordHint: String? = null,
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
)

data class TelegramCachedContact(
    val userId: Long,
    val displayName: String,
    val aliases: Set<String>,
)

data class TelegramCachedChat(
    val chatId: Long,
    val title: String,
    val unreadCount: Int,
    val lastMessageId: Long,
    val lastMessageText: String?,
    val order: Long,
    val linkedUserId: Long?,
)

data class TelegramInboxItem(
    val chatId: Long,
    val title: String,
    val unreadCount: Int,
    val lastText: String?,
)

data class TelegramMessageView(
    val chatId: Long,
    val chatTitle: String,
    val messageId: Long,
    val sender: String?,
    val unixTime: Long,
    val text: String?,
)

enum class TelegramChatAction {
    Mute,
    Archive,
    MarkRead,
    Delete,
}


class TelegramService(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val l = LoggerFactory.getLogger(TelegramService::class.java)
    private val botLookupHttpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(BOT_LOOKUP_TIMEOUT_MS))
        .build()

    private val authStateFlow = MutableStateFlow(TelegramAuthState())
    val authState: StateFlow<TelegramAuthState> = authStateFlow.asStateFlow()

    private val contactsByUserId = ConcurrentHashMap<Long, TelegramCachedContact>()
    private val usersById = ConcurrentHashMap<Long, TdApi.User>()
    private val chatsById = ConcurrentHashMap<Long, TelegramCachedChat>()
    private val privateChatByUserId = ConcurrentHashMap<Long, Long>()
    private val orderedChatIdsRef = AtomicReference<List<Long>>(emptyList())
    private val meUserIdRef = AtomicReference<Long?>(null)

    private val authBridge = InteractiveAuthBridge()
    private val clientFactory = SimpleTelegramClientFactory()
    private val clientMutex = Mutex()

    @Volatile
    private var client: SimpleTelegramClient? = null

    init {
        applyTdlightLogLevel(isTelegramDebugLogsEnabled())
        scope.launch {
            startClientIfNeeded()
            resumePendingBotTasks()
        }
    }

    private suspend fun resumePendingBotTasks() {
        val taskTypeStr = ConfigStore.get(ConfigStore.TG_BOT_TASK_TYPE, BotTaskType.NONE.name)
        val taskType = runCatching { BotTaskType.valueOf(taskTypeStr) }.getOrDefault(BotTaskType.NONE)

        if (taskType == BotTaskType.NONE) return

        l.info("Pending bot task found ({}). Waiting for Telegram to be READY...", taskType)
        authStateFlow.first { it.step == TelegramAuthStep.READY }

        when (taskType) {
            BotTaskType.CREATE -> {
                l.info("Resuming pending createControlBot task")
                createControlBot()
            }
            BotTaskType.DELETE -> {
                l.info("Resuming pending deleteControlBot task")
                deleteControlBot()
            }
            BotTaskType.NONE -> {
                /* Should not happen due to check above */
            }
        }
    }

    suspend fun submitPhoneNumber(phoneNumber: String) {
        val normalized = normalizePhone(phoneNumber)
        if (normalized.isBlank()) {
            throw IllegalArgumentException("Phone number is required")
        }
        startClientIfNeeded()
        authBridge.providePhone(normalized)
        authStateFlow.update {
            it.copy(
                step = TelegramAuthStep.WAIT_CODE,
                isBusy = true,
                errorMessage = null,
            )
        }
    }

    fun submitLoginCode(code: String) {
        val normalized = code.trim()
        if (normalized.isBlank()) {
            throw IllegalArgumentException("Login code is required")
        }
        authBridge.provideCode(normalized)
        authStateFlow.update {
            it.copy(
                isBusy = true,
                errorMessage = null,
            )
        }
    }

    fun submitTwoFaPassword(password: String) {
        if (password.isBlank()) {
            throw IllegalArgumentException("2FA password is required")
        }
        authBridge.providePassword(password)
        authStateFlow.update {
            it.copy(
                isBusy = true,
                errorMessage = null,
            )
        }
    }

    suspend fun logout() {
        val currentClient = client ?: return
        authStateFlow.update {
            it.copy(
                step = TelegramAuthStep.LOGGING_OUT,
                isBusy = true,
                errorMessage = null,
            )
        }
        runCatching { currentClient.logOutAsync().awaitResult() }
            .onFailure { err ->
                l.debug("Telegram logout returned error", err)
            }
        restartClient()
    }

    suspend fun cancelAuth() {
        restartClient()
    }

    suspend fun requestCodeAgain(phoneNumber: String) {
        val normalized = normalizePhone(phoneNumber)
        if (normalized.isBlank()) {
            throw IllegalArgumentException("Phone number is required")
        }
        restartClient()
        submitPhoneNumber(normalized)
    }

    suspend fun readUnreadInbox(limit: Int = 50): List<TelegramInboxItem> {
        requireReady()
        val cappedLimit = limit.coerceIn(1, TELEGRAM_MAX_CHATS_CACHE)
        refreshTopChatsCache()
        val ordered = orderedChatIdsRef.get()
        return ordered
            .asSequence()
            .mapNotNull(chatsById::get)
            .filter { it.unreadCount > 0 }
            .take(cappedLimit)
            .map {
                TelegramInboxItem(
                    chatId = it.chatId,
                    title = it.title,
                    unreadCount = it.unreadCount,
                    lastText = it.lastMessageText,
                )
            }
            .toList()
    }

    suspend fun getHistory(chatName: String, limit: Int): List<TelegramMessageView> {
        val chat = resolveChatByName(chatName)
        val cappedLimit = limit.coerceIn(1, 100)
        val result = requireClient().send(
            TdApi.GetChatHistory(chat.chatId, 0L, 0, cappedLimit, false)
        ).awaitResult()
        return result.messages.orEmpty()
            .map(::messageToView)
    }

    suspend fun setChatState(chatName: String, action: TelegramChatAction): TelegramCachedChat {
        val chat = resolveChatByName(chatName)
        val tdClient = requireClient()
        when (action) {
            TelegramChatAction.Mute -> {
                val fullChat = tdClient.send(TdApi.GetChat(chat.chatId)).awaitResult()
                val settings = fullChat.notificationSettings ?: TdApi.ChatNotificationSettings()
                settings.useDefaultMuteFor = false
                settings.muteFor = Int.MAX_VALUE
                tdClient.send(TdApi.SetChatNotificationSettings(chat.chatId, settings)).awaitResult()
            }

            TelegramChatAction.Archive -> {
                tdClient.send(TdApi.AddChatToList(chat.chatId, TdApi.ChatListArchive())).awaitResult()
            }

            TelegramChatAction.MarkRead -> {
                val messageId = chat.lastMessageId.takeIf { it > 0L }
                    ?: tdClient.send(TdApi.GetChat(chat.chatId)).awaitResult().lastMessage?.id
                    ?: 0L

                if (messageId > 0L) {
                    tdClient.send(
                        TdApi.ViewMessages(
                            chat.chatId,
                            longArrayOf(messageId),
                            null,
                            true,
                        )
                    ).awaitResult()
                }
                tdClient.send(TdApi.ToggleChatIsMarkedAsUnread(chat.chatId, false)).awaitResult()
            }

            TelegramChatAction.Delete -> {
                tdClient.send(TdApi.DeleteChatHistory(chat.chatId, true, false)).awaitResult()
            }
        }

        return refreshChat(chat.chatId)
    }

    suspend fun sendMessageToTarget(targetName: String, text: String): TelegramMessageView {
        if (text.isBlank()) {
            throw IllegalArgumentException("Message text is empty")
        }

        val contact = resolveContactByName(targetName)
        val tdClient = requireClient()
        val privateChat = tdClient.send(TdApi.CreatePrivateChat(contact.userId, false)).awaitResult()
        cacheChat(privateChat)

        sendChatAction(privateChat.id, typing = true)
        val sentMessage = try {
            tdClient.send(
                TdApi.SendMessage(
                    privateChat.id,
                    0L,
                    null,
                    null,
                    null,
                    TdApi.InputMessageText(
                        TdApi.FormattedText(text, null),
                        null,
                        false,
                    )
                )
            ).awaitResult()
        } finally {
            runCatching {
                sendChatAction(privateChat.id, typing = false)
            }
        }

        updateChatFromMessage(sentMessage)
        return messageToView(sentMessage)
    }

    suspend fun createControlBot(step: BotCreationStep = BotCreationStep.NONE, forceNew: Boolean = false) {
        if (forceNew) {
            ConfigStore.put(ConfigStore.TG_BOT_TASK_TYPE, BotTaskType.CREATE.name)
            ConfigStore.put(ConfigStore.TG_BOT_TASK_STEP, BotCreationStep.INIT.name)
            ConfigStore.rm(ConfigStore.TG_BOT_TASK_START_MSG_ID)
            ConfigStore.rm(ConfigStore.TG_BOT_USERNAME)
        }

        val isNewTask = forceNew || (step == BotCreationStep.NONE && ConfigStore.get(ConfigStore.TG_BOT_TASK_TYPE, "") != BotTaskType.CREATE.name)
        val currentStep = if (step == BotCreationStep.NONE) {
            if (forceNew) BotCreationStep.INIT
            else {
                val savedStepStr = ConfigStore.get(ConfigStore.TG_BOT_TASK_STEP, BotCreationStep.INIT.name)
                runCatching { BotCreationStep.valueOf(savedStepStr) }.getOrDefault(BotCreationStep.INIT)
            }
        } else step

        ConfigStore.put(ConfigStore.TG_BOT_TASK_TYPE, BotTaskType.CREATE.name)
        ConfigStore.put(ConfigStore.TG_BOT_TASK_STEP, currentStep.name)

        val tdClient = requireClient()
        val me = resolveCurrentUser(tdClient)
        val botFatherChat = resolveBotFatherChat(tdClient)
        
        if (isNewTask) {
            val startMsgId = latestMessageId(tdClient, botFatherChat.id)
            ConfigStore.put(ConfigStore.TG_BOT_TASK_START_MSG_ID, startMsgId)
        }
        val baselineMessageId = ConfigStore.get(ConfigStore.TG_BOT_TASK_START_MSG_ID, 0L)

        val cachedUsername = ConfigStore.get<String>(ConfigStore.TG_BOT_USERNAME)
        val botUsername = cachedUsername ?: "souz_control_${me.id}_${System.currentTimeMillis() % 10000}_bot"

        when (currentStep) {
            BotCreationStep.NONE -> { /* Should not be reached */ }

            BotCreationStep.INIT -> {
                l.info("BotCreationStep.INIT")
                val snapshots = loadBotFatherSnapshots(tdClient, botFatherChat.id)
                val waitingForName = BotFatherReplyParser.isWaitingForName(snapshots)
                l.info("BotCreationStep.INIT: isWaitingForName={}", waitingForName)
                
                if (!waitingForName) {
                    l.info("BotCreationStep.INIT: Sending /newbot")
                    sendTextMessage(tdClient, botFatherChat.id, "/newbot")
                    delay(BOT_FATHER_STEP_DELAY_MS)
                }
                createControlBot(BotCreationStep.NAME)
            }

            BotCreationStep.NAME -> {
                l.info("BotCreationStep.NAME")
                val snapshots = loadBotFatherSnapshots(tdClient, botFatherChat.id)
                val waitingForUsername = BotFatherReplyParser.isWaitingForUsername(snapshots)
                l.info("BotCreationStep.NAME: isWaitingForUsername={}", waitingForUsername)
                
                if (!waitingForUsername) {
                    val botName = "Souz PC Control"
                    l.info("BotCreationStep.NAME: Sending bot name: {}", botName)
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
                    loadBotFatherSnapshots(tdClient, botFatherChat.id)
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
                // TG_BOT_USERNAME is already saved in BotCreationStep.USERNAME
                l.info("Control bot created for ownerId={}", me.id)

                createControlBot(BotCreationStep.START)
            }

            BotCreationStep.START -> {
                runCatching {
                    delay(BOT_FATHER_STEP_DELAY_MS)
                    val newBotChat = tdClient.send(TdApi.SearchPublicChat(botUsername)).awaitResult()
                    sendTextMessage(tdClient, newBotChat.id, "/start")
                }.onFailure {
                    l.warn("Failed to send /start to newly created bot @{}", botUsername)
                }
                createControlBot(BotCreationStep.AVATAR_CMD)
            }

            BotCreationStep.AVATAR_CMD -> {
                runCatching {
                    delay(BOT_FATHER_STEP_DELAY_MS)
                    sendTextMessage(tdClient, botFatherChat.id, "/setuserpic")
                }.onFailure { l.warn("Failed to set /setuserpic via BotFather: ${it.message}") }
                createControlBot(BotCreationStep.AVATAR_MOCK)
            }

            BotCreationStep.AVATAR_MOCK -> {
                runCatching {
                    delay(BOT_FATHER_STEP_DELAY_MS)
                    sendTextMessage(tdClient, botFatherChat.id, "@$botUsername")
                }.onFailure { l.warn("Failed to set bot username for avatar via BotFather: ${it.message}") }
                createControlBot(BotCreationStep.AVATAR_PIC)
            }

            BotCreationStep.AVATAR_PIC -> {
                runCatching {
                    delay(BOT_FATHER_STEP_DELAY_MS)
                    uploadBotAvatar(tdClient, botFatherChat.id)
                }.onFailure { l.warn("Failed to set bot avatar via BotFather: ${it.message}") }
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

        val isNewTask = forceNew || (step == BotDeletionStep.NONE && ConfigStore.get(ConfigStore.TG_BOT_TASK_TYPE, "") != BotTaskType.DELETE.name)
        val currentStep = if (step == BotDeletionStep.NONE) {
            if (forceNew) BotDeletionStep.INIT
            else {
                val savedStepStr = ConfigStore.get(ConfigStore.TG_BOT_TASK_STEP, BotDeletionStep.INIT.name)
                runCatching { BotDeletionStep.valueOf(savedStepStr) }.getOrDefault(BotDeletionStep.INIT)
            }
        } else step

        ConfigStore.put(ConfigStore.TG_BOT_TASK_TYPE, BotTaskType.DELETE.name)
        ConfigStore.put(ConfigStore.TG_BOT_TASK_STEP, currentStep.name)

        val tdClient = requireClient()
        val botUsername = resolveControlBotUsername()

        if (botUsername == null) {
            clearControlBotCredentials()
            l.info("Control bot username is unknown, stale credentials were cleared")
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
        val baselineMessageId = ConfigStore.get(ConfigStore.TG_BOT_TASK_START_MSG_ID, 0L)

        when (currentStep) {
            BotDeletionStep.NONE -> { /* Should not be reached */ }

            BotDeletionStep.INIT -> {
                sendTextMessage(tdClient, botFatherChat.id, "/deletebot")
                deleteControlBot(BotDeletionStep.WAIT_NO_BOTS)
            }

            BotDeletionStep.WAIT_NO_BOTS -> {
                if (waitForNoBotsState(tdClient, botFatherChat.id)) {
                    clearControlBotCredentials()
                    l.info("Control bot credentials cleared because BotFather has no bots for current account")
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
                l.info("Control bot @{} deleted and local credentials cleared", botUsername)
                deleteControlBot(BotDeletionStep.FINISHED)
            }

            BotDeletionStep.FINISHED -> {
                ConfigStore.put(ConfigStore.TG_BOT_TASK_TYPE, BotTaskType.NONE.name)
                ConfigStore.put(ConfigStore.TG_BOT_TASK_STEP, BotDeletionStep.NONE.name)
                ConfigStore.rm(ConfigStore.TG_BOT_TASK_START_MSG_ID)
            }
        }
    }

    private suspend fun resolveCurrentUser(tdClient: SimpleTelegramClient): TdApi.User {
        val cachedMeId = meUserIdRef.get()
        val cached = cachedMeId?.let(usersById::get)
        if (cached != null) {
            return cached
        }

        val me = tdClient.send(TdApi.GetMe()).awaitResult()
        meUserIdRef.set(me.id)
        cacheUser(me)
        return me
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

    private suspend fun sendTextMessage(tdClient: SimpleTelegramClient, chatId: Long, text: String) {
        tdClient.send(
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
        val baselineMessageId = latestMessageId(tdClient, chatId)
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
            l.warn("Bot avatar resource not found at /bot_avatar.png")
            return
        }

        try {
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
        } finally {
            runCatching { Files.deleteIfExists(avatarFilePath) }
        }
    }

    private fun copyBotAvatarToTempFile(): Path? {
        val avatarStream = TelegramService::class.java.getResourceAsStream("/bot_avatar.png") ?: return null
        val file = Files.createTempFile("bot_avatar", ".png")
        avatarStream.use { input ->
            Files.newOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        return file
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

    suspend fun forwardMessage(fromChat: String, toChat: String, messageId: String): TelegramMessageView {
        val sourceChat = resolveChatByName(fromChat)
        val targetChat = resolveChatByName(toChat)
        val sourceMessageId = if (messageId.equals("last", ignoreCase = true)) {
            getHistory(sourceChat.title, 1).firstOrNull()?.messageId
                ?: throw IllegalStateException("No messages found in source chat")
        } else {
            messageId.toLongOrNull() ?: throw IllegalArgumentException("messageId must be numeric or 'last'")
        }

        val result = requireClient().send(
            TdApi.ForwardMessages(
                targetChat.chatId,
                0L,
                sourceChat.chatId,
                longArrayOf(sourceMessageId),
                null,
                false,
                false,
            )
        ).awaitResult()

        val forwarded = result.messages.orEmpty().firstOrNull()
            ?: throw IllegalStateException("Forward was not completed")

        updateChatFromMessage(forwarded)
        return messageToView(forwarded)
    }

    suspend fun searchMessages(query: String, chatName: String?, limit: Int): List<TelegramMessageView> {
        if (query.isBlank()) {
            throw IllegalArgumentException("query is required")
        }
        val cappedLimit = limit.coerceIn(1, 100)
        val tdClient = requireClient()

        return if (chatName.isNullOrBlank()) {
            val found = tdClient.send(
                TdApi.SearchMessages(
                    null,
                    query,
                    "",
                    cappedLimit,
                    TdApi.SearchMessagesFilterEmpty(),
                    null,
                    0,
                    0,
                )
            ).awaitResult()
            found.messages.orEmpty().map(::messageToView)
        } else {
            val chat = resolveChatByName(chatName)
            val found = tdClient.send(
                TdApi.SearchChatMessages(
                    chat.chatId,
                    null,
                    query,
                    null,
                    0L,
                    0,
                    cappedLimit,
                    TdApi.SearchMessagesFilterEmpty(),
                )
            ).awaitResult()
            found.messages.orEmpty().map(::messageToView)
        }
    }

    suspend fun sendToSavedMessages(text: String): TelegramMessageView {
        if (text.isBlank()) {
            throw IllegalArgumentException("Message text is empty")
        }

        val tdClient = requireClient()
        val meUserId = meUserIdRef.get() ?: tdClient.send(TdApi.GetMe()).awaitResult().id.also {
            meUserIdRef.set(it)
        }

        val savedMessagesChat = tdClient.send(TdApi.CreatePrivateChat(meUserId, false)).awaitResult()
        cacheChat(savedMessagesChat)

        sendChatAction(savedMessagesChat.id, typing = true)
        val sent = try {
            tdClient.send(
                TdApi.SendMessage(
                    savedMessagesChat.id,
                    0L,
                    null,
                    null,
                    null,
                    TdApi.InputMessageText(
                        TdApi.FormattedText(text, null),
                        null,
                        false,
                    )
                )
            ).awaitResult()
        } finally {
            runCatching {
                sendChatAction(savedMessagesChat.id, typing = false)
            }
        }

        updateChatFromMessage(sent)
        return messageToView(sent)
    }

    suspend fun sendChatAction(chatId: Long, typing: Boolean) {
        if (chatId <= 0L) return
        val action: TdApi.ChatAction = if (typing) TdApi.ChatActionTyping() else TdApi.ChatActionCancel()
        requireClient().send(TdApi.SendChatAction(chatId, 0L, null, action)).awaitResult()
    }

    private fun requireReady() {
        if (authState.value.step != TelegramAuthStep.READY) {
            throw IllegalStateException("Telegram is not connected. Open Settings -> Functions and complete login")
        }
    }

    private suspend fun requireClient(): SimpleTelegramClient {
        startClientIfNeeded()
        requireReady()
        return client ?: throw IllegalStateException("Telegram client is not initialized")
    }

    private suspend fun startClientIfNeeded() {
        clientMutex.withLock {
            if (client != null) {
                return
            }

            val settings = buildTdLibSettings()
            val builder = clientFactory.builder(settings)

            builder.setClientInteraction(authBridge)
            builder.addUpdateHandler(TdApi.UpdateAuthorizationState::class.java, ::onAuthorizationStateUpdate)
            builder.addUpdateHandler(TdApi.UpdateNewChat::class.java) { update ->
                update.chat?.let(::cacheChat)
            }
            builder.addUpdateHandler(TdApi.UpdateUser::class.java) { update ->
                update.user?.let(::cacheUser)
            }
            builder.addUpdateHandler(TdApi.UpdateChatTitle::class.java) { update ->
                chatsById.computeIfPresent(update.chatId) { _, old -> old.copy(title = update.title.orEmpty().ifBlank { old.title }) }
                rebuildOrderedChats()
            }
            builder.addUpdateHandler(TdApi.UpdateChatLastMessage::class.java) { update ->
                chatsById.computeIfPresent(update.chatId) { _, old ->
                    old.copy(
                        lastMessageId = update.lastMessage?.id ?: old.lastMessageId,
                        lastMessageText = update.lastMessage?.let(::extractMessageText) ?: old.lastMessageText,
                        order = update.positions.orEmpty().maxOfOrNull { it.order } ?: old.order,
                    )
                }
                rebuildOrderedChats()
            }
            builder.addUpdateHandler(TdApi.UpdateChatReadInbox::class.java) { update ->
                chatsById.computeIfPresent(update.chatId) { _, old -> old.copy(unreadCount = update.unreadCount) }
            }
            builder.addUpdateHandler(TdApi.UpdateChatPosition::class.java) { update ->
                chatsById.computeIfPresent(update.chatId) { _, old ->
                    old.copy(order = update.position?.order ?: old.order)
                }
                rebuildOrderedChats()
            }
            builder.addUpdateExceptionHandler { throwable ->
                onUnhandledError(throwable)
            }
            builder.addDefaultExceptionHandler { throwable ->
                onUnhandledError(throwable)
            }

            client = builder.build(authBridge)

            authStateFlow.update {
                it.copy(
                    step = TelegramAuthStep.INITIALIZING,
                    isBusy = true,
                    errorMessage = null,
                )
            }
        }
    }

    private suspend fun restartClient() {
        clientMutex.withLock {
            val oldClient = client
            client = null
            authBridge.reset()
            clearCaches()

            runCatching {
                oldClient?.closeAsync()?.awaitResult()
            }.onFailure { err ->
                l.debug("Error while closing Telegram client", err)
            }
        }

        startClientIfNeeded()
    }

    private fun clearCaches() {
        contactsByUserId.clear()
        usersById.clear()
        chatsById.clear()
        privateChatByUserId.clear()
        orderedChatIdsRef.set(emptyList())
        meUserIdRef.set(null)
    }

    private fun onAuthorizationStateUpdate(update: TdApi.UpdateAuthorizationState) {
        when (update.authorizationState?.constructor) {
            TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR -> {
                authStateFlow.update {
                    it.copy(
                        step = TelegramAuthStep.WAIT_PHONE,
                        isBusy = false,
                        codeHint = null,
                        passwordHint = null,
                    )
                }
            }

            TdApi.AuthorizationStateWaitCode.CONSTRUCTOR -> {
                val waitCode = update.authorizationState as TdApi.AuthorizationStateWaitCode
                authStateFlow.update {
                    it.copy(
                        step = TelegramAuthStep.WAIT_CODE,
                        codeHint = waitCode.codeInfo?.phoneNumber,
                        isBusy = false,
                        errorMessage = null,
                    )
                }
            }

            TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR -> {
                val waitPassword = update.authorizationState as TdApi.AuthorizationStateWaitPassword
                authStateFlow.update {
                    it.copy(
                        step = TelegramAuthStep.WAIT_PASSWORD,
                        passwordHint = waitPassword.passwordHint,
                        isBusy = false,
                        errorMessage = null,
                    )
                }
            }

            TdApi.AuthorizationStateReady.CONSTRUCTOR -> {
                authStateFlow.update { it.copy(isBusy = true) }
                scope.launch {
                    runCatching {
                        refreshMeAndCaches()
                        authStateFlow.update {
                            it.copy(
                                step = TelegramAuthStep.READY,
                                isBusy = false,
                                codeHint = null,
                                passwordHint = null,
                                errorMessage = null,
                            )
                        }
                    }.onFailure {
                        onUnhandledError(it)
                    }
                }
            }

            TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR -> {
                authStateFlow.update {
                    it.copy(
                        step = TelegramAuthStep.LOGGING_OUT,
                        isBusy = true,
                        errorMessage = null,
                    )
                }
            }

            TdApi.AuthorizationStateClosing.CONSTRUCTOR,
            TdApi.AuthorizationStateClosed.CONSTRUCTOR -> {
                authStateFlow.update {
                    it.copy(
                        step = TelegramAuthStep.CLOSED,
                        isBusy = false,
                    )
                }
                scope.launch {
                    restartClient()
                }
            }

            else -> {
                authStateFlow.update {
                    if (it.step == TelegramAuthStep.INITIALIZING) {
                        it.copy(isBusy = true)
                    } else {
                        it
                    }
                }
            }
        }
    }

    private suspend fun refreshMeAndCaches() {
        val tdClient = client ?: return

        val me = tdClient.send(TdApi.GetMe()).awaitResult()
        meUserIdRef.set(me.id)
        cacheUser(me)

        authStateFlow.update {
            it.copy(activePhoneMasked = maskPhone(me.phoneNumber))
        }

        refreshContactsCache()
        refreshTopChatsCache()
    }

    private suspend fun refreshContactsCache() {
        val tdClient = client ?: return

        val contacts = tdClient.send(TdApi.GetContacts()).awaitResult().userIds?.toList().orEmpty()
        if (contacts.isEmpty()) {
            return
        }

        val users = contacts
            .take(TELEGRAM_MAX_CONTACTS_CACHE)
            .map { userId ->
                scope.async {
                    runCatching {
                        tdClient.send(TdApi.GetUser(userId)).awaitResult()
                    }.getOrNull()
                }
            }
            .awaitAll()
            .filterNotNull()

        users.forEach(::cacheUser)
    }

    private suspend fun refreshTopChatsCache() {
        val tdClient = client ?: return

        val chats = tdClient.send(TdApi.GetChats(TdApi.ChatListMain(), TELEGRAM_MAX_CHATS_CACHE)).awaitResult()
        val chatIds = chats.chatIds?.toList().orEmpty().take(TELEGRAM_MAX_CHATS_CACHE)
        if (chatIds.isEmpty()) {
            return
        }

        val fullChats = chatIds.mapIndexed { index, chatId ->
            scope.async {
                runCatching {
                    tdClient.send(TdApi.GetChat(chatId)).awaitResult()
                }.getOrNull()?.also { chat ->
                    cacheChat(chat, syntheticOrder = (TELEGRAM_MAX_CHATS_CACHE - index).toLong())
                }
            }
        }.awaitAll()

        if (fullChats.isNotEmpty()) {
            rebuildOrderedChats()
        }
    }

    private suspend fun refreshChat(chatId: Long): TelegramCachedChat {
        val refreshed = requireClient().send(TdApi.GetChat(chatId)).awaitResult()
        return cacheChat(refreshed)
    }

    private fun cacheUser(user: TdApi.User): TelegramCachedContact {
        usersById[user.id] = user

        val displayName = userDisplayName(user)
        val aliases = userAliases(user)
        val cachedContact = TelegramCachedContact(
            userId = user.id,
            displayName = displayName,
            aliases = aliases,
        )

        if (user.isContact || user.id == meUserIdRef.get()) {
            contactsByUserId[user.id] = cachedContact
        }

        privateChatByUserId[user.id]?.let { chatId ->
            chatsById.computeIfPresent(chatId) { _, old -> old.copy(title = displayName) }
            rebuildOrderedChats()
        }

        return cachedContact
    }

    private fun cacheChat(chat: TdApi.Chat, syntheticOrder: Long? = null): TelegramCachedChat {
        val linkedUserId = chatLinkedUserId(chat)
        if (linkedUserId != null) {
            privateChatByUserId[linkedUserId] = chat.id
        }

        val cached = TelegramCachedChat(
            chatId = chat.id,
            title = chatDisplayTitle(chat),
            unreadCount = chat.unreadCount,
            lastMessageId = chat.lastMessage?.id ?: 0L,
            lastMessageText = chat.lastMessage?.let(::extractMessageText),
            order = syntheticOrder ?: chat.positions.orEmpty().maxOfOrNull { it.order } ?: 0L,
            linkedUserId = linkedUserId,
        )

        chatsById[chat.id] = cached
        rebuildOrderedChats()
        return cached
    }

    private fun rebuildOrderedChats() {
        orderedChatIdsRef.set(
            chatsById.values
                .sortedByDescending { it.order }
                .map { it.chatId }
                .take(TELEGRAM_MAX_CHATS_CACHE)
        )
    }

    private fun updateChatFromMessage(message: TdApi.Message) {
        chatsById.compute(message.chatId) { _, old ->
            val title = old?.title ?: "Chat ${message.chatId}"
            val unread = old?.unreadCount ?: 0
            val order = old?.order ?: 0
            val linkedUserId = old?.linkedUserId
            TelegramCachedChat(
                chatId = message.chatId,
                title = title,
                unreadCount = unread,
                lastMessageId = message.id,
                lastMessageText = extractMessageText(message),
                order = order,
                linkedUserId = linkedUserId,
            )
        }
    }

    private fun resolveContactByName(rawName: String): TelegramCachedContact {
        val query = normalizeLookup(rawName)
        if (query.isBlank()) {
            throw IllegalArgumentException("targetName is required")
        }

        val best = contactsByUserId.values
            .map { it to aliasScore(it.aliases, query) }
            .maxByOrNull { it.second }
            ?.takeIf { it.second > 0 }
            ?.first

        if (best != null) {
            return best
        }

        throw IllegalStateException("Contact '$rawName' not found in Telegram contacts cache")
    }

    private fun resolveChatByName(rawName: String): TelegramCachedChat {
        val asId = rawName.trim().toLongOrNull()
        if (asId != null) {
            chatsById[asId]?.let { return it }
        }

        val query = normalizeLookup(rawName)
        if (query.isBlank()) {
            throw IllegalArgumentException("chatName is required")
        }

        val best = chatsById.values
            .map { chat -> chat to aliasScore(setOf(normalizeLookup(chat.title)), query) }
            .maxByOrNull { it.second }
            ?.takeIf { it.second > 0 }
            ?.first

        if (best != null) {
            return best
        }

        throw IllegalStateException("Chat '$rawName' not found in Telegram cache")
    }

    private fun messageToView(message: TdApi.Message): TelegramMessageView {
        val cachedChat = chatsById[message.chatId]
        val sender = when (val senderId = message.senderId) {
            is TdApi.MessageSenderUser -> userDisplayName(usersById[senderId.userId])
            is TdApi.MessageSenderChat -> chatsById[senderId.chatId]?.title ?: senderId.chatId.toString()
            else -> null
        }

        return TelegramMessageView(
            chatId = message.chatId,
            chatTitle = cachedChat?.title ?: "Chat ${message.chatId}",
            messageId = message.id,
            sender = sender,
            unixTime = message.date.toLong(),
            text = extractMessageText(message),
        )
    }

    private fun extractMessageText(message: TdApi.Message): String? {
        val content = message.content ?: return null
        return when (content) {
            is TdApi.MessageText -> content.text?.text
            is TdApi.MessagePhoto -> content.caption?.text
            is TdApi.MessageVideo -> content.caption?.text
            is TdApi.MessageAudio -> content.caption?.text
            is TdApi.MessageDocument -> content.caption?.text
            is TdApi.MessageVoiceNote -> content.caption?.text
            is TdApi.MessageAnimation -> content.caption?.text
            else -> null
        }
    }

    private fun chatDisplayTitle(chat: TdApi.Chat): String {
        val explicitTitle = chat.title?.trim().orEmpty()
        if (explicitTitle.isNotEmpty()) {
            return explicitTitle
        }

        val linkedUserId = chatLinkedUserId(chat)
        if (linkedUserId != null) {
            return userDisplayName(usersById[linkedUserId])
        }

        return "Chat ${chat.id}"
    }

    private fun chatLinkedUserId(chat: TdApi.Chat): Long? = when (val type = chat.type) {
        is TdApi.ChatTypePrivate -> type.userId
        is TdApi.ChatTypeSecret -> type.userId
        else -> null
    }

    private fun userDisplayName(user: TdApi.User?): String {
        user ?: return "Unknown"
        val first = user.firstName.orEmpty().trim()
        val last = user.lastName.orEmpty().trim()
        val full = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ")
        if (full.isNotBlank()) {
            return full
        }

        val username = user.usernames?.activeUsernames?.firstOrNull()?.trim()
        if (!username.isNullOrEmpty()) {
            return "@$username"
        }

        if (user.phoneNumber.orEmpty().isNotBlank()) {
            return "+${user.phoneNumber}"
        }

        return user.id.toString()
    }

    private fun userAliases(user: TdApi.User): Set<String> {
        val aliases = linkedSetOf<String>()
        val first = user.firstName.orEmpty().trim()
        val last = user.lastName.orEmpty().trim()
        val full = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ")

        if (full.isNotBlank()) {
            aliases += normalizeLookup(full)
        }
        if (first.isNotBlank()) {
            aliases += normalizeLookup(first)
        }
        if (last.isNotBlank()) {
            aliases += normalizeLookup(last)
        }

        user.usernames?.activeUsernames?.forEach { rawUsername ->
            val username = rawUsername ?: return@forEach
            val normalized = normalizeLookup(username)
            if (normalized.isNotBlank()) {
                aliases += normalized
                aliases += normalizeLookup("@$username")
            }
        }

        val phoneAlias = normalizeLookup("+${user.phoneNumber.orEmpty()}")
        if (phoneAlias.isNotBlank()) {
            aliases += phoneAlias
        }

        return aliases
    }

    private fun aliasScore(aliases: Set<String>, query: String): Int {
        var best = 0
        val queryTokens = query.split(" ").filter { it.isNotBlank() }

        for (alias in aliases) {
            if (alias.isBlank()) continue
            val score = when {
                alias == query -> 100
                alias.startsWith(query) -> 80
                alias.contains(query) -> 65
                queryTokens.isNotEmpty() && queryTokens.all { token -> alias.contains(token) } -> 50
                else -> 0
            }
            if (score > best) {
                best = score
            }
        }
        return best
    }

    private fun onUnhandledError(throwable: Throwable) {
        val rawMessage = when (throwable) {
            is TelegramError -> throwable.errorMessage
            else -> throwable.message ?: throwable::class.simpleName.orEmpty()
        }
        val message = formatUserError(rawMessage)
        val nextStep = resolveStepAfterError(authStateFlow.value.step, rawMessage)

        l.debug("Telegram client error: {}", sanitizeTelegramError(rawMessage), throwable)

        authStateFlow.update {
            it.copy(
                step = nextStep,
                isBusy = false,
                errorMessage = message,
            )
        }
    }

    private fun resolveStepAfterError(currentStep: TelegramAuthStep, rawMessage: String?): TelegramAuthStep {
        val normalized = rawMessage.orEmpty()
        return when {
            currentStep == TelegramAuthStep.READY -> TelegramAuthStep.READY
            normalized.contains("PHONE_CODE", ignoreCase = true) -> TelegramAuthStep.WAIT_CODE
            normalized.contains("PASSWORD", ignoreCase = true) -> TelegramAuthStep.WAIT_PASSWORD
            normalized.contains("PHONE_NUMBER", ignoreCase = true) -> TelegramAuthStep.WAIT_PHONE
            currentStep == TelegramAuthStep.WAIT_PHONE ||
                currentStep == TelegramAuthStep.WAIT_CODE ||
                currentStep == TelegramAuthStep.WAIT_PASSWORD -> currentStep
            else -> TelegramAuthStep.ERROR
        }
    }

    private fun formatUserError(message: String?): String {
        val normalized = message.orEmpty()
        return when {
            normalized.contains("API_ID_PUBLISHED_FLOOD", ignoreCase = true) ->
                "Telegram отклонил встроенные API credentials (API_ID_PUBLISHED_FLOOD). " +
                    "Обратитесь в поддержку и опишите проблему."

            normalized.contains("PHONE_NUMBER_INVALID", ignoreCase = true) ->
                "Неверный номер телефона Telegram."

            normalized.contains("PHONE_CODE_INVALID", ignoreCase = true) ->
                "Неверный код подтверждения Telegram."

            normalized.contains("PHONE_CODE_EXPIRED", ignoreCase = true) ->
                "Срок действия кода Telegram истек. Запросите новый код."

            normalized.contains("PASSWORD_HASH_INVALID", ignoreCase = true) ->
                "Неверный пароль 2FA Telegram."

            else -> normalized.ifBlank { "Telegram error" }
        }
    }

    private fun sanitizeTelegramError(message: String?): String {
        if (message.isNullOrBlank()) return ""
        return message
            .replace(TELEGRAM_DEFAULT_API_HASH, "***")
            .replace(Regex("(?i)api_hash\\s*=\\s*\"[^\"]+\""), "api_hash=\"***\"")
            .replace(Regex("\\b[0-9a-fA-F]{24,}\\b"), "***")
    }

    private fun resolveApiToken(): APIToken {
        return APIToken(TELEGRAM_DEFAULT_API_ID, TELEGRAM_DEFAULT_API_HASH)
    }

    private fun isTelegramDebugLogsEnabled(): Boolean {
        val cfgValue = ConfigStore.get<Boolean>(TELEGRAM_CFG_DEBUG_LOGS)
        if (cfgValue != null) return cfgValue

        val envValue = System.getenv(TELEGRAM_ENV_DEBUG_LOGS) ?: System.getProperty(TELEGRAM_ENV_DEBUG_LOGS)
        return envValue?.equals("true", ignoreCase = true) == true
    }

    private fun applyTdlightLogLevel(debugLogsEnabled: Boolean) {
        runCatching {
            val loggerContext = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
            val level = if (debugLogsEnabled) Level.INFO else Level.WARN
            loggerContext.getLogger("it.tdlight").level = level
            loggerContext.getLogger("it.tdlight.TDLight").level = level
            loggerContext.getLogger("it.tdlight.TelegramClient").level = level
        }.onFailure {
            l.debug("Failed to configure tdlight logger level", it)
        }
    }

    private fun normalizeLookup(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}@+]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun normalizePhone(phone: String): String {
        val compact = phone
            .trim()
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "")
        return if (compact.startsWith("+")) compact else "+$compact"
    }

    private fun maskPhone(phone: String?): String? {
        if (phone.isNullOrBlank()) return null
        val normalized = if (phone.startsWith("+")) phone else "+$phone"
        if (normalized.length <= 9) return normalized.take(5) + "***"
        return normalized.take(5) + "***" + normalized.takeLast(4)
    }

    private fun buildTdLibSettings(): TDLibSettings {
        val settings = TDLibSettings.create(resolveApiToken())
        val sessionDir: Path = Path.of(System.getProperty("user.home"), ".souz", "tdlight")
        settings.databaseDirectoryPath = sessionDir.resolve("data")
        settings.downloadedFilesDirectoryPath = sessionDir.resolve("downloads")
        settings.isFileDatabaseEnabled = true
        settings.isChatInfoDatabaseEnabled = true
        settings.isMessageDatabaseEnabled = true
        settings.applicationVersion = "0.0.1"
        settings.deviceModel = "Souz AI"
        return settings
    }

    private suspend fun <T> CompletableFuture<T>.awaitResult(): T {
        return suspendCancellableCoroutine { continuation ->
            whenComplete { value, throwable ->
                if (throwable != null) {
                    continuation.resumeWithException(throwable)
                } else {
                    continuation.resume(value)
                }
            }

            continuation.invokeOnCancellation {
                cancel(true)
            }
        }
    }

    private inner class InteractiveAuthBridge : AuthenticationSupplier<AuthenticationData>, ClientInteraction {

        private val phoneFutureRef = AtomicReference<CompletableFuture<AuthenticationData>?>(null)
        private val codeFutureRef = AtomicReference<CompletableFuture<String>?>(null)
        private val passwordFutureRef = AtomicReference<CompletableFuture<String>?>(null)
        private val queuedPhoneRef = AtomicReference<String?>(null)
        private val queuedCodeRef = AtomicReference<String?>(null)
        private val queuedPasswordRef = AtomicReference<String?>(null)

        override fun get(): CompletableFuture<AuthenticationData> {
            val queuedPhone = queuedPhoneRef.getAndSet(null)
            if (!queuedPhone.isNullOrBlank()) {
                @Suppress("UNCHECKED_CAST")
                val authData = AuthenticationSupplier.user(queuedPhone) as AuthenticationData
                return CompletableFuture.completedFuture(authData)
            }

            val created = CompletableFuture<AuthenticationData>()
            phoneFutureRef.set(created)
            authStateFlow.update {
                it.copy(
                    step = TelegramAuthStep.WAIT_PHONE,
                    isBusy = false,
                    errorMessage = null,
                )
            }
            return created
        }

        override fun onParameterRequest(parameter: InputParameter, parameterInfo: ParameterInfo): CompletableFuture<String> {
            return when (parameter) {
                InputParameter.ASK_CODE -> {
                    val hintPhone = (parameterInfo as? ParameterInfoCode)?.phoneNumber
                    val queuedCode = queuedCodeRef.getAndSet(null)
                    if (!queuedCode.isNullOrBlank()) {
                        authStateFlow.update {
                            it.copy(
                                step = TelegramAuthStep.WAIT_CODE,
                                codeHint = hintPhone,
                                isBusy = true,
                                errorMessage = null,
                            )
                        }
                        return CompletableFuture.completedFuture(queuedCode)
                    }

                    val future = CompletableFuture<String>()
                    codeFutureRef.set(future)
                    authStateFlow.update {
                        it.copy(
                            step = TelegramAuthStep.WAIT_CODE,
                            codeHint = hintPhone,
                            isBusy = false,
                            errorMessage = null,
                        )
                    }
                    future
                }

                InputParameter.ASK_PASSWORD -> {
                    val hint = (parameterInfo as? ParameterInfoPasswordHint)?.hint
                    val queuedPassword = queuedPasswordRef.getAndSet(null)
                    if (!queuedPassword.isNullOrBlank()) {
                        authStateFlow.update {
                            it.copy(
                                step = TelegramAuthStep.WAIT_PASSWORD,
                                passwordHint = hint,
                                isBusy = true,
                                errorMessage = null,
                            )
                        }
                        return CompletableFuture.completedFuture(queuedPassword)
                    }

                    val future = CompletableFuture<String>()
                    passwordFutureRef.set(future)
                    authStateFlow.update {
                        it.copy(
                            step = TelegramAuthStep.WAIT_PASSWORD,
                            passwordHint = hint,
                            isBusy = false,
                            errorMessage = null,
                        )
                    }
                    future
                }

                else -> CompletableFuture.completedFuture("")
            }
        }

        fun providePhone(phone: String) {
            val pending = phoneFutureRef.getAndSet(null)
            if (pending != null && !pending.isDone) {
                @Suppress("UNCHECKED_CAST")
                pending.complete(AuthenticationSupplier.user(phone) as AuthenticationData)
            } else {
                queuedPhoneRef.set(phone)
            }
        }

        fun provideCode(code: String) {
            val pending = codeFutureRef.getAndSet(null)
            if (pending != null && !pending.isDone) {
                pending.complete(code)
            } else {
                queuedCodeRef.set(code)
            }
        }

        fun providePassword(password: String) {
            val pending = passwordFutureRef.getAndSet(null)
            if (pending != null && !pending.isDone) {
                pending.complete(password)
            } else {
                queuedPasswordRef.set(password)
            }
        }

        fun reset() {
            phoneFutureRef.getAndSet(null)?.cancel(true)
            codeFutureRef.getAndSet(null)?.cancel(true)
            passwordFutureRef.getAndSet(null)?.cancel(true)
            queuedPhoneRef.set(null)
            queuedCodeRef.set(null)
            queuedPasswordRef.set(null)
        }
    }
}
