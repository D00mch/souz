package tool.files

import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import ru.souz.llms.restJsonMapper
import ru.souz.tool.files.FilesToolUtil
import ru.souz.tool.files.ToolFindFolders
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolFindFoldersTest {
    private val filesToolUtil: FilesToolUtil = mockk()
    private val tool = ToolFindFolders(filesToolUtil)

    @AfterTest
    fun clearMocks() {
        clearAllMocks()
        unmockkAll()
    }

    @Test
    fun `filters forbidden paths and parses output`() {
        val homeDir = Files.createTempDirectory("souz-test-find-folders").toFile().canonicalFile
        val safeDir = File(homeDir, "Documents").apply { mkdirs() }.canonicalFile
        val forbiddenRoot = File(homeDir, "restricted").apply { mkdirs() }.canonicalFile
        val unsafeDir = File(forbiddenRoot, "Documents Secret").apply { mkdirs() }.canonicalFile

        mockkObject(FilesToolUtil.Companion)
        every { FilesToolUtil.homeDirectory } returns homeDir
        every { filesToolUtil.isPathSafe(any()) } answers {
            val file = firstArg<File>().canonicalFile
            file.toPath().startsWith(homeDir.toPath()) && !file.toPath().startsWith(forbiddenRoot.toPath())
        }

        try {
            val resultsJson = tool.invoke(ToolFindFolders.Input("Documents"))
            val results: List<String> = restJsonMapper.readValue(resultsJson)

            assertTrue(results.contains(safeDir.absolutePath), "Expected safe path in results")
            assertFalse(results.contains(unsafeDir.absolutePath), "Expected unsafe path to be filtered out")
        } finally {
            homeDir.deleteRecursively()
        }
    }

    @Test
    fun `handles quotes and special characters safely (Anti-Injection)`() {
        val nastyFolderName = "John's \"Folder\""
        val homeDir = Files.createTempDirectory("souz-test-folder-name").toFile().canonicalFile
        val matchingDir = File(homeDir, nastyFolderName).apply { mkdirs() }.canonicalFile

        mockkObject(FilesToolUtil.Companion)
        every { FilesToolUtil.homeDirectory } returns homeDir
        every { filesToolUtil.isPathSafe(any()) } answers {
            firstArg<File>().canonicalFile.toPath().startsWith(homeDir.toPath())
        }

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

        mockkObject(FilesToolUtil.Companion)
        every { FilesToolUtil.homeDirectory } returns homeDir
        every { filesToolUtil.isPathSafe(any()) } answers {
            firstArg<File>().canonicalFile.toPath().startsWith(homeDir.toPath())
        }

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
}
