package giga

import ru.gigadesk.giga.*
import ru.gigadesk.tool.files.ToolListFiles
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import ru.gigadesk.db.ConfigStore
import ru.gigadesk.db.SettingsProviderImpl
import ru.gigadesk.tool.files.FilesToolUtil
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class GigaToolTest {
    private val listFiles = ToolListFiles(FilesToolUtil(SettingsProviderImpl(ConfigStore)))
    
    private fun createTempDirectory(): File =
        Files.createTempDirectory(FilesToolUtil.homeDirectory.toPath(), "gigadesk-giga-test-").toFile()

    private fun createSampleFiles(baseDir: File) {
        val nestedDir = File(baseDir, "directory").apply { mkdirs() }
        File(nestedDir, "file.txt").writeText("Nested")
        File(baseDir, "sample.csv").writeText("name,score\nAlice,1")
        File(baseDir, "test.txt").writeText("Test content\n")
    }

    @Test
    fun `test function name and parameters setup`() {
        val fn = listFiles.toGiga().fn
        assertEquals(fn.name, "ListFiles")
        val jsonParams = gigaJsonMapper.writeValueAsString(fn.parameters)
        assertEquals(
            """
{"type":"object","properties":{"path":{"type":"string","description":"Relative path to list files from","enum":null},"depth":{"type":"number","description":"Max depth to traverse (1 = direct children only; <=0 = unlimited)","enum":null}},"required":[]}
            """.trimIndent(),
            jsonParams
        )
    }

    @Test
    fun `test function invocation`() = runBlocking {
        val tempDir = createTempDirectory()
        try {
            createSampleFiles(tempDir)
            val toolsMap: Map<String, GigaToolSetup> = listOf(listFiles.toGiga()).associateBy { it.fn.name }

            val functionCall = GigaResponse.FunctionCall(
                name = "ListFiles",
                arguments = mapOf("path" to tempDir.absolutePath),
            )

            val l = LoggerFactory.getLogger(GigaToolTest::class.java)
            val result = toolsMap[functionCall.name]!!.invoke(functionCall)
            l.info("$result")
            assertEquals(GigaMessageRole.function, result.role)
            val actual = gigaJsonMapper.readTree(result.content).let { nodes ->
                if (nodes.has("result")) {
                    nodes.get("result").asText()
                } else {
                    nodes.asText()
                }
            }
            val actualSet = actual.removePrefix("[").removeSuffix("]").split(",").toSet()
            val expected = setOf(
                "${tempDir.absolutePath}/directory/",
                "${tempDir.absolutePath}/directory/file.txt",
                "${tempDir.absolutePath}/sample.csv",
                "${tempDir.absolutePath}/test.txt",
            )
            assertEquals(expected, actualSet)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
