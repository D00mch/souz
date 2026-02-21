package ru.souz.service.telegram

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.jackson.jackson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import ru.souz.agent.GraphBasedAgent
import ru.souz.db.ConfigStore
import ru.souz.giga.gigaJsonMapper

interface TelegramBotConfigProvider {
    fun token(): String?
    fun ownerId(): Long?
}

object PreferencesTelegramBotConfigProvider : TelegramBotConfigProvider {
    override fun token(): String? = ConfigStore.get(ConfigStore.TG_BOT_TOKEN)

    override fun ownerId(): Long? = ConfigStore.get(ConfigStore.TG_BOT_OWNER_ID)
}

interface TelegramBotApi {
    suspend fun getUpdates(token: String, offset: Long, timeoutSeconds: Int = 30): TelegramUpdatesResponse

    suspend fun sendMessage(token: String, chatId: Long, text: String)

    fun close() {}
}

private class KtorTelegramBotApi : TelegramBotApi {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            jackson {
                disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 35_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 35_000
        }
    }

    override suspend fun getUpdates(token: String, offset: Long, timeoutSeconds: Int): TelegramUpdatesResponse {
        val response = client.get("https://api.telegram.org/bot$token/getUpdates") {
            parameter("offset", offset)
            parameter("timeout", timeoutSeconds)
        }
        return gigaJsonMapper.readValue(response.bodyAsText())
    }

    override suspend fun sendMessage(token: String, chatId: Long, text: String) {
        client.post("https://api.telegram.org/bot$token/sendMessage") {
            parameter("chat_id", chatId)
            parameter("text", text)
        }
    }

    override fun close() {
        client.close()
    }
}

class TelegramBotController(
    private val telegramService: TelegramService,
    private val agent: GraphBasedAgent,
    private val configProvider: TelegramBotConfigProvider = PreferencesTelegramBotConfigProvider,
    private val botApi: TelegramBotApi = KtorTelegramBotApi(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val logger = LoggerFactory.getLogger(TelegramBotController::class.java)

    private var authWatcherJob: Job? = null
    private var pollingJob: Job? = null

    data class IncomingMessage(
        val text: String,
        val responseDeferred: CompletableDeferred<String>
    )

    private val _incomingMessages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    val incomingMessages = _incomingMessages.asSharedFlow()

    fun start() {
        if (authWatcherJob?.isActive == true) return

        authWatcherJob = scope.launch {
            telegramService.authState
                .map { it.step == TelegramAuthStep.READY }
                .distinctUntilChanged()
                .collectLatest { isReady ->
                    if (isReady) {
                        tryStartPolling()
                    } else {
                        stopPolling()
                    }
                }
        }
    }

    fun close() {
        stopPolling()
        authWatcherJob?.cancel()
        authWatcherJob = null
        botApi.close()
        scope.cancel()
    }

    private fun tryStartPolling() {
        val token = configProvider.token()
        val ownerId = configProvider.ownerId()

        if (!token.isNullOrBlank() && ownerId != null) {
            startPolling(token, ownerId)
        } else {
            logger.info("Control bot credentials are not configured. Skipping bot polling startup.")
        }
    }

    fun restartPolling() {
        stopPolling()
        tryStartPolling()
    }

    private fun startPolling(token: String, ownerId: Long) {
        if (pollingJob?.isActive == true) return

        logger.info("Starting Telegram control bot long polling")

        pollingJob = scope.launch {
            var offset = 0L

            while (isActive) {
                try {
                    val result = botApi.getUpdates(token, offset)
                    if (!result.ok) {
                        logger.error("Telegram Bot API getUpdates failed: {}", result.description)
                        delay(5_000)
                        continue
                    }

                    offset = processUpdates(token, ownerId, result.result, offset)
                } catch (_: HttpRequestTimeoutException) {
                    // long-polling timeout is expected
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logger.error("Error during control bot polling ({})", e::class.simpleName)
                    delay(5_000)
                }
            }
        }
    }

    internal suspend fun processUpdates(
        token: String,
        ownerId: Long,
        updates: List<TelegramUpdate>,
        currentOffset: Long = 0L,
    ): Long {
        var nextOffset = currentOffset

        for (update in updates) {
            nextOffset = nextOffset.coerceAtLeast(update.updateId + 1)

            val message = update.message ?: continue
            if (!isAuthorizedOwnerMessage(message, ownerId)) {
                logger.warn("Unauthorized control command rejected (from={}, chatType={})", message.from?.id, message.chat.type)
                continue
            }

            val text = message.text?.trim().orEmpty()
            if (text.isNotEmpty()) {
                handleCommand(token, message.chat.id, text)
            }
        }

        return nextOffset
    }

    internal fun isAuthorizedOwnerMessage(message: TelegramMessage, ownerId: Long): Boolean {
        val isOwner = message.from?.id == ownerId
        val isPrivateChat = message.chat.type.equals("private", ignoreCase = true)
        return isOwner && isPrivateChat
    }

    fun stopPolling() {
        if (pollingJob?.isActive == true) {
            logger.info("Stopping Telegram control bot long polling")
            pollingJob?.cancel()
            pollingJob = null
        }
    }

    private suspend fun handleCommand(token: String, chatId: Long, text: String) {
        try {
            logger.info("Received control command (chatId={}, textLength={})", chatId, text.length)
            botApi.sendMessage(token, chatId, "Processing: $text")

            val response = if (_incomingMessages.subscriptionCount.value > 0) {
                val deferred = CompletableDeferred<String>()
                _incomingMessages.emit(IncomingMessage(text, deferred))
                deferred.await()
            } else {
                agent.execute(text)
            }
            botApi.sendMessage(token, chatId, response)
        } catch (e: Exception) {
            logger.error("Error processing control command ({})", e::class.simpleName)
            botApi.sendMessage(token, chatId, "Error processing command")
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramUpdatesResponse(
    val ok: Boolean,
    val result: List<TelegramUpdate> = emptyList(),
    val description: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramUpdate(
    @JsonProperty("update_id")
    val updateId: Long,
    val message: TelegramMessage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramMessage(
    @JsonProperty("message_id")
    val messageId: Long,
    val from: TelegramUser? = null,
    val chat: TelegramChat,
    val text: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramUser(
    val id: Long,
    @JsonProperty("is_bot")
    val isBot: Boolean,
    @JsonProperty("first_name")
    val firstName: String? = null,
    val username: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramChat(
    val id: Long,
    val type: String,
)
