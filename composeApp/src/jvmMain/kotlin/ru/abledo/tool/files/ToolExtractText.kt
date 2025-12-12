package ru.abledo.tool.files

import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.sax.BodyContentHandler
import ru.abledo.tool.FewShotExample
import ru.abledo.tool.InputParamDescription
import ru.abledo.tool.ReturnParameters
import ru.abledo.tool.ReturnProperty
import ru.abledo.tool.ToolSetup
import java.io.File
import java.io.FileInputStream

class ToolExtractText : ToolSetup<ToolExtractText.Input> {

    data class Input(
        @InputParamDescription("Absolute path to the file (pdf, xlsx, docx, pptx, csv, etc)")
        val filePath: String
    )

    override val name: String = "ExtractTextFromFile"
    override val description: String = "Extracts pure text content and metadata from documents (PDF, Excel, PowerPoint, CSV, Word) without opening the app. Returns structured text."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Прочитай, что написано в файле отчета",
            params = mapOf("filePath" to "/Users/user/Downloads/report.pdf")
        ),
        FewShotExample(
            request = "Какие данные в таблице salary.xlsx?",
            params = mapOf("filePath" to "/Users/user/Documents/salary.xlsx")
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Extracted text content")
        )
    )

    override fun invoke(input: Input): String {
        val file = File(input.filePath)
        if (!file.exists()) return "Error: File not found at ${input.filePath}"

        if (file.extension.lowercase() == "key") {
            return "Warning: .key format is proprietary. I cannot read slide content directly without opening Keynote. I can only try to read basic metadata.\n" + extractWithTika(file)
        }

        return extractWithTika(file)
    }

    private fun extractWithTika(file: File): String {
        return try {
            val parser = AutoDetectParser()
            val handler = BodyContentHandler(-1)
            val metadata = Metadata()

            FileInputStream(file).use { stream ->
                parser.parse(stream, handler, metadata)
            }

            val metaInfo = metadata.names().joinToString("\n") { name ->
                "$name: ${metadata.get(name)}"
            }

            """
            === METADATA ===
            Filename: ${file.name}
            $metaInfo
            
            === CONTENT ===
            ${handler.toString().trim()}
            """.trimIndent()

        } catch (e: Exception) {
            "Error extracting text: ${e.message}"
        }
    }
}