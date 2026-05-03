package ru.souz.tool.presentation

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.souz.db.SettingsProvider
import ru.souz.tool.files.FilesToolUtil
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class ToolPresentationCreateCompatibilityTest {
    companion object {
        private const val SPEAKER_NOTES_PPTX_PROPERTY = "souz.presentation.enableSpeakerNotesPptx"

        private fun createOutputDir(): File =
            File.createTempFile("souz-presentation-test-", "", FilesToolUtil.homeDirectory).apply {
                delete()
                mkdirs()
            }
    }

    @Test
    fun `notes slides should not contain malformed field runs`() {
        val previous = System.getProperty(SPEAKER_NOTES_PPTX_PROPERTY)
        System.setProperty(SPEAKER_NOTES_PPTX_PROPERTY, "true")
        try {
            val settingsProvider = mockk<SettingsProvider>(relaxed = true)
            every { settingsProvider.forbiddenFolders } returns emptyList()

            val filesToolUtil = FilesToolUtil(settingsProvider)
            val tool = ToolPresentationCreate(filesToolUtil = filesToolUtil)

            val outputDir = createOutputDir()
            val resultJson = tool.invoke(
                PresentationCreateInput(
                    title = "Notes Compatibility Test",
                    slides = listOf(
                        SlideContent(
                            title = "Slide 1",
                            points = listOf("Point 1"),
                            notes = "Speaker note 1",
                        ),
                        SlideContent(
                            title = "Slide 2",
                            points = listOf("Point 2"),
                            notes = "Speaker note 2",
                        ),
                    ),
                    filename = "notes_compatibility_test",
                    outputPath = outputDir.absolutePath,
                    renderMode = "CLASSIC",
                    includeSpeakerNotes = true,
                    saveHtmlPreview = false,
                )
            )

            val pptPath = jacksonObjectMapper().readTree(resultJson).path("path").asText()
            assertTrue(pptPath.isNotBlank(), "PresentationCreate should return a path")

            val notesXml = mutableListOf<String>()
            ZipFile(pptPath).use { zip ->
                val entries = Collections.list(zip.entries())
                entries
                    .filter { it.name.startsWith("ppt/notesSlides/") && it.name.endsWith(".xml") }
                    .forEach { entry ->
                        val xml = zip.getInputStream(entry).use { input ->
                            input.readBytes().toString(StandardCharsets.UTF_8)
                        }
                        notesXml.add(xml)
                    }
            }

            assertTrue(notesXml.isNotEmpty(), "Expected notes slides to be present in generated pptx")
            assertFalse(
                notesXml.any { it.contains("<a:r id=") },
                "Notes slides should not contain malformed <a:r id=...> runs",
            )
            assertFalse(
                notesXml.any { hasTextBodyWithoutParagraph(it) },
                "Every notes text body should contain at least one a:p paragraph element",
            )

            outputDir.deleteRecursively()
        } finally {
            if (previous == null) {
                System.clearProperty(SPEAKER_NOTES_PPTX_PROPERTY)
            } else {
                System.setProperty(SPEAKER_NOTES_PPTX_PROPERTY, previous)
            }
        }
    }

    @Test
    fun `speaker notes should be disabled by default for compatibility`() {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.forbiddenFolders } returns emptyList()

        val filesToolUtil = FilesToolUtil(settingsProvider)
        val tool = ToolPresentationCreate(filesToolUtil = filesToolUtil)

        val outputDir = createOutputDir()
        val resultJson = tool.invoke(
            PresentationCreateInput(
                title = "Notes Disabled By Default Test",
                slides = listOf(
                    SlideContent(
                        title = "Slide 1",
                        points = listOf("Point 1"),
                        notes = "Speaker note 1",
                    ),
                ),
                filename = "notes_disabled_by_default_test",
                outputPath = outputDir.absolutePath,
                renderMode = "CLASSIC",
                saveHtmlPreview = false,
            )
        )

        val pptPath = jacksonObjectMapper().readTree(resultJson).path("path").asText()
        assertTrue(pptPath.isNotBlank(), "PresentationCreate should return a path")

        ZipFile(pptPath).use { zip ->
            val entries = Collections.list(zip.entries()).map { it.name }
            assertFalse(
                entries.any { it.startsWith("ppt/notesSlides/") },
                "Notes slides should not be generated unless includeSpeakerNotes=true",
            )
            assertFalse(
                entries.any { it.startsWith("ppt/notesMasters/") },
                "Notes masters should not be generated unless includeSpeakerNotes=true",
            )
        }

        outputDir.deleteRecursively()
    }

    @Test
    fun `speaker notes request should be ignored when compatibility guard is off`() {
        System.clearProperty(SPEAKER_NOTES_PPTX_PROPERTY)

        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.forbiddenFolders } returns emptyList()

        val filesToolUtil = FilesToolUtil(settingsProvider)
        val tool = ToolPresentationCreate(filesToolUtil = filesToolUtil)

        val outputDir = createOutputDir()
        val resultJson = tool.invoke(
            PresentationCreateInput(
                title = "Notes Guard Off Test",
                slides = listOf(
                    SlideContent(
                        title = "Slide 1",
                        points = listOf("Point 1"),
                        notes = "Speaker note 1",
                    ),
                ),
                filename = "notes_guard_off_test",
                outputPath = outputDir.absolutePath,
                renderMode = "CLASSIC",
                includeSpeakerNotes = true,
                saveHtmlPreview = false,
            )
        )

        val pptPath = jacksonObjectMapper().readTree(resultJson).path("path").asText()
        assertTrue(pptPath.isNotBlank(), "PresentationCreate should return a path")

        ZipFile(pptPath).use { zip ->
            val entries = Collections.list(zip.entries()).map { it.name }
            assertFalse(
                entries.any { it.startsWith("ppt/notesSlides/") },
                "Notes slides should stay disabled unless compatibility guard property is enabled",
            )
            assertFalse(
                entries.any { it.startsWith("ppt/notesMasters/") },
                "Notes master should stay disabled unless compatibility guard property is enabled",
            )
        }

        outputDir.deleteRecursively()
    }

    @Test
    fun `presentation read should resolve generated files through files tool util`() {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.forbiddenFolders } returns emptyList()

        val filesToolUtil = FilesToolUtil(settingsProvider)
        val createTool = ToolPresentationCreate(filesToolUtil = filesToolUtil)
        val readTool = ToolPresentationRead(filesToolUtil)

        val outputDir = createOutputDir()
        val resultJson = createTool.invoke(
            PresentationCreateInput(
                title = "Round Trip Read Test",
                slides = listOf(
                    SlideContent(
                        title = "Slide 1",
                        points = listOf("Point 1", "Point 2"),
                        notes = "Speaker note 1",
                    ),
                ),
                filename = "round_trip_read_test",
                outputPath = outputDir.absolutePath,
                renderMode = "CLASSIC",
                saveHtmlPreview = false,
            )
        )

        val pptPath = jacksonObjectMapper().readTree(resultJson).path("path").asText()
        val readResult = jacksonObjectMapper().readTree(readTool.invoke(PresentationReadInput(pptPath)))

        assertEquals(1, readResult.path("totalSlides").asInt())
        assertEquals("Slide 1", readResult.path("slides").path(0).path("title").asText())

        outputDir.deleteRecursively()
    }

    private fun hasTextBodyWithoutParagraph(xml: String): Boolean {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)))
        val txBodies = document.getElementsByTagNameNS("http://schemas.openxmlformats.org/presentationml/2006/main", "txBody")
        for (index in 0 until txBodies.length) {
            val txBody = txBodies.item(index)
            val paragraphs = txBody.childNodes
            var hasParagraph = false
            for (childIndex in 0 until paragraphs.length) {
                val child = paragraphs.item(childIndex)
                if (child.namespaceURI == "http://schemas.openxmlformats.org/drawingml/2006/main" && child.localName == "p") {
                    hasParagraph = true
                    break
                }
            }
            if (!hasParagraph) return true
        }
        return false
    }
}
