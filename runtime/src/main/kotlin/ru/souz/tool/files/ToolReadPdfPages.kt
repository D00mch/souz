package ru.souz.tool.files

import org.apache.pdfbox.text.PDFTextStripper
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup
import ru.souz.db.ConfigStore
import ru.souz.db.SettingsProviderImpl
import ru.souz.llms.ToolInvocationMeta
import java.io.IOException

class ToolReadPdfPages(private val filesToolUtil: FilesToolUtil) : ToolSetup<ToolReadPdfPages.Input> {

    data class Input(
        @InputParamDescription("Absolute path to the PDF file")
        val filePath: String,
        @InputParamDescription("Start page number (1-based index). Default is 1.")
        val startPage: Int = 1,
        @InputParamDescription("End page number (inclusive). If null, reads only the start page.")
        val endPage: Int? = null
    )

    override val name: String = "ReadPdfPages"
    override val description: String = "Reads text from a specific page range in a PDF file. Returns text and total page count."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Прочитай первые 5 страниц книги",
            params = mapOf("filePath" to "/docs/book.pdf", "startPage" to 1, "endPage" to 5)
        ),
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "text" to ReturnProperty("string", "Extracted content"),
            "info" to ReturnProperty("string", "Debug info if text is empty")
        )
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String {
        val file = filesToolUtil.resolvePath(input.filePath, meta)
        if (!file.exists) return "Error: File not found at ${input.filePath}"

        if (file.name.substringAfterLast('.', "").lowercase() != "pdf") return "Error: Expecting .pdf file"

        return try {
            filesToolUtil.openPdfDocument(file, meta).use { loaded ->
                val document = loaded.document

                // 1. Проверка на шифрование (частая причина "пустоты")
                if (document.isEncrypted) {
                    // Пытаемся снять защиту (для стандартных прав доступа)
                    document.isAllSecurityToBeRemoved = true
                }

                val totalPages = document.numberOfPages
                val start = input.startPage.coerceAtLeast(1)
                val end = (input.endPage ?: start).coerceAtMost(totalPages)

                if (start > totalPages) {
                    return "Error: Requested page $start but document only has $totalPages pages."
                }

                // Настройка извлекателя
                val stripper = PDFTextStripper()
                stripper.startPage = start
                stripper.endPage = end

                // ВАЖНО: Сортировка по позиции помогает читать сложную верстку (колонки, врезки)
                stripper.sortByPosition = true

                // Настройка: не пытаться читать текст, скрытый слоями (иногда мешает)
                stripper.suppressDuplicateOverlappingText = true

                val text = stripper.getText(document).trim()

                if (text.isBlank()) {
                    """
                    Warning: Output is empty. 
                    - Requested pages: $start to $end
                    - Total pages in file: $totalPages
                    - Encryption: ${document.isEncrypted}
                    
                    Possible reasons:
                    1. These specific pages contain only images/scans without text layer.
                    2. The PDF has strict DRM protection.
                    """.trimIndent()
                } else {
                    """
                    === PDF CONTENT (Pages $start-$end of $totalPages) ===
                    $text
                    """
                }
            }
        } catch (e: IOException) {
            "IO Error reading PDF: ${e.message}"
        } catch (e: Exception) {
            "Unexpected error: ${e.toString()}"
        }
    }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String = invoke(input, meta)
}

fun main() {
    val filesToolUtil = FilesToolUtil(SettingsProviderImpl(ConfigStore))
    val result = ToolReadPdfPages(filesToolUtil).invoke(
        ToolReadPdfPages.Input(
            filePath = "/Users/duxx/Книги/100 ошибок в го.pdf",
            startPage = 27,
            endPage = 75
        ),
        ToolInvocationMeta.Empty,
    )
    println(result)
}
