package ru.souz.service.telegram

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.*
import io.ktor.serialization.jackson.*
import io.ktor.client.statement.*
import com.fasterxml.jackson.databind.DeserializationFeature
import kotlinx.coroutines.flow.collectLatest

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

import com.fasterxml.jackson.module.kotlin.readValue
import ru.souz.giga.gigaJsonMapper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.slf4j.LoggerFactory
import ru.souz.agent.GraphBasedAgent
import ru.souz.db.ConfigStore

class TelegramBotController(
    private val telegramService: TelegramService,
    private val agent: GraphBasedAgent
) {
    private val logger = LoggerFactory.getLogger(TelegramBotController::class.java)
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            jackson {
                disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 35_000 // Must be > long polling timeout (30s)
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 35_000
        }
    }

    init {
        scope.launch {
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

    private fun tryStartPolling() {
        val token = ConfigStore.get<String>(ConfigStore.TG_BOT_TOKEN)
        val ownerId = ConfigStore.get<Long>(ConfigStore.TG_BOT_OWNER_ID)
        
        if (token != null && ownerId != null) {
            startPolling(token, ownerId)
        } else {
            logger.info("Bot token or owner ID not found. Skipping bot initialization.")
        }
    }

    fun restartPolling() {
        stopPolling()
        tryStartPolling()
    }

    private fun startPolling(token: String, ownerId: Long) {
        if (pollingJob?.isActive == true) return
        
        logger.info("Starting Telegram Bot long polling...")
        
        pollingJob = scope.launch {
            var offset = 0L
            
            while (isActive) {
                try {
                    val response = client.get("https://api.telegram.org/bot$token/getUpdates") {
                        parameter("offset", offset)
                        parameter("timeout", 30)
                    }
                    val bodyStr = response.bodyAsText()
                    val result = gigaJsonMapper.readValue<TelegramUpdatesResponse>(bodyStr)

                    if (result.ok) {
                        result.result.forEach { update ->
                            offset = offset.coerceAtLeast(update.updateId + 1)
                            
                            val message = update.message
                            if (message != null && message.from?.id == ownerId) {
                                val text = message.text
                                if (!text.isNullOrBlank()) {
                                    handleCommand(token, message.chat.id, text)
                                }
                            } else if (message != null) {
                                logger.warn("Unauthorized access attempt by user ID: ${message.from?.id}")
                            }
                        }
                    } else {
                        logger.error("Failed to get updates: {}", result.description)
                        delay(5000)
                    }
                } catch (e: HttpRequestTimeoutException) {
                    logger.debug("Long polling timeout (expected), retrying...")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logger.error("Error during bot polling: ${e.message}")
                    delay(5000)
                }
            }
        }
    }

    fun stopPolling() {
        if (pollingJob?.isActive == true) {
            logger.info("Stopping Telegram Bot long polling.")
            pollingJob?.cancel()
            pollingJob = null
        }
    }

    private suspend fun handleCommand(token: String, chatId: Long, text: String) {
        try {
            logger.info("Received command from owner: $text")

            sendMessage(token, chatId, "Processing: $text")

            val response = agent.execute(text)

            sendMessage(token, chatId, response)
        } catch (e: Exception) {
            logger.error("Error processing command: ${e.message}", e)
            sendMessage(token, chatId, "Error processing command: ${e.message}")
        }
    }

    private suspend fun sendMessage(token: String, chatId: Long, text: String) {
        try {
            client.post("https://api.telegram.org/bot$token/sendMessage") {
                parameter("chat_id", chatId)
                parameter("text", text)
            }
        } catch (e: Exception) {
            logger.error("Failed to send message to Telegram: ${e.message}", e)
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramUpdatesResponse(
    val ok: Boolean,
    val result: List<TelegramUpdate> = emptyList(),
    val description: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramUpdate(
    @JsonProperty("update_id")
    val updateId: Long,
    val message: TelegramMessage? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramMessage(
    @JsonProperty("message_id")
    val messageId: Long,
    val from: TelegramUser? = null,
    val chat: TelegramChat,
    val text: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramUser(
    val id: Long,
    @JsonProperty("is_bot")
    val isBot: Boolean,
    @JsonProperty("first_name")
    val firstName: String? = null,
    val username: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramChat(
    val id: Long,
    val type: String
)
