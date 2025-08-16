package giga

import com.dumch.giga.GigaRequest
import com.dumch.giga.GigaResponse
import com.dumch.giga.GigaToolSetup
import com.dumch.giga.GigaMessageRole
import com.dumch.giga.gigaJsonMapper
import com.dumch.giga.toGiga
import com.dumch.tool.files.ToolListFiles
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import org.slf4j.LoggerFactory

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
        val actual = gigaJsonMapper.readTree(result.content).get("result").asText()
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