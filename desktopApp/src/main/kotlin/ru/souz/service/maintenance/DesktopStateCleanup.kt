package ru.souz.service.maintenance

import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** Desktop-only retention. All filesystem work runs on the application scope's IO job. */
internal class DesktopStateCleanup(
    private val stateRoot: Path,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val logger = LoggerFactory.getLogger(DesktopStateCleanup::class.java)

    fun start() = scope.launch(ioDispatcher) {
        while (isActive) {
            val waitMillis = try {
                cleanupIfDue()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.warn("Desktop state cleanup failed", error)
                DAY.toMillis()
            }
            delay(waitMillis)
        }
    }

    // A non-suspending boundary: the lock also coordinates separate desktop processes.
    private fun cleanupIfDue(): Long {
        if (Files.isSymbolicLink(stateRoot)) return DAY.toMillis()
        Files.createDirectories(stateRoot)
        FileChannel.open(stateRoot.resolve(".desktop-cleanup"), CREATE, READ, WRITE, NOFOLLOW_LINKS).use { channel ->
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } ?: return DAY.toMillis()
            lock.use {
                val now = clock.instant()
                val lastRun = Channels.newInputStream(channel).readNBytes(64)
                    .toString(Charsets.UTF_8).toLongOrNull()?.let(Instant::ofEpochMilli)
                if (lastRun != null && now < lastRun.plus(DAY)) {
                    return Duration.between(now, lastRun.plus(DAY)).toMillis().coerceAtLeast(1)
                }

                // Persist the attempt before deleting anything, including on partial failure or process exit.
                channel.truncate(0)
                channel.position(0)
                val timestamp = ByteBuffer.wrap(now.toEpochMilli().toString().toByteArray(Charsets.UTF_8))
                while (timestamp.hasRemaining()) channel.write(timestamp)
                channel.force(true)

                deleteExpired(stateRoot.resolve("logs"), ".log", now.minus(Duration.ofDays(7)))
                deleteExpired(stateRoot.resolve("sessions"), ".json", now.minus(Duration.ofDays(30)))
                return DAY.toMillis()
            }
        }
    }

    private fun deleteExpired(directory: Path, suffix: String, cutoff: Instant) {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return
        try {
            Files.newDirectoryStream(directory) { it.fileName.toString().endsWith(suffix) }.use { entries ->
                for (file in entries) {
                    try {
                        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                        if (attributes.isRegularFile && attributes.lastModifiedTime().toInstant() < cutoff) {
                            Files.deleteIfExists(file)
                        }
                    } catch (error: Exception) {
                        logger.warn("Failed to clean up desktop state file {}", file, error)
                    }
                }
            }
        } catch (error: Exception) {
            logger.warn("Failed to scan desktop state directory {}", directory, error)
        }
    }

    private companion object {
        val DAY: Duration = Duration.ofDays(1)
    }
}
