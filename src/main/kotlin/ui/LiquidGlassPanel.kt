package com.dumch.ui

import org.slf4j.LoggerFactory
import java.awt.*
import java.awt.event.*
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
        panel.add(textArea, BorderLayout.CENTER)
        window.contentPane.add(panel)

        val dragResizeListener = DragResizeListener()
        for (comp in listOf<Component>(window, panel, textArea)) {
            comp.addMouseListener(dragResizeListener)
            comp.addMouseMotionListener(dragResizeListener)
        }

        window.pack()
        window.setLocationRelativeTo(null)
    }

    private inner class DragResizeListener : MouseAdapter() {
        private val border = 10
        private var dragOffset: Point? = null
        private var resizeStart: Point? = null
        private var initialSize: Dimension? = null

        override fun mousePressed(e: MouseEvent) {
            val p = SwingUtilities.convertPoint(e.component, e.point, window)
            val size = window.size
            if (p.x >= size.width - border && p.y >= size.height - border) {
                resizeStart = e.locationOnScreen
                initialSize = Dimension(size)
            } else {
                dragOffset = p
            }
        }

        override fun mouseDragged(e: MouseEvent) {
            val screen = e.locationOnScreen
            resizeStart?.let { start ->
                val startSize = initialSize ?: return
                val dx = screen.x - start.x
                val dy = screen.y - start.y
                window.setSize(
                    maxOf(100, startSize.width + dx),
                    maxOf(50, startSize.height + dy)
                )
                window.revalidate()
                window.repaint()
            } ?: dragOffset?.let { offset ->
                window.setLocation(screen.x - offset.x, screen.y - offset.y)
            }
        }

        override fun mouseReleased(e: MouseEvent) {
            dragOffset = null
            resizeStart = null
            initialSize = null
        }

        override fun mouseMoved(e: MouseEvent) {
            val p = SwingUtilities.convertPoint(e.component, e.point, window)
            val size = window.size
            window.cursor = if (p.x >= size.width - border && p.y >= size.height - border) {
                Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR)
            } else {
                Cursor.getDefaultCursor()
            }
        }
    }

    fun showText(text: String) = SwingUtilities.invokeLater {
        textArea.text = text
        window.pack()
        window.isVisible = true
    }

    fun hide() = SwingUtilities.invokeLater {
        window.isVisible = false
    }
}

fun setAppIcon() {
    if (Taskbar.isTaskbarSupported()) {
        runCatching {
            {}::class.java.getResourceAsStream("/app_icon.png")?.use {
                Taskbar.getTaskbar().setIconImage(ImageIO.read(it))
            }
        }.onFailure { e ->
            l.warn("Failed to set app icon: ${e.message}")
        }
    }
}

