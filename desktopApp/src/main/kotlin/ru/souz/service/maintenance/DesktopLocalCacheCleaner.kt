package ru.souz.service.maintenance

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.souz.paths.SouzPaths
import ru.souz.ui.host.LocalCacheCleaner

internal class DesktopLocalCacheCleaner(
    private val paths: SouzPaths,
    private val logDirectory: Path,
) : LocalCacheCleaner {
    override suspend fun clear() = withContext(Dispatchers.IO) {
        check(!Files.isSymbolicLink(paths.stateRoot)) { "Cannot clear a symbolic-link state root." }
        val failure = IOException("Some local logs or graph sessions could not be cleared.")
        clearDirectory(logDirectory, ".log", failure) { file ->
            // Keep open appenders attached to the same file, including during rollover.
            FileChannel.open(file, WRITE, NOFOLLOW_LINKS).use { it.truncate(0) }
        }
        clearDirectory(paths.sessionsDir, ".json", failure) { Files.deleteIfExists(it) }
        if (failure.suppressed.isNotEmpty()) throw failure
    }

    private suspend fun clearDirectory(
        directory: Path,
        suffix: String,
        failure: IOException,
        clearFile: (Path) -> Unit,
    ) {
        try {
            val attributes = Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (attributes.isSymbolicLink) return
            if (!attributes.isDirectory) throw IOException("Cache path is not a directory: $directory")
            Files.newDirectoryStream(directory) { it.fileName.toString().endsWith(suffix) }.use { entries ->
                for (file in entries) {
                    currentCoroutineContext().ensureActive()
                    try {
                        if (Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS).isRegularFile) {
                            clearFile(file)
                        }
                    } catch (_: NoSuchFileException) {
                        // Retention or rollover may already have removed this entry.
                    } catch (error: IOException) {
                        failure.addSuppressed(error)
                    }
                }
            }
        } catch (_: NoSuchFileException) {
            // An unused cache directory needs no cleanup.
        } catch (error: DirectoryIteratorException) {
            failure.addSuppressed(error)
        } catch (error: IOException) {
            failure.addSuppressed(error)
        }
    }
}
