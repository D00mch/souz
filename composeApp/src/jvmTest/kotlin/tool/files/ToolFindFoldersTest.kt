package tool.files

import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.every
import io.mockk.mockk
import ru.souz.db.SettingsProvider
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.tool.files.FilesToolUtil
import ru.souz.tool.files.ToolFindFolders
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolFindFoldersTest {
    @Test
    fun `filters forbidden paths and parses output`() {
        val homeDir = Files.createTempDirectory("souz-test-find-folders").toFile().canonicalFile
        val safeDir = File(homeDir, "Documents").apply { mkdirs() }.canonicalFile
        val forbiddenRoot = File(homeDir, "restricted").apply { mkdirs() }.canonicalFile
        val unsafeDir = File(forbiddenRoot, "Documents Secret").apply { mkdirs() }.canonicalFile
        val tool = ToolFindFolders(createFilesToolUtil(homeDir, listOf(forbiddenRoot.absolutePath)))

        try {
            val resultsJson = tool.invoke(ToolFindFolders.Input("Documents"))
            val results: List<String> = restJsonMapper.readValue(resultsJson)

            assertTrue(results.contains(safeDir.absolutePath), "Expected safe path in results")
            assertFalse(results.contains(unsafeDir.absolutePath), "Expected unsafe path to be filtered")
        } finally {
            homeDir.deleteRecursively()
        }
    }

    @Test
    fun `handles quotes and special characters safely (Anti-Injection)`() {
        val nastyFolderName = "John's \"Folder\""
        val homeDir = Files.createTempDirectory("souz-test-folder-name").toFile().canonicalFile
        val matchingDir = File(homeDir, nastyFolderName).apply { mkdirs() }.canonicalFile
        val tool = ToolFindFolders(createFilesToolUtil(homeDir))

        try {
            val resultsJson = tool.invoke(ToolFindFolders.Input(nastyFolderName))
            val results: List<String> = restJsonMapper.readValue(resultsJson)

            assertEquals(listOf(matchingDir.absolutePath), results)
        } finally {
            homeDir.deleteRecursively()
        }
    }

    @Test
    fun `prefers exact matches before partial matches`() {
        val folderName = "Projects"
        val homeDir = Files.createTempDirectory("souz-test-folders").toFile().canonicalFile
        val exactDir = File(homeDir, folderName).apply { mkdirs() }.canonicalFile
        val partialDir = File(homeDir, "Old_Projects_Archive").apply { mkdirs() }.canonicalFile
        val tool = ToolFindFolders(createFilesToolUtil(homeDir))

        try {
            val resultsJson = tool.invoke(ToolFindFolders.Input(folderName))
            val results: List<String> = restJsonMapper.readValue(resultsJson)

            assertEquals(2, results.size)
            assertEquals(exactDir.absolutePath, results.first())
            assertTrue(results.contains(partialDir.absolutePath), "Expected partial match in results")
        } finally {
            homeDir.deleteRecursively()
        }
    }

    private fun createFilesToolUtil(homeDir: File, forbiddenFolders: List<String> = emptyList()): FilesToolUtil {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.forbiddenFolders } returns forbiddenFolders
        return FilesToolUtil(
            LocalRuntimeSandbox(
                scope = SandboxScope(userId = "user-1"),
                settingsProvider = settingsProvider,
                homePath = homeDir.toPath(),
                stateRoot = Files.createTempDirectory("souz-test-find-folders-state"),
            )
        )
    }
}
