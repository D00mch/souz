package ru.souz.test

import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeFalse

private val isWindows = System.getProperty("os.name").startsWith("Windows")

/** The local sandbox runs shell scripts only on POSIX hosts; Windows hosts need the Docker sandbox for them. */
fun assumeLocalShellScripts() =
    assumeFalse(isWindows, "Local shell scripts are unsupported on Windows hosts; use the Docker sandbox.")

/** Windows creates symbolic links only with Developer Mode or elevation. */
fun createSymbolicLinkOrSkip(link: Path, target: Path): Path = try {
    Files.createSymbolicLink(link, target)
} catch (error: FileSystemException) {
    assumeFalse(isWindows, "Enable Windows Developer Mode to run symbolic-link tests: ${error.message}")
    throw error
}
