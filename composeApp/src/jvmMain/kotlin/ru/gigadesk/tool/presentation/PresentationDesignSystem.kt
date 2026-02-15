package ru.gigadesk.tool.presentation

import org.apache.poi.xslf.usermodel.XSLFSlide
import org.apache.poi.sl.usermodel.ShapeType
import java.awt.Color
import java.awt.Rectangle

object PresentationDesignSystem {

    enum class DesignStyle {
        // Минимализм
        MINIMALIST_MODERN,
        CLEAN_LINES,
        SWISS_DESIGN,

        // Корпоративный
        CORPORATE_BLUE,
        CORPORATE_ELEGANT,
        EXECUTIVE,

        // Креативный
        CREATIVE_CHAOS,
        CREATIVE_SPLASH,
        ARTISTIC_FLOW,

        // Технологии
        TECH_GRID,
        DIGITAL_WAVE,
        CYBERPUNK,

        // Природа
        NATURE_GREEN,
        OCEAN_BLUE,
        FOREST,

        // Градиенты
        SUNSET_GRADIENT,
        NEON_GRADIENT,
        SOFT_PASTEL,

        // Геометрия
        GEOMETRIC_CIRCLES,
        GEOMETRIC_TRIANGLES,
        GEOMETRIC_HEXAGON,
        MODERN_SPLIT,

        // Legacy (для совместимости)
        GEOMETRIC,
        TECH,
        NATURE,
        ABSTRACT
    }

    /**
     * Применить дизайн к слайду
     */
    fun applyDesign(slide: XSLFSlide, designId: String, theme: PresentationTheme?) {
        val style = try {
            DesignStyle.valueOf(designId.uppercase())
        } catch (e: Exception) {
            return // Unknown design
        }

        val width = slide.slideShow.pageSize.width
        val height = slide.slideShow.pageSize.height

        val accentColor = theme?.accentColor ?: Color.BLUE
        val secondaryColor = theme?.titleColor ?: Color.DARK_GRAY

        when (style) {
            // Минимализм
            DesignStyle.MINIMALIST_MODERN -> applyMinimalistModern(slide, width, height, accentColor, secondaryColor)
            DesignStyle.CLEAN_LINES -> applyCleanLines(slide, width, height, accentColor, secondaryColor)
            DesignStyle.SWISS_DESIGN -> applySwissDesign(slide, width, height, accentColor, secondaryColor)

            // Корпоративный
            DesignStyle.CORPORATE_BLUE -> applyCorporateBlue(slide, width, height, accentColor, secondaryColor)
            DesignStyle.CORPORATE_ELEGANT -> applyCorporateElegant(slide, width, height, accentColor, secondaryColor)
            DesignStyle.EXECUTIVE -> applyExecutive(slide, width, height, accentColor, secondaryColor)

            // Креативный
            DesignStyle.CREATIVE_CHAOS -> applyCreativeChaos(slide, width, height, accentColor, secondaryColor)
            DesignStyle.CREATIVE_SPLASH -> applyCreativeSplash(slide, width, height, accentColor, secondaryColor)
            DesignStyle.ARTISTIC_FLOW -> applyArtisticFlow(slide, width, height, accentColor, secondaryColor)

            // Технологии
            DesignStyle.TECH_GRID -> applyTechGrid(slide, width, height, accentColor, secondaryColor)
            DesignStyle.DIGITAL_WAVE -> applyDigitalWave(slide, width, height, accentColor, secondaryColor)
            DesignStyle.CYBERPUNK -> applyCyberpunk(slide, width, height, accentColor, secondaryColor)

            // Природа
            DesignStyle.NATURE_GREEN -> applyNatureGreen(slide, width, height, accentColor, secondaryColor)
            DesignStyle.OCEAN_BLUE -> applyOceanBlue(slide, width, height, accentColor, secondaryColor)
            DesignStyle.FOREST -> applyForest(slide, width, height, accentColor, secondaryColor)

            // Градиенты
            DesignStyle.SUNSET_GRADIENT -> applySunsetGradient(slide, width, height, accentColor, secondaryColor)
            DesignStyle.NEON_GRADIENT -> applyNeonGradient(slide, width, height, accentColor, secondaryColor)
            DesignStyle.SOFT_PASTEL -> applySoftPastel(slide, width, height, accentColor, secondaryColor)

            // Геометрия
            DesignStyle.GEOMETRIC_CIRCLES -> applyGeometricCircles(slide, width, height, accentColor, secondaryColor)
            DesignStyle.GEOMETRIC_TRIANGLES -> applyGeometricTriangles(slide, width, height, accentColor, secondaryColor)
            DesignStyle.GEOMETRIC_HEXAGON -> applyGeometricHexagon(slide, width, height, accentColor, secondaryColor)
            DesignStyle.MODERN_SPLIT -> applyModernSplit(slide, width, height, accentColor, secondaryColor)

            // Legacy
            DesignStyle.GEOMETRIC -> applyGeometric(slide, width, height, accentColor, secondaryColor)
            DesignStyle.TECH -> applyTech(slide, width, height, accentColor, secondaryColor)
            DesignStyle.NATURE -> applyNature(slide, width, height, accentColor, secondaryColor)
            DesignStyle.ABSTRACT -> applyAbstract(slide, width, height, accentColor)
        }
    }

    // ==================== МИНИМАЛИЗМ ====================

    private fun applyMinimalistModern(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Тонкая акцентная линия сверху слева
        val accentLine = slide.createAutoShape()
        accentLine.shapeType = ShapeType.RECT
        accentLine.anchor = Rectangle(w / 12, h / 8, w / 5, 4)
        accentLine.fillColor = accent
        accentLine.lineColor = null

        // Большой полупрозрачный круг в правом нижнем углу (обрезан)
        val decorCircle = slide.createAutoShape()
        decorCircle.shapeType = ShapeType.ELLIPSE
        decorCircle.anchor = Rectangle(w - w / 4, h - h / 4, w / 3, w / 3)
        decorCircle.fillColor = withAlpha(secondary, 30)
        decorCircle.lineColor = null
    }

    private fun applyCleanLines(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Вертикальная цветная полоса слева
        val leftStripe = slide.createAutoShape()
        leftStripe.shapeType = ShapeType.RECT
        leftStripe.anchor = Rectangle(0, 0, w / 80, h)
        leftStripe.fillColor = accent
        leftStripe.lineColor = null

        // Три декоративных квадрата справа внизу
        for (i in 0..2) {
            val dot = slide.createAutoShape()
            dot.shapeType = ShapeType.RECT
            dot.anchor = Rectangle(w - w / 8 - (i * w / 40), h - h / 10, w / 80, w / 80)
            dot.fillColor = withAlpha(accent, 255 - i * 40)
            dot.lineColor = null
        }
    }

    private fun applySwissDesign(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Белая полоса сверху (1/3 высоты)
        val whiteSection = slide.createAutoShape()
        whiteSection.shapeType = ShapeType.RECT
        whiteSection.anchor = Rectangle(0, 0, w, h / 3)
        whiteSection.fillColor = Color.WHITE
        whiteSection.lineColor = null

        // Красный фон (оставшаяся часть естественна через accent color)
        val redSection = slide.createAutoShape()
        redSection.shapeType = ShapeType.RECT
        redSection.anchor = Rectangle(0, h / 3, w, h * 2 / 3)
        redSection.fillColor = accent
        redSection.lineColor = null

        // Черный квадрат справа внизу
        val blackSquare = slide.createAutoShape()
        blackSquare.shapeType = ShapeType.RECT
        blackSquare.anchor = Rectangle(w - w / 6, h - h / 6, w / 8, w / 8)
        blackSquare.fillColor = Color.BLACK
        blackSquare.lineColor = null
    }

    // ==================== КОРПОРАТИВНЫЙ ====================

    private fun applyCorporateBlue(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Градиентный фон (эмуляция через полупрозрачные слои)
        val darkBase = slide.createAutoShape()
        darkBase.shapeType = ShapeType.RECT
        darkBase.anchor = Rectangle(0, 0, w, h)
        darkBase.fillColor = secondary
        darkBase.lineColor = null

        // Большой полупрозрачный круг справа сверху (обрезан)
        val circle = slide.createAutoShape()
        circle.shapeType = ShapeType.ELLIPSE
        circle.anchor = Rectangle(w - w / 3, -h / 4, w / 2, w / 2)
        circle.fillColor = withAlpha(Color.WHITE, 20)
        circle.lineColor = null

        // Декоративная линия внизу
        val bottomLine = slide.createAutoShape()
        bottomLine.shapeType = ShapeType.RECT
        bottomLine.anchor = Rectangle(w / 16, h - h / 8, w / 2, 3)
        bottomLine.fillColor = accent
        bottomLine.lineColor = null
    }

    private fun applyCorporateElegant(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Темный фон
        val darkBg = slide.createAutoShape()
        darkBg.shapeType = ShapeType.RECT
        darkBg.anchor = Rectangle(0, 0, w, h)
        darkBg.fillColor = secondary
        darkBg.lineColor = null

        // Вертикальная цветная панель слева
        val leftPanel = slide.createAutoShape()
        leftPanel.shapeType = ShapeType.RECT
        leftPanel.anchor = Rectangle(0, 0, w / 3, h)
        leftPanel.fillColor = accent
        leftPanel.lineColor = null

        // Два декоративных квадрата справа внизу
        for (i in 0..1) {
            val square = slide.createAutoShape()
            square.shapeType = ShapeType.RECT
            val size = w / 20
            square.anchor = Rectangle(w - w / 8 - i * (size + w / 40), h - h / 10, size, size)
            square.fillColor = null
            square.lineColor = if (i == 0) Color.WHITE else accent
            square.lineWidth = 3.0
        }
    }

    private fun applyExecutive(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Темный градиентный фон
        val darkBg = slide.createAutoShape()
        darkBg.shapeType = ShapeType.RECT
        darkBg.anchor = Rectangle(0, 0, w, h)
        darkBg.fillColor = Color.BLACK
        darkBg.lineColor = null

        // Верхняя и нижняя рамочные линии
        val topBorder = slide.createAutoShape()
        topBorder.shapeType = ShapeType.RECT
        topBorder.anchor = Rectangle(w / 12, h / 12, w - w / 6, 2)
        topBorder.fillColor = secondary
        topBorder.lineColor = null

        val bottomBorderTop = slide.createAutoShape()
        bottomBorderTop.shapeType = ShapeType.RECT
        bottomBorderTop.anchor = Rectangle(w / 12, h / 12 + h / 15, w - w / 6, 2)
        bottomBorderTop.fillColor = secondary
        bottomBorderTop.lineColor = null

        // Акцентная линия внизу слева
        val accentLine = slide.createAutoShape()
        accentLine.shapeType = ShapeType.RECT
        accentLine.anchor = Rectangle(w / 12, h - h / 8, w / 4, 4)
        accentLine.fillColor = accent
        accentLine.lineColor = null

        // Повернутый квадрат справа внизу
        val rotatedSquare = slide.createAutoShape()
        rotatedSquare.shapeType = ShapeType.RECT
        rotatedSquare.anchor = Rectangle(w - w / 8, h - h / 8, w / 16, w / 16)
        rotatedSquare.fillColor = null
        rotatedSquare.lineColor = accent
        rotatedSquare.lineWidth = 4.0
        rotatedSquare.rotation = 45.0
    }

    // ==================== КРЕАТИВНЫЙ ====================

    private fun applyCreativeChaos(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Яркий фон
        val bgColor = Color(253, 224, 71) // Yellow
        val bg = slide.createAutoShape()
        bg.shapeType = ShapeType.RECT
        bg.anchor = Rectangle(0, 0, w, h)
        bg.fillColor = bgColor
        bg.lineColor = null

        // Большой розовый круг вверху слева (обрезан)
        val pinkCircle = slide.createAutoShape()
        pinkCircle.shapeType = ShapeType.ELLIPSE
        pinkCircle.anchor = Rectangle(w / 12, -h / 8, w / 3, w / 3)
        pinkCircle.fillColor = withAlpha(Color(236, 72, 153), 180)
        pinkCircle.lineColor = null

        // Голубой квадрат справа
        val cyanSquare = slide.createAutoShape()
        cyanSquare.shapeType = ShapeType.RECT
        cyanSquare.anchor = Rectangle(w - w / 6, h / 3, w / 6, w / 6)
        cyanSquare.fillColor = Color(6, 182, 212)
        cyanSquare.lineColor = null
        cyanSquare.rotation = 45.0

        // Фиолетовый круг внизу слева
        val purpleCircle = slide.createAutoShape()
        purpleCircle.shapeType = ShapeType.ELLIPSE
        purpleCircle.anchor = Rectangle(w / 4, h - h / 4, w / 3, w / 3)
        purpleCircle.fillColor = withAlpha(Color(168, 85, 247), 150)
        purpleCircle.lineColor = null
    }

    private fun applyCreativeSplash(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Градиентный фон (многослойный)
        val basePurple = slide.createAutoShape()
        basePurple.shapeType = ShapeType.RECT
        basePurple.anchor = Rectangle(0, 0, w, h)
        basePurple.fillColor = Color(147, 51, 234)
        basePurple.lineColor = null

        val midPink = slide.createAutoShape()
        midPink.shapeType = ShapeType.ELLIPSE
        midPink.anchor = Rectangle(w / 4, 0, w / 2, h)
        midPink.fillColor = withAlpha(Color(236, 72, 153), 180)
        midPink.lineColor = null

        val topOrange = slide.createAutoShape()
        topOrange.shapeType = ShapeType.ELLIPSE
        topOrange.anchor = Rectangle(w / 2, 0, w / 2, h / 2)
        topOrange.fillColor = withAlpha(Color(251, 146, 60), 150)
        topOrange.lineColor = null

        // Белые точки (звезды)
        val random = java.util.Random(42)
        for (i in 0..15) {
            val dot = slide.createAutoShape()
            dot.shapeType = ShapeType.ELLIPSE
            val size = 8 + random.nextInt(8)
            dot.anchor = Rectangle(random.nextInt(w), random.nextInt(h), size, size)
            dot.fillColor = withAlpha(Color.WHITE, 100 + random.nextInt(100))
            dot.lineColor = null
        }
    }

    private fun applyArtisticFlow(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Темный градиентный фон
        val darkBase = slide.createAutoShape()
        darkBase.shapeType = ShapeType.RECT
        darkBase.anchor = Rectangle(0, 0, w, h)
        darkBase.fillColor = Color(76, 29, 149)
        darkBase.lineColor = null

        // Волнообразные элементы (эмуляция через овалы)
        for (i in 0..4) {
            val wave = slide.createAutoShape()
            wave.shapeType = ShapeType.ELLIPSE
            val yPos = h / 3 + i * h / 15
            wave.anchor = Rectangle(-w / 4 + i * w / 8, yPos, w / 2, h / 8)
            wave.fillColor = withAlpha(if (i % 2 == 0) Color.WHITE else Color(6, 182, 212), 40)
            wave.lineColor = null
        }
    }

    // ==================== ТЕХНОЛОГИИ ====================

    private fun applyTechGrid(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Черный фон
        val blackBg = slide.createAutoShape()
        blackBg.shapeType = ShapeType.RECT
        blackBg.anchor = Rectangle(0, 0, w, h)
        blackBg.fillColor = Color.BLACK
        blackBg.lineColor = null

        // Сетка из линий
        val gridColor = Color(6, 182, 212) // Cyan
        val gridSpacing = 60

        // Горизонтальные линии
        for (i in 0 until h / gridSpacing) {
            val line = slide.createAutoShape()
            line.shapeType = ShapeType.RECT
            line.anchor = Rectangle(0, i * gridSpacing, w, 1)
            line.fillColor = withAlpha(gridColor, 50)
            line.lineColor = null
        }

        // Вертикальные линии
        for (i in 0 until w / gridSpacing) {
            val line = slide.createAutoShape()
            line.shapeType = ShapeType.RECT
            line.anchor = Rectangle(i * gridSpacing, 0, 1, h)
            line.fillColor = withAlpha(gridColor, 50)
            line.lineColor = null
        }

        // Декоративная рамка справа сверху
        val frameSize = w / 4
        val frame = slide.createAutoShape()
        frame.shapeType = ShapeType.RECT
        frame.anchor = Rectangle(w - frameSize - w / 12, h / 12, frameSize, frameSize)
        frame.fillColor = null
        frame.lineColor = gridColor
        frame.lineWidth = 2.0

        // Угловые акценты на рамке
        val corners = arrayOf(
            Rectangle(w - frameSize - w / 12, h / 12, 15, 15),
            Rectangle(w - w / 12 - 15, h / 12, 15, 15),
            Rectangle(w - frameSize - w / 12, h / 12 + frameSize - 15, 15, 15),
            Rectangle(w - w / 12 - 15, h / 12 + frameSize - 15, 15, 15)
        )
        for (corner in corners) {
            val dot = slide.createAutoShape()
            dot.shapeType = ShapeType.RECT
            dot.anchor = corner
            dot.fillColor = gridColor
            dot.lineColor = null
        }
    }

    private fun applyDigitalWave(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Градиентный фон (синий-фиолетовый)
        val baseBlue = slide.createAutoShape()
        baseBlue.shapeType = ShapeType.RECT
        baseBlue.anchor = Rectangle(0, 0, w, h)
        baseBlue.fillColor = Color(37, 99, 235)
        baseBlue.lineColor = null

        val overlayPurple = slide.createAutoShape()
        overlayPurple.shapeType = ShapeType.ELLIPSE
        overlayPurple.anchor = Rectangle(w / 2, 0, w, h)
        overlayPurple.fillColor = withAlpha(Color(124, 58, 237), 180)
        overlayPurple.lineColor = null

        // Волнистые линии
        for (i in 0..8) {
            val line = slide.createAutoShape()
            line.shapeType = ShapeType.RECT
            line.anchor = Rectangle(0, i * h / 10, w, 1)
            line.fillColor = withAlpha(Color.WHITE, 30)
            line.lineColor = null
        }

        // Столбчатая диаграмма справа внизу (визуализация данных)
        val random = java.util.Random(42)
        for (i in 0..7) {
            val bar = slide.createAutoShape()
            bar.shapeType = ShapeType.RECT
            val barHeight = h / 10 + random.nextInt(h / 6)
            bar.anchor = Rectangle(w - w / 6 + i * w / 70, h - h / 8 - barHeight, w / 100, barHeight)
            bar.fillColor = withAlpha(Color.WHITE, 128)
            bar.lineColor = null
        }
    }

    private fun applyCyberpunk(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Черный фон
        val blackBg = slide.createAutoShape()
        blackBg.shapeType = ShapeType.RECT
        blackBg.anchor = Rectangle(0, 0, w, h)
        blackBg.fillColor = Color.BLACK
        blackBg.lineColor = null

        // Розовый градиент
        val pinkOverlay = slide.createAutoShape()
        pinkOverlay.shapeType = ShapeType.ELLIPSE
        pinkOverlay.anchor = Rectangle(-w / 4, -h / 4, w / 2, h / 2)
        pinkOverlay.fillColor = withAlpha(Color(236, 72, 153), 40)
        pinkOverlay.lineColor = null

        // Голубой градиент
        val cyanOverlay = slide.createAutoShape()
        cyanOverlay.shapeType = ShapeType.ELLIPSE
        cyanOverlay.anchor = Rectangle(w / 2, h / 2, w, h)
        cyanOverlay.fillColor = withAlpha(Color(6, 182, 212), 40)
        cyanOverlay.lineColor = null

        // Розовая полоса сверху
        val pinkBar = slide.createAutoShape()
        pinkBar.shapeType = ShapeType.RECT
        pinkBar.anchor = Rectangle(w / 40, h / 40, w - w / 20, h / 25)
        pinkBar.fillColor = withAlpha(Color(236, 72, 153), 30)
        pinkBar.lineColor = null

        // Градиентная линия внизу
        val bottomGradient = slide.createAutoShape()
        bottomGradient.shapeType = ShapeType.RECT
        bottomGradient.anchor = Rectangle(0, h - 4, w, 4)
        bottomGradient.fillColor = Color(236, 72, 153)
        bottomGradient.lineColor = null
    }

    // ==================== ПРИРОДА ====================

    private fun applyNatureGreen(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Светлый зеленый фон
        val lightBg = slide.createAutoShape()
        lightBg.shapeType = ShapeType.RECT
        lightBg.anchor = Rectangle(0, 0, w, h)
        lightBg.fillColor = Color(236, 253, 245)
        lightBg.lineColor = null

        // Большой зеленый круг справа сверху (обрезан)
        val greenCircle1 = slide.createAutoShape()
        greenCircle1.shapeType = ShapeType.ELLIPSE
        greenCircle1.anchor = Rectangle(w - w / 3, -h / 4, w / 2, w / 2)
        greenCircle1.fillColor = withAlpha(Color(52, 211, 153), 50)
        greenCircle1.lineColor = null

        // Зеленый круг слева внизу (обрезан)
        val greenCircle2 = slide.createAutoShape()
        greenCircle2.shapeType = ShapeType.ELLIPSE
        greenCircle2.anchor = Rectangle(-w / 6, h - h / 3, w / 3, w / 3)
        greenCircle2.fillColor = withAlpha(Color(20, 184, 166), 50)
        greenCircle2.lineColor = null

        // Цветные акцентные круги справа внизу
        val colors = arrayOf(
            Color(16, 185, 129),
            Color(20, 184, 166),
            Color(34, 197, 94)
        )
        for (i in colors.indices) {
            val dot = slide.createAutoShape()
            dot.shapeType = ShapeType.ELLIPSE
            val size = w / 25
            dot.anchor = Rectangle(w - w / 8 - i * (size + w / 50), h - h / 10, size, size)
            dot.fillColor = colors[i]
            dot.lineColor = null
        }
    }

    private fun applyOceanBlue(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Градиент от светло-голубого к темно-синему
        val topColor = slide.createAutoShape()
        topColor.shapeType = ShapeType.RECT
        topColor.anchor = Rectangle(0, 0, w, h / 3)
        topColor.fillColor = Color(125, 211, 252)
        topColor.lineColor = null

        val midColor = slide.createAutoShape()
        midColor.shapeType = ShapeType.RECT
        midColor.anchor = Rectangle(0, h / 3, w, h / 3)
        midColor.fillColor = Color(59, 130, 246)
        midColor.lineColor = null

        val bottomColor = slide.createAutoShape()
        bottomColor.shapeType = ShapeType.RECT
        bottomColor.anchor = Rectangle(0, h * 2 / 3, w, h / 3)
        bottomColor.fillColor = Color(37, 99, 235)
        bottomColor.lineColor = null

        // Волны (эмуляция через полупрозрачные овалы)
        for (i in 0..4) {
            val wave = slide.createAutoShape()
            wave.shapeType = ShapeType.ELLIPSE
            wave.anchor = Rectangle(-w / 4, h / 2 + i * h / 15, w * 3 / 2, h / 8)
            wave.fillColor = withAlpha(Color.WHITE, 20)
            wave.lineColor = null
        }
    }

    private fun applyForest(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Темно-зеленый градиентный фон
        val darkGreen = slide.createAutoShape()
        darkGreen.shapeType = ShapeType.RECT
        darkGreen.anchor = Rectangle(0, 0, w, h)
        darkGreen.fillColor = Color(20, 83, 45)
        darkGreen.lineColor = null

        val midGreen = slide.createAutoShape()
        midGreen.shapeType = ShapeType.RECT
        midGreen.anchor = Rectangle(0, h / 3, w, h * 2 / 3)
        midGreen.fillColor = Color(21, 128, 61)
        midGreen.lineColor = null

        // "Деревья" - треугольники внизу
        val random = java.util.Random(42)
        for (i in 0..7) {
            val tree = slide.createAutoShape()
            tree.shapeType = ShapeType.TRIANGLE
            val treeWidth = 40 + random.nextInt(40)
            val treeHeight = 100 + random.nextInt(100)
            tree.anchor = Rectangle(i * w / 8 + random.nextInt(30), h - treeHeight, treeWidth, treeHeight)
            tree.fillColor = withAlpha(Color(6, 78, 59), 100)
            tree.lineColor = null
        }
    }

    // ==================== ГРАДИЕНТЫ ====================

    private fun applySunsetGradient(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Многослойный градиент заката
        val orange = slide.createAutoShape()
        orange.shapeType = ShapeType.RECT
        orange.anchor = Rectangle(0, 0, w, h / 3)
        orange.fillColor = Color(251, 146, 60)
        orange.lineColor = null

        val pink = slide.createAutoShape()
        pink.shapeType = ShapeType.RECT
        pink.anchor = Rectangle(0, h / 3, w, h / 3)
        pink.fillColor = Color(236, 72, 153)
        pink.lineColor = null

        val purple = slide.createAutoShape()
        purple.shapeType = ShapeType.RECT
        purple.anchor = Rectangle(0, h * 2 / 3, w, h / 3)
        purple.fillColor = Color(147, 51, 234)
        purple.lineColor = null

        // Темный overlay снизу
        val darkOverlay = slide.createAutoShape()
        darkOverlay.shapeType = ShapeType.RECT
        darkOverlay.anchor = Rectangle(0, h * 2 / 3, w, h / 3)
        darkOverlay.fillColor = withAlpha(Color.BLACK, 80)
        darkOverlay.lineColor = null
    }

    private fun applyNeonGradient(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Черный фон
        val blackBg = slide.createAutoShape()
        blackBg.shapeType = ShapeType.RECT
        blackBg.anchor = Rectangle(0, 0, w, h)
        blackBg.fillColor = Color.BLACK
        blackBg.lineColor = null

        // Розово-фиолетовый градиент
        val pinkGradient = slide.createAutoShape()
        pinkGradient.shapeType = ShapeType.ELLIPSE
        pinkGradient.anchor = Rectangle(-w / 4, -h / 4, w, h)
        pinkGradient.fillColor = withAlpha(Color(236, 72, 153), 200)
        pinkGradient.lineColor = null

        val purpleGradient = slide.createAutoShape()
        purpleGradient.shapeType = ShapeType.ELLIPSE
        purpleGradient.anchor = Rectangle(w / 4, h / 4, w, h)
        purpleGradient.fillColor = withAlpha(Color(168, 85, 247), 200)
        purpleGradient.lineColor = null

        val cyanGradient = slide.createAutoShape()
        cyanGradient.shapeType = ShapeType.ELLIPSE
        cyanGradient.anchor = Rectangle(w / 2, -h / 4, w / 2, h)
        cyanGradient.fillColor = withAlpha(Color(6, 182, 212), 180)
        cyanGradient.lineColor = null

        // Желтый акцент
        val yellowAccent = slide.createAutoShape()
        yellowAccent.shapeType = ShapeType.ELLIPSE
        yellowAccent.anchor = Rectangle(0, h / 2, w / 3, h / 2)
        yellowAccent.fillColor = withAlpha(Color(250, 204, 21), 100)
        yellowAccent.lineColor = null
    }

    private fun applySoftPastel(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Пастельный градиент
        val pinkPastel = slide.createAutoShape()
        pinkPastel.shapeType = ShapeType.RECT
        pinkPastel.anchor = Rectangle(0, 0, w, h)
        pinkPastel.fillColor = Color(252, 231, 243)
        pinkPastel.lineColor = null

        // Размытые круги (эмуляция blur через множественные слои)
        val colors = arrayOf(
            Color(252, 231, 243),
            Color(233, 213, 255),
            Color(219, 234, 254)
        )

        val positions = arrayOf(
            Rectangle(w - w / 3, -h / 4, w / 2, w / 2),
            Rectangle(-w / 6, h - h / 3, w / 3, w / 3),
            Rectangle(w / 3, h / 4, w / 3, w / 3)
        )

        for (i in colors.indices) {
            // Создаем несколько слоев для эффекта blur
            for (layer in 0..3) {
                val circle = slide.createAutoShape()
                circle.shapeType = ShapeType.ELLIPSE
                val pos = positions[i]
                val offset = layer * 10
                circle.anchor = Rectangle(
                    pos.x - offset,
                    pos.y - offset,
                    pos.width + offset * 2,
                    pos.height + offset * 2
                )
                circle.fillColor = withAlpha(colors[i], 80 - layer * 15)
                circle.lineColor = null
            }
        }
    }

    // ==================== ГЕОМЕТРИЯ ====================

    private fun applyGeometricCircles(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Темный фон
        val darkBg = slide.createAutoShape()
        darkBg.shapeType = ShapeType.RECT
        darkBg.anchor = Rectangle(0, 0, w, h)
        darkBg.fillColor = Color(15, 23, 42)
        darkBg.lineColor = null

        // Большие кольца
        val rings = arrayOf(
            Triple(w / 3, h / 4, Color(250, 204, 21)),
            Triple(w / 4, h * 3 / 4, Color(6, 182, 212))
        )

        for ((x, y, color) in rings) {
            val ring = slide.createAutoShape()
            ring.shapeType = ShapeType.ELLIPSE
            ring.anchor = Rectangle(x, y, w / 4, w / 4)
            ring.fillColor = null
            ring.lineColor = color
            ring.lineWidth = 8.0
        }

        // Центральный круг
        val centerCircle = slide.createAutoShape()
        centerCircle.shapeType = ShapeType.ELLIPSE
        centerCircle.anchor = Rectangle(w / 2 - w / 16, h / 2 - w / 16, w / 8, w / 8)
        centerCircle.fillColor = Color(236, 72, 153)
        centerCircle.lineColor = null
    }

    private fun applyGeometricTriangles(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Белый фон
        val whiteBg = slide.createAutoShape()
        whiteBg.shapeType = ShapeType.RECT
        whiteBg.anchor = Rectangle(0, 0, w, h)
        whiteBg.fillColor = Color.WHITE
        whiteBg.lineColor = null

        // Большой треугольник справа сверху (синий)
        val blueTriangle = slide.createAutoShape()
        blueTriangle.shapeType = ShapeType.RT_TRIANGLE
        blueTriangle.anchor = Rectangle(w - w / 3, 0, w / 3, w / 3)
        blueTriangle.fillColor = withAlpha(Color(59, 130, 246), 50)
        blueTriangle.lineColor = null
        blueTriangle.rotation = 0.0

        // Треугольник слева снизу (красный)
        val redTriangle = slide.createAutoShape()
        redTriangle.shapeType = ShapeType.RT_TRIANGLE
        redTriangle.anchor = Rectangle(0, h - h / 4, h / 4, h / 4)
        redTriangle.fillColor = withAlpha(Color(239, 68, 68), 50)
        redTriangle.lineColor = null
        redTriangle.rotation = 180.0
    }

    private fun applyGeometricHexagon(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Градиентный фон
        val purpleBg = slide.createAutoShape()
        purpleBg.shapeType = ShapeType.RECT
        purpleBg.anchor = Rectangle(0, 0, w, h)
        purpleBg.fillColor = Color(99, 102, 241)
        purpleBg.lineColor = null

        val darkerPurple = slide.createAutoShape()
        darkerPurple.shapeType = ShapeType.ELLIPSE
        darkerPurple.anchor = Rectangle(w / 2, h / 2, w, h)
        darkerPurple.fillColor = withAlpha(Color(168, 85, 247), 180)
        darkerPurple.lineColor = null

        // Шестиугольники (эмуляция через повернутые прямоугольники)
        val hexPositions = arrayOf(
            Pair(w / 5, h / 5),
            Pair(w / 2, h / 5),
            Pair(w * 4 / 5, h / 5),
            Pair(w / 5, h / 2),
            Pair(w / 2, h / 2),
            Pair(w * 4 / 5, h / 2)
        )

        for ((x, y) in hexPositions) {
            val hex = slide.createAutoShape()
            hex.shapeType = ShapeType.HEXAGON
            hex.anchor = Rectangle(x, y, w / 8, w / 8)
            hex.fillColor = null
            hex.lineColor = withAlpha(Color.WHITE, 50)
            hex.lineWidth = 2.0
        }
    }

    private fun applyModernSplit(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Левая половина (фиолетовая)
        val leftHalf = slide.createAutoShape()
        leftHalf.shapeType = ShapeType.RECT
        leftHalf.anchor = Rectangle(0, 0, w / 2, h)
        leftHalf.fillColor = Color(147, 51, 234)
        leftHalf.lineColor = null

        // Правая половина (желтая)
        val rightHalf = slide.createAutoShape()
        rightHalf.shapeType = ShapeType.RECT
        rightHalf.anchor = Rectangle(w / 2, 0, w / 2, h)
        rightHalf.fillColor = Color(250, 204, 21)
        rightHalf.lineColor = null
    }

    // ==================== LEGACY (для обратной совместимости) ====================

    private fun applyGeometric(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Старый дизайн "Geometric"
        val circle = slide.createAutoShape()
        circle.shapeType = ShapeType.ELLIPSE
        circle.anchor = Rectangle(w - 200, -100, 400, 400)
        circle.fillColor = withAlpha(accent, 40)
        circle.lineColor = null

        val strip = slide.createAutoShape()
        strip.shapeType = ShapeType.RT_TRIANGLE
        strip.anchor = Rectangle(0, h - 150, 150, 150)
        strip.fillColor = withAlpha(secondary, 60)
        strip.lineColor = null
        strip.rotation = 180.0
    }

    private fun applyTech(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Старый дизайн "Tech"
        val bar = slide.createAutoShape()
        bar.shapeType = ShapeType.RECT
        bar.anchor = Rectangle(20, 20, w - 40, 5)
        bar.fillColor = accent
        bar.lineColor = null

        for (i in 0..2) {
            val dot = slide.createAutoShape()
            dot.shapeType = ShapeType.RECT
            dot.anchor = Rectangle(w - 30 - (i * 15), 10, 8, 8)
            dot.fillColor = secondary
            dot.lineColor = null
        }

        for (i in 0..4) {
            val line = slide.createAutoShape()
            line.shapeType = ShapeType.RECT
            line.anchor = Rectangle(w - 100, h - 20 - (i * 10), 80, 1)
            line.fillColor = withAlpha(accent, 100)
            line.lineColor = null
        }
    }

    private fun applyNature(slide: XSLFSlide, w: Int, h: Int, accent: Color, secondary: Color) {
        // Старый дизайн "Nature"
        val blob1 = slide.createAutoShape()
        blob1.shapeType = ShapeType.ELLIPSE
        blob1.anchor = Rectangle(-50, -50, 250, 180)
        blob1.fillColor = withAlpha(Color(0, 128, 0), 30)
        blob1.lineColor = null

        val blob2 = slide.createAutoShape()
        blob2.shapeType = ShapeType.ELLIPSE
        blob2.anchor = Rectangle(w - 200, h - 150, 300, 250)
        blob2.fillColor = withAlpha(accent, 40)
        blob2.lineColor = null
    }

    private fun applyAbstract(slide: XSLFSlide, w: Int, h: Int, accent: Color) {
        // Старый дизайн "Abstract"
        val random = java.util.Random()
        for (i in 0..5) {
            val s = slide.createAutoShape()
            s.shapeType = if (random.nextBoolean()) ShapeType.ELLIPSE else ShapeType.RECT
            val size = 20 + random.nextInt(60)
            s.anchor = Rectangle(random.nextInt(w), random.nextInt(h), size, size)
            s.fillColor = withAlpha(accent, 20)
            s.lineColor = null
        }
    }

    // ==================== УТИЛИТЫ ====================

    private fun withAlpha(color: Color, alpha: Int): Color {
        return Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))
    }

    /**
     * Получить список всех доступных дизайнов с описаниями
     */
    fun getAvailableDesigns(): Map<String, String> {
        return mapOf(
            // Минимализм
            "MINIMALIST_MODERN" to "Минималистичный современный дизайн",
            "CLEAN_LINES" to "Чистые линии и элегантность",
            "SWISS_DESIGN" to "Швейцарская школа типографики",

            // Корпоративный
            "CORPORATE_BLUE" to "Профессиональный синий стиль",
            "CORPORATE_ELEGANT" to "Элегантный корпоративный дизайн",
            "EXECUTIVE" to "Стиль для руководителей",

            // Креативный
            "CREATIVE_CHAOS" to "Креативный хаос ярких цветов",
            "CREATIVE_SPLASH" to "Взрыв креативности",
            "ARTISTIC_FLOW" to "Художественные плавные формы",

            // Технологии
            "TECH_GRID" to "Технологичная сетка",
            "DIGITAL_WAVE" to "Цифровые волны",
            "CYBERPUNK" to "Футуристичный киберпанк",

            // Природа
            "NATURE_GREEN" to "Природные зеленые оттенки",
            "OCEAN_BLUE" to "Глубина океана",
            "FOREST" to "Лесная тематика",

            // Градиенты
            "SUNSET_GRADIENT" to "Градиент заката",
            "NEON_GRADIENT" to "Неоновый градиент",
            "SOFT_PASTEL" to "Мягкий пастельный",

            // Геометрия
            "GEOMETRIC_CIRCLES" to "Геометрические круги",
            "GEOMETRIC_TRIANGLES" to "Треугольные формы",
            "GEOMETRIC_HEXAGON" to "Шестиугольники",
            "MODERN_SPLIT" to "Современный split-экран",

            // Legacy
            "GEOMETRIC" to "Базовый геометрический (legacy)",
            "TECH" to "Базовый технологичный (legacy)",
            "NATURE" to "Базовая природа (legacy)",
            "ABSTRACT" to "Базовый абстрактный (legacy)"
        )
    }
}
