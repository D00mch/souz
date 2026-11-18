package ru.souz.backend.attachments

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import ru.souz.runtime.sandbox.RuntimeSandboxFactory

/** An OS lock protects this runtime's executions even when PostgreSQL is temporarily unavailable. */
internal class PreparedAttachmentRuntime(sandboxFactory: RuntimeSandboxFactory) : AutoCloseable {
    private val directory = sandboxFactory.preparedAttachmentsRoot()?.resolve("runtimes")
        ?.let { Files.createDirectories(it).toRealPath() }
    val id: UUID? = directory?.let { UUID.randomUUID() }
    private val lock = id?.let(::hold)

    private fun hold(id: UUID): FileLock {
        val path = marker(id)
        liveInJvm.add(path)
        try {
            val channel = FileChannel.open(path, CREATE_NEW, WRITE)
            try {
                return checkNotNull(channel.tryLock())
            } catch (error: Throwable) {
                channel.close()
                throw error
            }
        } catch (error: Throwable) {
            liveInJvm.remove(path)
            throw error
        }
    }

    fun isAlive(id: UUID): Boolean {
        val path = marker(id)
        // Opening and closing a second channel can release this JVM's locks on some systems.
        return path in liveInJvm || try {
            FileChannel.open(path, WRITE).use { channel ->
                val probe = channel.tryLock() ?: return true
                probe.use { Files.deleteIfExists(path) }
                false
            }
        } catch (_: NoSuchFileException) {
            false
        } catch (_: OverlappingFileLockException) {
            true
        }
    }

    override fun close() {
        if (lock == null || id == null) return
        val path = marker(id)
        try {
            lock.channel().close()
            Files.deleteIfExists(path)
        } finally {
            liveInJvm.remove(path)
        }
    }

    private fun marker(id: UUID): Path = checkNotNull(directory).resolve("$id.lock")

    private companion object {
        val liveInJvm: MutableSet<Path> = ConcurrentHashMap.newKeySet()
    }
}
