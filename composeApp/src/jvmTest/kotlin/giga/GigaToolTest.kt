package giga

import io.mockk.every
import io.mockk.mockk
import ru.souz.tool.files.ToolListFiles
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.llms.giga.toGiga
import ru.souz.runtime.sandbox.DefaultRuntimeSandboxFactory
import ru.souz.runtime.sandbox.RuntimeSandboxModeResolver
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.ToolInvocationSandboxScopeResolver
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ToolSetup
import ru.souz.tool.files.FilesToolUtil
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class GigaToolTest {
    private val settingsProvider = mockk<SettingsProvider> {
        every { forbiddenFolders } returns emptyList()
    }
    private val listFiles = ToolListFiles(
        FilesToolUtil(
            sandboxFactory = DefaultRuntimeSandboxFactory(
                settingsProvider = settingsProvider,
                modeResolver = RuntimeSandboxModeResolver { "local" },
            ),
            scopeResolver = ToolInvocationSandboxScopeResolver { SandboxScope.localDefault() },
        )
    )
    
    private fun createTempDirectory(): File =
        FilesToolUtil.souzDocumentsDirectoryPath.toFile().apply { mkdirs() }
            .let { Files.createTempDirectory(it.toPath(), "souz-giga-test-").toFile() }

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
        val jsonParams = restJsonMapper.writeValueAsString(fn.parameters)
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
            val toolsMap: Map<String, LLMToolSetup> = listOf(listFiles.toGiga()).associateBy { it.fn.name }

            val functionCall = LLMResponse.FunctionCall(
                name = "ListFiles",
                arguments = mapOf("path" to tempDir.absolutePath),
            )

            val l = LoggerFactory.getLogger(GigaToolTest::class.java)
            val result = toolsMap[functionCall.name]!!.invoke(functionCall)
            l.info("$result")
            assertEquals(LLMMessageRole.function, result.role)
            val actual = restJsonMapper.readTree(result.content).let { nodes ->
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

    @Test
    fun `toGiga forwards invocation metadata to tool setup`() = runBlocking {
        var receivedMeta: ToolInvocationMeta? = null
        val tool = object : ToolSetup<TestInput> {
            override val name: String = "MetaEcho"
            override val description: String = "Captures metadata for tests"
            override val fewShotExamples: List<FewShotExample> = emptyList()
            override val returnParameters: ReturnParameters = ReturnParameters(properties = emptyMap())

            override fun invoke(input: TestInput, meta: ToolInvocationMeta): String = input.value

            override suspend fun suspendInvoke(input: TestInput, meta: ToolInvocationMeta): String {
                receivedMeta = meta
                return input.value
            }
        }
        val meta = ToolInvocationMeta(
            userId = "user-1",
            conversationId = "conversation-1",
            requestId = "request-1",
            locale = "en-US",
            timeZone = "America/New_York",
            attributes = mapOf("source" to "test"),
        )

        val result = tool.toGiga<TestInput>().invoke(
            LLMResponse.FunctionCall(
                name = "MetaEcho",
                arguments = mapOf("value" to "hello"),
            ),
            meta,
        )

        assertEquals(LLMMessageRole.function, result.role)
        assertEquals(meta, receivedMeta)
    }

    private data class TestInput(
        @InputParamDescription("Echo value")
        val value: String,
    )
}
