package ru.souz.ui.common

import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window

/**
 * Applies minimum window size constraints and repositions window if needed to stay within screen bounds.
 *
 * @return the original minimum size to restore on dispose
 */
fun applyMinWindowSize(window: Window, minWidth: Int, minHeight: Int): Dimension? {
    val originalMinSize = window.minimumSize
    val newMinSize = Dimension(minWidth, minHeight)

    val currentWidth = window.width
    val currentHeight = window.height

    if (currentWidth < minWidth || currentHeight < minHeight) {
        val targetWidth = maxOf(currentWidth, minWidth)
        val targetHeight = maxOf(currentHeight, minHeight)

        val windowCenter = Point(window.x + currentWidth / 2, window.y + currentHeight / 2)
        val config = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { it.defaultConfiguration }
            .firstOrNull { it.bounds.contains(windowCenter) }
            ?: window.graphicsConfiguration

        val screenBounds = config.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        val usableX = screenBounds.x + insets.left
        val usableY = screenBounds.y + insets.top
        val usableWidth = screenBounds.width - (insets.left + insets.right)
        val usableHeight = screenBounds.height - (insets.top + insets.bottom)

        val finalWidth = minOf(targetWidth, usableWidth)
        val finalHeight = minOf(targetHeight, usableHeight)
        val newX = (window.x).coerceIn(usableX, usableX + usableWidth - finalWidth)
        val newY = (window.y).coerceIn(usableY, usableY + usableHeight - finalHeight)

        window.setBounds(newX, newY, finalWidth, finalHeight)
    }

    window.minimumSize = newMinSize
    return originalMinSize
}
