package ru.souz.tool.presentation

import org.apache.poi.sl.usermodel.ShapeType
import org.apache.poi.xslf.usermodel.XSLFSlide
import java.awt.Color
import java.awt.Rectangle
import java.util.Random

object PresentationDesignSystem {

    enum class DesignStyle {
        MINIMALIST_MODERN,
        CLEAN_LINES,
        SWISS_DESIGN,

        CORPORATE_BLUE,
        CORPORATE_ELEGANT,
        EXECUTIVE,

        CREATIVE_CHAOS,
        CREATIVE_SPLASH,
        ARTISTIC_FLOW,

        TECH_GRID,
        DIGITAL_WAVE,
        CYBERPUNK,

        NATURE_GREEN,
        OCEAN_BLUE,
        FOREST,

        SUNSET_GRADIENT,
        NEON_GRADIENT,
        SOFT_PASTEL,

        GEOMETRIC_CIRCLES,
        GEOMETRIC_TRIANGLES,
        GEOMETRIC_HEXAGON,
        MODERN_SPLIT,

        GEOMETRIC,
        TECH,
        NATURE,
        ABSTRACT
    }

    private data class Palette(
        val accent: Color,
        val secondary: Color
    )

    private data class ShapeSpec(
        val shapeType: ShapeType,
        val anchor: (Int, Int) -> Rectangle,
        val fillColor: (Palette) -> Color? = { null },
        val lineColor: (Palette) -> Color? = { null },
        val lineWidth: Double? = null,
        val rotation: Double? = null
    )

    private data class DesignSpec(
        val staticShapes: List<ShapeSpec> = emptyList(),
        val dynamicRenderer: DesignContext.() -> Unit = {}
    )

    private class DesignContext(
        val slide: XSLFSlide,
        val width: Int,
        val height: Int,
        val palette: Palette
    ) {
        fun draw(spec: ShapeSpec) {
            draw(
                shapeType = spec.shapeType,
                anchor = spec.anchor(width, height),
                fillColor = spec.fillColor(palette),
                lineColor = spec.lineColor(palette),
                lineWidth = spec.lineWidth,
                rotation = spec.rotation
            )
        }

        fun draw(
            shapeType: ShapeType,
            anchor: Rectangle,
            fillColor: Color? = null,
            lineColor: Color? = null,
            lineWidth: Double? = null,
            rotation: Double? = null
        ) {
            val shape = slide.createAutoShape()
            shape.shapeType = shapeType
            shape.anchor = anchor
            shape.fillColor = fillColor
            shape.lineColor = lineColor
            lineWidth?.let { shape.lineWidth = it }
            rotation?.let { shape.rotation = it }
        }
    }

    private val accentColor: (Palette) -> Color? = { it.accent }
    private val secondaryColor: (Palette) -> Color? = { it.secondary }

    private fun color(value: Color?): (Palette) -> Color? = { value }

    private fun alpha(base: (Palette) -> Color?, alpha: Int): (Palette) -> Color? = { palette ->
        base(palette)?.let { withAlpha(it, alpha) }
    }

    private fun spec(
        staticShapes: List<ShapeSpec> = emptyList(),
        dynamicRenderer: DesignContext.() -> Unit = {}
    ): DesignSpec = DesignSpec(staticShapes, dynamicRenderer)

    private fun shape(
        type: ShapeType,
        anchor: (Int, Int) -> Rectangle,
        fillColor: (Palette) -> Color? = { null },
        lineColor: (Palette) -> Color? = { null },
        lineWidth: Double? = null,
        rotation: Double? = null
    ): ShapeSpec = ShapeSpec(type, anchor, fillColor, lineColor, lineWidth, rotation)

    private fun rect(
        anchor: (Int, Int) -> Rectangle,
        fillColor: (Palette) -> Color? = { null },
        lineColor: (Palette) -> Color? = { null },
        lineWidth: Double? = null,
        rotation: Double? = null
    ): ShapeSpec = shape(ShapeType.RECT, anchor, fillColor, lineColor, lineWidth, rotation)

    private fun ellipse(
        anchor: (Int, Int) -> Rectangle,
        fillColor: (Palette) -> Color? = { null },
        lineColor: (Palette) -> Color? = { null },
        lineWidth: Double? = null,
        rotation: Double? = null
    ): ShapeSpec = shape(ShapeType.ELLIPSE, anchor, fillColor, lineColor, lineWidth, rotation)

    private fun rightTriangle(
        anchor: (Int, Int) -> Rectangle,
        fillColor: (Palette) -> Color? = { null },
        lineColor: (Palette) -> Color? = { null },
        lineWidth: Double? = null,
        rotation: Double? = null
    ): ShapeSpec = shape(ShapeType.RT_TRIANGLE, anchor, fillColor, lineColor, lineWidth, rotation)

    private val designSpecs: Map<DesignStyle, DesignSpec> = mapOf(
        DesignStyle.MINIMALIST_MODERN to spec(
            staticShapes = listOf(
                rect(
                    anchor = { w, h -> Rectangle(w / 12, h / 8, w / 5, 4) },
                    fillColor = accentColor
                ),
                ellipse(
                    anchor = { w, h -> Rectangle(w - w / 4, h - h / 4, w / 3, w / 3) },
                    fillColor = alpha(secondaryColor, 30)
                )
            )
        ),

        DesignStyle.CLEAN_LINES to spec(
            staticShapes = listOf(
                rect(
                    anchor = { w, h -> Rectangle(0, 0, w / 80, h) },
                    fillColor = accentColor
                )
            ),
            dynamicRenderer = {
                for (i in 0..2) {
                    draw(
                        shapeType = ShapeType.RECT,
                        anchor = Rectangle(width - width / 8 - (i * width / 40), height - height / 10, width / 80, width / 80),
                        fillColor = withAlpha(palette.accent, 255 - i * 40)
                    )
                }
            }
        ),

        DesignStyle.SWISS_DESIGN to spec(
            staticShapes = listOf(
                rect(
                    anchor = { w, h -> Rectangle(0, 0, w, h / 3) },
                    fillColor = color(Color.WHITE)
                ),
                rect(
                    anchor = { w, h -> Rectangle(0, h / 3, w, h * 2 / 3) },
                    fillColor = accentColor
                ),
                rect(
                    anchor = { w, h -> Rectangle(w - w / 6, h - h / 6, w / 8, w / 8) },
                    fillColor = color(Color.BLACK)
                )
            )
        ),

        DesignStyle.CORPORATE_BLUE to spec(
            staticShapes = listOf(
                rect(
                    anchor = { w, h -> Rectangle(0, 0, w, h) },
                    fillColor = secondaryColor
                ),
                ellipse(
                    anchor = { w, h -> Rectangle(w - w / 3, -h / 4, w / 2, w / 2) },
                    fillColor = color(withAlpha(Color.WHITE, 20))
                ),
                rect(
                    anchor = { w, h -> Rectangle(w / 16, h - h / 8, w / 2, 3) },
                    fillColor = accentColor
                )
            )
        ),

        DesignStyle.CORPORATE_ELEGANT to spec(
            staticShapes = listOf(
                rect(
                    anchor = { w, h -> Rectangle(0, 0, w, h) },
                    fillColor = secondaryColor
                ),
                rect(
                    anchor = { w, h -> Rectangle(0, 0, w / 3, h) },
                    fillColor = accentColor
                )
            ),
            dynamicRenderer = {
                for (i in 0..1) {
                    val size = width / 20
                    draw(
                        shapeType = ShapeType.RECT,
                        anchor = Rectangle(width - width / 8 - i * (size + width / 40), height - height / 10, size, size),
                        fillColor = null,
                        lineColor = if (i == 0) Color.WHITE else palette.accent,
                        lineWidth = 3.0
                    )
                }
            }
        ),

        DesignStyle.EXECUTIVE to spec(
            staticShapes = listOf(
                rect(
                    anchor = { w, h -> Rectangle(0, 0, w, h) },
                    fillColor = color(Color.BLACK)
                ),
                rect(
                    anchor = { w, h -> Rectangle(w / 12, h / 12, w - w / 6, 2) },
                    fillColor = secondaryColor
                ),
                rect(
                    anchor = { w, h -> Rectangle(w / 12, h / 12 + h / 15, w - w / 6, 2) },
                    fillColor = secondaryColor
                ),
                rect(
                    anchor = { w, h -> Rectangle(w / 12, h - h / 8, w / 4, 4) },
                    fillColor = accentColor
                ),
                rect(
                    anchor = { w, h -> Rectangle(w - w / 8, h - h / 8, w / 16, w / 16) },
                    fillColor = color(null),
                    lineColor = accentColor,
                    lineWidth = 4.0,
                    rotation = 45.0
                )
            )
        ),

        DesignStyle.CREATIVE_CHAOS to spec(
            staticShapes = listOf(
                rect(
                    anchor = { w, h -> Rectangle(0, 0, w, h) },
                    fillColor = color(Color(253, 224, 71))
                ),
                ellipse(
                    anchor = { w, h -> Rectangle(w / 12, -h / 8, w / 3, w / 3) },
                    fillColor = color(withAlpha(Color(236, 72, 153), 180))
                ),
                rect(
                    anchor = { w, h -> Rectangle(w - w / 6, h / 3, w / 6, w / 6) },
                    fillColor = color(Color(6, 182, 212)),
                    rotation = 45.0
                ),
                ellipse(
                    anchor = { w, h -> Rectangle(w / 4, h - h / 4, w / 3, w / 3) },
                    fillColor = color(withAlpha(Color(168, 85, 247), 150))
                )
            )
        ),

        DesignStyle.CREATIVE_SPLASH to spec(
            staticShapes = listOf(
                rect(
                    anchor = { w, h -> Rectangle(0, 0, w, h) },
                    fillColor = color(Color(147, 51, 234))
                ),
                ellipse(
                    anchor = { w, h -> Rectangle(w / 4, 0, w / 2, h) },
                    fillColor = color(withAlpha(Color(236, 72, 153), 180))
                ),
                ellipse(
                    anchor = { w, h -> Rectangle(w / 2, 0, w / 2, h / 2) },
                    fillColor = color(withAlpha(Color(251, 146, 60), 150))
                )
            ),
            dynamicRenderer = {
                val random = Random(42)
                for (i in 0..15) {
                    val size = 8 + random.nextInt(8)
                    draw(
                        shapeType = ShapeType.ELLIPSE,
                        anchor = Rectangle(random.nextInt(width), random.nextInt(height), size, size),
                        fillColor = withAlpha(Color.WHITE, 100 + random.nextInt(100))
                    )
                }
            }
        ),

        DesignStyle.ARTISTIC_FLOW to spec(
            staticShapes = listOf(
                rect(
                    anchor = { w, h -> Rectangle(0, 0, w, h) },
                    fillColor = color(Color(76, 29, 149))
                )
            ),
            dynamicRenderer = {
                for (i in 0..4) {
                    val yPos = height / 3 + i * height / 15
                    draw(
                        shapeType = ShapeType.ELLIPSE,
                        anchor = Rectangle(-width / 4 + i * width / 8, yPos, width / 2, height / 8),
                        fillColor = withAlpha(if (i % 2 == 0) Color.WHITE else Color(6, 182, 212), 40)
                    )
                }
            }
        ),

        DesignStyle.TECH_GRID to spec(
            staticShapes = listOf(
                rect(
                    anchor = { w, h -> Rectangle(0, 0, w, h) },
                    fillColor = color(Color.BLACK)
                )
            ),
            dynamicRenderer = {
                val gridColor = Color(6, 182, 212)
                val gridSpacing = 60

                for (i in 0 until height / gridSpacing) {
                    draw(
                        shapeType = ShapeType.RECT,
                        anchor = Rectangle(0, i * gridSpacing, width, 1),
                        fillColor = withAlpha(gridColor, 50)
                    )
                }

                for (i in 0 until width / gridSpacing) {
                    draw(
                        shapeType = ShapeType.RECT,
                        anchor = Rectangle(i * gridSpacing, 0, 1, height),
                        fillColor = withAlpha(gridColor, 50)
                    )
                }

                val frameSize = width / 4
                draw(
                    shapeType = ShapeType.RECT,
                    anchor = Rectangle(width - frameSize - width / 12, height / 12, frameSize, frameSize),
                    fillColor = null,
                    lineColor = gridColor,
                    lineWidth = 2.0
                )

                val corners = arrayOf(
                    Rectangle(width - frameSize - width / 12, height / 12, 15, 15),
                    Rectangle(width - width / 12 - 15, height / 12, 15, 15),
                    Rectangle(width - frameSize - width / 12, height / 12 + frameSize - 15, 15, 15),
                    Rectangle(width - width / 12 - 15, height / 12 + frameSize - 15, 15, 15)
                )
                for (corner in corners) {
                    draw(
                        shapeType = ShapeType.RECT,
                        anchor = corner,
                        fillColor = gridColor
                    )
                }
            }
        ),

        DesignStyle.DIGITAL_WAVE to spec(
            staticShapes = listOf(
                rect(
                    anchor = { w, h -> Rectangle(0, 0, w, h) },
                    fillColor = color(Color(37, 99, 235))
                ),
                ellipse(
                    anchor = { w, h -> Rectangle(w / 2, 0, w, h) },
                    fillColor = color(withAlpha(Color(124, 58, 237), 180))
                )
            ),
            dynamicRenderer = {
                for (i in 0..8) {
                    draw(
                        shapeType = ShapeType.RECT,
                        anchor = Rectangle(0, i * height / 10, width, 1),
                        fillColor = withAlpha(Color.WHITE, 30)
                    )
                }

                val random = Random(42)
                for (i in 0..7) {
                    val barHeight = height / 10 + random.nextInt(height / 6)
                    draw(
                        shapeType = ShapeType.RECT,
                        anchor = Rectangle(width - width / 6 + i * width / 70, height - height / 8 - barHeight, width / 100, barHeight),
                        fillColor = withAlpha(Color.WHITE, 128)
                    )
                }
            }
        ),

        DesignStyle.CYBERPUNK to spec(
            staticShapes = listOf(
                rect(
                    anchor = { w, h -> Rectangle(0, 0, w, h) },
                    fillColor = color(Color.BLACK)
                ),
                ellipse(
                    anchor = { w, h -> Rectangle(-w / 4, -h / 4, w / 2, h / 2) },
                    fillColor = color(withAlpha(Color(236, 72, 153), 40))
                ),
                ellipse(
                    anchor = { w, h -> Rectangle(w / 2, h / 2, w, h) },
                    fillColor = color(withAlpha(Color(6, 182, 212), 40))
                ),
                rect(
                    anchor = { w, h -> Rectangle(w / 40, h / 40, w - w / 20, h / 25) },
                    fillColor = color(withAlpha(Color(236, 72, 153), 30))
                ),
                rect(
                    anchor = { w, h -> Rectangle(0, h - 4, w, 4) },
                    fillColor = color(Color(236, 72, 153))
                )
            )
        ),

        DesignStyle.NATURE_GREEN to spec(
            staticShapes = listOf(
                rect(
                    anchor = { w, h -> Rectangle(0, 0, w, h) },
                    fillColor = color(Color(236, 253, 245))
                ),
                ellipse(
                    anchor = { w, h -> Rectangle(w - w / 3, -h / 4, w / 2, w / 2) },
                    fillColor = color(withAlpha(Color(52, 211, 153), 50))
                ),
                ellipse(
                    anchor = { w, h -> Rectangle(-w / 6, h - h / 3, w / 3, w / 3) },
                    fillColor = color(withAlpha(Color(20, 184, 166), 50))
                )
            ),
            dynamicRenderer = {
                val colors = arrayOf(
                    Color(16, 185, 129),
                    Color(20, 184, 166),
                    Color(34, 197, 94)
                )
                for (i in colors.indices) {
                    val size = width / 25
                    draw(
                        shapeType = ShapeType.ELLIPSE,
                        anchor = Rectangle(width - width / 8 - i * (size + width / 50), height - height / 10, size, size),
                        fillColor = colors[i]
                    )
                }
            }
        ),

        DesignStyle.OCEAN_BLUE to spec(
            staticShapes = listOf(
                rect(
                    anchor = { w, h -> Rectangle(0, 0, w, h / 3) },
                    fillColor = color(Color(125, 211, 252))
                ),
                rect(
                    anchor = { w, h -> Rectangle(0, h / 3, w, h / 3) },
                    fillColor = color(Color(59, 130, 246))
                ),
                rect(
                    anchor = { w, h -> Rectangle(0, h * 2 / 3, w, h / 3) },
                    fillColor = color(Color(37, 99, 235))
                )
            ),
            dynamicRenderer = {
                for (i in 0..4) {
                    draw(
                        shapeType = ShapeType.ELLIPSE,
                        anchor = Rectangle(-width / 4, height / 2 + i * height / 15, width * 3 / 2, height / 8),
                        fillColor = withAlpha(Color.WHITE, 20)
                    )
                }
            }
        ),

        DesignStyle.FOREST to spec(
            staticShapes = listOf(
                rect(
                    anchor = { w, h -> Rectangle(0, 0, w, h) },
                    fillColor = color(Color(20, 83, 45))
                ),
                rect(
                    anchor = { w, h -> Rectangle(0, h / 3, w, h * 2 / 3) },
                    fillColor = color(Color(21, 128, 61))
                )
            ),
            dynamicRenderer = {
                val random = Random(42)
                for (i in 0..7) {
                    val treeWidth = 40 + random.nextInt(40)
                    val treeHeight = 100 + random.nextInt(100)
                    draw(
                        shapeType = ShapeType.TRIANGLE,
                        anchor = Rectangle(i * width / 8 + random.nextInt(30), height - treeHeight, treeWidth, treeHeight),
                        fillColor = withAlpha(Color(6, 78, 59), 100)
                    )
                }
            }
        ),

        DesignStyle.SUNSET_GRADIENT to spec(
            staticShapes = listOf(
                rect(
                    anchor = { w, h -> Rectangle(0, 0, w, h / 3) },
                    fillColor = color(Color(251, 146, 60))
                ),
                rect(
                    anchor = { w, h -> Rectangle(0, h / 3, w, h / 3) },
                    fillColor = color(Color(236, 72, 153))
                ),
                rect(
                    anchor = { w, h -> Rectangle(0, h * 2 / 3, w, h / 3) },
                    fillColor = color(Color(147, 51, 234))
                ),
                rect(
                    anchor = { w, h -> Rectangle(0, h * 2 / 3, w, h / 3) },
                    fillColor = color(withAlpha(Color.BLACK, 80))
                )
            )
        ),

        DesignStyle.NEON_GRADIENT to spec(
            staticShapes = listOf(
                rect(
                    anchor = { w, h -> Rectangle(0, 0, w, h) },
                    fillColor = color(Color.BLACK)
                ),
                ellipse(
                    anchor = { w, h -> Rectangle(-w / 4, -h / 4, w, h) },
                    fillColor = color(withAlpha(Color(236, 72, 153), 200))
                ),
                ellipse(
                    anchor = { w, h -> Rectangle(w / 4, h / 4, w, h) },
                    fillColor = color(withAlpha(Color(168, 85, 247), 200))
                ),
                ellipse(
                    anchor = { w, h -> Rectangle(w / 2, -h / 4, w / 2, h) },
                    fillColor = color(withAlpha(Color(6, 182, 212), 180))
                ),
                ellipse(
                    anchor = { w, h -> Rectangle(0, h / 2, w / 3, h / 2) },
                    fillColor = color(withAlpha(Color(250, 204, 21), 100))
                )
            )
        ),

        DesignStyle.SOFT_PASTEL to spec(
            staticShapes = listOf(
                rect(
                    anchor = { w, h -> Rectangle(0, 0, w, h) },
                    fillColor = color(Color(252, 231, 243))
                )
            ),
            dynamicRenderer = {
                val colors = arrayOf(
                    Color(252, 231, 243),
                    Color(233, 213, 255),
                    Color(219, 234, 254)
                )

                val positions = arrayOf(
                    Rectangle(width - width / 3, -height / 4, width / 2, width / 2),
                    Rectangle(-width / 6, height - height / 3, width / 3, width / 3),
                    Rectangle(width / 3, height / 4, width / 3, width / 3)
                )

                for (i in colors.indices) {
                    for (layer in 0..3) {
                        val offset = layer * 10
                        val position = positions[i]
                        draw(
                            shapeType = ShapeType.ELLIPSE,
                            anchor = Rectangle(
                                position.x - offset,
                                position.y - offset,
                                position.width + offset * 2,
                                position.height + offset * 2
                            ),
                            fillColor = withAlpha(colors[i], 80 - layer * 15)
                        )
                    }
                }
            }
        ),

        DesignStyle.GEOMETRIC_CIRCLES to spec(
            staticShapes = listOf(
                rect(
                    anchor = { w, h -> Rectangle(0, 0, w, h) },
                    fillColor = color(Color(15, 23, 42))
                )
            ),
            dynamicRenderer = {
                val rings = arrayOf(
                    Triple(width / 3, height / 4, Color(250, 204, 21)),
                    Triple(width / 4, height * 3 / 4, Color(6, 182, 212))
                )

                for ((x, y, ringColor) in rings) {
                    draw(
                        shapeType = ShapeType.ELLIPSE,
                        anchor = Rectangle(x, y, width / 4, width / 4),
                        fillColor = null,
                        lineColor = ringColor,
                        lineWidth = 8.0
                    )
                }

                draw(
                    shapeType = ShapeType.ELLIPSE,
                    anchor = Rectangle(width / 2 - width / 16, height / 2 - width / 16, width / 8, width / 8),
                    fillColor = Color(236, 72, 153)
                )
            }
        ),

        DesignStyle.GEOMETRIC_TRIANGLES to spec(
            staticShapes = listOf(
                rect(
                    anchor = { w, h -> Rectangle(0, 0, w, h) },
                    fillColor = color(Color.WHITE)
                ),
                rightTriangle(
                    anchor = { w, h -> Rectangle(w - w / 3, 0, w / 3, w / 3) },
                    fillColor = color(withAlpha(Color(59, 130, 246), 50)),
                    rotation = 0.0
                ),
                rightTriangle(
                    anchor = { w, h -> Rectangle(0, h - h / 4, h / 4, h / 4) },
                    fillColor = color(withAlpha(Color(239, 68, 68), 50)),
                    rotation = 180.0
                )
            )
        ),

        DesignStyle.GEOMETRIC_HEXAGON to spec(
            staticShapes = listOf(
                rect(
                    anchor = { w, h -> Rectangle(0, 0, w, h) },
                    fillColor = color(Color(99, 102, 241))
                ),
                ellipse(
                    anchor = { w, h -> Rectangle(w / 2, h / 2, w, h) },
                    fillColor = color(withAlpha(Color(168, 85, 247), 180))
                )
            ),
            dynamicRenderer = {
                val hexPositions = arrayOf(
                    Pair(width / 5, height / 5),
                    Pair(width / 2, height / 5),
                    Pair(width * 4 / 5, height / 5),
                    Pair(width / 5, height / 2),
                    Pair(width / 2, height / 2),
                    Pair(width * 4 / 5, height / 2)
                )

                for ((x, y) in hexPositions) {
                    draw(
                        shapeType = ShapeType.HEXAGON,
                        anchor = Rectangle(x, y, width / 8, width / 8),
                        fillColor = null,
                        lineColor = withAlpha(Color.WHITE, 50),
                        lineWidth = 2.0
                    )
                }
            }
        ),

        DesignStyle.MODERN_SPLIT to spec(
            staticShapes = listOf(
                rect(
                    anchor = { w, h -> Rectangle(0, 0, w / 2, h) },
                    fillColor = color(Color(147, 51, 234))
                ),
                rect(
                    anchor = { w, h -> Rectangle(w / 2, 0, w / 2, h) },
                    fillColor = color(Color(250, 204, 21))
                )
            )
        ),

        DesignStyle.GEOMETRIC to spec(
            staticShapes = listOf(
                ellipse(
                    anchor = { w, h -> Rectangle(w - 200, -100, 400, 400) },
                    fillColor = alpha(accentColor, 40)
                ),
                rightTriangle(
                    anchor = { w, h -> Rectangle(0, h - 150, 150, 150) },
                    fillColor = alpha(secondaryColor, 60),
                    rotation = 180.0
                )
            )
        ),

        DesignStyle.TECH to spec(
            staticShapes = listOf(
                rect(
                    anchor = { w, h -> Rectangle(20, 20, w - 40, 5) },
                    fillColor = accentColor
                )
            ),
            dynamicRenderer = {
                for (i in 0..2) {
                    draw(
                        shapeType = ShapeType.RECT,
                        anchor = Rectangle(width - 30 - (i * 15), 10, 8, 8),
                        fillColor = palette.secondary
                    )
                }

                for (i in 0..4) {
                    draw(
                        shapeType = ShapeType.RECT,
                        anchor = Rectangle(width - 100, height - 20 - (i * 10), 80, 1),
                        fillColor = withAlpha(palette.accent, 100)
                    )
                }
            }
        ),

        DesignStyle.NATURE to spec(
            staticShapes = listOf(
                ellipse(
                    anchor = { _, _ -> Rectangle(-50, -50, 250, 180) },
                    fillColor = color(withAlpha(Color(0, 128, 0), 30))
                ),
                ellipse(
                    anchor = { w, h -> Rectangle(w - 200, h - 150, 300, 250) },
                    fillColor = alpha(accentColor, 40)
                )
            )
        ),

        DesignStyle.ABSTRACT to spec(
            dynamicRenderer = {
                val random = Random()
                for (i in 0..5) {
                    val shapeType = if (random.nextBoolean()) ShapeType.ELLIPSE else ShapeType.RECT
                    val size = 20 + random.nextInt(60)
                    draw(
                        shapeType = shapeType,
                        anchor = Rectangle(random.nextInt(width), random.nextInt(height), size, size),
                        fillColor = withAlpha(palette.accent, 20)
                    )
                }
            }
        )
    )

    fun applyDesign(slide: XSLFSlide, designId: String, theme: PresentationTheme?) {
        val style = try {
            DesignStyle.valueOf(designId.uppercase())
        } catch (e: Exception) {
            return
        }

        val palette = Palette(
            accent = theme?.accentColor ?: Color.BLUE,
            secondary = theme?.titleColor ?: Color.DARK_GRAY
        )

        val designSpec = designSpecs[style] ?: return
        val context = DesignContext(
            slide = slide,
            width = slide.slideShow.pageSize.width,
            height = slide.slideShow.pageSize.height,
            palette = palette
        )

        designSpec.staticShapes.forEach { context.draw(it) }
        designSpec.dynamicRenderer.invoke(context)
    }

    private fun withAlpha(color: Color, alpha: Int): Color {
        return Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))
    }

    fun getAvailableDesigns(): Map<String, String> {
        return mapOf(
            "MINIMALIST_MODERN" to "Минималистичный современный дизайн",
            "CLEAN_LINES" to "Чистые линии и элегантность",
            "SWISS_DESIGN" to "Швейцарская школа типографики",

            "CORPORATE_BLUE" to "Профессиональный синий стиль",
            "CORPORATE_ELEGANT" to "Элегантный корпоративный дизайн",
            "EXECUTIVE" to "Стиль для руководителей",

            "CREATIVE_CHAOS" to "Креативный хаос ярких цветов",
            "CREATIVE_SPLASH" to "Взрыв креативности",
            "ARTISTIC_FLOW" to "Художественные плавные формы",

            "TECH_GRID" to "Технологичная сетка",
            "DIGITAL_WAVE" to "Цифровые волны",
            "CYBERPUNK" to "Футуристичный киберпанк",

            "NATURE_GREEN" to "Природные зеленые оттенки",
            "OCEAN_BLUE" to "Глубина океана",
            "FOREST" to "Лесная тематика",

            "SUNSET_GRADIENT" to "Градиент заката",
            "NEON_GRADIENT" to "Неоновый градиент",
            "SOFT_PASTEL" to "Мягкий пастельный",

            "GEOMETRIC_CIRCLES" to "Геометрические круги",
            "GEOMETRIC_TRIANGLES" to "Треугольные формы",
            "GEOMETRIC_HEXAGON" to "Шестиугольники",
            "MODERN_SPLIT" to "Современный split-экран",

            "GEOMETRIC" to "Базовый геометрический (legacy)",
            "TECH" to "Базовый технологичный (legacy)",
            "NATURE" to "Базовая природа (legacy)",
            "ABSTRACT" to "Базовый абстрактный (legacy)"
        )
    }
}
