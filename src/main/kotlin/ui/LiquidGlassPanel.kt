package com.dumch.ui

import org.slf4j.LoggerFactory
import java.awt.*
import java.awt.event.*
import java.util.EnumSet
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.border.EmptyBorder

private val l = LoggerFactory.getLogger("UI")

class LiquidGlassPanel {
    private val window = JWindow().apply {
        isAlwaysOnTop = true
        background = Color(0, 0, 0, 0)
    }

    private val textArea = JTextArea().apply {
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        foreground = Color.WHITE
        font = Font("SanSerif", Font.PLAIN, 16)
        isEditable = false
    }

    init {
        val panel = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.composite = AlphaComposite.SrcOver.derive(0.6f)
                g2.paint = GradientPaint(0f, 0f, Color(255, 255, 255, 200), 0f, height.toFloat(), Color(255, 255, 255, 100))
                g2.fillRoundRect(0, 0, width, height, 20, 20)
            }
        }
        panel.isOpaque = false
        panel.border = EmptyBorder(16, 20, 16, 20)
        panel.layout = BorderLayout()
        val scrollPane = JScrollPane(textArea).apply {
            isOpaque = false
            border = null
            viewport.isOpaque = false
            viewportBorder = null
            background = Color(0, 0, 0, 0)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        panel.add(scrollPane, BorderLayout.CENTER)
        window.contentPane.add(panel)

        val dragResizeListener = DragResizeListener()
        for (comp in listOf<Component>(window, panel, scrollPane, scrollPane.viewport, textArea)) {
            comp.addMouseListener(dragResizeListener)
            comp.addMouseMotionListener(dragResizeListener)
        }

        window.pack()
        window.setLocationRelativeTo(null)
    }

    private enum class ResizeDirection { LEFT, RIGHT, TOP, BOTTOM }

    private inner class DragResizeListener : MouseAdapter() {
        private val border = 10
        private var dragOffset: Point? = null
        private var resizeStart: Point? = null
        private var initialBounds: Rectangle? = null
        private var resizeDirections: EnumSet<ResizeDirection>? = null

        private fun detectResizeDirections(point: Point): EnumSet<ResizeDirection> {
            val size = window.size
            val directions = EnumSet.noneOf(ResizeDirection::class.java)
            val isLeft = point.x <= border
            val isRight = point.x >= size.width - border
            val isTop = point.y <= border
            val isBottom = point.y >= size.height - border

            if (isLeft) directions.add(ResizeDirection.LEFT) else if (isRight) directions.add(ResizeDirection.RIGHT)
            if (isTop) directions.add(ResizeDirection.TOP) else if (isBottom) directions.add(ResizeDirection.BOTTOM)

            return directions
        }

        private fun getCursorForDirections(directions: EnumSet<ResizeDirection>): Cursor {
            val hasLeft = directions.contains(ResizeDirection.LEFT)
            val hasRight = directions.contains(ResizeDirection.RIGHT)
            val hasTop = directions.contains(ResizeDirection.TOP)
            val hasBottom = directions.contains(ResizeDirection.BOTTOM)

            return when {
                hasLeft && hasTop -> Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR)
                hasRight && hasTop -> Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR)
                hasLeft && hasBottom -> Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR)
                hasRight && hasBottom -> Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR)
                hasLeft -> Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)
                hasRight -> Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
                hasTop -> Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
                hasBottom -> Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR)
                else -> Cursor.getDefaultCursor()
            }
        }

        override fun mousePressed(e: MouseEvent) {
            val p = SwingUtilities.convertPoint(e.component, e.point, window)
            val directions = detectResizeDirections(p)
            if (!directions.isEmpty()) {
                resizeDirections = directions
                resizeStart = e.locationOnScreen
                initialBounds = window.bounds
            } else {
                dragOffset = p
                resizeDirections = null
                resizeStart = null
                initialBounds = null
            }
        }

        override fun mouseDragged(e: MouseEvent) {
            val screen = e.locationOnScreen
            resizeDirections?.let { directions ->
                val start = resizeStart ?: return
                val startBounds = initialBounds ?: return
                val dx = screen.x - start.x
                val dy = screen.y - start.y

                var newX = startBounds.x
                var newY = startBounds.y
                var newWidth = startBounds.width
                var newHeight = startBounds.height

                if (directions.contains(ResizeDirection.LEFT)) {
                    val maxX = startBounds.x + startBounds.width - MIN_WIDTH
                    val proposedX = (startBounds.x + dx).coerceAtMost(maxX)
                    newX = proposedX
                    newWidth = (startBounds.x + startBounds.width) - proposedX
                }

                if (directions.contains(ResizeDirection.RIGHT)) {
                    newWidth = maxOf(MIN_WIDTH, startBounds.width + dx)
                }

                if (directions.contains(ResizeDirection.TOP)) {
                    val maxY = startBounds.y + startBounds.height - MIN_HEIGHT
                    val proposedY = (startBounds.y + dy).coerceAtMost(maxY)
                    newY = proposedY
                    newHeight = (startBounds.y + startBounds.height) - proposedY
                }

                if (directions.contains(ResizeDirection.BOTTOM)) {
                    newHeight = maxOf(MIN_HEIGHT, startBounds.height + dy)
                }

                if (newWidth < MIN_WIDTH) {
                    val correction = MIN_WIDTH - newWidth
                    if (directions.contains(ResizeDirection.LEFT)) {
                        newX -= correction
                    }
                    newWidth = MIN_WIDTH
                }

                if (newHeight < MIN_HEIGHT) {
                    val correction = MIN_HEIGHT - newHeight
                    if (directions.contains(ResizeDirection.TOP)) {
                        newY -= correction
                    }
                    newHeight = MIN_HEIGHT
                }

                window.setBounds(newX, newY, newWidth, newHeight)
                window.revalidate()
                window.repaint()
            } ?: dragOffset?.let { offset ->
                window.setLocation(screen.x - offset.x, screen.y - offset.y)
            }
        }

        override fun mouseReleased(e: MouseEvent) {
            dragOffset = null
            resizeStart = null
            initialBounds = null
            resizeDirections = null
        }

        override fun mouseMoved(e: MouseEvent) {
            val p = SwingUtilities.convertPoint(e.component, e.point, window)
            val directions = detectResizeDirections(p)
            window.cursor = if (directions.isEmpty()) Cursor.getDefaultCursor() else getCursorForDirections(directions)
        }
    }

    fun showText(text: String) = SwingUtilities.invokeLater {
        textArea.text = text
        textArea.caretPosition = 0
        window.pack()
        window.isVisible = true
    }

    fun hide() = SwingUtilities.invokeLater {
        window.isVisible = false
    }
}

private const val MIN_WIDTH = 100
private const val MIN_HEIGHT = 50

fun setAppIcon() {
    if (Taskbar.isTaskbarSupported()) {
        runCatching {
            {}::class.java.getResourceAsStream("/icon.png")?.use {
                Taskbar.getTaskbar().setIconImage(ImageIO.read(it))
            }
        }.onFailure { e ->
            l.warn("Failed to set app icon: ${e.message}")
        }
    }
}

