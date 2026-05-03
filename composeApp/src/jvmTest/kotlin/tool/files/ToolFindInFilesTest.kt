package tool.files

import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.every
import io.mockk.mockk
import ru.souz.db.SettingsProvider
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.tool.files.FilesToolUtil
import ru.souz.tool.files.ToolFindInFiles
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolFindInFilesTest {
    @Test
    fun `filters forbidden subfolder results`() {
        val baseDir = Files.createTempDirectory("souz-test-find-in-files").toFile().canonicalFile
        val allowedFile = File(baseDir, "file.txt").canonicalFile
        val forbiddenDir = File(baseDir, "forbidden").canonicalFile
        val forbiddenFile = File(forbiddenDir, "secret.txt").canonicalFile
        forbiddenDir.mkdirs()
        allowedFile.writeText("Allowed content with needle")
        forbiddenFile.writeText("Forbidden content with needle")

        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.forbiddenFolders } returns listOf(forbiddenDir.absolutePath)
        val filesToolUtil = FilesToolUtil(
            LocalRuntimeSandbox(
                scope = SandboxScope(userId = "user-1"),
                settingsProvider = settingsProvider,
                homePath = baseDir.toPath(),
                stateRoot = Files.createTempDirectory("souz-test-find-in-files-state"),
            )
        )

        try {
            val resultsJson = ToolFindInFiles(filesToolUtil)
                .invoke(ToolFindInFiles.Input(baseDir.absolutePath, "needle"))
            val results: List<List<String>> = restJsonMapper.readValue(resultsJson)
            val paths = results.map { it.first() }

            assertTrue(allowedFile.absolutePath in paths, "Expected allowed file result")
            assertFalse(forbiddenFile.absolutePath in paths, "Expected forbidden file to be filtered")
        } finally {
            baseDir.deleteRecursively()
        }
    }
}
