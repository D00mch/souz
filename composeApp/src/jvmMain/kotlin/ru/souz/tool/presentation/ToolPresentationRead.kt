package ru.souz.tool.presentation

import org.apache.poi.xslf.usermodel.XMLSlideShow
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetupWithAttachments
import java.io.File
import java.io.FileInputStream

data class PresentationReadInput(
    @InputParamDescription("Absolute path to the .pptx file")
    val filePath: String
)

class ToolPresentationRead : ToolSetupWithAttachments<PresentationReadInput> {
    override val name: String = "PresentationRead"
    override val description: String = "Read text content and speaker notes from a PowerPoint presentation (.pptx). " +
            "Returns a structured list of slides with their titles, bullet points, and notes."

    override val fewShotExamples = listOf(
        ru.souz.tool.FewShotExample(
            request = "Read the presentation at /Users/user/Desktop/report.pptx",
            params = mapOf(
                "filePath" to "/Users/user/Desktop/report.pptx"
            )
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "slides" to ReturnProperty("array", "List of slides with extracted content"),
            "totalSlides" to ReturnProperty("integer", "Total number of slides read")
        )
    )

    override val attachments: List<String> = emptyList()

    override fun invoke(input: PresentationReadInput): String {
        val file = File(input.filePath)
        if (!file.exists()) {
            throw IllegalArgumentException("File not found: ${input.filePath}")
        }

        FileInputStream(file).use { fis ->
            val ppt = XMLSlideShow(fis)
            val slidesData = ppt.slides.mapIndexed { index, slide ->
                val title = slide.title
                
                // Extract text from all shapes except title
                val textContent = slide.shapes.mapNotNull { shape ->
                    if (shape is org.apache.poi.xslf.usermodel.XSLFTextShape && shape.text != title) {
                        shape.text
                    } else {
                        null
                    }
                }

                // Extract notes
                val notes = slide.notes?.shapes?.filterIsInstance<org.apache.poi.xslf.usermodel.XSLFTextShape>()
                    ?.flatMap { it.textParagraphs }
                    ?.flatMap { it.textRuns }
                    ?.joinToString("") { it.rawText }

                mapOf(
                    "slideNumber" to index + 1,
                    "title" to title,
                    "content" to textContent,
                    "notes" to notes
                )
            }

            return ru.souz.giga.gigaJsonMapper.writeValueAsString(
                mapOf(
                    "totalSlides" to slidesData.size,
                    "slides" to slidesData
                )
            )
        }
    }
}
