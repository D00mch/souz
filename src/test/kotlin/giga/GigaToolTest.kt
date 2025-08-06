package giga

import com.dumch.giga.GigaRequest
import com.dumch.giga.GigaResponse
import com.dumch.giga.GigaToolSetup
import com.dumch.giga.GigaMessageRole
import com.dumch.giga.gigaJsonMapper
import com.dumch.giga.toGiga
import com.dumch.tool.files.ToolListFiles
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
            {"type":"object","properties":{"path":{"type":"string","description":"Relative path to list files from"}}}
        """.trimIndent(),
            jsonParams
        )
    }

    @Test
    fun `test function invocation`() {
        val toolsMap: Map<String, GigaToolSetup> = listOf(ToolListFiles.toGiga()).associateBy { it.fn.name }

        val functionCall = GigaResponse.FunctionCall(
            name = "ListFiles",
            arguments = mapOf("path" to "src/test/resources"),
        )

        val result = toolsMap[functionCall.name]!!.invoke(functionCall)
        println(result)
        assertEquals(
            GigaRequest.Message(
                role = GigaMessageRole.function,
                content = """{"result":"[directory/,directory/file.txt,test.txt]"}""",
            ),
            result
        )
    }
}