package ru.souz.tool.presentation

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.poi.xslf.usermodel.SlideLayout
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import ru.souz.tool.BadInputException
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetupWithAttachments
import ru.souz.tool.files.FilesToolUtil
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.nio.charset.StandardCharsets
import kotlin.math.ceil
import kotlin.math.roundToInt

@JsonIgnoreProperties(ignoreUnknown = true)
data class SlideContent(
    @InputParamDescription("Title of the slide")
    val title: String,
    @InputParamDescription("Subtitle for Title slides")
    val subtitle: String? = null,
    @InputParamDescription("Bullet points for the slide body")
    val points: List<String> = emptyList(),
    @InputParamDescription("Speaker notes for this slide")
    val notes: String? = null,
    @InputParamDescription("Absolute path to an image file OR image URL (http/https) to insert on the slide")
    val imagePath: String? = null,
    @InputParamDescription("Optional: X coordinate for image (in points, default auto-layout).")
    val imageX: Int? = null,
    @InputParamDescription("Optional: Y coordinate for image (in points, default auto-layout).")
    val imageY: Int? = null,
    @InputParamDescription("Optional: Width for image (in points, default auto-layout).")
    val imageWidth: Int? = null,
    @InputParamDescription("Optional: Height for image (in points, default auto-layout).")
    val imageHeight: Int? = null,
    @InputParamDescription("Slide layout name. Options: TITLE, TITLE_ONLY, TITLE_AND_CONTENT (default), SECTION_HEADER, TWO_COL_TX, TWO_COL_TX_IMG, PIC_TX")
    val layout: String? = null,
    @InputParamDescription("Table data to include on the slide")
    val table: PresentationTable? = null,
    @InputParamDescription("List of geometric shapes to add")
    val shapes: List<PresentationShape>? = null,
    @InputParamDescription("Chart data to include on the slide")
    val chart: PresentationChart? = null,
    @InputParamDescription("Optional: Design ID. One of: MINIMALIST_MODERN, CLEAN_LINES, SWISS_DESIGN, CORPORATE_BLUE, CORPORATE_ELEGANT, EXECUTIVE, CONSULTING, CREATIVE_CHAOS, CREATIVE_SPLASH, ARTISTIC_FLOW, TECH_GRID, DIGITAL_WAVE, CYBERPUNK, NATURE_GREEN, OCEAN_BLUE, FOREST, SUNSET_GRADIENT, NEON_GRADIENT, SOFT_PASTEL, GEOMETRIC_CIRCLES, GEOMETRIC_TRIANGLES, GEOMETRIC_HEXAGON, MODERN_SPLIT")
    val designId: String? = null,
    @InputParamDescription("Optional: List of shapes to be rendered in the background (behind text/images).")
    val backgroundShapes: List<PresentationShape>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PresentationChart(
    @InputParamDescription("Chart title")
    val title: String,
    @InputParamDescription("Chart type. Options: BAR, PIE, LINE, DOUGHNUT")
    val type: String,
    @InputParamDescription("List of categories (X-axis labels)")
    val categories: List<String>? = null,
    @InputParamDescription("List of data series")
    val series: List<PresentationChartSeries>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PresentationChartSeries(
    val name: String,
    val values: List<Double>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PresentationShape(
    @InputParamDescription("Shape type. Options: RECT/RECTANGLE, OVAL/ELLIPSE/CIRCLE, TRIANGLE, ARROW_RIGHT, STAR_5, LINE")
    val type: String,
    @InputParamDescription("Text to display inside the shape")
    val text: String? = null,
    @InputParamDescription("X coordinate in points (0-960)")
    val x: Int,
    @InputParamDescription("Y coordinate in points (0-540)")
    val y: Int,
    @InputParamDescription("Width in points")
    val width: Int,
    @InputParamDescription("Height in points")
    val height: Int,
    @InputParamDescription("Fill color (HEX code or theme color name)")
    val color: String? = null,
    @InputParamDescription("Optional opacity for fill color (0.0 to 1.0)")
    val opacity: Double? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PresentationTable(
    @InputParamDescription("CSV string representing the table data. Cells separated by comma, rows by newline.")
    val csvData: String,
    @InputParamDescription("Whether the first row is a header row (will be styled differently)")
    val hasHeader: Boolean = true
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CustomThemeParam(
    @InputParamDescription("Background color (HEX)")
    val backgroundColor: String? = null,
    @InputParamDescription("Title text color (HEX)")
    val titleColor: String? = null,
    @InputParamDescription("Body text color (HEX)")
    val contentColor: String? = null,
    @InputParamDescription("Accent color (HEX) for shapes/headers")
    val accentColor: String? = null,
    @InputParamDescription("Font family for titles")
    val titleFont: String? = null,
    @InputParamDescription("Font family for body text")
    val bodyFont: String? = null
)

enum class PresentationRenderMode {
    HTML_FIRST,
    CLASSIC
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class PresentationCreateInput(
    @InputParamDescription("Title of the presentation")
    val title: String,
    @InputParamDescription("""
        Preferred typed slides payload. 
        Pass an array of slide objects with fields like `layout`, `title`, `points`, `imagePath`, `table`, `chart`.
        Valid layouts: TITLE, TITLE_ONLY, SECTION_HEADER, PIC_TX, TWO_COL_TX, TWO_COL_TX_IMG, TITLE_AND_CONTENT.
    """)
    val slides: List<SlideContent>? = null,
    @InputParamDescription("""
        Legacy fallback: JSON string representing the list of slides.
        Use `slides` instead when possible.
        Example:
        [
          {
            "layout": "TITLE", 
            "title": "Slide Title", 
            "subtitle": "Subtitle",
            "points": ["Point 1", "Point 2"],
            "imagePath": "/path/to/image.png",
            "imageX": 50, "imageY": 100, "imageWidth": 200, "imageHeight": 150,
            "table": { "csvData": "Header1,Header2\nVal1,Val2" },
            "chart": { "title": "Chart", "type": "BAR", "series": [...] }
          }
        ]
        Valid layouts: TITLE, TITLE_ONLY, SECTION_HEADER, PIC_TX, TWO_COL_TX, TWO_COL_TX_IMG, TITLE_AND_CONTENT.
    """)
    val slidesData: String? = null,
    @InputParamDescription("Optional output filename (without extension). Defaults to 'Presentation_<Title>'")
    val filename: String? = null,
    @InputParamDescription("Optional output path (file or directory). If omitted, uses ~/souz/Documents.")
    val outputPath: String? = null,
    @InputParamDescription("Absolute path to a .pptx file to use as a template")
    val templatePath: String? = null,
    @InputParamDescription("Visual theme. Supports 30+ themes. PREFERRED: Use 'customTheme' for unique designs instead of presets.")
    val theme: String? = null,
    // Flat parameters for Custom Theme (to avoid schema nesting issues)
    @InputParamDescription("Custom Theme: Background color (HEX). Gets priority over 'theme'.")
    val themeBackgroundColor: String? = null,
    @InputParamDescription("Custom Theme: Title text color (HEX)")
    val themeTitleColor: String? = null,
    @InputParamDescription("Custom Theme: Body text color (HEX)")
    val themeContentColor: String? = null,
    @InputParamDescription("Custom Theme: Accent color (HEX)")
    val themeAccentColor: String? = null,
    @InputParamDescription("Custom Theme: Font family for titles")
    val themeTitleFont: String? = null,
    @InputParamDescription("Custom Theme: Font family for body text")
    val themeBodyFont: String? = null,
    @InputParamDescription("Render mode. HTML_FIRST (default) uses a storyboard-first renderer for cleaner layouts. CLASSIC keeps legacy placeholder rendering.")
    val renderMode: String? = null,
    @InputParamDescription("Request embedding speaker notes into the PPTX notes pane. Ignored unless JVM property 'souz.presentation.enableSpeakerNotesPptx=true' is enabled.")
    val includeSpeakerNotes: Boolean = false,
    @InputParamDescription("Save generated HTML storyboard next to pptx. Recommended for easy manual tweaking/reuse.")
    val saveHtmlPreview: Boolean = true
)

class ToolPresentationCreate(
    private val filesToolUtil: FilesToolUtil,
    private val webImageDownloader: WebImageDownloader,
) : ToolSetupWithAttachments<PresentationCreateInput> {
    companion object {
        private const val SPEAKER_NOTES_PPTX_PROPERTY = "souz.presentation.enableSpeakerNotesPptx"
    }

    private val playfulKeywords = setOf(
        "шут", "шуточ", "юмор", "смеш", "мем", "прикол", "пук", "перд", "funny", "joke", "humor", "playful", "lighthearted", "lol", "fart"
    )
    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    override val name: String = "PresentationCreate"
    override val description: String = "Create a PowerPoint presentation (.pptx) from text content. " +
            "Supports creating slides with bullet points and images. " +
            "Speaker notes in PPTX are guarded for compatibility and require JVM property '$SPEAKER_NOTES_PPTX_PROPERTY=true'. " +
            "Can use a custom template (.pptx) and specific slide layouts (e.g. TITLE, PIC_TX, TWO_COL_TX). " +
            "Default rendering mode is HTML_FIRST for cleaner visual balance and fewer broken placeholder layouts. " +
            "Chart blocks currently render a clear placeholder with source data summary (not a native PPT chart). " +
            "For real charts, use the 'CreatePlot' tool and insert the image via `imagePath`.\n" +
            "Supports 30+ built-in visual themes (e.g. CONSULTING, STARTUP, CYBERPUNK) AND Custom Themes via 'themeBackgroundColor', 'themeAccentColor' etc. " +
            "If you need external facts, call 'WebSearch'. For visuals, call 'WebImageSearch' before this tool.\n" +
            "\n\nBEST PRACTICES (Pyramid Principle & Barbara Minto):" +
            "\n- **SCQA Structure**: Situation (Context) -> Complication (Problem) -> Question (What to do?) -> Answer (Solution)." +
            "\n- **Top-Down Logic**: Start with the main conclusion/recommendation, then support it with arguments." +
            "\n- **MECE**: Ensure arguments are Mutually Exclusive and Collectively Exhaustive." +
            "\n\nDESIGN GUIDELINES:" +
            "\n- **Use Shapes**: Add abstract shapes (Circle, Star) in the background or corners for unique flair. Don't just list text." +
            "\n- **Visual Focus**: Use 'PIC_TX' layout. People read faster than you speak." +
            "\n- **Tables**: Use tables for data comparison, avoid bullet lists for numbers." +
            "\n- **Custom Themes**: Create a unique look by passing hex colors in 'customTheme' instead of using standard presets." +
            "\n\nCRITICAL CONSTRAINTS:" +
            "\n- **DO NOT INVENT DATA**: If you need specific metrics, images, or details that the user hasn't provided, **ASK THE USER** first. Do not make up fake numbers." +
            "\n- **Ask Questions**: If the user says 'Make a presentation about X', ask 'Who is the audience?', 'What is the goal?', 'Do you have specific data points?' before generating." +
            "\n- **Placeholders**: If forced to generate without data, use placeholders like '[INSERT REVENUE HERE]' instead of fake numbers."

    override val fewShotExamples = listOf(
        ru.souz.tool.FewShotExample(
            request = "Создай презентацию о нашем стартапе 'souz' для инвесторов. Тема яркая.",
            params = mapOf(
                "title" to "souz Investor Pitch",
                "theme" to "STARTUP",
                "slides" to listOf(
                    mapOf("layout" to "TITLE", "title" to "souz", "subtitle" to "Future of Work"),
                    mapOf("layout" to "PIC_TX", "title" to "The Problem", "points" to listOf("Chaos in files", "Lost productivity")),
                    mapOf("layout" to "PIC_TX", "title" to "The Solution", "points" to listOf("AI Agent Integration", "Automated Workflows")),
                    mapOf("layout" to "TITLE", "title" to "Join Us")
                ),
                "themeTitleColor" to "#FFFFFF",
                "themeBackgroundColor" to "#1A1A1A",
                "themeAccentColor" to "#00FF99"
            )
        ),
        ru.souz.tool.FewShotExample(
            request = "Сделай отчет по экологии леса. Спокойные тона.",
            params = mapOf(
                "title" to "Forest Ecology Report",
                "theme" to "NATURE",
                "slides" to listOf(
                    mapOf("layout" to "TITLE", "title" to "Forest Ecology"),
                    mapOf("layout" to "TWO_COL_TX", "title" to "Flora & Fauna", "points" to listOf("Trees: Oak, Pine", "Animals: Deer, Fox")),
                    mapOf("layout" to "PIC_TX", "title" to "Conservation", "points" to listOf("Protect habitats", "Reduce logging"))
                )
            )
        ),
        ru.souz.tool.FewShotExample(
            request = "Подготовь строгий квартальный отчет для совета директоров.",
            params = mapOf(
                "title" to "Q3 Financial Report",
                "theme" to "EXECUTIVE",
                "designId" to "CORPORATE_ELEGANT",
                "slides" to listOf(
                    mapOf("layout" to "TITLE", "title" to "Q3 Financial Results"),
                    mapOf("layout" to "TITLE_AND_CONTENT", "title" to "Executive Summary", "points" to listOf("Revenue up 15%", "Costs down 5%")),
                    mapOf("layout" to "PIC_TX", "title" to "Growth Metrics", "points" to listOf("User base doubled"))
                )
            )
        ),
        ru.souz.tool.FewShotExample(
            request = "Сделай дерзкую презентацию про киберпанк. Дизайн должен быть уникальным.",
            params = mapOf(
                "title" to "Cyberpunk Aesthetics",
                "theme" to "CYBERPUNK",
                "designId" to "CYBERPUNK",
                "slides" to listOf(
                    mapOf("layout" to "TITLE", "title" to "High Tech, Low Life"),
                    mapOf("layout" to "PIC_TX", "title" to "Visual Style", "points" to listOf("Neon lights", "Chrome surfaces", "Dark alleys"), "imagePath" to "/path/to/city.jpg")
                )
            )
        )
    )


    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "path" to ReturnProperty("string", "Absolute path to the created .pptx file"),
            "slideCount" to ReturnProperty("integer", "Number of slides created"),
            "renderMode" to ReturnProperty("string", "Actual render mode used"),
            "htmlPath" to ReturnProperty("string", "Absolute path to generated HTML storyboard (if enabled)")
        )
    )

    override val attachments: List<String> = emptyList()

    override fun invoke(input: PresentationCreateInput): String {
        val renderMode = resolveRenderMode(input.renderMode)
        val customTheme = buildCustomTheme(input)
        val includeSpeakerNotesInPptx = input.includeSpeakerNotes && isSpeakerNotesPptxEnabled()
        if (input.includeSpeakerNotes && !includeSpeakerNotesInPptx) {
            println(
                "Warning: includeSpeakerNotes=true requested, but PPTX notes are disabled by compatibility guard. " +
                    "Set -D$SPEAKER_NOTES_PPTX_PROPERTY=true to re-enable experimental notes export."
            )
        }
        val slides = resolveSlides(input).map { slide ->
            slide.copy(points = polishPoints(slide.points))
        }

        // 1. Load Template or Create New
        // Treat blank/empty templatePath as null
        val effectiveTemplatePath = if (input.templatePath.isNullOrBlank()) null else input.templatePath

        val ppt = if (effectiveTemplatePath != null) {
            val resolvedTemplatePath = if (effectiveTemplatePath.startsWith("~"))
                effectiveTemplatePath.replaceFirst("~", System.getProperty("user.home"))
            else effectiveTemplatePath

            val file = File(resolvedTemplatePath)
            if (file.exists()) {
                file.inputStream().use { inputStream ->
                    XMLSlideShow(inputStream)
                }
            } else {
                throw IllegalArgumentException("Template file not found at: ${input.templatePath}")
            }
        } else {
            XMLSlideShow()
        }

        try {
            val master = ppt.slideMasters[0]

            // Resolve Theme once (for reuse across slides)
            val resolvedThemeObj = resolveTheme(input.theme) ?: inferTheme(input.title, slides)
            val inferredDeckDesignId = inferDeckDesignId(input.title, slides, resolvedThemeObj)

            // Apply Theme if specified and NO template is used (template takes precedence)
            if (effectiveTemplatePath == null) {
                // Keep 16:9 canvas for cleaner HTML-first layouts.
                ppt.pageSize = java.awt.Dimension(1280, 720)

                if (customTheme != null) {
                    applyCustomTheme(master, customTheme)
                } else {
                    applyTheme(master, resolvedThemeObj)
                }
            }

            if (effectiveTemplatePath == null && renderMode == PresentationRenderMode.HTML_FIRST) {
                slides.forEachIndexed { index, slideData ->
                    renderHtmlFirstSlide(
                        ppt = ppt,
                        slideData = slideData,
                        resolvedTheme = resolvedThemeObj,
                        customTheme = customTheme,
                        defaultDesignId = inferredDeckDesignId,
                        includeSpeakerNotes = includeSpeakerNotesInPptx,
                        slideIndex = index + 1,
                        totalSlides = slides.size,
                    )
                }
            } else {
                slides.forEach { slideData ->
            // Smart Layout Fallback: Check if image is actually available
            var effectiveLayoutName = slideData.layout?.uppercase()
            var effectiveImagePath = resolveImagePath(slideData.imagePath)
            var preparedImage = effectiveImagePath?.let { prepareImageData(it) }

            // If layout implies image but image is missing/invalid, fallback to text-only layout
            if (effectiveLayoutName == "PIC_TX" || effectiveLayoutName == "TWO_COL_TX_IMG") {
                val hasValidImage = preparedImage != null

                if (!hasValidImage) {
                    // Fallback to text layout
                    effectiveLayoutName = "TITLE_AND_CONTENT"
                    effectiveImagePath = null // Ensure we don't try to add it later
                    preparedImage = null
                    // Log warning?
                    println("Warning: Image missing or unsupported format for slide '${slideData.title}', falling back to text layout.")
                }
            }

            // Determine Layout
            val layoutType = when (effectiveLayoutName) {
                "TITLE" -> SlideLayout.TITLE
                "TITLE_ONLY" -> SlideLayout.TITLE_ONLY
                "SECTION_HEADER" -> SlideLayout.SECTION_HEADER
                "TWO_COL_TX" -> SlideLayout.TWO_TX_TWO_OBJ
                "TWO_COL_TX_IMG" -> SlideLayout.TWO_TX_TWO_OBJ // We will use one placeholder for image
                "PIC_TX" -> SlideLayout.PIC_TX
                else -> SlideLayout.TITLE_AND_CONTENT
            }

            val contentLayout = master.getLayout(layoutType)
            val slide = ppt.createSlide(contentLayout)

            // --- APPLY BACKGROUND DESIGN (Before Content) ---
            val effectiveDesignId = slideData.designId ?: inferredDeckDesignId
            if (effectiveDesignId != null) {
                PresentationDesignSystem.applyDesign(slide, effectiveDesignId, resolvedThemeObj)
            }

            if (includeSpeakerNotesInPptx) {
                addSpeakerNotes(ppt, slide, slideData.notes)
            }

            if (slideData.backgroundShapes != null) {
                slideData.backgroundShapes.forEach { shape ->
                     createShape(slide, shape, resolvedThemeObj, customTheme)
                }
            }
            // ------------------------------------------------

            // Set Title
            val slideTitle = slide.getPlaceholder(0)
            if (slideTitle != null) {
                slideTitle.text = slideData.title

                if (effectiveTemplatePath == null) {
                    slideTitle.textParagraphs.forEach { p ->
                        p.textRuns.forEach { r ->
                            r.fontColor = org.apache.poi.sl.draw.DrawPaint.createSolidPaint(resolvedThemeObj.titleColor)
                            r.fontFamily = resolvedThemeObj.titleFont
                            r.isBold = true
                        }
                    }
                }
            }

            val allPlaceholders = slide.placeholders.toList()

            // Set Subtitle (if available and placeholder exists)
            val subtitlePlaceholder = allPlaceholders.firstOrNull {
                it.placeholderDetails.placeholder == org.apache.poi.sl.usermodel.Placeholder.SUBTITLE
            }
            if (subtitlePlaceholder != null && slideData.subtitle != null) {
                subtitlePlaceholder.text = slideData.subtitle
            }

            // Iterate over all placeholders to find the best match for Text and Image
            var textPlaceholder: org.apache.poi.xslf.usermodel.XSLFTextShape? = null
            var imagePlaceholder: org.apache.poi.xslf.usermodel.XSLFTextShape? = null

            // Priority:
            // Text -> BODY, CONTENT, or fallback to any non-title if not found
            // Image -> PICTURE, or CONTENT (if text didn't take it), or fallback

            // Find specific placeholders first
            val titlePlaceholder = allPlaceholders.firstOrNull {
                val ph = it.placeholderDetails.placeholder
                ph == org.apache.poi.sl.usermodel.Placeholder.TITLE || ph == org.apache.poi.sl.usermodel.Placeholder.CENTERED_TITLE
            }

            // Find Body/Text placeholder
            textPlaceholder = allPlaceholders.firstOrNull {
                val ph = it.placeholderDetails.placeholder
                (ph == org.apache.poi.sl.usermodel.Placeholder.BODY || ph == org.apache.poi.sl.usermodel.Placeholder.CONTENT) && it != titlePlaceholder
            }

            // Find Picture placeholder
            imagePlaceholder = allPlaceholders.firstOrNull {
                val ph = it.placeholderDetails.placeholder
                ph == org.apache.poi.sl.usermodel.Placeholder.PICTURE
            }

            // Fallbacks
            if (textPlaceholder == null && allPlaceholders.isNotEmpty()) {
                 // Try to find ANY shape that is not title and not picture
                 textPlaceholder = allPlaceholders.firstOrNull {
                     it != titlePlaceholder && it != imagePlaceholder
                 }
            }

            // If we have an image but no picture placeholder, and we have multiple content placeholders (e.g. 2 cols), try to grab one for image
            if (effectiveImagePath != null && imagePlaceholder == null) {
                 // Check if there is another content placeholder available
                 imagePlaceholder = allPlaceholders.firstOrNull {
                     it != titlePlaceholder && it != textPlaceholder &&
                     (it.placeholderDetails.placeholder == org.apache.poi.sl.usermodel.Placeholder.CONTENT ||
                      it.placeholderDetails.placeholder == org.apache.poi.sl.usermodel.Placeholder.BODY)
                 }
            }

                // Fill Text
                val effectiveTextPlaceholder = if (textPlaceholder == null && slideData.points.isNotEmpty()) {
                    // Fallback: Create a new text box if no placeholder found
                    val textBox = slide.createTextBox()
                    textBox.anchor = java.awt.Rectangle(50, 150, 400, 300) // Default left-side position
                    textBox
                } else textPlaceholder

            // Smart Layout Logic: Detect potential intersection

            // Check if we have both Text and Image, but they might conflict
            val hasImage = preparedImage != null

            // Initial assumption: custom position is valid ONLY if specified
            val isCustomImagePos = slideData.imageX != null

            var forceSplitView = false

            // List of text shapes to check for collision (Title, Subtitle, Body)
            val textShapesToCheck = listOfNotNull(titlePlaceholder, subtitlePlaceholder, effectiveTextPlaceholder)
            val hasAnyText = textShapesToCheck.any { it.text != null && it.text.isNotEmpty() } || slideData.points.isNotEmpty() || slideData.title.isNotEmpty()

            // COLLISION DETECTION: Check if intended image position overlaps with ANY text
            if (hasAnyText && hasImage) {
                 val imageRect = if (
                    slideData.imageX != null && slideData.imageY != null &&
                    slideData.imageWidth != null && slideData.imageHeight != null
                ) {
                    java.awt.Rectangle(slideData.imageX, slideData.imageY, slideData.imageWidth, slideData.imageHeight)
                } else {
                    // Default fallback position
                    java.awt.Rectangle(450, 150, 250, 250)
                }

                // Check intersection with multiple text blocks
                for (textShape in textShapesToCheck) {
                    if (textShape.anchor.intersects(imageRect)) {
                        forceSplitView = true
                        break
                    }
                }

                if (!forceSplitView && !isCustomImagePos && imagePlaceholder == null) {
                     // Standard case without coordinates: also force split
                     forceSplitView = true
                }
            }

            if (forceSplitView) {
                 // Resize Text to Left Half (with some padding)
                 // We need to resize ALL text shapes if they are in the way?
                 // Or typically just the main content.
                 // For Title slides, we might need to move Title/Subtitle to left.

                 val safeWidth = (slide.slideShow.pageSize.width / 2.0) - 20

                 textShapesToCheck.forEach { shape ->
                     val anchor = shape.anchor
                     // Only resize if it's wide (spanning across the slide)
                     if (anchor.width > safeWidth + 50) {
                         val newRect = java.awt.Rectangle(
                             anchor.x.toInt(),
                             anchor.y.toInt(),
                             safeWidth.toInt(),
                             anchor.height.toInt()
                         )
                         shape.anchor = newRect
                     }
                 }
            }

            if (effectiveTextPlaceholder != null && slideData.points.isNotEmpty()) {
                    effectiveTextPlaceholder.clearText()
                    // ... (existing text filling logic)
                    slideData.points.forEach { point ->
                        val paragraph = effectiveTextPlaceholder.addNewTextParagraph()
                        val isTitleLayout = layoutType == SlideLayout.TITLE || layoutType == SlideLayout.SECTION_HEADER || layoutType == SlideLayout.TITLE_ONLY
                        val cleanPoint = point.trim()

                        val hasVisualBullet = cleanPoint.isNotEmpty() && (
                            !cleanPoint.first().isLetterOrDigit() && cleanPoint.first() !in listOf('"', '\'', '(', '[', '{', '<')
                        )

                        paragraph.isBullet = !isTitleLayout && !hasVisualBullet
                        val run = paragraph.addNewTextRun()
                        run.setText(point)

                        if (effectiveTemplatePath == null) {
                            run.fontColor = org.apache.poi.sl.draw.DrawPaint.createSolidPaint(resolvedThemeObj.contentColor)
                            run.fontFamily = resolvedThemeObj.bodyFont
                            run.fontSize = 20.0
                        } else if (run.fontSize == null || run.fontSize < 12.0) {
                            run.fontSize = 18.0
                        }
                    }
            }

            val theme = if (customTheme != null) null else resolvedThemeObj

            // Fill Image
            if (preparedImage != null && effectiveImagePath != null) {
                val customAnchor = if (
                    !forceSplitView &&
                    slideData.imageX != null && slideData.imageY != null &&
                    slideData.imageWidth != null && slideData.imageHeight != null
                ) {
                    java.awt.Rectangle(slideData.imageX, slideData.imageY, slideData.imageWidth, slideData.imageHeight)
                } else null

                val smartAnchor = if (forceSplitView) {
                    val slideWidth = slide.slideShow.pageSize.width
                    val slideHeight = slide.slideShow.pageSize.height
                    val x = (slideWidth / 2.0) + 20
                    val w = (slideWidth / 2.0) - 40
                    val y = 100.0
                    val h = slideHeight - 150.0
                    java.awt.Rectangle(x.toInt(), y.toInt(), w.toInt(), h.toInt())
                } else null

                val fallbackAnchor = imagePlaceholder?.anchor?.bounds ?: smartAnchor ?: run {
                    val sw = slide.slideShow.pageSize.width
                    val sh = slide.slideShow.pageSize.height
                    java.awt.Rectangle(sw / 2, sh / 4, sw / 2 - 50, sh / 2)
                }

                addImageToSlide(
                    ppt = ppt,
                    slide = slide,
                    preparedImage = preparedImage,
                    fallbackAnchor = fallbackAnchor,
                    customAnchor = customAnchor,
                )
                if (imagePlaceholder != null) imagePlaceholder.clearText()
            }

            // Fill Table
            if (slideData.table != null) {
                // Try to use imagePlaceholder for table if image is not present
                val tablePlaceholder = if (effectiveImagePath == null) imagePlaceholder else null

                val anchor = tablePlaceholder?.anchor ?: java.awt.Rectangle(100, 150, 500, 300)

                // If using placeholder, clear it
                tablePlaceholder?.clearText()

                createTable(slide, slideData.table, theme, anchor)
            }

            // Fill Shapes
            if (slideData.shapes != null) {
                slideData.shapes.forEach { shapeData ->
                    createShape(slide, shapeData, theme, customTheme)
                }
            }

            // Fill Chart
            if (slideData.chart != null) {
                try {
                   createChartPlaceholder(slide, slideData.chart, theme, customTheme)
                } catch (e: Exception) {
                    println("Error creating chart: ${e.message}")
                }
            }

            // Auto-Decoration for Custom Themes
            if (customTheme != null) {
                addThemeDecoration(slide, customTheme)
            }

            // CLEANUP: Remove any unused text placeholders

            val usedShapes = mutableSetOf<org.apache.poi.xslf.usermodel.XSLFShape>()
            if (slideTitle != null) usedShapes.add(slideTitle)

            if (effectiveTextPlaceholder != null && slideData.points.isNotEmpty()) usedShapes.add(effectiveTextPlaceholder)

            if (imagePlaceholder != null && effectiveImagePath != null) usedShapes.add(imagePlaceholder)

            // Check table usage
            if (slideData.table != null) {
                val tablePlaceholder = if (effectiveImagePath == null) imagePlaceholder else null
                if (tablePlaceholder != null) usedShapes.add(tablePlaceholder)
            }

            // Iterate and remove unused placeholders
            val shapesToRemove = mutableListOf<org.apache.poi.xslf.usermodel.XSLFShape>()
            slide.shapes.forEach { shape ->
                if (shape is org.apache.poi.xslf.usermodel.XSLFTextShape && shape.placeholderDetails.placeholder != null) {
                    if (!usedShapes.contains(shape)) {
                        shapesToRemove.add(shape)
                    }
                }
            }

            shapesToRemove.forEach { slide.removeShape(it) }
        }
            }

            // Sanitize filename, but allow letters and digits from any language (Unicode) and combining marks.
            var safeTitle = (input.filename ?: input.title).replace(Regex("[^\\p{L}\\p{N}\\p{M} ._-]"), "_")
            if (safeTitle.isBlank() || safeTitle.all { it == '_' || it == '.' }) {
                safeTitle = "Presentation_${System.currentTimeMillis()}"
            }
            val fileName = if (safeTitle.endsWith(".pptx", ignoreCase = true)) safeTitle else "$safeTitle.pptx"

            val outputFile = resolveOutputFile(input, fileName)
            outputFile.parentFile?.mkdirs()
            filesToolUtil.requirePathIsSave(outputFile)

            val htmlPath = if (input.saveHtmlPreview || renderMode == PresentationRenderMode.HTML_FIRST) {
                val htmlFile = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}.html")
                filesToolUtil.requirePathIsSave(htmlFile)
                htmlFile.writeText(
                    buildHtmlStoryboard(input.title, slides, resolvedThemeObj, customTheme),
                    StandardCharsets.UTF_8
                )
                htmlFile.absolutePath
            } else null

            FileOutputStream(outputFile).use { out ->
                ppt.write(out)
            }

            val slideCount = ppt.slides.size
            return """
                {
                    "path": "${outputFile.absolutePath}",
                    "slideCount": $slideCount,
                    "renderMode": "${renderMode.name}",
                    "htmlPath": ${htmlPath?.let { "\"$it\"" } ?: "null"}
                }
            """.trimIndent()
        } finally {
            ppt.close()
        }
    }

    private fun resolveRenderMode(raw: String?): PresentationRenderMode {
        return when (raw?.trim()?.uppercase()) {
            null, "", "HTML_FIRST", "HTML" -> PresentationRenderMode.HTML_FIRST
            "CLASSIC", "LEGACY" -> PresentationRenderMode.CLASSIC
            else -> PresentationRenderMode.HTML_FIRST
        }
    }

    private fun polishPoints(points: List<String>): List<String> {
        return points.asSequence()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .map { line ->
                if (line.length <= 180) line else "${line.take(177).trimEnd()}..."
            }
            .distinct()
            .take(8)
            .toList()
    }

    private fun inferTheme(title: String, slides: List<SlideContent>): PresentationTheme {
        val corpus = buildString {
            append(title)
            append(' ')
            slides.forEach { slide ->
                append(slide.title)
                append(' ')
                slide.points.forEach { append(it).append(' ') }
            }
        }.lowercase()

        return when {
            isPlayfulCorpus(corpus) -> PresentationTheme.PASTEL
            corpus.contains("инвест") || corpus.contains("финанс") || corpus.contains("board") || corpus.contains("report") -> PresentationTheme.CONSULTING
            corpus.contains("кибер") || corpus.contains("cyber") || corpus.contains("security") -> PresentationTheme.TECHNICAL
            corpus.contains("ai ") || corpus.contains("artificial intelligence") || corpus.contains("llm") || corpus.contains("genai") -> PresentationTheme.TECHNICAL
            corpus.contains("стартап") || corpus.contains("startup") || corpus.contains("product") || corpus.contains("saas") -> PresentationTheme.TECHNICAL
            corpus.contains("здоров") || corpus.contains("мед") || corpus.contains("health") || corpus.contains("hygiene") || corpus.contains("clean") -> PresentationTheme.PROFESSIONAL_TEAL
            corpus.contains("эколог") || corpus.contains("лес") || corpus.contains("nature") || corpus.contains("sustain") -> PresentationTheme.NATURE
            corpus.contains("океан") || corpus.contains("ocean") || corpus.contains("sea") -> PresentationTheme.OCEAN
            corpus.contains("creative") || corpus.contains("дизайн") || corpus.contains("brand") -> PresentationTheme.PASTEL
            else -> PresentationTheme.CONSULTING
        }
    }

    private fun inferDeckDesignId(
        title: String,
        slides: List<SlideContent>,
        theme: PresentationTheme?,
    ): String? {
        val corpus = buildString {
            append(title.lowercase())
            slides.forEach { append(' ').append(it.title.lowercase()) }
        }

        if (isPlayfulCorpus(corpus)) return "SOFT_PASTEL"
        if (corpus.contains("инвест") || corpus.contains("finance") || corpus.contains("board")) return "CONSULTING"
        if (corpus.contains("кибер") || corpus.contains("cyber") || corpus.contains("security")) return "TECH_GRID"
        if (corpus.contains("ai ") || corpus.contains("artificial intelligence") || corpus.contains("llm") || corpus.contains("genai")) return "DIGITAL_WAVE"
        if (corpus.contains("health") || corpus.contains("hygiene") || corpus.contains("clean") || corpus.contains("здоров") || corpus.contains("рук")) return "SOFT_PASTEL"
        if (corpus.contains("эколог") || corpus.contains("nature") || corpus.contains("forest")) return "NATURE_GREEN"
        if (corpus.contains("ocean") || corpus.contains("sea") || corpus.contains("океан")) return "OCEAN_BLUE"
        if (corpus.contains("creative") || corpus.contains("дизайн") || corpus.contains("brand")) return "MODERN_SPLIT"

        return when (theme) {
            PresentationTheme.CYBERPUNK, PresentationTheme.MATRIX -> "CYBERPUNK"
            PresentationTheme.TECHNICAL, PresentationTheme.STARTUP, PresentationTheme.SAAS, PresentationTheme.SPACE -> "DIGITAL_WAVE"
            PresentationTheme.PROFESSIONAL_TEAL, PresentationTheme.PASTEL, PresentationTheme.CANDY, PresentationTheme.WARMTH -> "SOFT_PASTEL"
            PresentationTheme.EXECUTIVE, PresentationTheme.FINANCE, PresentationTheme.CORPORATE, PresentationTheme.CONSULTING -> "CONSULTING"
            PresentationTheme.NATURE, PresentationTheme.FOREST -> "NATURE_GREEN"
            PresentationTheme.OCEAN, PresentationTheme.MIDNIGHT -> "OCEAN_BLUE"
            else -> "MODERN_SPLIT"
        }
    }

    private fun resolveImagePath(raw: String?): String? {
        val normalized = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val expanded = filesToolUtil.applyDefaultEnvs(normalized)
        return when {
            expanded.startsWith("http://", ignoreCase = true) || expanded.startsWith("https://", ignoreCase = true) ->
                webImageDownloader.downloadToTemp(expanded)

            else -> expanded
        }
    }

    private fun renderHtmlFirstSlide(
        ppt: XMLSlideShow,
        slideData: SlideContent,
        resolvedTheme: PresentationTheme?,
        customTheme: CustomThemeParam?,
        defaultDesignId: String?,
        includeSpeakerNotes: Boolean,
        slideIndex: Int,
        totalSlides: Int,
    ) {
        val slide = ppt.createSlide()
        val pageWidth = ppt.pageSize.width
        val pageHeight = ppt.pageSize.height
        val layoutName = slideData.layout?.uppercase()

        val requestedBackgroundColor = when {
            customTheme?.backgroundColor != null -> runCatching { java.awt.Color.decode(customTheme.backgroundColor) }.getOrNull()
            else -> resolvedTheme?.backgroundColor
        } ?: java.awt.Color.WHITE

        val effectiveDesign = slideData.designId ?: defaultDesignId
        val titleColor = resolveTitleColor(resolvedTheme, customTheme)
        val bodyColor = resolveBodyColor(resolvedTheme, customTheme)
        val accentColor = resolveAccentColor(resolvedTheme, customTheme)
        val playfulTone = isPlayfulSlide(slideData)
        val variant = resolveHtmlFirstVariant(resolvedTheme, effectiveDesign, requestedBackgroundColor, playfulTone)

        if (includeSpeakerNotes) {
            addSpeakerNotes(ppt, slide, slideData.notes)
        }

        slideData.backgroundShapes?.forEach { createShape(slide, it, resolvedTheme, customTheme) }

        val polishedPoints = polishPoints(slideData.points)
        val preparedImageAsset = resolvePreparedImage(slideData.imagePath)
        val preparedImage = preparedImageAsset?.preparedImage
        val hasImage = preparedImage != null
        val hasTextPoints = polishedPoints.isNotEmpty()
        val imageOnly = hasImage && !hasTextPoints && slideData.table == null && slideData.chart == null
        val templateProfile = resolveHtmlFirstTemplateProfile(
            variant = variant,
            layoutName = layoutName,
            hasImage = hasImage,
            hasTextPoints = hasTextPoints,
            imageOnly = imageOnly,
            slideIndex = slideIndex,
        )
        val templateBehavior = resolveHtmlFirstTemplateBehavior(templateProfile.id)
        val bgColor = resolveHtmlFirstBackgroundColor(
            requestedBackgroundColor = requestedBackgroundColor,
            variant = variant,
            templateId = templateProfile.id,
            customTheme = customTheme,
            slideIndex = slideIndex,
        )
        slide.background.fillColor = bgColor
        val palette = buildHtmlFirstPalette(
            variant = variant,
            backgroundColor = bgColor,
            titleColor = titleColor,
            bodyColor = bodyColor,
            accentColor = accentColor,
            templateId = templateProfile.id,
        )
        renderHtmlFirstBackdrop(
            slide = slide,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            behavior = templateBehavior,
            palette = palette,
            backgroundColor = bgColor,
        )
        val titleX = templateProfile.titleX
        val titleY = templateProfile.titleY
        val eyebrowX = titleX
        val eyebrowWidth = templateProfile.eyebrowWidth
        val heroTitleSlide = isHeroTitleSlide(slideData, layoutName, slideIndex, hasTextPoints, hasImage)

        if (heroTitleSlide) {
            renderHtmlFirstHeroSlide(
                slide = slide,
                slideData = slideData,
                customTheme = customTheme,
                resolvedTheme = resolvedTheme,
                slideIndex = slideIndex,
                totalSlides = totalSlides,
                variant = variant,
                template = templateProfile,
                behavior = templateBehavior,
                palette = palette,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
            )
            slideData.shapes?.forEach { createShape(slide, it, resolvedTheme, customTheme) }
            return
        }

        if (templateProfile.eyebrowBadgeWidth != null) {
            val eyebrowBadge = slide.createAutoShape()
            eyebrowBadge.shapeType = org.apache.poi.sl.usermodel.ShapeType.ROUND_RECT
            eyebrowBadge.anchor = java.awt.Rectangle(eyebrowX - 14, templateProfile.eyebrowY - 4, templateProfile.eyebrowBadgeWidth, 28)
            eyebrowBadge.fillColor = mixColors(palette.accentColor, bgColor, 0.80)
            eyebrowBadge.setLineColor(mixColors(palette.accentColor, bgColor, 0.55))
            eyebrowBadge.lineWidth = 1.0
        }

        val eyebrow = slide.createTextBox()
        eyebrow.anchor = java.awt.Rectangle(eyebrowX, templateProfile.eyebrowY, eyebrowWidth, 20)
        eyebrow.text = buildSlideEyebrow(slideData, slideIndex, variant)
        eyebrow.textParagraphs.forEach { p ->
            p.textRuns.forEach { run ->
                run.fontSize = 12.0
                run.isBold = true
                run.fontFamily = customTheme?.bodyFont ?: resolvedTheme?.bodyFont ?: "Arial"
                run.fontColor = org.apache.poi.sl.draw.DrawPaint.createSolidPaint(
                    palette.eyebrowColor
                )
                run.characterSpacing = 1.2
            }
        }

        val slideCounter = slide.createTextBox()
        slideCounter.anchor = java.awt.Rectangle(pageWidth - 160, 20, 96, 20)
        slideCounter.text = "${slideIndex.toString().padStart(2, '0')} / ${totalSlides.toString().padStart(2, '0')}"
        slideCounter.textParagraphs.forEach { p ->
            p.textAlign = org.apache.poi.sl.usermodel.TextParagraph.TextAlign.RIGHT
            p.textRuns.forEach { run ->
                run.fontSize = 12.0
                run.fontFamily = customTheme?.bodyFont ?: resolvedTheme?.bodyFont ?: "Arial"
                run.fontColor = org.apache.poi.sl.draw.DrawPaint.createSolidPaint(palette.counterColor)
            }
        }

        val titleFontSize = selectTitleFontSize(slideData.title)
        val baseTitleWidth = pageWidth - titleX - templateProfile.titleRightInset
        val titleWidth = if ((hasImage || hasTextPoints) && templateProfile.denseTitleRatio != null) {
            (baseTitleWidth * templateProfile.denseTitleRatio).toInt()
        } else {
            baseTitleWidth
        }
        val titleLineCount = estimateWrappedLineCount(slideData.title, titleFontSize, titleWidth)
        val titleHeight = (titleLineCount * titleFontSize * 1.08).roundToInt().coerceIn(52, 108)

        val titleBox = slide.createTextBox()
        titleBox.anchor = java.awt.Rectangle(titleX, titleY, titleWidth, titleHeight)
        titleBox.text = slideData.title
        titleBox.textParagraphs.forEach { p ->
            p.textRuns.forEach { run ->
                run.isBold = true
                run.fontSize = titleFontSize
                run.fontFamily = customTheme?.titleFont ?: resolvedTheme?.titleFont ?: "Arial"
                run.fontColor = org.apache.poi.sl.draw.DrawPaint.createSolidPaint(
                    palette.titleColor
                )
            }
        }

        val titleBottom = (titleBox.anchor.y + titleBox.anchor.height).roundToInt()
        val titleRuleAnchor = renderHtmlFirstTitleRule(
            slide = slide,
            template = templateProfile,
            titleX = titleX,
            titleY = titleY,
            titleBottom = titleBottom,
            titleWidth = titleWidth,
            titleHeight = titleHeight,
            palette = palette,
            bgColor = bgColor,
        )
        renderHtmlFirstHeaderDecoration(
            slide = slide,
            template = templateProfile,
            pageWidth = pageWidth,
            titleY = titleY,
            palette = palette,
            bgColor = bgColor,
        )

        var contentTop = titleRuleAnchor.y + titleRuleAnchor.height + 18
        if (!slideData.subtitle.isNullOrBlank()) {
            val subtitleFontSize = 18.0
            val subtitleLineCount = estimateWrappedLineCount(slideData.subtitle, subtitleFontSize, titleWidth)
            val subtitleHeight = (subtitleLineCount * subtitleFontSize * 1.22).roundToInt().coerceIn(28, 72)
            val subtitle = slide.createTextBox()
            subtitle.anchor = java.awt.Rectangle(titleX, titleBottom + 22, titleWidth, subtitleHeight)
            subtitle.text = slideData.subtitle
            subtitle.textParagraphs.forEach { p ->
                p.textRuns.forEach { run ->
                    run.fontSize = subtitleFontSize
                    run.fontFamily = customTheme?.bodyFont ?: resolvedTheme?.bodyFont ?: "Arial"
                    run.fontColor = org.apache.poi.sl.draw.DrawPaint.createSolidPaint(
                        palette.subtitleColor
                    )
                }
            }
            contentTop = (subtitle.anchor.y + subtitle.anchor.height).roundToInt() + 20
        }

        val twoColumn = hasImage && hasTextPoints

        val margin = titleX
        val gap = templateProfile.gap
        val contentHeight = pageHeight - contentTop - 56
        val contentWidth = pageWidth - margin - 64

        val textRegion: java.awt.Rectangle
        val imageRegion: java.awt.Rectangle
        if (twoColumn) {
            val leftRatio = templateProfile.leftRatio
            val leftWidth = ((contentWidth - gap) * leftRatio).toInt()
            val rightWidth = contentWidth - gap - leftWidth
            val imageY = contentTop + templateProfile.imageOffsetY
            val imageHeight = (contentHeight + templateProfile.imageHeightAdjust).coerceAtLeast(180)
            textRegion = java.awt.Rectangle(margin, contentTop, leftWidth, contentHeight)
            imageRegion = java.awt.Rectangle(margin + leftWidth + gap, imageY, rightWidth, imageHeight)
        } else if (imageOnly) {
            textRegion = java.awt.Rectangle(margin, contentTop, contentWidth, contentHeight)
            imageRegion = java.awt.Rectangle(margin, contentTop, contentWidth, contentHeight)
        } else {
            val preferredTextWidth = (contentWidth * templateBehavior.textOnlyWidthRatio).roundToInt()
            val textWidth = preferredTextWidth.coerceAtLeast(minOf(420, contentWidth)).coerceAtMost(contentWidth)
            val textX = when (templateBehavior.textOnlyAlignment) {
                HtmlFirstTextAlignment.LEFT -> margin
                HtmlFirstTextAlignment.CENTER -> margin + (contentWidth - textWidth) / 2
                HtmlFirstTextAlignment.RIGHT -> margin + (contentWidth - textWidth)
            }
            textRegion = java.awt.Rectangle(textX, contentTop, textWidth, contentHeight)
            imageRegion = java.awt.Rectangle(margin, contentTop + 24, contentWidth, contentHeight - 24)
        }

        val bodyFontSize = selectBodyFontSize(polishedPoints, textRegion.width - 36)
        val bodyPanelHeight = estimateBodyPanelHeight(polishedPoints, bodyFontSize, textRegion.width - 48)
            .coerceAtMost(textRegion.height)
        val bodyPanelAnchor = java.awt.Rectangle(
            textRegion.x,
            textRegion.y,
            textRegion.width,
            bodyPanelHeight
        )
        val imagePanelHeight = when {
            !hasImage -> 0
            imageOnly -> imageRegion.height
            else -> minOf(imageRegion.height, maxOf(bodyPanelHeight, 250))
        }
        val imagePanelAnchor = java.awt.Rectangle(
            imageRegion.x,
            imageRegion.y,
            imageRegion.width,
            imagePanelHeight
        )

        if (hasTextPoints) {
            val bodyPanel = slide.createAutoShape()
            bodyPanel.shapeType = if (templateProfile.roundedPanels) {
                org.apache.poi.sl.usermodel.ShapeType.ROUND_RECT
            } else {
                org.apache.poi.sl.usermodel.ShapeType.RECT
            }
            bodyPanel.anchor = bodyPanelAnchor
            bodyPanel.fillColor = palette.panelFill
            bodyPanel.setLineColor(palette.panelBorder)
            bodyPanel.lineWidth = templateProfile.lineWidth

            val bodyAccentSpec = resolveHtmlFirstBodyAccent(templateProfile, bodyPanelAnchor)
            val bodyAccent = slide.createAutoShape()
            bodyAccent.shapeType = bodyAccentSpec.first
            bodyAccent.anchor = bodyAccentSpec.second
            bodyAccent.fillColor = palette.accentColor
            bodyAccent.setLineColor(null)
        }

        if (hasImage && imagePanelAnchor.height > 0) {
            val imagePanel = slide.createAutoShape()
            imagePanel.shapeType = if (templateProfile.roundedPanels) {
                org.apache.poi.sl.usermodel.ShapeType.ROUND_RECT
            } else {
                org.apache.poi.sl.usermodel.ShapeType.RECT
            }
            imagePanel.anchor = imagePanelAnchor
            imagePanel.fillColor = palette.imagePanelFill
            imagePanel.setLineColor(palette.panelBorder)
            imagePanel.lineWidth = templateProfile.lineWidth
        }

        if (hasTextPoints) {
            val body = slide.createTextBox()
            body.anchor = java.awt.Rectangle(
                bodyPanelAnchor.x + 20,
                bodyPanelAnchor.y + 18,
                bodyPanelAnchor.width - 36,
                bodyPanelAnchor.height - 28
            )
            body.clearText()
            polishedPoints.forEach { point ->
                val paragraph = body.addNewTextParagraph()
                paragraph.isBullet = layoutName != "TITLE" && layoutName != "TITLE_ONLY"
                val run = paragraph.addNewTextRun()
                run.setText(point)
                run.fontFamily = customTheme?.bodyFont ?: resolvedTheme?.bodyFont ?: "Arial"
                run.fontSize = bodyFontSize
                run.fontColor = org.apache.poi.sl.draw.DrawPaint.createSolidPaint(
                    palette.bodyColorOnPanel
                )
            }
        }

        if (preparedImage != null) {
            addImageToSlide(
                ppt = ppt,
                slide = slide,
                preparedImage = preparedImage,
                fallbackAnchor = imagePanelAnchor,
                customAnchor = if (
                    slideData.imageX != null && slideData.imageY != null &&
                    slideData.imageWidth != null && slideData.imageHeight != null
                ) java.awt.Rectangle(slideData.imageX, slideData.imageY, slideData.imageWidth, slideData.imageHeight) else null
            )
        }

        if (slideData.table != null) {
            createTable(slide, slideData.table, resolvedTheme, textRegion)
        }
        slideData.shapes?.forEach { createShape(slide, it, resolvedTheme, customTheme) }
        slideData.chart?.let { createChartPlaceholder(slide, it, resolvedTheme, customTheme) }
    }

    private fun buildSlideEyebrow(
        slideData: SlideContent,
        slideIndex: Int,
        variant: HtmlFirstVariant,
    ): String {
        val layoutName = slideData.layout?.uppercase()
        val localized = containsCyrillic(slideData.title) || slideData.subtitle?.let(::containsCyrillic) == true
        return when (variant) {
            HtmlFirstVariant.PLAYFUL -> when (layoutName) {
                "TITLE", "SECTION_HEADER" -> if (localized) "СТАРТ ИСТОРИИ" else "OPENING BEAT"
                "PIC_TX", "TWO_COL_TX_IMG" -> if (localized) "ЗАБАВНЫЙ ФАКТ" else "FUN FACT"
                "TITLE_ONLY" -> if (localized) "СУТЬ ШУТКИ" else "PUNCHLINE"
                else -> if (slideIndex == 1) {
                    if (localized) "ПОЕХАЛИ" else "LET'S BEGIN"
                } else {
                    if (localized) "ПОЧТИ СЕРЬЕЗНО" else "SERIOUSLY THOUGH"
                }
            }

            else -> when (layoutName) {
                "TITLE", "SECTION_HEADER" -> if (localized) "ОБЗОР" else "EXECUTIVE OVERVIEW"
                "PIC_TX", "TWO_COL_TX_IMG" -> if (localized) "ФАКТЫ" else "FACT BASE"
                "TITLE_ONLY" -> if (localized) "КЛЮЧЕВАЯ МЫСЛЬ" else "KEY MESSAGE"
                else -> if (slideIndex == 1) {
                    if (localized) "РЕЗЮМЕ" else "EXECUTIVE SUMMARY"
                } else {
                    if (localized) "АНАЛИЗ" else "ANALYSIS"
                }
            }
        }
    }

    private fun isPlayfulSlide(slideData: SlideContent): Boolean {
        val corpus = buildString {
            append(slideData.title)
            append(' ')
            slideData.subtitle?.let { append(it).append(' ') }
            slideData.points.forEach { append(it).append(' ') }
        }
        return isPlayfulCorpus(corpus)
    }

    private fun isPlayfulCorpus(raw: String): Boolean {
        val normalized = raw.lowercase()
        if (containsEmojiLikeChars(raw)) return true
        return playfulKeywords.any { normalized.contains(it) }
    }

    private fun containsEmojiLikeChars(raw: String): Boolean {
        var index = 0
        while (index < raw.length) {
            val codePoint = raw.codePointAt(index)
            val type = Character.getType(codePoint)
            if (
                codePoint >= 0x1F300 ||
                type == Character.OTHER_SYMBOL.toInt()
            ) {
                return true
            }
            index += Character.charCount(codePoint)
        }
        return false
    }

    private fun resolveTitleColor(theme: PresentationTheme?, customTheme: CustomThemeParam?): java.awt.Color {
        return when {
            customTheme?.titleColor != null -> runCatching { java.awt.Color.decode(customTheme.titleColor) }.getOrNull()
            else -> theme?.titleColor
        } ?: java.awt.Color.BLACK
    }

    private fun resolveBodyColor(theme: PresentationTheme?, customTheme: CustomThemeParam?): java.awt.Color {
        return when {
            customTheme?.contentColor != null -> runCatching { java.awt.Color.decode(customTheme.contentColor) }.getOrNull()
            else -> theme?.contentColor
        } ?: java.awt.Color.DARK_GRAY
    }

    private fun resolveAccentColor(theme: PresentationTheme?, customTheme: CustomThemeParam?): java.awt.Color {
        return when {
            customTheme?.accentColor != null -> runCatching { java.awt.Color.decode(customTheme.accentColor) }.getOrNull()
            else -> theme?.accentColor
        } ?: java.awt.Color(13, 92, 155)
    }

    private enum class HtmlFirstVariant {
        CONSULTING,
        DARK_TECH,
        EDITORIAL,
        PLAYFUL,
    }

    private enum class HtmlFirstTemplateId {
        BOARDROOM,
        COLUMN_GRID,
        SIDEBAR_LEDGER,
        TITLE_RIBBON,
        SIGNAL_FRAME,
        BLUEPRINT,
        DATA_BAND,
        TERMINAL_POCKET,
        CHAPTER_BAND,
        CANVAS_FLOAT,
        GALLERY_TAG,
        PAPER_TAB,
        CONFETTI_NOTE,
        BUBBLE_LAB,
        POSTER_STACK,
        SCRAPBOOK,
    }

    private enum class HtmlFirstRuleStyle {
        UNDERLINE_SHORT,
        UNDERLINE_LONG,
        SIDE_BAR,
        DOUBLE_UNDERLINE,
        OFFSET_BAR,
        RIBBON,
    }

    private enum class HtmlFirstDecorationStyle {
        NONE,
        BLOCK,
        DOT,
        BUBBLES,
        TABS,
        BRACKET,
        STACK,
        CONFETTI,
        SQUARE_TRIO,
        RING,
    }

    private enum class HtmlFirstAccentStyle {
        SIDE_BAR,
        TOP_BAR,
        FLOATING_TAB,
        RIGHT_BAR,
        INSET_PILL,
    }

    private enum class HtmlFirstTextAlignment {
        LEFT,
        CENTER,
        RIGHT,
    }

    private enum class HtmlFirstBackdropStyle {
        NONE,
        CORNER_WASH,
        HALO,
        DIAGONAL_SWEEP,
        SPLIT_PANEL,
        DOT_FIELD,
        PAPER_ARC,
        POSTER_GLOW,
    }

    private data class HtmlFirstTemplateBehavior(
        val textOnlyWidthRatio: Double,
        val textOnlyAlignment: HtmlFirstTextAlignment,
        val heroTitleWidthRatio: Double,
        val backdropStyle: HtmlFirstBackdropStyle,
    )

    private data class HtmlFirstTemplateProfile(
        val id: HtmlFirstTemplateId,
        val titleX: Int,
        val titleY: Int,
        val eyebrowY: Int,
        val eyebrowWidth: Int,
        val eyebrowBadgeWidth: Int? = null,
        val titleRightInset: Int,
        val denseTitleRatio: Double? = null,
        val gap: Int,
        val leftRatio: Double,
        val imageOffsetY: Int,
        val imageHeightAdjust: Int,
        val roundedPanels: Boolean,
        val lineWidth: Double,
        val ruleStyle: HtmlFirstRuleStyle,
        val decorationStyle: HtmlFirstDecorationStyle,
        val accentStyle: HtmlFirstAccentStyle,
    )

    private data class HtmlFirstPalette(
        val backgroundColor: java.awt.Color,
        val titleColor: java.awt.Color,
        val eyebrowColor: java.awt.Color,
        val subtitleColor: java.awt.Color,
        val counterColor: java.awt.Color,
        val footerColor: java.awt.Color,
        val accentColor: java.awt.Color,
        val panelFill: java.awt.Color,
        val panelBorder: java.awt.Color,
        val imagePanelFill: java.awt.Color,
        val bodyColorOnPanel: java.awt.Color,
    )

    private data class HtmlFirstPaletteSeed(
        val background: java.awt.Color,
        val title: java.awt.Color,
        val body: java.awt.Color,
        val accent: java.awt.Color,
    )

    private val consultingTemplates = listOf(
        HtmlFirstTemplateProfile(HtmlFirstTemplateId.BOARDROOM, 64, 58, 20, 360, null, 128, null, 24, 0.55, 0, 0, true, 1.0, HtmlFirstRuleStyle.UNDERLINE_SHORT, HtmlFirstDecorationStyle.DOT, HtmlFirstAccentStyle.SIDE_BAR),
        HtmlFirstTemplateProfile(HtmlFirstTemplateId.COLUMN_GRID, 72, 50, 20, 360, null, 160, 0.64, 30, 0.50, 6, -10, false, 1.2, HtmlFirstRuleStyle.DOUBLE_UNDERLINE, HtmlFirstDecorationStyle.BLOCK, HtmlFirstAccentStyle.TOP_BAR),
        HtmlFirstTemplateProfile(HtmlFirstTemplateId.SIDEBAR_LEDGER, 88, 62, 24, 300, 180, 170, null, 28, 0.52, 8, -8, true, 1.0, HtmlFirstRuleStyle.SIDE_BAR, HtmlFirstDecorationStyle.BRACKET, HtmlFirstAccentStyle.RIGHT_BAR),
        HtmlFirstTemplateProfile(HtmlFirstTemplateId.TITLE_RIBBON, 64, 72, 26, 320, 210, 190, null, 26, 0.56, 4, -6, true, 1.0, HtmlFirstRuleStyle.RIBBON, HtmlFirstDecorationStyle.TABS, HtmlFirstAccentStyle.FLOATING_TAB),
    )

    private val darkTechTemplates = listOf(
        HtmlFirstTemplateProfile(HtmlFirstTemplateId.SIGNAL_FRAME, 64, 52, 18, 400, null, 180, 0.58, 32, 0.48, -6, 6, false, 1.4, HtmlFirstRuleStyle.UNDERLINE_LONG, HtmlFirstDecorationStyle.BRACKET, HtmlFirstAccentStyle.TOP_BAR),
        HtmlFirstTemplateProfile(HtmlFirstTemplateId.BLUEPRINT, 72, 56, 20, 360, 190, 190, 0.60, 30, 0.50, 0, 0, false, 1.4, HtmlFirstRuleStyle.DOUBLE_UNDERLINE, HtmlFirstDecorationStyle.SQUARE_TRIO, HtmlFirstAccentStyle.RIGHT_BAR),
        HtmlFirstTemplateProfile(HtmlFirstTemplateId.DATA_BAND, 68, 66, 20, 360, null, 210, 0.57, 28, 0.49, 0, -6, false, 1.3, HtmlFirstRuleStyle.OFFSET_BAR, HtmlFirstDecorationStyle.STACK, HtmlFirstAccentStyle.TOP_BAR),
        HtmlFirstTemplateProfile(HtmlFirstTemplateId.TERMINAL_POCKET, 74, 64, 18, 300, 200, 220, 0.54, 34, 0.46, 4, -4, false, 1.3, HtmlFirstRuleStyle.SIDE_BAR, HtmlFirstDecorationStyle.BLOCK, HtmlFirstAccentStyle.FLOATING_TAB),
    )

    private val editorialTemplates = listOf(
        HtmlFirstTemplateProfile(HtmlFirstTemplateId.CHAPTER_BAND, 92, 58, 24, 300, 190, 180, null, 28, 0.50, 10, -10, true, 1.0, HtmlFirstRuleStyle.SIDE_BAR, HtmlFirstDecorationStyle.DOT, HtmlFirstAccentStyle.SIDE_BAR),
        HtmlFirstTemplateProfile(HtmlFirstTemplateId.CANVAS_FLOAT, 80, 64, 22, 340, null, 220, null, 30, 0.50, 12, -18, true, 1.0, HtmlFirstRuleStyle.OFFSET_BAR, HtmlFirstDecorationStyle.RING, HtmlFirstAccentStyle.FLOATING_TAB),
        HtmlFirstTemplateProfile(HtmlFirstTemplateId.GALLERY_TAG, 86, 54, 18, 320, 180, 200, 0.64, 28, 0.51, 8, -10, true, 1.0, HtmlFirstRuleStyle.RIBBON, HtmlFirstDecorationStyle.STACK, HtmlFirstAccentStyle.TOP_BAR),
        HtmlFirstTemplateProfile(HtmlFirstTemplateId.PAPER_TAB, 90, 70, 22, 280, 200, 170, null, 26, 0.53, 4, -12, true, 1.0, HtmlFirstRuleStyle.DOUBLE_UNDERLINE, HtmlFirstDecorationStyle.TABS, HtmlFirstAccentStyle.FLOATING_TAB),
    )

    private val playfulTemplates = listOf(
        HtmlFirstTemplateProfile(HtmlFirstTemplateId.CONFETTI_NOTE, 72, 68, 18, 280, 222, 196, null, 28, 0.52, 4, -4, true, 1.0, HtmlFirstRuleStyle.SIDE_BAR, HtmlFirstDecorationStyle.CONFETTI, HtmlFirstAccentStyle.FLOATING_TAB),
        HtmlFirstTemplateProfile(HtmlFirstTemplateId.BUBBLE_LAB, 78, 70, 18, 260, 210, 210, null, 30, 0.50, 6, -8, true, 1.0, HtmlFirstRuleStyle.RIBBON, HtmlFirstDecorationStyle.BUBBLES, HtmlFirstAccentStyle.INSET_PILL),
        HtmlFirstTemplateProfile(HtmlFirstTemplateId.POSTER_STACK, 68, 60, 20, 320, null, 210, 0.62, 26, 0.54, 8, -10, false, 1.1, HtmlFirstRuleStyle.UNDERLINE_LONG, HtmlFirstDecorationStyle.STACK, HtmlFirstAccentStyle.TOP_BAR),
        HtmlFirstTemplateProfile(HtmlFirstTemplateId.SCRAPBOOK, 84, 74, 24, 260, 220, 190, null, 32, 0.52, 10, -14, true, 1.0, HtmlFirstRuleStyle.DOUBLE_UNDERLINE, HtmlFirstDecorationStyle.SQUARE_TRIO, HtmlFirstAccentStyle.FLOATING_TAB),
    )

    private fun resolveHtmlFirstVariant(
        theme: PresentationTheme?,
        designId: String?,
        backgroundColor: java.awt.Color,
        playfulTone: Boolean,
    ): HtmlFirstVariant {
        if (playfulTone) return HtmlFirstVariant.PLAYFUL
        val normalizedDesign = designId?.uppercase()
        if (normalizedDesign in setOf("CYBERPUNK", "TECH_GRID", "DIGITAL_WAVE")) return HtmlFirstVariant.DARK_TECH
        if (normalizedDesign in setOf("NATURE_GREEN", "OCEAN_BLUE", "SOFT_PASTEL", "MODERN_SPLIT")) return HtmlFirstVariant.EDITORIAL

        return when (theme) {
            PresentationTheme.CYBERPUNK,
            PresentationTheme.MATRIX,
            PresentationTheme.STARTUP,
            PresentationTheme.TECHNICAL,
            PresentationTheme.SPACE,
            PresentationTheme.MIDNIGHT,
            PresentationTheme.NEON_NIGHT,
            PresentationTheme.HACKER,
            -> HtmlFirstVariant.DARK_TECH

            PresentationTheme.NATURE,
            PresentationTheme.OCEAN,
            PresentationTheme.PASTEL,
            PresentationTheme.CANDY,
            PresentationTheme.WARMTH,
            PresentationTheme.PROFESSIONAL_TEAL,
            -> HtmlFirstVariant.EDITORIAL

            else -> if (isDarkColor(backgroundColor)) HtmlFirstVariant.DARK_TECH else HtmlFirstVariant.CONSULTING
        }
    }

    private fun resolveHtmlFirstTemplateProfile(
        variant: HtmlFirstVariant,
        layoutName: String?,
        hasImage: Boolean,
        hasTextPoints: Boolean,
        imageOnly: Boolean,
        slideIndex: Int,
    ): HtmlFirstTemplateProfile {
        val family = when (variant) {
            HtmlFirstVariant.CONSULTING -> consultingTemplates
            HtmlFirstVariant.DARK_TECH -> darkTechTemplates
            HtmlFirstVariant.EDITORIAL -> editorialTemplates
            HtmlFirstVariant.PLAYFUL -> playfulTemplates
        }
        val roleOffset = when {
            layoutName == "TITLE" || layoutName == "SECTION_HEADER" || layoutName == "TITLE_ONLY" -> 0
            imageOnly -> 2
            hasImage && hasTextPoints -> 1
            else -> 3
        }
        val profileIndex = Math.floorMod(slideIndex - 1 + roleOffset, family.size)
        return family[profileIndex]
    }

    private fun resolveHtmlFirstTemplateBehavior(templateId: HtmlFirstTemplateId): HtmlFirstTemplateBehavior {
        return when (templateId) {
            HtmlFirstTemplateId.BOARDROOM -> HtmlFirstTemplateBehavior(0.68, HtmlFirstTextAlignment.LEFT, 0.62, HtmlFirstBackdropStyle.NONE)
            HtmlFirstTemplateId.COLUMN_GRID -> HtmlFirstTemplateBehavior(0.74, HtmlFirstTextAlignment.CENTER, 0.68, HtmlFirstBackdropStyle.SPLIT_PANEL)
            HtmlFirstTemplateId.SIDEBAR_LEDGER -> HtmlFirstTemplateBehavior(0.62, HtmlFirstTextAlignment.LEFT, 0.60, HtmlFirstBackdropStyle.DIAGONAL_SWEEP)
            HtmlFirstTemplateId.TITLE_RIBBON -> HtmlFirstTemplateBehavior(0.72, HtmlFirstTextAlignment.CENTER, 0.66, HtmlFirstBackdropStyle.CORNER_WASH)
            HtmlFirstTemplateId.SIGNAL_FRAME -> HtmlFirstTemplateBehavior(0.66, HtmlFirstTextAlignment.LEFT, 0.62, HtmlFirstBackdropStyle.HALO)
            HtmlFirstTemplateId.BLUEPRINT -> HtmlFirstTemplateBehavior(0.74, HtmlFirstTextAlignment.CENTER, 0.64, HtmlFirstBackdropStyle.DOT_FIELD)
            HtmlFirstTemplateId.DATA_BAND -> HtmlFirstTemplateBehavior(0.78, HtmlFirstTextAlignment.LEFT, 0.68, HtmlFirstBackdropStyle.SPLIT_PANEL)
            HtmlFirstTemplateId.TERMINAL_POCKET -> HtmlFirstTemplateBehavior(0.64, HtmlFirstTextAlignment.RIGHT, 0.58, HtmlFirstBackdropStyle.DIAGONAL_SWEEP)
            HtmlFirstTemplateId.CHAPTER_BAND -> HtmlFirstTemplateBehavior(0.66, HtmlFirstTextAlignment.LEFT, 0.58, HtmlFirstBackdropStyle.PAPER_ARC)
            HtmlFirstTemplateId.CANVAS_FLOAT -> HtmlFirstTemplateBehavior(0.70, HtmlFirstTextAlignment.CENTER, 0.62, HtmlFirstBackdropStyle.HALO)
            HtmlFirstTemplateId.GALLERY_TAG -> HtmlFirstTemplateBehavior(0.76, HtmlFirstTextAlignment.CENTER, 0.70, HtmlFirstBackdropStyle.CORNER_WASH)
            HtmlFirstTemplateId.PAPER_TAB -> HtmlFirstTemplateBehavior(0.64, HtmlFirstTextAlignment.RIGHT, 0.60, HtmlFirstBackdropStyle.PAPER_ARC)
            HtmlFirstTemplateId.CONFETTI_NOTE -> HtmlFirstTemplateBehavior(0.66, HtmlFirstTextAlignment.LEFT, 0.58, HtmlFirstBackdropStyle.DOT_FIELD)
            HtmlFirstTemplateId.BUBBLE_LAB -> HtmlFirstTemplateBehavior(0.62, HtmlFirstTextAlignment.CENTER, 0.56, HtmlFirstBackdropStyle.HALO)
            HtmlFirstTemplateId.POSTER_STACK -> HtmlFirstTemplateBehavior(0.72, HtmlFirstTextAlignment.LEFT, 0.66, HtmlFirstBackdropStyle.POSTER_GLOW)
            HtmlFirstTemplateId.SCRAPBOOK -> HtmlFirstTemplateBehavior(0.68, HtmlFirstTextAlignment.RIGHT, 0.62, HtmlFirstBackdropStyle.CORNER_WASH)
        }
    }

    private fun resolveHtmlFirstBackgroundColor(
        requestedBackgroundColor: java.awt.Color,
        variant: HtmlFirstVariant,
        templateId: HtmlFirstTemplateId,
        customTheme: CustomThemeParam?,
        slideIndex: Int,
    ): java.awt.Color {
        if (customTheme?.backgroundColor != null) return requestedBackgroundColor
        val curated = when (variant) {
            HtmlFirstVariant.CONSULTING -> listOf(
                java.awt.Color(250, 251, 253),
                java.awt.Color(245, 247, 250),
                java.awt.Color(252, 250, 247),
                java.awt.Color(247, 250, 252),
            )

            HtmlFirstVariant.DARK_TECH -> listOf(
                java.awt.Color(14, 22, 36),
                java.awt.Color(18, 26, 44),
                java.awt.Color(10, 28, 36),
                java.awt.Color(27, 23, 44),
            )

            HtmlFirstVariant.EDITORIAL -> listOf(
                java.awt.Color(249, 245, 238),
                java.awt.Color(240, 246, 244),
                java.awt.Color(244, 242, 248),
                java.awt.Color(252, 248, 242),
            )

            HtmlFirstVariant.PLAYFUL -> listOf(
                java.awt.Color(255, 247, 235),
                java.awt.Color(241, 247, 255),
                java.awt.Color(253, 243, 246),
                java.awt.Color(245, 250, 236),
            )
        }
        val index = Math.floorMod(templateId.ordinal + slideIndex - 1, curated.size)
        return curated[index]
    }

    private fun renderHtmlFirstBackdrop(
        slide: org.apache.poi.xslf.usermodel.XSLFSlide,
        pageWidth: Int,
        pageHeight: Int,
        behavior: HtmlFirstTemplateBehavior,
        palette: HtmlFirstPalette,
        backgroundColor: java.awt.Color,
    ) {
        fun rect(anchor: java.awt.Rectangle, fill: java.awt.Color?, line: java.awt.Color? = null, lineWidth: Double? = null, rotation: Double? = null) {
            val shape = slide.createAutoShape()
            shape.shapeType = org.apache.poi.sl.usermodel.ShapeType.RECT
            shape.anchor = anchor
            shape.fillColor = fill
            shape.lineColor = line
            lineWidth?.let { shape.lineWidth = it }
            rotation?.let { shape.rotation = it }
        }

        fun ellipse(anchor: java.awt.Rectangle, fill: java.awt.Color?, line: java.awt.Color? = null, lineWidth: Double? = null) {
            val shape = slide.createAutoShape()
            shape.shapeType = org.apache.poi.sl.usermodel.ShapeType.ELLIPSE
            shape.anchor = anchor
            shape.fillColor = fill
            shape.lineColor = line
            lineWidth?.let { shape.lineWidth = it }
        }

        val mist = mixColors(palette.accentColor, backgroundColor, 0.82)
        val haze = mixColors(palette.panelFill, backgroundColor, 0.52)
        when (behavior.backdropStyle) {
            HtmlFirstBackdropStyle.NONE -> Unit
            HtmlFirstBackdropStyle.CORNER_WASH -> {
                ellipse(java.awt.Rectangle(-pageWidth / 10, pageHeight - pageHeight / 3, pageWidth / 3, pageWidth / 3), withAlpha(haze, 74))
                ellipse(java.awt.Rectangle(pageWidth - pageWidth / 4, -pageHeight / 5, pageWidth / 3, pageWidth / 3), withAlpha(mist, 66))
            }

            HtmlFirstBackdropStyle.HALO -> {
                ellipse(
                    anchor = java.awt.Rectangle(pageWidth / 2 - 140, pageHeight / 2 - 110, 320, 320),
                    fill = null,
                    line = withAlpha(mist, 108),
                    lineWidth = 4.0
                )
                ellipse(java.awt.Rectangle(pageWidth / 2 - 90, pageHeight / 2 - 60, 220, 220), withAlpha(haze, 54))
            }

            HtmlFirstBackdropStyle.DIAGONAL_SWEEP -> rect(
                anchor = java.awt.Rectangle(pageWidth - 300, pageHeight / 5, 260, pageHeight),
                fill = withAlpha(mist, 72),
                rotation = 28.0
            )

            HtmlFirstBackdropStyle.SPLIT_PANEL -> {
                rect(java.awt.Rectangle(pageWidth - 260, 0, 260, pageHeight), withAlpha(haze, 58))
                rect(java.awt.Rectangle(0, pageHeight - 84, pageWidth / 4, 84), withAlpha(mist, 72))
            }

            HtmlFirstBackdropStyle.DOT_FIELD -> {
                for (row in 0..2) {
                    for (col in 0..3) {
                        ellipse(
                            anchor = java.awt.Rectangle(pageWidth - 170 + col * 22, 42 + row * 18, 8, 8),
                            fill = withAlpha(mist, 110 - row * 18 - col * 6)
                        )
                    }
                }
            }

            HtmlFirstBackdropStyle.PAPER_ARC -> {
                ellipse(java.awt.Rectangle(-pageWidth / 14, pageHeight - pageHeight / 4, pageWidth / 2, pageWidth / 2), withAlpha(haze, 70))
                ellipse(
                    anchor = java.awt.Rectangle(pageWidth - 250, 40, 190, 190),
                    fill = null,
                    line = withAlpha(mist, 96),
                    lineWidth = 3.0
                )
            }

            HtmlFirstBackdropStyle.POSTER_GLOW -> {
                rect(java.awt.Rectangle(pageWidth - 280, 86, 220, 280), withAlpha(haze, 70))
                ellipse(java.awt.Rectangle(-50, 80, 180, 180), withAlpha(mist, 60))
            }
        }
    }

    private fun isHeroTitleSlide(
        slideData: SlideContent,
        layoutName: String?,
        slideIndex: Int,
        hasTextPoints: Boolean,
        hasImage: Boolean,
    ): Boolean {
        if (hasTextPoints || hasImage || slideData.table != null || slideData.chart != null) return false
        return slideIndex == 1 || layoutName == "TITLE" || layoutName == "TITLE_ONLY" || layoutName == "SECTION_HEADER"
    }

    private fun renderHtmlFirstHeroSlide(
        slide: org.apache.poi.xslf.usermodel.XSLFSlide,
        slideData: SlideContent,
        customTheme: CustomThemeParam?,
        resolvedTheme: PresentationTheme?,
        slideIndex: Int,
        totalSlides: Int,
        variant: HtmlFirstVariant,
        template: HtmlFirstTemplateProfile,
        behavior: HtmlFirstTemplateBehavior,
        palette: HtmlFirstPalette,
        pageWidth: Int,
        pageHeight: Int,
    ) {
        val titleWidth = (pageWidth * behavior.heroTitleWidthRatio).roundToInt().coerceIn(520, pageWidth - 180)
        val titleX = when (behavior.textOnlyAlignment) {
            HtmlFirstTextAlignment.LEFT -> template.titleX
            HtmlFirstTextAlignment.CENTER -> (pageWidth - titleWidth) / 2
            HtmlFirstTextAlignment.RIGHT -> pageWidth - titleWidth - 96
        }
        val eyebrowText = buildSlideEyebrow(slideData, slideIndex, variant)
        val titleFont = customTheme?.titleFont ?: resolvedTheme?.titleFont ?: "Arial"
        val bodyFont = customTheme?.bodyFont ?: resolvedTheme?.bodyFont ?: "Arial"
        val titleFontSize = selectTitleFontSize(slideData.title) + if (variant == HtmlFirstVariant.PLAYFUL) 8.0 else 6.0
        val titleHeight = (estimateWrappedLineCount(slideData.title, titleFontSize, titleWidth) * titleFontSize * 1.12).roundToInt().coerceIn(76, 180)
        val titleY = when (variant) {
            HtmlFirstVariant.PLAYFUL -> 170
            HtmlFirstVariant.DARK_TECH -> 154
            else -> 148
        }

        val eyebrowChip = slide.createAutoShape()
        eyebrowChip.shapeType = org.apache.poi.sl.usermodel.ShapeType.ROUND_RECT
        eyebrowChip.anchor = java.awt.Rectangle(titleX, 64, if (variant == HtmlFirstVariant.PLAYFUL) 188 else 176, 30)
        eyebrowChip.fillColor = mixColors(palette.accentColor, palette.panelFill, 0.18)
        eyebrowChip.setLineColor(mixColors(palette.accentColor, palette.panelFill, 0.42))
        eyebrowChip.lineWidth = 1.0

        val eyebrow = slide.createTextBox()
        eyebrow.anchor = java.awt.Rectangle(titleX + 16, 70, 220, 18)
        eyebrow.text = eyebrowText
        eyebrow.textParagraphs.forEach { p ->
            p.textRuns.forEach { run ->
                run.fontFamily = bodyFont
                run.fontSize = 11.5
                run.isBold = true
                run.characterSpacing = 1.2
                run.fontColor = org.apache.poi.sl.draw.DrawPaint.createSolidPaint(palette.eyebrowColor)
            }
        }

        val counter = slide.createTextBox()
        counter.anchor = java.awt.Rectangle(pageWidth - 148, 68, 84, 18)
        counter.text = "${slideIndex.toString().padStart(2, '0')} / ${totalSlides.toString().padStart(2, '0')}"
        counter.textParagraphs.forEach { paragraph ->
            paragraph.textAlign = org.apache.poi.sl.usermodel.TextParagraph.TextAlign.RIGHT
            paragraph.textRuns.forEach { run ->
                run.fontFamily = bodyFont
                run.fontSize = 12.0
                run.fontColor = org.apache.poi.sl.draw.DrawPaint.createSolidPaint(palette.counterColor)
            }
        }

        val titleBox = slide.createTextBox()
        titleBox.anchor = java.awt.Rectangle(titleX, titleY, titleWidth, titleHeight)
        titleBox.text = slideData.title
        titleBox.textParagraphs.forEach { p ->
            p.textRuns.forEach { run ->
                run.fontFamily = titleFont
                run.fontSize = titleFontSize
                run.isBold = true
                run.fontColor = org.apache.poi.sl.draw.DrawPaint.createSolidPaint(palette.titleColor)
            }
        }

        val accentBar = slide.createAutoShape()
        accentBar.shapeType = org.apache.poi.sl.usermodel.ShapeType.RECT
        accentBar.anchor = java.awt.Rectangle(titleX - 18, titleY + 10, 8, minOf(118, titleHeight + 12))
        accentBar.fillColor = palette.accentColor
        accentBar.setLineColor(null)

        slideData.subtitle?.takeIf { it.isNotBlank() }?.let { subtitleText ->
            val subtitleWidth = minOf((titleWidth * 0.92).roundToInt(), pageWidth - titleX - 120)
            val subtitleY = titleY + titleHeight + 26
            val subtitleCard = slide.createAutoShape()
            subtitleCard.shapeType = org.apache.poi.sl.usermodel.ShapeType.ROUND_RECT
            subtitleCard.anchor = java.awt.Rectangle(titleX, subtitleY, subtitleWidth, 56)
            subtitleCard.fillColor = mixColors(palette.panelFill, palette.backgroundColor, 0.18)
            subtitleCard.setLineColor(mixColors(palette.panelBorder, palette.backgroundColor, 0.28))
            subtitleCard.lineWidth = 1.0

            val subtitle = slide.createTextBox()
            subtitle.anchor = java.awt.Rectangle(titleX + 18, subtitleY + 14, subtitleWidth - 32, 28)
            subtitle.text = subtitleText
            subtitle.textParagraphs.forEach { p ->
                p.textRuns.forEach { run ->
                    run.fontFamily = bodyFont
                    run.fontSize = 20.0
                    run.fontColor = org.apache.poi.sl.draw.DrawPaint.createSolidPaint(palette.subtitleColor)
                }
            }
        }

        renderHtmlFirstHeaderDecoration(
            slide = slide,
            template = template,
            pageWidth = pageWidth,
            titleY = 72,
            palette = palette,
            bgColor = palette.backgroundColor,
        )
    }

    private fun renderHtmlFirstTitleRule(
        slide: org.apache.poi.xslf.usermodel.XSLFSlide,
        template: HtmlFirstTemplateProfile,
        titleX: Int,
        titleY: Int,
        titleBottom: Int,
        titleWidth: Int,
        titleHeight: Int,
        palette: HtmlFirstPalette,
        bgColor: java.awt.Color,
    ): java.awt.Rectangle {
        fun createRect(anchor: java.awt.Rectangle): java.awt.Rectangle {
            val shape = slide.createAutoShape()
            shape.shapeType = org.apache.poi.sl.usermodel.ShapeType.RECT
            shape.anchor = anchor
            shape.fillColor = palette.accentColor
            shape.setLineColor(null)
            return anchor
        }

        return when (template.ruleStyle) {
            HtmlFirstRuleStyle.UNDERLINE_SHORT -> createRect(
                java.awt.Rectangle(titleX, titleBottom + 14, minOf(124, maxOf(92, titleWidth / 4)), 4)
            )

            HtmlFirstRuleStyle.UNDERLINE_LONG -> createRect(
                java.awt.Rectangle(titleX, titleBottom + 16, minOf(220, maxOf(136, titleWidth / 3)), 4)
            )

            HtmlFirstRuleStyle.SIDE_BAR -> createRect(
                java.awt.Rectangle(titleX - 14, titleY + 8, 8, titleHeight + 18)
            )

            HtmlFirstRuleStyle.DOUBLE_UNDERLINE -> {
                createRect(java.awt.Rectangle(titleX, titleBottom + 10, minOf(180, maxOf(120, titleWidth / 3)), 4))
                createRect(java.awt.Rectangle(titleX, titleBottom + 20, minOf(92, maxOf(64, titleWidth / 5)), 4))
            }

            HtmlFirstRuleStyle.OFFSET_BAR -> createRect(
                java.awt.Rectangle(titleX + 22, titleBottom + 14, minOf(168, maxOf(110, titleWidth / 4)), 4)
            )

            HtmlFirstRuleStyle.RIBBON -> {
                val anchor = java.awt.Rectangle(titleX, titleBottom + 10, minOf(172, maxOf(122, titleWidth / 3)), 14)
                val ribbon = slide.createAutoShape()
                ribbon.shapeType = org.apache.poi.sl.usermodel.ShapeType.ROUND_RECT
                ribbon.anchor = anchor
                ribbon.fillColor = mixColors(palette.accentColor, bgColor, 0.12)
                ribbon.setLineColor(mixColors(palette.accentColor, bgColor, 0.32))
                ribbon.lineWidth = 1.0
                anchor
            }
        }
    }

    private fun renderHtmlFirstHeaderDecoration(
        slide: org.apache.poi.xslf.usermodel.XSLFSlide,
        template: HtmlFirstTemplateProfile,
        pageWidth: Int,
        titleY: Int,
        palette: HtmlFirstPalette,
        bgColor: java.awt.Color,
    ) {
        fun rect(anchor: java.awt.Rectangle, fill: java.awt.Color?, line: java.awt.Color? = null, lineWidth: Double? = null) {
            val shape = slide.createAutoShape()
            shape.shapeType = org.apache.poi.sl.usermodel.ShapeType.RECT
            shape.anchor = anchor
            shape.fillColor = fill
            shape.lineColor = line
            lineWidth?.let { shape.lineWidth = it }
        }

        fun ellipse(anchor: java.awt.Rectangle, fill: java.awt.Color?, line: java.awt.Color? = null, lineWidth: Double? = null) {
            val shape = slide.createAutoShape()
            shape.shapeType = org.apache.poi.sl.usermodel.ShapeType.ELLIPSE
            shape.anchor = anchor
            shape.fillColor = fill
            shape.lineColor = line
            lineWidth?.let { shape.lineWidth = it }
        }

        when (template.decorationStyle) {
            HtmlFirstDecorationStyle.NONE -> Unit
            HtmlFirstDecorationStyle.BLOCK -> rect(java.awt.Rectangle(pageWidth - 96, titleY + 2, 20, 20), palette.accentColor)
            HtmlFirstDecorationStyle.DOT -> ellipse(java.awt.Rectangle(pageWidth - 120, titleY + 6, 24, 24), palette.accentColor)
            HtmlFirstDecorationStyle.BUBBLES -> {
                ellipse(java.awt.Rectangle(pageWidth - 136, titleY + 4, 28, 28), palette.accentColor)
                ellipse(java.awt.Rectangle(pageWidth - 100, titleY + 18, 14, 14), mixColors(palette.accentColor, bgColor, 0.42))
            }

            HtmlFirstDecorationStyle.TABS -> {
                for (index in 0..2) {
                    rect(
                        java.awt.Rectangle(pageWidth - 136 + (index * 16), titleY + 8, 12, 12),
                        if (index == 0) palette.accentColor else mixColors(palette.accentColor, bgColor, 0.36 + index * 0.12)
                    )
                }
            }

            HtmlFirstDecorationStyle.BRACKET -> rect(
                anchor = java.awt.Rectangle(pageWidth - 136, titleY + 2, 34, 34),
                fill = null,
                line = palette.accentColor,
                lineWidth = 2.0
            )

            HtmlFirstDecorationStyle.STACK -> {
                rect(java.awt.Rectangle(pageWidth - 140, titleY + 8, 20, 20), mixColors(palette.accentColor, bgColor, 0.38))
                rect(
                    anchor = java.awt.Rectangle(pageWidth - 128, titleY + 2, 22, 22),
                    fill = null,
                    line = palette.accentColor,
                    lineWidth = 2.0
                )
            }

            HtmlFirstDecorationStyle.CONFETTI -> {
                ellipse(java.awt.Rectangle(pageWidth - 140, titleY + 6, 10, 10), palette.accentColor)
                rect(java.awt.Rectangle(pageWidth - 124, titleY + 18, 10, 10), mixColors(palette.accentColor, bgColor, 0.35))
                ellipse(java.awt.Rectangle(pageWidth - 110, titleY + 6, 16, 16), mixColors(palette.accentColor, bgColor, 0.18))
                rect(java.awt.Rectangle(pageWidth - 92, titleY + 22, 8, 8), palette.accentColor)
            }

            HtmlFirstDecorationStyle.SQUARE_TRIO -> {
                for (index in 0..2) {
                    rect(
                        anchor = java.awt.Rectangle(pageWidth - 142 + (index * 16), titleY + 8, 12, 12),
                        fill = if (index == 1) palette.accentColor else null,
                        line = palette.accentColor,
                        lineWidth = 1.8
                    )
                }
            }

            HtmlFirstDecorationStyle.RING -> ellipse(
                anchor = java.awt.Rectangle(pageWidth - 132, titleY + 4, 28, 28),
                fill = null,
                line = palette.accentColor,
                lineWidth = 2.2
            )
        }
    }

    private fun resolveHtmlFirstBodyAccent(
        template: HtmlFirstTemplateProfile,
        bodyPanelAnchor: java.awt.Rectangle,
    ): Pair<org.apache.poi.sl.usermodel.ShapeType, java.awt.Rectangle> {
        return when (template.accentStyle) {
            HtmlFirstAccentStyle.SIDE_BAR -> org.apache.poi.sl.usermodel.ShapeType.RECT to
                java.awt.Rectangle(bodyPanelAnchor.x, bodyPanelAnchor.y, 6, minOf(120, bodyPanelAnchor.height))

            HtmlFirstAccentStyle.TOP_BAR -> org.apache.poi.sl.usermodel.ShapeType.RECT to
                java.awt.Rectangle(bodyPanelAnchor.x, bodyPanelAnchor.y, bodyPanelAnchor.width, 6)

            HtmlFirstAccentStyle.FLOATING_TAB -> org.apache.poi.sl.usermodel.ShapeType.RECT to
                java.awt.Rectangle(bodyPanelAnchor.x + 20, bodyPanelAnchor.y - 6, 104, 10)

            HtmlFirstAccentStyle.RIGHT_BAR -> org.apache.poi.sl.usermodel.ShapeType.RECT to
                java.awt.Rectangle(bodyPanelAnchor.x + bodyPanelAnchor.width - 8, bodyPanelAnchor.y + 16, 8, minOf(96, bodyPanelAnchor.height - 24))

            HtmlFirstAccentStyle.INSET_PILL -> org.apache.poi.sl.usermodel.ShapeType.ROUND_RECT to
                java.awt.Rectangle(bodyPanelAnchor.x + 18, bodyPanelAnchor.y + 14, 86, 12)
        }
    }

    private fun buildHtmlFirstPalette(
        variant: HtmlFirstVariant,
        backgroundColor: java.awt.Color,
        titleColor: java.awt.Color,
        bodyColor: java.awt.Color,
        accentColor: java.awt.Color,
        templateId: HtmlFirstTemplateId,
    ): HtmlFirstPalette {
        val curatedSeed = resolveHtmlFirstPaletteSeed(variant, templateId, backgroundColor)
        val normalizedTitleInput = if (variant == HtmlFirstVariant.PLAYFUL) {
            mixColors(titleColor, curatedSeed.title, 0.76)
        } else {
            titleColor
        }
        val normalizedBodyInput = if (variant == HtmlFirstVariant.PLAYFUL) {
            mixColors(bodyColor, curatedSeed.body, 0.58)
        } else {
            bodyColor
        }
        val normalizedAccentInput = if (variant == HtmlFirstVariant.PLAYFUL) {
            mixColors(accentColor, curatedSeed.accent, 0.72)
        } else {
            accentColor
        }
        val panelFill = when (variant) {
            HtmlFirstVariant.CONSULTING -> if (isDarkColor(backgroundColor)) {
                mixColors(backgroundColor, java.awt.Color.WHITE, 0.10)
            } else {
                mixColors(backgroundColor, java.awt.Color(248, 250, 252), 0.92)
            }

            HtmlFirstVariant.DARK_TECH -> mixColors(backgroundColor, normalizedAccentInput, 0.10)
            HtmlFirstVariant.PLAYFUL -> if (isDarkColor(backgroundColor)) {
                mixColors(backgroundColor, curatedSeed.background, 0.22)
            } else {
                mixColors(backgroundColor, curatedSeed.background, 0.34)
            }
            HtmlFirstVariant.EDITORIAL -> if (isDarkColor(backgroundColor)) {
                mixColors(backgroundColor, java.awt.Color.WHITE, 0.12)
            } else {
                mixColors(backgroundColor, normalizedAccentInput, 0.08)
            }
        }

        val imagePanelFill = when (variant) {
            HtmlFirstVariant.CONSULTING -> if (isDarkColor(backgroundColor)) {
                mixColors(backgroundColor, java.awt.Color.WHITE, 0.08)
            } else {
                mixColors(backgroundColor, java.awt.Color.WHITE, 0.98)
            }

            HtmlFirstVariant.DARK_TECH -> mixColors(backgroundColor, java.awt.Color.WHITE, 0.04)
            HtmlFirstVariant.PLAYFUL -> mixColors(panelFill, java.awt.Color.WHITE, if (isDarkColor(panelFill)) 0.10 else 0.42)
            HtmlFirstVariant.EDITORIAL -> mixColors(panelFill, java.awt.Color.WHITE, if (isDarkColor(panelFill)) 0.12 else 0.55)
        }

        val panelBorder = when (variant) {
            HtmlFirstVariant.CONSULTING -> mixColors(panelFill, normalizedAccentInput, 0.18)
            HtmlFirstVariant.DARK_TECH -> mixColors(panelFill, normalizedAccentInput, 0.55)
            HtmlFirstVariant.PLAYFUL -> mixColors(panelFill, normalizedAccentInput, 0.26)
            HtmlFirstVariant.EDITORIAL -> mixColors(panelFill, normalizedAccentInput, 0.32)
        }

        val safeTitleColor = stabilizeThemeColor(normalizedTitleInput, backgroundColor, variant, forText = true)
        val safeAccentColor = stabilizeThemeColor(normalizedAccentInput, backgroundColor, variant, forText = false)
        val safeBodyOnPanel = ensureReadableColor(normalizedBodyInput, panelFill)
        val subtitleBase = mixColors(normalizedBodyInput, safeTitleColor, 0.18)
        val chromeBase = mixColors(safeTitleColor, backgroundColor, if (isDarkColor(backgroundColor)) 0.40 else 0.55)

        return HtmlFirstPalette(
            backgroundColor = backgroundColor,
            titleColor = safeTitleColor,
            eyebrowColor = safeAccentColor,
            subtitleColor = ensureReadableColor(subtitleBase, backgroundColor),
            counterColor = ensureReadableColor(chromeBase, backgroundColor, minimumContrast = 2.6),
            footerColor = ensureReadableColor(chromeBase, backgroundColor, minimumContrast = 2.2),
            accentColor = safeAccentColor,
            panelFill = panelFill,
            panelBorder = panelBorder,
            imagePanelFill = imagePanelFill,
            bodyColorOnPanel = safeBodyOnPanel,
        )
    }

    private fun resolveHtmlFirstPaletteSeed(
        variant: HtmlFirstVariant,
        templateId: HtmlFirstTemplateId,
        backgroundColor: java.awt.Color,
    ): HtmlFirstPaletteSeed {
        if (variant != HtmlFirstVariant.PLAYFUL) {
            return HtmlFirstPaletteSeed(
                background = backgroundColor,
                title = if (isDarkColor(backgroundColor)) java.awt.Color(245, 247, 250) else java.awt.Color(28, 34, 48),
                body = if (isDarkColor(backgroundColor)) java.awt.Color(229, 233, 240) else java.awt.Color(72, 78, 92),
                accent = if (isDarkColor(backgroundColor)) java.awt.Color(121, 201, 255) else java.awt.Color(13, 92, 155),
            )
        }
        val seeds = listOf(
            HtmlFirstPaletteSeed(
                background = java.awt.Color(255, 247, 235),
                title = java.awt.Color(74, 53, 35),
                body = java.awt.Color(94, 74, 56),
                accent = java.awt.Color(212, 109, 81),
            ),
            HtmlFirstPaletteSeed(
                background = java.awt.Color(241, 247, 255),
                title = java.awt.Color(38, 57, 96),
                body = java.awt.Color(76, 92, 126),
                accent = java.awt.Color(70, 145, 197),
            ),
            HtmlFirstPaletteSeed(
                background = java.awt.Color(253, 243, 246),
                title = java.awt.Color(83, 46, 77),
                body = java.awt.Color(112, 78, 104),
                accent = java.awt.Color(215, 103, 146),
            ),
            HtmlFirstPaletteSeed(
                background = java.awt.Color(245, 250, 236),
                title = java.awt.Color(58, 74, 44),
                body = java.awt.Color(86, 100, 70),
                accent = java.awt.Color(120, 170, 88),
            ),
        )
        return seeds[Math.floorMod(templateId.ordinal, seeds.size)]
    }

    private fun mixColors(
        from: java.awt.Color,
        to: java.awt.Color,
        toRatio: Double,
    ): java.awt.Color {
        val ratio = toRatio.coerceIn(0.0, 1.0)
        val fromRatio = 1.0 - ratio
        return java.awt.Color(
            (from.red * fromRatio + to.red * ratio).roundToInt().coerceIn(0, 255),
            (from.green * fromRatio + to.green * ratio).roundToInt().coerceIn(0, 255),
            (from.blue * fromRatio + to.blue * ratio).roundToInt().coerceIn(0, 255),
        )
    }

    private fun withAlpha(color: java.awt.Color, alpha: Int): java.awt.Color {
        return java.awt.Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))
    }

    private fun stabilizeThemeColor(
        preferred: java.awt.Color,
        background: java.awt.Color,
        variant: HtmlFirstVariant,
        forText: Boolean,
    ): java.awt.Color {
        val adjusted = when {
            variant == HtmlFirstVariant.PLAYFUL -> softenExpressiveColor(preferred, background, forText)
            variant == HtmlFirstVariant.EDITORIAL && !isDarkColor(background) && isHighlySaturated(preferred) ->
                mixColors(preferred, java.awt.Color(52, 58, 78), if (forText) 0.56 else 0.36)
            else -> preferred
        }
        return ensureReadableColor(adjusted, background, if (forText) 3.8 else 2.3)
    }

    private fun softenExpressiveColor(
        preferred: java.awt.Color,
        background: java.awt.Color,
        forText: Boolean,
    ): java.awt.Color {
        val anchor = if (isDarkColor(background)) {
            java.awt.Color(244, 241, 248)
        } else {
            java.awt.Color(69, 56, 89)
        }
        val ratio = when {
            forText && isHighlySaturated(preferred) -> 0.64
            forText -> 0.48
            isHighlySaturated(preferred) -> 0.38
            else -> 0.24
        }
        return mixColors(preferred, anchor, ratio)
    }

    private fun isHighlySaturated(color: java.awt.Color): Boolean {
        val hsb = java.awt.Color.RGBtoHSB(color.red, color.green, color.blue, null)
        return hsb[1] >= 0.58f && hsb[2] >= 0.60f
    }

    private fun containsCyrillic(text: String): Boolean = text.any { it in '\u0400'..'\u04FF' }

    private fun isSpeakerNotesPptxEnabled(): Boolean =
        System.getProperty(SPEAKER_NOTES_PPTX_PROPERTY)?.equals("true", ignoreCase = true) == true

    private fun isDarkColor(color: java.awt.Color): Boolean = relativeLuminance(color) < 0.42

    private fun ensureReadableColor(
        preferred: java.awt.Color,
        background: java.awt.Color,
        minimumContrast: Double = 3.4,
    ): java.awt.Color {
        if (contrastRatio(preferred, background) >= minimumContrast) return preferred
        return if (isDarkColor(background)) java.awt.Color(245, 247, 250) else java.awt.Color(28, 34, 48)
    }

    private fun contrastRatio(first: java.awt.Color, second: java.awt.Color): Double {
        val lighter = maxOf(relativeLuminance(first), relativeLuminance(second))
        val darker = minOf(relativeLuminance(first), relativeLuminance(second))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: java.awt.Color): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.03928) normalized / 12.92 else Math.pow((normalized + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun addSpeakerNotes(
        ppt: XMLSlideShow,
        slide: org.apache.poi.xslf.usermodel.XSLFSlide,
        notes: String?,
    ) {
        if (notes.isNullOrBlank()) return
        val notesSlide = getOrCreateNotesSlide(ppt, slide) ?: return
        sanitizeNotesSystemPlaceholders(notesSlide)
        val notesPlaceholder = resolveNotesTextPlaceholder(notesSlide) ?: return
        notesPlaceholder.clearText()
        val paragraph = notesPlaceholder.addNewTextParagraph()
        paragraph.addNewTextRun().setText(notes.trim())
    }

    private fun getOrCreateNotesSlide(
        ppt: XMLSlideShow,
        slide: org.apache.poi.xslf.usermodel.XSLFSlide,
    ): org.apache.poi.xslf.usermodel.XSLFNotes? {
        return ppt.getNotesSlide(slide) ?: try {
            val method = XMLSlideShow::class.java.getDeclaredMethod(
                "createNotesSlide",
                org.apache.poi.xslf.usermodel.XSLFSlide::class.java
            )
            method.isAccessible = true
            method.invoke(ppt, slide) as org.apache.poi.xslf.usermodel.XSLFNotes
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveNotesTextPlaceholder(
        notesSlide: org.apache.poi.xslf.usermodel.XSLFNotes,
    ): org.apache.poi.xslf.usermodel.XSLFTextShape? {
        val bodyPlaceholder = notesSlide.shapes
            .filterIsInstance<org.apache.poi.xslf.usermodel.XSLFTextShape>()
            .firstOrNull { shape ->
                val placeholder = runCatching { shape.placeholderDetails.placeholder }.getOrNull()
                placeholder == org.apache.poi.sl.usermodel.Placeholder.BODY
            }
        if (bodyPlaceholder != null) return bodyPlaceholder
        return (notesSlide.getPlaceholder(1) as? org.apache.poi.xslf.usermodel.XSLFTextShape)
            ?: (notesSlide.getPlaceholder(0) as? org.apache.poi.xslf.usermodel.XSLFTextShape)
    }

    private fun sanitizeNotesSystemPlaceholders(
        notesSlide: org.apache.poi.xslf.usermodel.XSLFNotes,
    ) {
        notesSlide.shapes
            .filterIsInstance<org.apache.poi.xslf.usermodel.XSLFTextShape>()
            .forEach { shape ->
                val placeholder = runCatching { shape.placeholderDetails.placeholder }.getOrNull()
                if (placeholder == org.apache.poi.sl.usermodel.Placeholder.HEADER ||
                    placeholder == org.apache.poi.sl.usermodel.Placeholder.DATETIME ||
                    placeholder == org.apache.poi.sl.usermodel.Placeholder.FOOTER ||
                    placeholder == org.apache.poi.sl.usermodel.Placeholder.SLIDE_NUMBER
                ) {
                    shape.clearText()
                    shape.addNewTextParagraph()
                }
            }
    }

    private fun addImageToSlide(
        ppt: XMLSlideShow,
        slide: org.apache.poi.xslf.usermodel.XSLFSlide,
        preparedImage: PreparedImage,
        fallbackAnchor: java.awt.Rectangle,
        customAnchor: java.awt.Rectangle?,
    ) {
        val pictureIdx = ppt.addPicture(preparedImage.data, preparedImage.format)
        val picture = slide.createPicture(pictureIdx)
        picture.anchor = customAnchor ?: fitImageIntoBounds(
            container = fallbackAnchor,
            imageWidth = preparedImage.width,
            imageHeight = preparedImage.height,
        )
    }

    private data class PreparedImage(
        val data: ByteArray,
        val format: org.apache.poi.sl.usermodel.PictureData.PictureType,
        val width: Int,
        val height: Int,
    )

    private data class ResolvedImageAsset(
        val preparedImage: PreparedImage,
    )

    private enum class NormalizedImageKind {
        PNG,
        JPEG,
        BMP,
        GIF,
        WEBP,
        AVIF,
        SVG,
        UNKNOWN,
    }

    private fun resolvePreparedImage(raw: String?): ResolvedImageAsset? {
        val path = resolveImagePath(raw) ?: return null
        val prepared = prepareImageData(path) ?: return null
        return ResolvedImageAsset(prepared)
    }

    private fun prepareImageData(imagePath: String): PreparedImage? {
        val imageFile = File(imagePath)
        if (!imageFile.exists() || !imageFile.isFile || imageFile.length() == 0L) return null

        var pictureData = imageFile.readBytes()
        val sniffedKind = sniffImageKind(pictureData, imageFile.extension)
        val decodedImage = runCatching { Image.makeFromEncoded(pictureData) }.getOrNull()

        if (sniffedKind == NormalizedImageKind.SVG && decodedImage == null) {
            return null
        }

        val format = when {
            sniffedKind == NormalizedImageKind.JPEG -> org.apache.poi.sl.usermodel.PictureData.PictureType.JPEG
            sniffedKind == NormalizedImageKind.PNG -> org.apache.poi.sl.usermodel.PictureData.PictureType.PNG
            decodedImage != null -> {
                val pngData = runCatching { decodedImage.encodeToData(EncodedImageFormat.PNG) }.getOrNull() ?: return null
                pictureData = pngData.bytes
                org.apache.poi.sl.usermodel.PictureData.PictureType.PNG
            }
            else -> return null
        }

        return PreparedImage(
            data = pictureData,
            format = format,
            width = decodedImage?.width?.takeIf { it > 0 } ?: 1,
            height = decodedImage?.height?.takeIf { it > 0 } ?: 1,
        )
    }

    private fun sniffImageKind(data: ByteArray, extensionHint: String): NormalizedImageKind {
        if (data.size >= 8 &&
            data[0] == 0x89.toByte() &&
            data[1] == 0x50.toByte() &&
            data[2] == 0x4E.toByte() &&
            data[3] == 0x47.toByte()
        ) {
            return NormalizedImageKind.PNG
        }
        if (data.size >= 3 &&
            data[0] == 0xFF.toByte() &&
            data[1] == 0xD8.toByte() &&
            data[2] == 0xFF.toByte()
        ) {
            return NormalizedImageKind.JPEG
        }
        if (data.size >= 2 &&
            data[0] == 0x42.toByte() &&
            data[1] == 0x4D.toByte()
        ) {
            return NormalizedImageKind.BMP
        }
        if (data.size >= 6) {
            val signature = String(data.copyOfRange(0, 6), StandardCharsets.US_ASCII)
            if (signature == "GIF87a" || signature == "GIF89a") return NormalizedImageKind.GIF
        }
        if (data.size >= 12) {
            val riff = String(data.copyOfRange(0, 4), StandardCharsets.US_ASCII)
            val webp = String(data.copyOfRange(8, 12), StandardCharsets.US_ASCII)
            if (riff == "RIFF" && webp == "WEBP") return NormalizedImageKind.WEBP
            val ftyp = String(data.copyOfRange(4, 12), StandardCharsets.US_ASCII)
            if (ftyp.contains("avif")) return NormalizedImageKind.AVIF
        }

        val headerText = data.copyOfRange(0, minOf(data.size, 256)).toString(StandardCharsets.UTF_8).trimStart()
        if (headerText.startsWith("<svg", ignoreCase = true) || headerText.contains("<svg", ignoreCase = true)) {
            return NormalizedImageKind.SVG
        }

        return when (extensionHint.lowercase()) {
            "png" -> NormalizedImageKind.PNG
            "jpeg", "jpg" -> NormalizedImageKind.JPEG
            "bmp" -> NormalizedImageKind.BMP
            "gif" -> NormalizedImageKind.GIF
            "webp" -> NormalizedImageKind.WEBP
            "avif" -> NormalizedImageKind.AVIF
            "svg" -> NormalizedImageKind.SVG
            else -> NormalizedImageKind.UNKNOWN
        }
    }

    private fun selectTitleFontSize(title: String): Double {
        val length = title.trim().length
        return when {
            length <= 24 -> 34.0
            length <= 40 -> 30.0
            length <= 64 -> 26.0
            else -> 22.0
        }
    }

    private fun selectBodyFontSize(points: List<String>, width: Int): Double {
        val longestPoint = points.maxOfOrNull { it.length } ?: 0
        return when {
            points.size <= 3 && longestPoint <= 70 && width >= 520 -> 24.0
            points.size <= 5 && longestPoint <= 100 -> 21.0
            else -> 18.0
        }
    }

    private fun estimateWrappedLineCount(
        text: String,
        fontSize: Double,
        width: Int,
    ): Int {
        if (text.isBlank() || width <= 0) return 1
        val charsPerLine = (width / (fontSize * 0.56)).toInt().coerceAtLeast(8)
        return text.lineSequence().sumOf { line ->
            ceil(line.trim().length.coerceAtLeast(1) / charsPerLine.toDouble()).toInt().coerceAtLeast(1)
        }.coerceAtLeast(1)
    }

    private fun estimateBodyPanelHeight(
        points: List<String>,
        fontSize: Double,
        width: Int,
    ): Int {
        if (points.isEmpty()) return 0
        val lineCount = points.sumOf { point ->
            estimateWrappedLineCount(point, fontSize, width - 18)
        }
        val lineHeight = fontSize * 1.28
        val estimatedTextHeight = (lineCount * lineHeight).roundToInt()
        val bulletSpacing = ((points.size - 1).coerceAtLeast(0) * (fontSize * 0.36)).roundToInt()
        return (estimatedTextHeight + bulletSpacing + 42).coerceAtLeast(160)
    }

    private fun fitImageIntoBounds(
        container: java.awt.Rectangle,
        imageWidth: Int,
        imageHeight: Int,
    ): java.awt.Rectangle {
        if (imageWidth <= 0 || imageHeight <= 0 || container.width <= 0 || container.height <= 0) {
            return container
        }

        val scale = minOf(
            container.width.toDouble() / imageWidth.toDouble(),
            container.height.toDouble() / imageHeight.toDouble(),
        )
        val fittedWidth = (imageWidth * scale).toInt().coerceAtLeast(1)
        val fittedHeight = (imageHeight * scale).toInt().coerceAtLeast(1)
        val x = container.x + (container.width - fittedWidth) / 2
        val y = container.y + (container.height - fittedHeight) / 2
        return java.awt.Rectangle(x, y, fittedWidth, fittedHeight)
    }

    private fun buildHtmlStoryboard(
        title: String,
        slides: List<SlideContent>,
        theme: PresentationTheme?,
        customTheme: CustomThemeParam?,
    ): String {
        val bgColor = customTheme?.backgroundColor ?: theme?.backgroundColor?.toHexColor() ?: "#FFFFFF"
        val titleColor = customTheme?.titleColor ?: theme?.titleColor?.toHexColor() ?: "#111111"
        val bodyColor = customTheme?.contentColor ?: theme?.contentColor?.toHexColor() ?: "#222222"
        val accentColor = customTheme?.accentColor ?: theme?.accentColor?.toHexColor() ?: "#0057FF"
        val titleFont = customTheme?.titleFont ?: theme?.titleFont ?: "Arial"
        val bodyFont = customTheme?.bodyFont ?: theme?.bodyFont ?: "Arial"

        val sections = slides.mapIndexed { index, slide ->
            val pointsHtml = if (slide.points.isNotEmpty()) {
                "<div class=\"body-card\"><ul>${slide.points.joinToString("") { "<li>${escapeHtml(it)}</li>" }}</ul></div>"
            } else ""
            val imageHtml = slide.imagePath?.takeIf { it.isNotBlank() }?.let { source ->
                "<div class=\"image-card\"><img src=\"${escapeHtml(source)}\" alt=\"slide-image\"/></div>"
            }.orEmpty()
            val notesHtml = slide.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                "<div class=\"speaker-notes\"><strong>Speaker notes:</strong> ${escapeHtml(notes)}</div>"
            }.orEmpty()
            val storyboardVariant = resolveHtmlFirstVariant(
                theme = theme,
                designId = slide.designId,
                backgroundColor = runCatching { java.awt.Color.decode(bgColor) }.getOrNull() ?: java.awt.Color.WHITE,
                playfulTone = isPlayfulSlide(slide),
            )
            val eyebrow = buildSlideEyebrow(slide, index + 1, storyboardVariant)
            """
            <section class="slide" data-layout="${escapeHtml(slide.layout ?: "TITLE_AND_CONTENT")}">
              <div class="eyebrow">${escapeHtml(eyebrow)}</div>
              <div class="counter">${(index + 1).toString().padStart(2, '0')} / ${slides.size.toString().padStart(2, '0')}</div>
              <h1>${escapeHtml(slide.title)}</h1>
              ${slide.subtitle?.takeIf { it.isNotBlank() }?.let { "<h2>${escapeHtml(it)}</h2>" } ?: ""}
              $pointsHtml
              $imageHtml
              $notesHtml
            </section>
            """.trimIndent()
        }.joinToString("\n")

        return """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8"/>
          <meta name="viewport" content="width=device-width, initial-scale=1"/>
          <title>${escapeHtml(title)} - Storyboard</title>
          <style>
            :root { --bg: $bgColor; --title: $titleColor; --body: $bodyColor; --accent: $accentColor; }
            * { box-sizing: border-box; }
            body { margin: 0; background: #e9edf2; color: var(--body); font-family: '$bodyFont', sans-serif; padding: 32px; }
            .slide {
              width: min(1280px, 100%);
              aspect-ratio: 16 / 9;
              margin: 0 auto 24px auto;
              background: var(--bg);
              border: 2px solid color-mix(in srgb, var(--accent), #ffffff 70%);
              border-radius: 18px;
              padding: 42px 54px;
              overflow: hidden;
              position: relative;
              box-shadow: 0 18px 40px rgba(15, 23, 42, 0.12);
            }
            .eyebrow {
              color: var(--accent);
              font-size: 12px;
              font-weight: 700;
              letter-spacing: .14em;
              text-transform: uppercase;
              margin-bottom: 14px;
            }
            .counter {
              position: absolute;
              top: 42px;
              right: 54px;
              color: #7a8797;
              font-size: 12px;
            }
            .slide::after {
              content: '';
              position: absolute;
              right: 0;
              bottom: 0;
              width: 180px;
              height: 8px;
              background: var(--accent);
            }
            h1 { margin: 0 0 12px 0; color: var(--title); font-family: '$titleFont', sans-serif; font-size: 52px; line-height: 1.05; max-width: 72%; }
            h2 { margin: 0 0 16px 0; color: var(--body); opacity: 0.85; font-size: 26px; font-weight: 500; }
            .body-card {
              max-width: 58%;
              margin-top: 24px;
              background: #f9fafc;
              border: 1px solid rgba(15, 23, 42, 0.10);
              border-radius: 18px;
              padding: 24px 28px;
            }
            ul { margin: 0; padding-left: 26px; font-size: 30px; line-height: 1.28; }
            li { margin-bottom: 10px; }
            .image-card {
              position: absolute;
              right: 54px;
              top: 168px;
              width: 36%;
              height: 62%;
              background: #fff;
              border: 1px solid rgba(15, 23, 42, 0.10);
              border-radius: 18px;
              padding: 18px;
            }
            .speaker-notes {
              margin-top: 14px;
              max-width: 72%;
              padding: 12px 14px;
              border-radius: 10px;
              border: 1px dashed color-mix(in srgb, var(--accent), #ffffff 58%);
              background: color-mix(in srgb, var(--accent), #ffffff 90%);
              font-size: 14px;
              line-height: 1.4;
            }
            img { width: 100%; height: 100%; object-fit: contain; border-radius: 10px; }
          </style>
        </head>
        <body>
          $sections
        </body>
        </html>
        """.trimIndent()
    }

    private fun java.awt.Color.toHexColor(): String {
        return "#%02X%02X%02X".format(this.red, this.green, this.blue)
    }

    private fun escapeHtml(raw: String): String {
        return raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun buildCustomTheme(input: PresentationCreateInput): CustomThemeParam? {
        val hasCustomThemeInput = input.themeBackgroundColor != null ||
            input.themeTitleColor != null ||
            input.themeContentColor != null ||
            input.themeAccentColor != null

        if (!hasCustomThemeInput) return null

        return CustomThemeParam(
            backgroundColor = input.themeBackgroundColor,
            titleColor = input.themeTitleColor,
            contentColor = input.themeContentColor,
            accentColor = input.themeAccentColor,
            titleFont = input.themeTitleFont,
            bodyFont = input.themeBodyFont
        )
    }

    private fun resolveSlides(input: PresentationCreateInput): List<SlideContent> {
        if (!input.slides.isNullOrEmpty()) return input.slides
        if (input.slidesData.isNullOrBlank()) {
            throw BadInputException("No slides provided. Pass `slides` (preferred) or legacy `slidesData`.")
        }

        return try {
            mapper.readValue(input.slidesData)
        } catch (e: Exception) {
            throw BadInputException("Failed to parse `slidesData` JSON: ${e.message}")
        }
    }

    private fun resolveTheme(themeName: String?): PresentationTheme? {
        if (themeName.isNullOrBlank()) return null
        return try {
            PresentationTheme.valueOf(themeName.uppercase())
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveOutputFile(input: PresentationCreateInput, defaultFileName: String): File {
        val rawOutputPath = input.outputPath?.takeIf { it.isNotBlank() } ?: "~/souz/Documents/$defaultFileName"
        val expandedPath = filesToolUtil.applyDefaultEnvs(rawOutputPath)
        val outputTarget = File(expandedPath)

        val looksLikeDirectory = rawOutputPath.endsWith("/") ||
            rawOutputPath.endsWith("\\") ||
            outputTarget.isDirectory

        if (looksLikeDirectory) {
            return File(outputTarget, defaultFileName)
        }

        val hasExtension = outputTarget.name.contains(".")
        return when {
            outputTarget.extension.equals("pptx", ignoreCase = true) -> outputTarget
            hasExtension -> File(outputTarget.parentFile ?: File("."), "${outputTarget.nameWithoutExtension}.pptx")
            else -> File(outputTarget.parentFile ?: File("."), "${outputTarget.name}.pptx")
        }
    }

    private fun createChartPlaceholder(
        slide: org.apache.poi.xslf.usermodel.XSLFSlide,
        chartData: PresentationChart,
        theme: PresentationTheme?,
        customTheme: CustomThemeParam?
    ) {
        val title = chartData.title

        val categories = chartData.categories ?: emptyList()
        val series = chartData.series ?: emptyList()

        val textBox = slide.createTextBox()
        textBox.anchor = java.awt.Rectangle(100, 100, 500, 50)
        textBox.text = "Chart placeholder: $title"
        textBox.textParagraphs.first().textRuns.first().apply {
            fontSize = 18.0
            isBold = true
            fontFamily = theme?.titleFont ?: customTheme?.titleFont ?: "Arial"
            fontColor = org.apache.poi.sl.draw.DrawPaint.createSolidPaint(
                if (customTheme?.titleColor != null) java.awt.Color.decode(customTheme.titleColor)
                else theme?.titleColor ?: java.awt.Color.BLACK
            )
        }

        val bg = slide.createAutoShape()
        bg.shapeType = org.apache.poi.sl.usermodel.ShapeType.RECT
        bg.anchor = java.awt.Rectangle(100, 150, 500, 300)
        bg.fillColor = java.awt.Color.WHITE
        bg.setLineColor(java.awt.Color.LIGHT_GRAY)

        val infoText = slide.createTextBox()
        infoText.anchor = java.awt.Rectangle(120, 170, 460, 260)
        infoText.text = buildString {
            append("Native chart rendering is not implemented in PresentationCreate.\n")
            append("Use CreatePlot + imagePath for a real chart image.\n\n")
            append("Requested type: ${chartData.type}\n")
            append("Categories: $categories\n")
            append("Series: ${series.map { "${it.name}: ${it.values}" }}")
        }
    }

    private fun createTable(
        slide: org.apache.poi.xslf.usermodel.XSLFSlide,
        tableData: PresentationTable,
        theme: PresentationTheme?,
        anchor: java.awt.geom.Rectangle2D
    ) {
        val table = slide.createTable()
        table.anchor = anchor

        val headerColor = theme?.accentColor ?: java.awt.Color.LIGHT_GRAY
        val textColor = theme?.contentColor ?: java.awt.Color.BLACK
        val fontFamily = theme?.bodyFont ?: "Arial"

        val format = CSVFormat.DEFAULT.builder()
            .setTrim(true)
            .setIgnoreEmptyLines(true)
            .build()

        val rows = CSVParser(StringReader(tableData.csvData), format).use { parser ->
            parser.records.map { record -> record.map { it } }
        }

        rows.forEachIndexed { rowIndex, cells ->
            val row = table.addRow()
            val isHeader = tableData.hasHeader && rowIndex == 0

            // Set row height (default)
            row.height = 30.0

            cells.forEach { cellText ->
                val cell = row.addCell()
                cell.setText(cellText)

                // Styling
                cell.verticalAlignment = org.apache.poi.sl.usermodel.VerticalAlignment.MIDDLE

                // Borders
                cell.setBorderColor(org.apache.poi.sl.usermodel.TableCell.BorderEdge.bottom, java.awt.Color.GRAY)
                cell.setBorderColor(org.apache.poi.sl.usermodel.TableCell.BorderEdge.top, java.awt.Color.GRAY)
                cell.setBorderColor(org.apache.poi.sl.usermodel.TableCell.BorderEdge.left, java.awt.Color.GRAY)
                cell.setBorderColor(org.apache.poi.sl.usermodel.TableCell.BorderEdge.right, java.awt.Color.GRAY)

                val p = cell.textParagraphs.firstOrNull()
                if (p != null) {
                    p.textAlign = org.apache.poi.sl.usermodel.TextParagraph.TextAlign.CENTER
                    val run = p.textRuns.firstOrNull() ?: p.addNewTextRun()

                    if (isHeader) {
                        cell.fillColor = headerColor
                        run.isBold = true
                        run.fontColor = org.apache.poi.sl.draw.DrawPaint.createSolidPaint(java.awt.Color.WHITE)
                        run.fontFamily = fontFamily
                        run.fontSize = 14.0
                    } else {
                        run.fontColor = org.apache.poi.sl.draw.DrawPaint.createSolidPaint(textColor)
                        run.fontFamily = fontFamily
                        run.fontSize = 12.0
                    }
                }
            }
        }
    }



    private fun createShape(
        slide: org.apache.poi.xslf.usermodel.XSLFSlide,
        shapeData: PresentationShape,
        theme: PresentationTheme?,
        customTheme: CustomThemeParam?
    ) {
        val shapeType = when (shapeData.type.uppercase()) {
            "RECT", "RECTANGLE" -> org.apache.poi.sl.usermodel.ShapeType.RECT
            "OVAL", "ELLIPSE", "CIRCLE" -> org.apache.poi.sl.usermodel.ShapeType.ELLIPSE
            "TRIANGLE" -> org.apache.poi.sl.usermodel.ShapeType.TRIANGLE
            "ARROW_RIGHT", "ARROW" -> org.apache.poi.sl.usermodel.ShapeType.RIGHT_ARROW
            "STAR_5", "STAR" -> org.apache.poi.sl.usermodel.ShapeType.STAR_5
            "LINE" -> org.apache.poi.sl.usermodel.ShapeType.LINE
            else -> org.apache.poi.sl.usermodel.ShapeType.RECT
        }

        val shape = slide.createAutoShape()
        shape.shapeType = shapeType
        shape.anchor = java.awt.Rectangle(shapeData.x, shapeData.y, shapeData.width, shapeData.height)

        val fillColor = if (shapeData.color != null) {
             try {
                if (shapeData.color.startsWith("#")) java.awt.Color.decode(shapeData.color)
                else {
                    val field = java.awt.Color::class.java.getField(shapeData.color.uppercase())
                    field.get(null) as java.awt.Color
                }
             } catch (e: Exception) {
                 if (customTheme?.accentColor != null) java.awt.Color.decode(customTheme.accentColor)
                 else theme?.accentColor ?: java.awt.Color.BLUE
             }
        } else {
             if (customTheme?.accentColor != null) java.awt.Color.decode(customTheme.accentColor)
             else theme?.accentColor ?: java.awt.Color.BLUE
        }

        val fillColorWithOpacity = shapeData.opacity?.let { opacity ->
            val alpha = (opacity.coerceIn(0.0, 1.0) * 255).roundToInt()
            java.awt.Color(fillColor.red, fillColor.green, fillColor.blue, alpha)
        } ?: fillColor

        shape.fillColor = fillColorWithOpacity
        shape.setLineColor(java.awt.Color.DARK_GRAY)

        if (shapeData.text != null) {
            shape.setText(shapeData.text)
            shape.verticalAlignment = org.apache.poi.sl.usermodel.VerticalAlignment.MIDDLE
            shape.textParagraphs.forEach { p ->
                p.textAlign = org.apache.poi.sl.usermodel.TextParagraph.TextAlign.CENTER
                p.textRuns.forEach { r ->
                    r.fontColor = org.apache.poi.sl.draw.DrawPaint.createSolidPaint(java.awt.Color.WHITE)
                    r.isBold = true
                    r.fontFamily = theme?.bodyFont ?: "Arial"
                }
            }
        }
    }

    private fun applyTheme(master: org.apache.poi.xslf.usermodel.XSLFSlideMaster, theme: PresentationTheme) {
        // Set Background Color
        val background = master.background
        background.fillColor = theme.backgroundColor

        // Naive approach: Iterate over all shapes in master and set text color if it's a text shape
        // Better: Set color on specific placeholders (Title, Body) in the master layout

        // Note: Changing master fonts/colors deeply in POI is complex.
        // We will do a best-effort application:
        // 1. Set background
        // 2. We already set content color in the iterate loop for runs.
        // 3. Let's try to set title color on layouts.

        master.slideLayouts.forEach { layout ->
             layout.shapes.forEach { shape ->
                 if (shape is org.apache.poi.xslf.usermodel.XSLFTextShape) {
                      val phDetails = shape.placeholderDetails
                      if (phDetails.placeholder != null) {
                          when(phDetails.placeholder) {
                              org.apache.poi.sl.usermodel.Placeholder.TITLE, org.apache.poi.sl.usermodel.Placeholder.CENTERED_TITLE -> {
                                  shape.textParagraphs.forEach { p ->
                                      p.textRuns.forEach { r ->
                                          r.fontColor = org.apache.poi.sl.draw.DrawPaint.createSolidPaint(theme.titleColor)
                                          r.fontFamily = theme.titleFont
                                          // Optional: bold title
                                          r.isBold = true
                                      }
                                  }
                              }
                              org.apache.poi.sl.usermodel.Placeholder.BODY, org.apache.poi.sl.usermodel.Placeholder.CONTENT -> {
                                  shape.textParagraphs.forEach { p ->
                                      // Set bullet color to accent color if possible (POI complex for this, skipping for now)
                                      p.textRuns.forEach { r ->
                                          r.fontColor = org.apache.poi.sl.draw.DrawPaint.createSolidPaint(theme.contentColor)
                                          r.fontFamily = theme.bodyFont
                                      }
                                  }
                              }
                              else -> {
                                  // Apply accent color to other text? Or keep content color.
                                  shape.textParagraphs.forEach { p ->
                                      p.textRuns.forEach { r -> r.fontFamily = theme.bodyFont }
                                  }
                              }
                          }
                      }


                 }
                 // Try to colorize shapes/lines with accent color?
                 // If there were shapes... (templates usually handle this, but for blank slides we don't have many shapes)
             }
        }
    }

    private fun applyCustomTheme(master: org.apache.poi.xslf.usermodel.XSLFSlideMaster, theme: CustomThemeParam) {
        // Set Background Color
        if (theme.backgroundColor != null) {
            try {
                val color = java.awt.Color.decode(theme.backgroundColor)
                master.background.fillColor = color
            } catch (e: Exception) {}
        }

        // Apply fonts/colors to placeholders
         master.slideLayouts.forEach { layout ->
             layout.shapes.forEach { shape ->
                 if (shape is org.apache.poi.xslf.usermodel.XSLFTextShape) {
                      val phDetails = shape.placeholderDetails
                      if (phDetails.placeholder != null) {
                          when(phDetails.placeholder) {
                              org.apache.poi.sl.usermodel.Placeholder.TITLE, org.apache.poi.sl.usermodel.Placeholder.CENTERED_TITLE -> {
                                  shape.textParagraphs.forEach { p ->
                                      p.textRuns.forEach { r ->
                                          if (theme.titleColor != null) try { r.fontColor = org.apache.poi.sl.draw.DrawPaint.createSolidPaint(java.awt.Color.decode(theme.titleColor)) } catch(e: Exception){}
                                          if (theme.titleFont != null) r.fontFamily = theme.titleFont
                                          r.isBold = true
                                      }
                                  }
                              }
                              org.apache.poi.sl.usermodel.Placeholder.BODY, org.apache.poi.sl.usermodel.Placeholder.CONTENT -> {
                                  shape.textParagraphs.forEach { p ->
                                      p.textRuns.forEach { r ->
                                          if (theme.contentColor != null) try { r.fontColor = org.apache.poi.sl.draw.DrawPaint.createSolidPaint(java.awt.Color.decode(theme.contentColor)) } catch(e: Exception){}
                                          if (theme.bodyFont != null) r.fontFamily = theme.bodyFont
                                      }
                                  }
                              }
                              else -> {
                                  shape.textParagraphs.forEach { p ->
                                      p.textRuns.forEach { r -> if (theme.bodyFont != null) r.fontFamily = theme.bodyFont }
                                  }
                              }
                          }
                      }
                 }
             }
        }
        }


    private fun addThemeDecoration(slide: org.apache.poi.xslf.usermodel.XSLFSlide, theme: CustomThemeParam) {
        // Add a subtle bottom strip with accent color
        val footerStrip = slide.createAutoShape()
        footerStrip.shapeType = org.apache.poi.sl.usermodel.ShapeType.RECT
        // Slide size is typically 720x540 or 960x540. Let's assume standard width.
        // Better to get actual dimensions, but for now fixed bottom bar.
        val pageSize = slide.slideShow.pageSize
        val width = pageSize.width
        val height = pageSize.height

        footerStrip.anchor = java.awt.Rectangle(0, height - 8, width, 8)
        val accentColor = if (theme.accentColor != null) {
            try { java.awt.Color.decode(theme.accentColor) } catch(e: Exception) { java.awt.Color.BLUE }
        } else { java.awt.Color.BLUE }

        footerStrip.fillColor = accentColor
        footerStrip.setLineColor(null) // No border

        // Add a small top-right corner accent
        val cornerAccent = slide.createAutoShape()
        cornerAccent.shapeType = org.apache.poi.sl.usermodel.ShapeType.RT_TRIANGLE
        cornerAccent.anchor = java.awt.Rectangle(width - 50, 0, 50, 50)
        cornerAccent.fillColor = accentColor
        cornerAccent.setLineColor(null)
    }
}
