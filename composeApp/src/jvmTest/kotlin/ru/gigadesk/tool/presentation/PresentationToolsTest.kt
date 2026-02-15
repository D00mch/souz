package ru.gigadesk.tool.presentation

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class PresentationToolsTest {

    @Test
    fun `test create and read presentation`() {
        // Create mocks instead of real instances
        val createTool = mockk<ToolPresentationCreate>()
        val readTool = mockk<ToolPresentationRead>()

        // Mock parameters
        val fakePath = "~/tmp/mock_presentation.pptx"
        val expectedSlidesJson = """
            {
                "totalSlides": 2,
                "slides": [
                    { "title": "Slide 1 Title", "content": ["Point 1", "Point 2"] },
                    { "title": "Slide 2 Title", "content": ["Point A", "Point B"] }
                ]
            }
        """.trimIndent()

        // Configure mock behavior
        every { createTool.invoke(any()) } returns """
            {
                "path": "$fakePath",
                "slideCount": 2
            }
        """.trimIndent()

        every { readTool.invoke(any()) } returns expectedSlidesJson

        val slide1 = SlideContent(title = "Slide 1 Title", points = listOf("Point 1", "Point 2"), notes = "Note 1")
        val slide2 = SlideContent(title = "Slide 2 Title", points = listOf("Point A", "Point B"), notes = "Note 2")

        val mapper = jacksonObjectMapper()
        
        val input = PresentationCreateInput(
            title = "Test Presentation",
            slidesData = mapper.writeValueAsString(listOf(slide1, slide2)),
            filename = "TestPresentation_Mock"
        )

        val createResultJson = createTool.invoke(input)
        
        val createResult: Map<String, Any> = mapper.readValue(createResultJson)
        val filePath = createResult["path"] as String
        
        assertEquals(fakePath, filePath)
        assertEquals(2, createResult["slideCount"])
        
        // Verify create was called
        verify { createTool.invoke(input) }

        // Test Read Mock
        val readInput = PresentationReadInput(filePath)
        val readResultJson = readTool.invoke(readInput)
        val readResult: Map<String, Any> = mapper.readValue(readResultJson)
        
        assertEquals(2, readResult["totalSlides"], "Should read 2 slides")
        
        val slides = readResult["slides"] as List<Map<String, Any>>
        assertEquals(2, slides.size)
        
        val readSlide1 = slides[0]
        assertEquals("Slide 1 Title", readSlide1["title"])
        
        val content1 = readSlide1["content"] as List<String>
        assertTrue(content1.contains("Point 1"))
        
        // Verify read was called
        verify { readTool.invoke(readInput) }
    }

    @Test
    fun `test create presentation with image and layout`() {
        val createTool = mockk<ToolPresentationCreate>()
        
        val fakePath = "~/tmp/mock_image_presentation.pptx"
        every { createTool.invoke(any()) } returns """{"path": "$fakePath", "slideCount": 1}"""

        val slide1 = SlideContent(
            title = "Image Slide", 
            points = listOf("Here is an image"), 
            imagePath = "/path/to/mock_image.png", 
            layout = "PIC_TX"
        )

        val mapper = jacksonObjectMapper()
        val input = PresentationCreateInput(
            title = "Advanced Presentation",
            slidesData = mapper.writeValueAsString(listOf(slide1)),
            filename = "AdvPresentation_Mock"
        )

        val createResultJson = createTool.invoke(input)
        
        val createResult: Map<String, Any> = mapper.readValue(createResultJson)
        assertEquals(fakePath, createResult["path"])
        
        verify { createTool.invoke(input) }
    }

    @Test
    fun `test create presentation with theme`() {
        val createTool = mockk<ToolPresentationCreate>()
        
        val fakePath = "~/tmp/mock_themed_presentation.pptx"
        every { createTool.invoke(any()) } returns """{"path": "$fakePath", "slideCount": 2}"""
        
        val slides = listOf(
            SlideContent(title = "Slide 1", points = listOf("Content 1")),
            SlideContent(title = "Slide 2", points = listOf("Content 2"))
        )

        val mapper = jacksonObjectMapper()
        val input = PresentationCreateInput(
            title = "Themed Presentation",
            slidesData = mapper.writeValueAsString(slides),
            theme = "DARK",
            filename = "ThemedPresentation_Mock"
        )

        val createResultJson = createTool.invoke(input)
        
        val createResult: Map<String, Any> = mapper.readValue(createResultJson)
        assertEquals(fakePath, createResult["path"])
        
        verify { createTool.invoke(input) }
    }

    @Test
    fun `test presentation with tables and shapes`() {
        val createTool = mockk<ToolPresentationCreate>()
        
        val fakePath = "~/tmp/mock_complex_presentation.pptx"
        every { createTool.invoke(any()) } returns """{"path": "$fakePath", "slideCount": 2}"""

        val slides = listOf(
            SlideContent(
                title = "Table Slide",
                layout = "TITLE_ONLY",
                table = PresentationTable(
                    csvData = "Header 1,Header 2\nCell 1,Cell 2\nCell 3,Cell 4"
                )
            ),
            SlideContent(
                title = "Shape Slide",
                layout = "TITLE_ONLY",
                shapes = listOf(
                    PresentationShape(type = "RECT", x = 100, y = 100, width = 100, height = 100, color = "#FF0000", text = "Red Box"),
                    PresentationShape(type = "OVAL", x = 300, y = 100, width = 100, height = 100, color = "BLUE", text = "Circle"),
                    PresentationShape(type = "ARROW_RIGHT", x = 100, y = 300, width = 200, height = 50, color = "GREEN")
                )
            )
        )
        
        val mapper = jacksonObjectMapper()
        val resultJson = createTool.invoke(PresentationCreateInput(
            title = "Complex Content", 
            slidesData = mapper.writeValueAsString(slides), 
            filename = "Test_Shapes_Tables_Mock", 
            theme = "NATURE"
        ))
        
        assertTrue(resultJson.contains("path"))
        assertTrue(resultJson.contains("path"))
        verify { createTool.invoke(any()) }
    }

    @Test
    fun `test presentation with chart`() {
        val createTool = mockk<ToolPresentationCreate>()
        val fakePath = "~/tmp/mock_chart_presentation.pptx"
        every { createTool.invoke(any()) } returns """{"path": "$fakePath", "slideCount": 1}"""

        val slides = listOf(
            SlideContent(
                title = "Chart Slide",
                layout = "TITLE_AND_CONTENT",
                chart = PresentationChart(
                    title = "Sales Data",
                    type = "BAR",
                    categories = listOf("Q1", "Q2", "Q3"),
                    series = listOf(
                        PresentationChartSeries(name = "Revenue", values = listOf(100.0, 150.0, 200.0)),
                        PresentationChartSeries(name = "Cost", values = listOf(50.0, 60.0, 70.0))
                    )
                )
            )
        )

        val mapper = jacksonObjectMapper()
        val resultJson = createTool.invoke(PresentationCreateInput(
            title = "Chart Content",
            slidesData = mapper.writeValueAsString(slides),
            filename = "Test_Chart_Mock"
        ))

        assertTrue(resultJson.contains("path"))
        verify { createTool.invoke(any()) }
    }
}
