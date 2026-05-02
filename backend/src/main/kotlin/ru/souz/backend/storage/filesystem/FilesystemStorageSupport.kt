package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun filesystemStorageObjectMapper(): ObjectMapper =
    jacksonObjectMapper()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

class FilesystemStorageLayout(
    private val dataDir: Path,
) {
    fun userDir(userId: String): Path =
        dataDir.resolve("users").resolve(FilesystemPathSegmentCodec.encode(userId))

    fun settingsFile(userId: String): Path =
        userDir(userId).resolve("settings.json")

    fun providerKeysFile(userId: String): Path =
        userDir(userId).resolve("provider-keys.json")

    fun chatsDir(userId: String): Path =
        userDir(userId).resolve("chats")

    fun chatDir(userId: String, chatId: java.util.UUID): Path =
        chatsDir(userId).resolve(chatId.toString())

    fun chatFile(userId: String, chatId: java.util.UUID): Path =
        chatDir(userId, chatId).resolve("chat.json")

    fun messagesFile(userId: String, chatId: java.util.UUID): Path =
        chatDir(userId, chatId).resolve("messages.jsonl")

    fun agentStateFile(userId: String, chatId: java.util.UUID): Path =
        chatDir(userId, chatId).resolve("agent-state.json")

    fun executionsFile(userId: String, chatId: java.util.UUID): Path =
        chatDir(userId, chatId).resolve("executions.jsonl")

    fun choicesFile(userId: String, chatId: java.util.UUID): Path =
        chatDir(userId, chatId).resolve("choices.jsonl")

    fun eventsFile(userId: String, chatId: java.util.UUID): Path =
        chatDir(userId, chatId).resolve("events.jsonl")

    fun chatDirectories(userId: String): List<Path> {
        val chatsRoot = chatsDir(userId)
        if (!Files.isDirectory(chatsRoot)) {
            return emptyList()
        }
        return Files.list(chatsRoot).use { stream ->
            stream.filter { Files.isDirectory(it) }.toList()
        }
    }
}

internal object FilesystemPathSegmentCodec {
    fun encode(raw: String): String =
        "u_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(UTF_8))
}

internal suspend fun <T> filesystemIo(block: () -> T): T =
    withContext(Dispatchers.IO) { block() }

abstract class BaseFilesystemRepository(
    dataDir: Path,
    protected val mapper: ObjectMapper,
) {
    protected val mutex = Mutex()
    protected val layout = FilesystemStorageLayout(dataDir)

    protected suspend fun <T> withFileLock(block: () -> T): T =
        mutex.withLock { filesystemIo(block) }
}

internal inline fun <reified T> ObjectMapper.readJsonIfExists(path: Path): T? =
    readTextIfExists(path)?.let { readValue<T>(it) }

internal inline fun <reified T> ObjectMapper.readJsonLines(path: Path): List<T> =
    readLinesIfExists(path).map { readValue<T>(it) }

internal inline fun <reified T> ObjectMapper.readJsonLinesFromChatDirectories(
    layout: FilesystemStorageLayout,
    userId: String,
    fileName: String,
): List<T> =
    layout.chatDirectories(userId).flatMap { chatDirectory ->
        readJsonLines<T>(chatDirectory.resolve(fileName))
    }

internal fun ObjectMapper.writeJsonFile(target: Path, value: Any) {
    writeAtomicString(target, writeValueAsString(value))
}

internal fun ObjectMapper.appendJsonValue(target: Path, value: Any) {
    appendJsonLine(target, writeValueAsString(value))
}

internal fun writeAtomicString(target: Path, content: String) {
    Files.createDirectories(target.parent)
    val tempFile = target.resolveSibling("${target.fileName}.tmp-${java.util.UUID.randomUUID()}")
    val bytes = content.toByteArray(UTF_8)
    try {
        java.nio.channels.FileChannel.open(tempFile, CREATE, WRITE, TRUNCATE_EXISTING).use { channel ->
            channel.write(ByteBuffer.wrap(bytes))
            channel.force(true)
        }
        moveAtomically(tempFile, target)
        forceDirectory(target.parent)
    } finally {
        Files.deleteIfExists(tempFile)
    }
}

internal fun appendJsonLine(target: Path, line: String) {
    Files.createDirectories(target.parent)
    val bytes = (line + "\n").toByteArray(UTF_8)
    java.nio.channels.FileChannel.open(target, CREATE, WRITE, APPEND).use { channel ->
        channel.write(ByteBuffer.wrap(bytes))
        channel.force(true)
    }
}

internal fun readTextIfExists(path: Path): String? =
    if (Files.exists(path)) {
        Files.readString(path)
    } else {
        null
    }

internal fun readLinesIfExists(path: Path): List<String> =
    if (Files.exists(path)) {
        Files.readAllLines(path, UTF_8).filter { it.isNotBlank() }
    } else {
        emptyList()
    }

private fun moveAtomically(source: Path, target: Path) {
    try {
        Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target, REPLACE_EXISTING)
    }
}

private fun forceDirectory(directory: Path) {
    runCatching {
        java.nio.channels.FileChannel.open(directory, READ).use { channel ->
            channel.force(true)
        }
    }
}
