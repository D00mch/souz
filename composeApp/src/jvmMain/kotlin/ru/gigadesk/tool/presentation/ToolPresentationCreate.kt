package ru.gigadesk.tool.presentation

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.poi.xslf.usermodel.SlideLayout
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import ru.gigadesk.tool.BadInputException
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolSetupWithAttachments
import ru.gigadesk.tool.files.FilesToolUtil
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader

data class SlideContent(
    @InputParamDescription("Title of the slide")
    val title: String,
    @InputParamDescription("Subtitle for Title slides")
    val subtitle: String? = null,
    @InputParamDescription("Bullet points for the slide body")
    val points: List<String> = emptyList(),
    @InputParamDescription("Speaker notes for this slide")
    val notes: String? = null,
    @InputParamDescription("Absolute path to an image file to insert on the slide")
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
    @InputParamDescription("Optional: Design ID. One of: MINIMALIST_MODERN, CLEAN_LINES, SWISS_DESIGN, CORPORATE_BLUE, CORPORATE_ELEGANT, EXECUTIVE, CREATIVE_CHAOS, CREATIVE_SPLASH, ARTISTIC_FLOW, TECH_GRID, DIGITAL_WAVE, CYBERPUNK, NATURE_GREEN, OCEAN_BLUE, FOREST, SUNSET_GRADIENT, NEON_GRADIENT, SOFT_PASTEL, GEOMETRIC_CIRCLES, GEOMETRIC_TRIANGLES, GEOMETRIC_HEXAGON, MODERN_SPLIT")
    val designId: String? = null,
    @InputParamDescription("Optional: List of shapes to be rendered in the background (behind text/images).")
    val backgroundShapes: List<PresentationShape>? = null
)

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

data class PresentationChartSeries(
    val name: String,
    val values: List<Double>
)

data class PresentationShape(
    @InputParamDescription("Shape type. Options: RECT/RECTANGLE, OVAL/ELLIPSE, TRIANGLE, ARROW_RIGHT, STAR_5")
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
    val color: String? = null
)

data class PresentationTable(
    @InputParamDescription("CSV string representing the table data. Cells separated by comma, rows by newline.")
    val csvData: String,
    @InputParamDescription("Whether the first row is a header row (will be styled differently)")
    val hasHeader: Boolean = true
)

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
    @InputParamDescription("Optional output path (file or directory). If omitted, uses ~/GigaDesk/Documents.")
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
    val themeBodyFont: String? = null
)

class ToolPresentationCreate(
    private val filesToolUtil: FilesToolUtil
) : ToolSetupWithAttachments<PresentationCreateInput> {
    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    override val name: String = "PresentationCreate"
    override val description: String = "Create a PowerPoint presentation (.pptx) from text content. " +
            "Supports creating slides with bullet points, speaker notes, and images. " +
            "Can use a custom template (.pptx) and specific slide layouts (e.g. TITLE, PIC_TX, TWO_COL_TX). " +
            "Chart blocks currently render a clear placeholder with source data summary (not a native PPT chart). " +
            "For real charts, use the 'CreatePlot' tool and insert the image via `imagePath`.\n" +
            "Supports 30+ built-in visual themes (e.g. STARTUP, CYBERPUNK) AND Custom Themes via 'themeBackgroundColor', 'themeAccentColor' etc.\n" +
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
        ru.gigadesk.tool.FewShotExample(
            request = "Создай презентацию о нашем стартапе 'GigaDesk' для инвесторов. Тема яркая.",
            params = mapOf(
                "title" to "GigaDesk Investor Pitch",
                "theme" to "STARTUP",
                "slides" to listOf(
                    mapOf("layout" to "TITLE", "title" to "GigaDesk", "subtitle" to "Future of Work"),
                    mapOf("layout" to "PIC_TX", "title" to "The Problem", "points" to listOf("Chaos in files", "Lost productivity")),
                    mapOf("layout" to "PIC_TX", "title" to "The Solution", "points" to listOf("AI Agent Integration", "Automated Workflows")),
                    mapOf("layout" to "TITLE", "title" to "Join Us")
                ),
                "themeTitleColor" to "#FFFFFF",
                "themeBackgroundColor" to "#1A1A1A",
                "themeAccentColor" to "#00FF99"
            )
        ),
        ru.gigadesk.tool.FewShotExample(
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
        ru.gigadesk.tool.FewShotExample(
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
        ru.gigadesk.tool.FewShotExample(
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
            "slideCount" to ReturnProperty("integer", "Number of slides created")
        )
    )
    
    override val attachments: List<String> = emptyList()
    
    override fun invoke(input: PresentationCreateInput): String {
        val customTheme = buildCustomTheme(input)
        val slides = resolveSlides(input)
        
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
            val resolvedThemeObj = resolveTheme(input.theme)

            // Apply Theme if specified and NO template is used (template takes precedence)
            if (effectiveTemplatePath == null) {
                if (customTheme != null) {
                    applyCustomTheme(master, customTheme)
                } else if (resolvedThemeObj != null) {
                    applyTheme(master, resolvedThemeObj)
                }
            }

            slides.forEach { slideData ->
            // Smart Layout Fallback: Check if image is actually available
            var effectiveLayoutName = slideData.layout?.uppercase()
            var effectiveImagePath = slideData.imagePath?.let { 
                if (it.startsWith("~")) it.replaceFirst("~", System.getProperty("user.home")) else it 
            }

            // If layout implies image but image is missing/invalid, fallback to text-only layout
            if (effectiveLayoutName == "PIC_TX" || effectiveLayoutName == "TWO_COL_TX_IMG") {
                val hasValidImage = if (effectiveImagePath != null) {
                    val imgFile = File(effectiveImagePath)
                    val isSupported = when (imgFile.extension.lowercase()) {
                        "png", "jpeg", "jpg", "gif", "bmp", "svg", "webp" -> true
                        else -> false
                    }
                    imgFile.exists() && imgFile.isFile && imgFile.length() > 0 && isSupported
                } else false

                if (!hasValidImage) {
                    // Fallback to text layout
                    effectiveLayoutName = "TITLE_AND_CONTENT"
                    effectiveImagePath = null // Ensure we don't try to add it later
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
            if (slideData.designId != null) {
                PresentationDesignSystem.applyDesign(slide, slideData.designId, resolvedThemeObj)
            }
            
            // Add Speaker Notes
            if (slideData.notes != null) {
                 val notesSlide = ppt.getNotesSlide(slide) ?: try {
                     val method = XMLSlideShow::class.java.getDeclaredMethod("createNotesSlide", org.apache.poi.xslf.usermodel.XSLFSlide::class.java)
                     method.isAccessible = true
                     method.invoke(ppt, slide) as org.apache.poi.xslf.usermodel.XSLFNotes
                 } catch (e: Exception) {
                     null
                 }
                 
                 if (notesSlide != null) {
                     val notesPlaceholder = notesSlide.getPlaceholder(0) as? org.apache.poi.xslf.usermodel.XSLFTextShape
                     if (notesPlaceholder != null) {
                         notesPlaceholder.text = slideData.notes
                     }
                 }
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
                
                if (effectiveTemplatePath == null && resolvedThemeObj != null) {
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
            val hasImage = effectiveImagePath != null
            
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
                        
                        if (effectiveTemplatePath == null && resolvedThemeObj != null) {
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
            if (effectiveImagePath != null) {
                val imageFile = File(effectiveImagePath)
                if (imageFile.exists()) {
                    var pictureData = imageFile.readBytes()
                    var format = when (imageFile.extension.lowercase()) {
                        "png" -> org.apache.poi.sl.usermodel.PictureData.PictureType.PNG
                        "jpeg", "jpg" -> org.apache.poi.sl.usermodel.PictureData.PictureType.JPEG
                        "gif" -> org.apache.poi.sl.usermodel.PictureData.PictureType.GIF
                        "bmp" -> org.apache.poi.sl.usermodel.PictureData.PictureType.BMP
                        "svg" -> org.apache.poi.sl.usermodel.PictureData.PictureType.SVG
                        "webp", "avif" -> {
                             // ... (existing conversion logic)
                             // Convert WebP/AVIF to PNG using Skia
                            try {
                                val skiaImage = Image.makeFromEncoded(pictureData)
                                val pngData = skiaImage.encodeToData(EncodedImageFormat.PNG)
                                if (pngData != null) {
                                    pictureData = pngData.bytes
                                    org.apache.poi.sl.usermodel.PictureData.PictureType.PNG
                                } else null
                            } catch (e: Exception) {
                                println("Failed to convert image ${imageFile.name}: ${e.message}")
                                null
                            }
                        }
                        else -> null
                    }

                    if (format != null) {
                        val pictureIdx = ppt.addPicture(pictureData, format)
                        val picture = slide.createPicture(pictureIdx)
                        
                        // Determine anchor
                        // Use Custom Anchor ONLY if not forced to split
                        val customAnchor = if (
                            !forceSplitView &&
                            slideData.imageX != null && slideData.imageY != null && 
                            slideData.imageWidth != null && slideData.imageHeight != null
                        ) {
                            java.awt.Rectangle(slideData.imageX, slideData.imageY, slideData.imageWidth, slideData.imageHeight)
                        } else null
                        
                        // IF force detected (or just fallback), use SMART SIZING
                        val smartAnchor = if (forceSplitView) {
                             // Place to the right of text area
                             // We assume text has been pushed to left (0..width/2)
                             // So image goes to right (width/2..width)
                             
                             val slideWidth = slide.slideShow.pageSize.width
                             val slideHeight = slide.slideShow.pageSize.height
                             val x = (slideWidth / 2.0) + 20
                             val w = (slideWidth / 2.0) - 40
                             
                             // Try to center vertically or fill height
                             val y = 100.0 // Header margin
                             val h = slideHeight - 150.0
                             
                             java.awt.Rectangle(x.toInt(), y.toInt(), w.toInt(), h.toInt())
                        } else null

                        if (customAnchor != null) {
                             picture.anchor = customAnchor
                             if (imagePlaceholder != null) imagePlaceholder.clearText()
                        } else if (imagePlaceholder != null) {
                             val anchor = imagePlaceholder.anchor
                             picture.anchor = anchor
                             imagePlaceholder.clearText()
                        } else if (smartAnchor != null) {
                             // Apply Smart Anchor (Split View)
                             picture.anchor = smartAnchor
                        } else {
                             // Default Fallback (Bottom Right, reasonable size)
                             val sw = slide.slideShow.pageSize.width
                             val sh = slide.slideShow.pageSize.height
                             picture.anchor = java.awt.Rectangle(sw/2, sh/4, sw/2 - 50, sh/2)
                        }

                    } else {
                        println("Unsupported image format: ${imageFile.extension}")
                    }
                }
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

            // Sanitize filename, but allow letters and digits from any language (Unicode) and combining marks.
            var safeTitle = (input.filename ?: input.title).replace(Regex("[^\\p{L}\\p{N}\\p{M} ._-]"), "_")
            if (safeTitle.isBlank() || safeTitle.all { it == '_' || it == '.' }) {
                safeTitle = "Presentation_${System.currentTimeMillis()}"
            }
            val fileName = if (safeTitle.endsWith(".pptx", ignoreCase = true)) safeTitle else "$safeTitle.pptx"

            val outputFile = resolveOutputFile(input, fileName)
            outputFile.parentFile?.mkdirs()
            filesToolUtil.requirePathIsSave(outputFile)

            FileOutputStream(outputFile).use { out ->
                ppt.write(out)
            }

            val slideCount = ppt.slides.size
            return """
                {
                    "path": "${outputFile.absolutePath}",
                    "slideCount": $slideCount
                }
            """.trimIndent()
        } finally {
            ppt.close()
        }
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
        val rawOutputPath = input.outputPath?.takeIf { it.isNotBlank() } ?: "~/GigaDesk/Documents/$defaultFileName"
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
            "OVAL", "ELLIPSE" -> org.apache.poi.sl.usermodel.ShapeType.ELLIPSE
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
        
        shape.fillColor = fillColor
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
        
        footerStrip.anchor = java.awt.Rectangle(0, height - 20, width, 20)
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
