package ru.souz.runtime.sandbox

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.slf4j.Logger
import ru.souz.tool.BadInputException

internal fun movePath(
    sourcePath: Path,
    destinationPath: Path,
    replaceExisting: Boolean,
    logger: Logger?,
) {
    val atomicOptions = buildList {
        add(StandardCopyOption.ATOMIC_MOVE)
        if (replaceExisting) add(StandardCopyOption.REPLACE_EXISTING)
    }.toTypedArray()
    val fallbackOptions = buildList {
        if (replaceExisting) add(StandardCopyOption.REPLACE_EXISTING)
    }.toTypedArray()
    try {
        Files.move(sourcePath, destinationPath, *atomicOptions)
    } catch (exception: AtomicMoveNotSupportedException) {
        logger?.warn("Failed to make an atomic move", exception)
        Files.move(sourcePath, destinationPath, *fallbackOptions)
    }
}

internal fun uniqueTrashTarget(trashDirectory: Path, originalFileName: String): Path {
    val directTarget = trashDirectory.resolve(originalFileName)
    if (!Files.exists(directTarget, LinkOption.NOFOLLOW_LINKS)) return directTarget

    val file = File(originalFileName)
    val withSuffix = "${file.nameWithoutExtension}-${System.currentTimeMillis()}"
    val extensionSuffix = if (file.extension.isBlank()) "" else ".${file.extension}"
    val suffixedTarget = trashDirectory.resolve(withSuffix + extensionSuffix)
    if (Files.exists(suffixedTarget, LinkOption.NOFOLLOW_LINKS)) {
        throw BadInputException("Unable to move file to Trash. Target exists: $suffixedTarget")
    }
    return suffixedTarget
}
