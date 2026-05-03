package ru.souz.tool.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.sax.BodyContentHandler
import org.xml.sax.SAXException
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup

class ToolExtractText(private val filesToolUtil: FilesToolUtil) : ToolSetup<ToolExtractText.Input> {

    data class Input(
        @InputParamDescription("Absolute path to the file (pdf, xlsx, docx, pptx, csv, etc)")
        val filePath: String
    )

    override val name: String = "ExtractTextFromFile"
    override val description: String = "READ ONLY preview of documents " +
            "(PDF, Excel, PowerPoint, CSV, Word, etc). Use this to SEE content. Does NOT modify files. To edit Excel, use ExcelWrite."

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

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String {
        return try {
            withTimeout(EXTRACTION_TIMEOUT_MS) {
                runInterruptible(Dispatchers.IO) { invoke(input, meta) }
            }
        } catch (_: TimeoutCancellationException) {
            "Error extracting text: timed out after ${EXTRACTION_TIMEOUT_MS / 1000} seconds. Try a smaller file/range (for PDFs use ReadPdfPages)."
        }
    }

    override fun invoke(input: Input, meta: ToolInvocationMeta): String {
        val file = filesToolUtil.resolvePath(input.filePath)
        if (!file.exists) return "Error: File not found at ${input.filePath}"

        if (java.io.File(file.path).extension.lowercase() == "key") {
            return "Warning: .key format is proprietary. I cannot read slide content directly without opening Keynote. I can only try to read basic metadata.\n" + extractWithTika(file)
        }

        if (isPlainTextPreview(file)) {
            return extractPlainText(file)
        }

        return extractWithTika(file)
    }

    private fun extractWithTika(file: SandboxPathInfo): String {
        return try {
            val parser = AutoDetectParser()
            val handler = BodyContentHandler(TEXT_CHAR_LIMIT)
            val metadata = Metadata()

            filesToolUtil.openInputStream(file).use { stream ->
                parser.parse(stream, handler, metadata)
            }

            val metaInfo = metadata.names().joinToString("\n") { name ->
                "$name: ${metadata.get(name)}"
            }.ifBlank { "(none)" }

            formatExtractionResult(
                file = file,
                metaInfo = metaInfo,
                content = handler.toString().trim()
            )

        } catch (_: SAXException) {
            """
            |Error: The file is too large for full extraction (limit $TEXT_CHAR_LIMIT chars).
            |
            |ACTION REQUIRED:
            |You MUST use the tool 'ReadPdfPages' instead. 
            |1. Check the table of contents (pages 1-20) using 'ReadPdfPages'.
            |2. Find the start/end pages of the chapter you need.
            |3. Call 'ReadPdfPages' with those specific page numbers.
            """.trimIndent().trimMargin()
        } catch (e: LinkageError) {
            "Error extracting text: ${e.message ?: e::class.simpleName ?: "LinkageError"}. " +
                    "Release runtime may be missing a required Java module (e.g. java.sql for Apache Tika)."
        } catch (e: Exception) {
            "Error extracting text: ${e.message}"
        }
    }

    private fun extractPlainText(file: SandboxPathInfo): String {
        return try {
            val preview = readUtf8Preview(file, TEXT_CHAR_LIMIT)
            val metaLines = buildList {
                add("Content-Type: text/plain (direct)")
                add("Charset: UTF-8")
                if (preview.truncated) add("Truncated: true (preview limited to $TEXT_CHAR_LIMIT chars)")
            }.joinToString("\n")
            formatExtractionResult(
                file = file,
                metaInfo = metaLines,
                content = preview.text.trim()
            )
        } catch (_: Exception) {
            extractWithTika(file)
        }
    }

    private fun readUtf8Preview(file: SandboxPathInfo, charLimit: Int): TextPreview {
        val builder = StringBuilder(minOf(charLimit, 4096))
        filesToolUtil.openInputStream(file).buffered().reader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(4096)
            while (builder.length < charLimit) {
                val remaining = minOf(buffer.size, charLimit - builder.length)
                val read = reader.read(buffer, 0, remaining)
                if (read <= 0) break
                builder.append(buffer, 0, read)
            }
            val truncated = reader.read() != -1
            return TextPreview(builder.toString(), truncated)
        }
    }

    private fun formatExtractionResult(
        file: SandboxPathInfo,
        metaInfo: String,
        content: String,
    ): String {
        return """
            |=== METADATA ===
            |Filename: ${file.name}
            |$metaInfo
            |
            |=== CONTENT ===
            |$content
            """.trimIndent().trimMargin()
    }

    private fun isPlainTextPreview(file: SandboxPathInfo): Boolean =
        java.io.File(file.path).extension.lowercase() in PLAIN_TEXT_EXTENSIONS

    private data class TextPreview(val text: String, val truncated: Boolean)

    companion object {
        private const val TEXT_CHAR_LIMIT = 50000
        private const val EXTRACTION_TIMEOUT_MS = 30_000L

        private val PLAIN_TEXT_EXTENSIONS = setOf(
            "txt",
            "md",
            "markdown",
            "csv",
            "tsv",
            "json",
            "xml",
            "yaml",
            "yml",
            "log",
            "properties",
            "ini",
            "conf"
        )
    }
}
