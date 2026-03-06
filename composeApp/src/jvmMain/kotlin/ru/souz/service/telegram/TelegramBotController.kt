package ru.souz.service.telegram

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
import ru.souz.tool.files.FilesToolUtil
import ru.souz.ui.main.usecases.SpeechRecognitionProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

interface TelegramBotConfigProvider {
    fun token(): String?
    fun ownerId(): Long?
}

object PreferencesTelegramBotConfigProvider : TelegramBotConfigProvider {
    override fun token(): String? = ConfigStore.get(ConfigStore.TG_BOT_TOKEN)

    override fun ownerId(): Long? = ConfigStore.get(ConfigStore.TG_BOT_OWNER_ID)
}

interface TelegramBotApi {
    /**
     * Reads updates from Telegram Bot API (`getUpdates`).
     */
    suspend fun getUpdates(token: String, offset: Long, timeoutSeconds: Int = 30): TelegramUpdatesResponse

    /**
     * Sends text back to Telegram chat (`sendMessage`).
     */
    suspend fun sendMessage(token: String, chatId: Long, text: String)

    /**
     * Resolves Telegram Bot API file metadata by file id (`getFile`).
     */
    suspend fun getTelegramFileInfo(token: String, fileId: String): TelegramBotFileResponse

    /**
     * Downloads raw file bytes from Telegram file CDN using `file_path` from [getTelegramFileInfo].
     */
    suspend fun downloadTelegramFileBytes(token: String, filePath: String): ByteArray

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

    override suspend fun getTelegramFileInfo(token: String, fileId: String): TelegramBotFileResponse {
        val response = client.get("https://api.telegram.org/bot$token/getFile") {
            parameter("file_id", fileId)
        }
        return gigaJsonMapper.readValue(response.bodyAsText())
    }

    override suspend fun downloadTelegramFileBytes(token: String, filePath: String): ByteArray {
        return client.get("https://api.telegram.org/file/bot$token/$filePath").body()
    }

    override fun close() {
        client.close()
    }
}

class TelegramBotController(
    private val telegramService: TelegramService,
    private val agent: GraphBasedAgent,
    private val speechRecognitionProvider: SpeechRecognitionProvider? = null,
    private val configProvider: TelegramBotConfigProvider = PreferencesTelegramBotConfigProvider,
    private val botApi: TelegramBotApi = KtorTelegramBotApi(),
    private val downloadsDirProvider: () -> Path = { FilesToolUtil.souzTelegramControlDirectoryPath },
    private val voiceToPcmDecoder: suspend (ByteArray, String?) -> ByteArray =
        { audio, fileName -> decodeTelegramVoiceToPcm(audio, fileName) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val logger = LoggerFactory.getLogger(TelegramBotController::class.java)

    private var authWatcherJob: Job? = null
    private var pollingJob: Job? = null

    data class IncomingMessage(
        val text: String,
        val responseDeferred: CompletableDeferred<String>,
        val isVoice: Boolean = false,
    )

    private val _incomingMessages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    val incomingMessages = _incomingMessages.asSharedFlow()

    private val _cleanCommands = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val cleanCommands = _cleanCommands.asSharedFlow()

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

            val prepared = prepareIncomingCommand(token, message) ?: continue
            handleCommand(
                token = token,
                chatId = message.chat.id,
                text = prepared.text,
                isVoice = prepared.isVoice,
            )
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

    internal suspend fun handleCommand(token: String, chatId: Long, text: String, isVoice: Boolean = false) {
        try {
            if (text.equals("/clean", ignoreCase = true)) {
                logger.info("Received /clean command (chatId={})", chatId)
                _cleanCommands.emit(Unit)
                botApi.sendMessage(token, chatId, "Context cleared")
                return
            }

            logger.info("Received control command (chatId={}, textLength={})", chatId, text.length)
            botApi.sendMessage(token, chatId, "Processing command...")

            val response = if (_incomingMessages.subscriptionCount.value > 0) {
                val deferred = CompletableDeferred<String>()
                _incomingMessages.emit(IncomingMessage(text = text, responseDeferred = deferred, isVoice = isVoice))
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

    private suspend fun prepareIncomingCommand(token: String, message: TelegramMessage): PreparedIncomingCommand? {
        val text = message.text?.trim().orEmpty()
        val caption = message.caption?.trim().orEmpty()

        val downloadedPaths = mutableListOf<String>()
        message.document?.let { document ->
            runCatching {
                downloadDocumentToDownloads(token, document)
            }.onFailure { error ->
                logger.warn("Failed to download control-bot document ({})", error::class.simpleName)
            }.getOrNull()?.let(downloadedPaths::add)
        }

        val voiceTranscription = message.voice?.let { voice ->
            runCatching {
                transcribeVoiceMessage(token, voice)
            }.onFailure { error ->
                logger.warn("Failed to transcribe control-bot voice message ({})", error::class.simpleName)
            }.getOrNull()
        }

        val baseText = when {
            !voiceTranscription.isNullOrBlank() -> voiceTranscription
            text.isNotBlank() -> text
            caption.isNotBlank() -> caption
            downloadedPaths.isNotEmpty() -> DEFAULT_ATTACHMENT_PROMPT
            else -> ""
        }
        if (baseText.isBlank() && downloadedPaths.isEmpty()) {
            return null
        }

        val commandText = if (downloadedPaths.isEmpty() || baseText.equals("/clean", ignoreCase = true)) {
            baseText
        } else {
            "$baseText\n\n${downloadedPaths.joinToString("\n")}".trim()
        }

        return PreparedIncomingCommand(
            text = commandText,
            isVoice = !voiceTranscription.isNullOrBlank(),
        )
    }

    private suspend fun downloadDocumentToDownloads(token: String, document: TelegramDocument): String {
        val downloaded = downloadBotFile(token, document.fileId, document.fileName)
        val downloadsDir = ensureDownloadsDirectory()
        val target = uniquePath(downloadsDir, downloaded.fileName)
        Files.write(target, downloaded.bytes)
        logger.info("Downloaded control-bot attachment: {}", target)
        return target.toAbsolutePath().toString()
    }

    private suspend fun transcribeVoiceMessage(token: String, voice: TelegramVoice): String? {
        val recognizer = speechRecognitionProvider ?: return null
        if (!recognizer.enabled || !recognizer.hasRequiredKey) {
            logger.warn("Skipping voice note: speech recognition is not configured")
            return null
        }

        val downloaded = downloadBotFile(token, voice.fileId, "voice_note.ogg")
        val pcmBytes = voiceToPcmDecoder(downloaded.bytes, downloaded.fileName)
        val recognized = recognizer.recognize(pcmBytes).trim()
        return recognized.takeIf { it.isNotBlank() }
    }

    private suspend fun downloadBotFile(token: String, fileId: String, fileNameHint: String?): DownloadedBotFile {
        val info = botApi.getTelegramFileInfo(token, fileId)
        if (!info.ok) {
            throw IllegalStateException(info.description ?: "Telegram Bot API getFile failed")
        }
        val result = info.result ?: throw IllegalStateException("Telegram Bot API getFile returned empty result")
        val filePath = result.filePath?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Telegram Bot API getFile returned empty file_path")
        val fileSize = result.fileSize
        if (fileSize != null && fileSize > MAX_BOT_FILE_BYTES) {
            throw IllegalStateException("Telegram file is too large: $fileSize bytes (max $MAX_BOT_FILE_BYTES)")
        }
        val bytes = botApi.downloadTelegramFileBytes(token, filePath)
        if (bytes.size.toLong() > MAX_BOT_FILE_BYTES) {
            throw IllegalStateException("Telegram file is too large: ${bytes.size} bytes (max $MAX_BOT_FILE_BYTES)")
        }
        val fallbackName = filePath.substringAfterLast('/').ifBlank { "telegram_file_${System.currentTimeMillis()}" }
        val rawName = fileNameHint?.takeIf { it.isNotBlank() } ?: fallbackName
        return DownloadedBotFile(
            bytes = bytes,
            fileName = sanitizeFileName(rawName),
        )
    }

    private fun ensureDownloadsDirectory(): Path {
        val preferred = downloadsDirProvider()
        return runCatching {
            Files.createDirectories(preferred)
            preferred
        }.getOrElse {
            val fallback = FilesToolUtil.souzDocumentsDirectoryPath
            Files.createDirectories(fallback)
            fallback
        }
    }

    private fun uniquePath(dir: Path, fileName: String): Path {
        val safeName = fileName.ifBlank { "telegram_file_${System.currentTimeMillis()}" }
        val dot = safeName.lastIndexOf('.')
        val base = if (dot > 0) safeName.substring(0, dot) else safeName
        val ext = if (dot > 0) safeName.substring(dot) else ""
        var index = 0
        while (true) {
            val candidate = if (index == 0) "$base$ext" else "${base}_$index$ext"
            val path = dir.resolve(candidate)
            if (!Files.exists(path)) return path
            index++
        }
    }

    private fun sanitizeFileName(rawName: String): String {
        val normalized = rawName
            .trim()
            .replace('\\', '_')
            .replace('/', '_')
            .replace(':', '_')
            .replace('*', '_')
            .replace('?', '_')
            .replace('"', '_')
            .replace('<', '_')
            .replace('>', '_')
            .replace('|', '_')
        return normalized.takeIf { it.isNotBlank() } ?: "telegram_file_${System.currentTimeMillis()}"
    }

    private data class PreparedIncomingCommand(
        val text: String,
        val isVoice: Boolean,
    )

    private data class DownloadedBotFile(
        val bytes: ByteArray,
        val fileName: String,
    )

    private companion object {
        private const val DEFAULT_ATTACHMENT_PROMPT = "Обработай приложенный файл"
        private const val MAX_BOT_FILE_BYTES = 100L * 1024L * 1024L
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
    val caption: String? = null,
    val document: TelegramDocument? = null,
    val voice: TelegramVoice? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramDocument(
    @JsonProperty("file_id")
    val fileId: String,
    @JsonProperty("file_name")
    val fileName: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramVoice(
    @JsonProperty("file_id")
    val fileId: String,
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

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramBotFileResponse(
    val ok: Boolean,
    val result: TelegramBotFile? = null,
    val description: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramBotFile(
    @JsonProperty("file_id")
    val fileId: String,
    @JsonProperty("file_path")
    val filePath: String? = null,
    @JsonProperty("file_size")
    val fileSize: Long? = null,
)

private fun decodeTelegramVoiceToPcm(audio: ByteArray, fileName: String?): ByteArray {
    val sourceExt = fileName
        ?.substringAfterLast('.', "")
        ?.takeIf { it.isNotBlank() }
        ?.let { ".$it" }
        ?: ".ogg"
    val sourceFile = Files.createTempFile("tg_voice_src_", sourceExt)
    val targetFile = Files.createTempFile("tg_voice_dst_", ".pcm")
    val logsFile = Files.createTempFile("tg_voice_logs_", ".txt")
    return try {
        Files.write(sourceFile, audio)
        val process = ProcessBuilder(
            "ffmpeg",
            "-nostdin",
            "-y",
            "-i",
            sourceFile.toString(),
            "-ac",
            "1",
            "-ar",
            "16000",
            "-f",
            "s16le",
            targetFile.toString(),
        )
            .redirectErrorStream(true)
            .redirectOutput(logsFile.toFile())
            .start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw IllegalStateException("ffmpeg timed out while converting voice message")
        }
        if (process.exitValue() != 0) {
            val shortLogs = runCatching { Files.readString(logsFile).takeLast(600) }.getOrDefault("")
            throw IllegalStateException("ffmpeg failed to convert voice message: $shortLogs")
        }
        Files.readAllBytes(targetFile)
    } finally {
        runCatching { Files.deleteIfExists(sourceFile) }
        runCatching { Files.deleteIfExists(targetFile) }
        runCatching { Files.deleteIfExists(logsFile) }
    }
}
