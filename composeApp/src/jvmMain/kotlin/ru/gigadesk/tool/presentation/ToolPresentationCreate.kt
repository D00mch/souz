package ru.gigadesk.tool.presentation

import org.apache.poi.xslf.usermodel.SlideLayout
import org.apache.poi.xslf.usermodel.XMLSlideShow
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolSetupWithAttachments
import java.io.File
import java.io.FileOutputStream

data class SlideContent(
    @InputParamDescription("Title of the slide")
    val title: String,
    @InputParamDescription("Bullet points for the slide body")
    val points: List<String> = emptyList(),
    @InputParamDescription("Speaker notes for this slide")
    val notes: String? = null,
    @InputParamDescription("Absolute path to an image file to insert on the slide")
    val imagePath: String? = null,
    @InputParamDescription("Slide layout name. Options: TITLE, TITLE_ONLY, TITLE_AND_CONTENT (default), SECTION_HEADER, TWO_COL_TX, TWO_COL_TX_IMG, PIC_TX")
    val layout: String? = null,
    @InputParamDescription("Table data to include on the slide")
    val table: PresentationTable? = null,
    @InputParamDescription("List of geometric shapes to add")
    val shapes: List<PresentationShape>? = null,
    @InputParamDescription("Chart data to include on the slide")
    val chart: PresentationChart? = null
)

data class PresentationChart(
    @InputParamDescription("Chart title")
    val title: String,
    @InputParamDescription("Chart type. Options: BAR, PIE, LINE, DOUGHNUT")
    val type: String,
    @InputParamDescription("Categories (labels for X axis or Pie slices)")
    val categories: List<String>,
    @InputParamDescription("Data series. For Pie charts, only one series is used.")
    val series: List<PresentationChartSeries>
)

data class PresentationChartSeries(
    @InputParamDescription("Name of the series")
    val name: String,
    @InputParamDescription("Numerical values")
    val values: List<Double>
)

data class PresentationShape(
// ... (keep existing PresentationShape properties)
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
    @InputParamDescription("List of rows, where each row is a list of cell text strings")
    val rows: List<List<String>>,
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
    @InputParamDescription("List of slides to include")
    val slides: List<SlideContent>,
    @InputParamDescription("Optional output filename (without extension). Defaults to 'Presentation_<Title>'")
    val filename: String? = null,
    @InputParamDescription("Absolute path to a .pptx file to use as a template")
    val templatePath: String? = null,
    @InputParamDescription("Visual theme. Supports 30+ themes. PREFERRED: Use 'customTheme' for unique designs instead of presets.")
    val theme: String? = null,
    @InputParamDescription("Define a purely custom theme (colors, fonts) instead of using a preset")
    val customTheme: CustomThemeParam? = null
)

class ToolPresentationCreate : ToolSetupWithAttachments<PresentationCreateInput> {
    override val name: String = "PresentationCreate"
    override val description: String = "Create a PowerPoint presentation (.pptx) from text content. " +
            "Supports creating slides with bullet points, speaker notes, and images. " +
            "Can use a custom template (.pptx) and specific slide layouts (e.g. TITLE, PIC_TX, TWO_COL_TX). " +
            "To include charts, use the 'chart' block with data, OR use 'CreatePlot' tool to generate an image.\n" +
            "Supports 30+ built-in visual themes (e.g. STARTUP, CYBERPUNK) AND Custom Themes via 'customTheme'.\n" +
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
                )
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
                "slides" to listOf(
                    mapOf("layout" to "TITLE", "title" to "Q3 Financial Results"),
                    mapOf("layout" to "TITLE_AND_CONTENT", "title" to "Executive Summary", "points" to listOf("Revenue up 15%", "Costs down 5%")),
                    mapOf("layout" to "PIC_TX", "title" to "Growth Metrics", "points" to listOf("User base doubled", "Retention 90%"))
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
        // 1. Load Template or Create New
        val ppt = if (input.templatePath != null) {
            val resolvedTemplatePath = if (input.templatePath.startsWith("~")) 
                input.templatePath.replaceFirst("~", System.getProperty("user.home")) 
            else input.templatePath
            
            val file = File(resolvedTemplatePath)
            if (file.exists()) {
                XMLSlideShow(java.io.FileInputStream(file))
            } else {
                throw IllegalArgumentException("Template file not found at: ${input.templatePath}")
            }
        } else {
            XMLSlideShow()
        }
        
        val master = ppt.slideMasters[0]

        // Apply Theme if specified and NO template is used (template takes precedence)
        if (input.templatePath == null) {
            if (input.customTheme != null) {
                applyCustomTheme(master, input.customTheme)
            } else if (input.theme != null) {
                try {
                    val theme = PresentationTheme.valueOf(input.theme.uppercase())
                    applyTheme(master, theme)
                } catch (e: IllegalArgumentException) {
                    // Warning: invalid theme, ignore or log
                }
            }
        }

        // 2. Create Title Slide (only if not using a template, or if explicitly requested - for now, always create if not template)
        // ... (rest of the logic remains same, but we need to ensure text runs pick up theme colors if not handled by master)
        


        // 3. Create Content Slides
        input.slides.forEach { slideData ->
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
                        "png", "jpeg", "jpg", "gif", "bmp", "svg" -> true
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

            // Set Title
            val slideTitle = slide.getPlaceholder(0)
            if (slideTitle != null) {
                slideTitle.text = slideData.title
                
                // Explicitly apply theme to title (master inheritance can be flaky with .text setter)
                if (input.templatePath == null && input.theme != null) {
                     try {
                        val theme = PresentationTheme.valueOf(input.theme.uppercase())
                        slideTitle.textParagraphs.forEach { p ->
                            p.textRuns.forEach { r ->
                                r.fontColor = org.apache.poi.sl.draw.DrawPaint.createSolidPaint(theme.titleColor)
                                r.fontFamily = theme.titleFont
                                r.isBold = true // Force bold for titles
                            }
                        }
                     } catch (e: Exception) {}
                }
            }
            
            // Iterate over all placeholders to find the best match for Text and Image
            var textPlaceholder: org.apache.poi.xslf.usermodel.XSLFTextShape? = null
            var imagePlaceholder: org.apache.poi.xslf.usermodel.XSLFTextShape? = null
            
            // Priority: 
            // Text -> BODY, CONTENT, or fallback to any non-title if not found
            // Image -> PICTURE, or CONTENT (if text didn't take it), or fallback
            
            val allPlaceholders = slide.placeholders.toList()
            
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
            if (textPlaceholder != null) {
                textPlaceholder.clearText()
                slideData.points.forEach { point ->
                    val paragraph = textPlaceholder.addNewTextParagraph()
                    paragraph.isBullet = true
                    val run = paragraph.addNewTextRun()
                    run.setText(point)
                    
                    if (input.templatePath == null && input.theme != null) {
                         try {
                            val theme = PresentationTheme.valueOf(input.theme.uppercase())
                            run.fontColor = org.apache.poi.sl.draw.DrawPaint.createSolidPaint(theme.contentColor)
                            run.fontFamily = theme.bodyFont
                         } catch (e: Exception) {}
                    }
                }
            }

            


            
            // Resolve Theme: Custom > Preset > Default
            val theme = if (input.customTheme != null) {
                // Create a synthetic PresentationTheme or use properties directly
                // Since PresentationTheme is an enum, we can't instantiate it. 
                // But we can create a temporary object or use the custom colors directly.
                // Refactor: We should probably change PresentationTheme to a data class, but that's a big refactor.
                // Shortcut: We will use the custom properties where 'theme' was used.
                // But 'theme' variable expects PresentationTheme enum.
                // Let's make 'theme' nullable enum, and handle customTheme explicitly in calls.
                null
            } else if (input.theme != null) {
                try { PresentationTheme.valueOf(input.theme.uppercase()) } catch (e: Exception) { null }
            } else null

            val customTheme = input.customTheme



            // Fill Image
            if (effectiveImagePath != null) {
                val imageFile = File(effectiveImagePath)
                if (imageFile.exists()) {
                    val pictureData = imageFile.readBytes()
                    val format = when (imageFile.extension.lowercase()) {
                        "png" -> org.apache.poi.sl.usermodel.PictureData.PictureType.PNG
                        "jpeg", "jpg" -> org.apache.poi.sl.usermodel.PictureData.PictureType.JPEG
                        "gif" -> org.apache.poi.sl.usermodel.PictureData.PictureType.GIF
                        "bmp" -> org.apache.poi.sl.usermodel.PictureData.PictureType.BMP
                        "svg" -> org.apache.poi.sl.usermodel.PictureData.PictureType.SVG
                        else -> null
                    }

                    if (format != null) {
                        val pictureIdx = ppt.addPicture(pictureData, format)
                        
                        if (imagePlaceholder != null) {
                             val anchor = imagePlaceholder.anchor
                             val picture = slide.createPicture(pictureIdx)
                             picture.anchor = anchor
                             
                             // Optional: clear text from the placeholder if it was a text shape used for image
                             imagePlaceholder.clearText()
                        } else {
                             val picture = slide.createPicture(pictureIdx)
                             // Default position if no placeholder found: Right side, moderate size
                             // Assuming slide size is roughly 720x540 or 960x540
                             picture.setAnchor(java.awt.Rectangle(450, 150, 250, 250))
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
                
                // Or fallback to textPlaceholder if text is empty? 
                // Better: if both image and table are present, and we have 2 cols, use one for each.
                // But for now, let's prioritize explicit placeholders logic.
                
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
                   createChart(slide, slideData.chart, theme, customTheme)
                } catch (e: Exception) {
                    println("Error creating chart: ${e.message}")
                }
            }
            
            // Auto-Decoration for Custom Themes to ensure colors are visible
            if (customTheme != null) {
                addThemeDecoration(slide, customTheme)
            }
        }

        // 4. Save file
// ... (rest of file)



        val safeTitle = input.filename ?: input.title.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val fileName = if (safeTitle.endsWith(".pptx")) safeTitle else "$safeTitle.pptx"
        val userHome = System.getProperty("user.home")
        val desktopPath = File(userHome, "Desktop")
        val outputFile = File(desktopPath, fileName)

        FileOutputStream(outputFile).use { out ->
            ppt.write(out)
        }
        
        val slideCount = ppt.slides.size
        ppt.close()

        return """
            {
                "path": "${outputFile.absolutePath}",
                "slideCount": $slideCount
            }
        """.trimIndent()
    }

    private fun createChart(
        slide: org.apache.poi.xslf.usermodel.XSLFSlide,
        chartData: PresentationChart,
        theme: PresentationTheme?,
        customTheme: CustomThemeParam?
    ) {
        val title = chartData.title
        
        // 1. Draw Title
        val textBox = slide.createTextBox()
        textBox.anchor = java.awt.Rectangle(100, 100, 500, 50)
        textBox.text = "Chart: $title (Native rendering placeholder)"
        textBox.textParagraphs.first().textRuns.first().apply {
            fontSize = 18.0
            isBold = true
            fontFamily = theme?.titleFont ?: customTheme?.titleFont ?: "Arial"
            fontColor = org.apache.poi.sl.draw.DrawPaint.createSolidPaint(
                if (customTheme?.titleColor != null) java.awt.Color.decode(customTheme.titleColor) 
                else theme?.titleColor ?: java.awt.Color.BLACK
            )
        }
        
        // 2. Draw a placeholder rectangle
        val bg = slide.createAutoShape()
        bg.shapeType = org.apache.poi.sl.usermodel.ShapeType.RECT
        bg.anchor = java.awt.Rectangle(100, 150, 500, 300)
        bg.fillColor = java.awt.Color.WHITE
        bg.setLineColor(java.awt.Color.LIGHT_GRAY)
        
        val infoText = slide.createTextBox()
        infoText.anchor = java.awt.Rectangle(120, 170, 460, 260)
        infoText.text = "Data: ${chartData.series.map { "${it.name}: ${it.values}" }}"
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
        
        tableData.rows.forEachIndexed { rowIndex, rowData ->
            val row = table.addRow()
            val isHeader = tableData.hasHeader && rowIndex == 0
            
            // Set row height (default)
            row.height = 30.0
            
            rowData.forEach { cellData ->
                val cell = row.addCell()
                cell.setText(cellData)
                
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
