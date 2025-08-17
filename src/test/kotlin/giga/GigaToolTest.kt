package giga

import com.dumch.giga.*
import com.dumch.tool.files.ToolListFiles
import com.fasterxml.jackson.module.kotlin.contains
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals

class GigaToolTest {
    @Test
    fun `test function name and parameters setup`() {
        val fn = ToolListFiles.toGiga().fn
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
        val toolsMap: Map<String, GigaToolSetup> = listOf(ToolListFiles.toGiga()).associateBy { it.fn.name }

        val functionCall = GigaResponse.FunctionCall(
            name = "ListFiles",
            arguments = mapOf("path" to "src/test/resources"),
        )

        val l = LoggerFactory.getLogger(GigaToolTest::class.java)
        val result = toolsMap[functionCall.name]!!.invoke(functionCall)
        l.info("$result")
        assertEquals(GigaMessageRole.function, result.role)
        val actual = gigaJsonMapper.readTree(result.content).let { nodes ->
            if (nodes.contains("result")) {
                nodes.get("result").asText()
            } else {
                nodes.asText()
            }
        }
        val actualSet = actual.removePrefix("[").removeSuffix("]").split(",").toSet()
        val expected = setOf(
            "src/test/resources/directory/",
            "src/test/resources/directory/file.txt",
            "src/test/resources/sample.csv",
            "src/test/resources/test.txt",
        )
        assertEquals(expected, actualSet)
    }
}