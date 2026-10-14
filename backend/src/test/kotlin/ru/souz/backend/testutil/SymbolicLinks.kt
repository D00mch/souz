package ru.souz.backend.testutil

import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeFalse

/** Windows creates symbolic links only with Developer Mode or elevation. */
internal fun createSymbolicLinkOrSkip(link: Path, target: Path): Path = try {
    Files.createSymbolicLink(link, target)
} catch (error: FileSystemException) {
    assumeFalse(
        System.getProperty("os.name").startsWith("Windows"),
        "Enable Windows Developer Mode to run symbolic-link tests: ${error.message}",
    )
    throw error
}
