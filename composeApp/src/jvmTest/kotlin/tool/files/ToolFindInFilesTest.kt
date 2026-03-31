package tool.files

import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.every
import io.mockk.mockk
import ru.souz.llms.restJsonMapper
import ru.souz.tool.files.FilesToolUtil
import ru.souz.tool.files.ToolFindInFiles
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolFindInFilesTest {
    private val filesToolUtil: FilesToolUtil = mockk()

    @Test
    fun `filters forbidden subfolder results`() {
        val baseDir = File("composeApp/src/jvmTest/resources/directory").canonicalFile
        val allowedFile = File(baseDir, "file.txt").canonicalFile
        val forbiddenFile = File(baseDir, "forbidden/secret.txt").canonicalFile

        every { filesToolUtil.applyDefaultEnvs(any()) } answers { firstArg() }
        every { filesToolUtil.isPathSafe(match { it.canonicalFile == baseDir }) } returns true
        every { filesToolUtil.isPathSafe(match { it.canonicalFile == allowedFile }) } returns true
        every { filesToolUtil.isPathSafe(match { it.canonicalFile == forbiddenFile }) } returns false
        every { filesToolUtil.resourceAsText("scripts/find_in_files.sh") } returns """
            printf '%s\n%s\n' "${allowedFile.absolutePath}" "Allowed content"
            printf '%s\n%s\n' "${forbiddenFile.absolutePath}" "Forbidden content"
        """.trimIndent()

        val resultsJson = ToolFindInFiles(filesToolUtil)
            .invoke(ToolFindInFiles.Input(baseDir.absolutePath, "needle"))
        val results: List<List<String>> = restJsonMapper.readValue(resultsJson)
        val paths = results.map { it.first() }

        assertTrue(allowedFile.absolutePath in paths, "Expected allowed file result")
        assertFalse(forbiddenFile.absolutePath in paths, "Expected forbidden file to be filtered")
    }
}
