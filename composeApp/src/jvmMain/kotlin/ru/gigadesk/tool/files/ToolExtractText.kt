package ru.gigadesk.tool.files

import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.sax.BodyContentHandler
import org.xml.sax.SAXException
import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolSetup
import java.io.File
import java.io.FileInputStream

class ToolExtractText(private val filesToolUtil: FilesToolUtil) : ToolSetup<ToolExtractText.Input> {

    data class Input(
        @InputParamDescription("Absolute path to the file (pdf, xlsx, docx, pptx, csv, etc)")
        val filePath: String
    )

    override val name: String = "ExtractTextFromFile"
    override val description: String = "Extracts pure text content and metadata from documents " +
            "(PDF, Excel, PowerPoint, CSV, Word, etc) without opening the app. Returns structured text."

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
        val fixedPath = filesToolUtil.applyDefaultEnvs(input.filePath)
        val file = File(fixedPath)
        if (!filesToolUtil.isPathSafe(file)) {
            throw ForbiddenFolder(fixedPath)
        }
        if (!file.exists()) return "Error: File not found at ${input.filePath}"

        if (file.extension.lowercase() == "key") {
            return "Warning: .key format is proprietary. I cannot read slide content directly without opening Keynote. I can only try to read basic metadata.\n" + extractWithTika(file)
        }

        return extractWithTika(file)
    }

    private fun extractWithTika(file: File): String {
        val charLimit = 25000

        return try {
            val parser = AutoDetectParser()
            val handler = BodyContentHandler(charLimit)
            val metadata = Metadata()

            FileInputStream(file).use { stream ->
                parser.parse(stream, handler, metadata)
            }

            val metaInfo = metadata.names().joinToString("\n") { name ->
                "$name: ${metadata.get(name)}"
            }

            """
            |=== METADATA ===
            |Filename: ${file.name}
            |$metaInfo
            |
            |=== CONTENT ===
            |${handler.toString().trim()}
            """.trimIndent().trimMargin()

        } catch (_: SAXException) {
            """
            |Error: The file is too large for full extraction (limit 25000 chars).
            |
            |ACTION REQUIRED:
            |You MUST use the tool 'ReadPdfPages' instead. 
            |1. Check the table of contents (pages 1-20) using 'ReadPdfPages'.
            |2. Find the start/end pages of the chapter you need.
            |3. Call 'ReadPdfPages' with those specific page numbers.
            """.trimIndent().trimMargin()
        } catch (e: Exception) {
            "Error extracting text: ${e.message}"
        }
    }
}
