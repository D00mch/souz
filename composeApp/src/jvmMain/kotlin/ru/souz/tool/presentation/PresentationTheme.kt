package ru.souz.tool.presentation

import java.awt.Color

enum class PresentationTheme(
    val backgroundColor: Color,
    val titleColor: Color,
    val contentColor: Color,
    val accentColor: Color = titleColor,
    val titleFont: String = "Arial",
    val bodyFont: String = "Arial"
) {
    // === 1. BASIC / ESSENTIAL ===
    LIGHT(
        backgroundColor = Color.WHITE,
        titleColor = Color.BLACK,
        contentColor = Color.DARK_GRAY,
        accentColor = Color(0, 120, 215) // Blue accent
    ),
    DARK(
        backgroundColor = Color(30, 30, 30),
        titleColor = Color.WHITE,
        contentColor = Color(200, 200, 200),
        accentColor = Color(255, 165, 0) // Orange accent
    ),
    MINIMALIST(
        backgroundColor = Color(250, 250, 250),
        titleColor = Color.BLACK,
        contentColor = Color(60, 60, 60),
        titleFont = "Helvetica",
        bodyFont = "Helvetica Light"
    ),

    // === 2. BUSINESS / CORPORATE ===
    BLUE(
        backgroundColor = Color(0, 51, 102),
        titleColor = Color(255, 215, 0),
        contentColor = Color.WHITE,
        accentColor = Color(255, 215, 0)
    ),
    EXECUTIVE(
        backgroundColor = Color(20, 30, 48), // Deep Navy
        titleColor = Color(192, 192, 192), // Silver
        contentColor = Color.WHITE,
        titleFont = "Times New Roman"
    ),
    CORPORATE(
        backgroundColor = Color(240, 240, 240),
        titleColor = Color(0, 51, 153),
        contentColor = Color.BLACK,
        accentColor = Color(204, 0, 0) // Red accent
    ),
    CONSULTING(
        backgroundColor = Color.WHITE,
        titleColor = Color(24, 34, 54),
        contentColor = Color(58, 68, 84),
        accentColor = Color(13, 92, 155),
        titleFont = "Arial",
        bodyFont = "Arial"
    ),
    PROFESSIONAL_TEAL(
        backgroundColor = Color.WHITE,
        titleColor = Color(0, 128, 128),
        contentColor = Color.DARK_GRAY,
        accentColor = Color(0, 128, 128)
    ),
    FINANCE(
        backgroundColor = Color(245, 245, 255),
        titleColor = Color(0, 0, 139), // DarkBlue
        contentColor = Color.BLACK
    ),

    // === 3. TECH / STARTUP ===
    STARTUP(
        backgroundColor = Color(18, 18, 18),
        titleColor = Color(118, 255, 3), // Neon Green
        contentColor = Color(240, 240, 240),
        titleFont = "Courier New",
        accentColor = Color(118, 255, 3)
    ),
    PITCH(
        backgroundColor = Color.WHITE,
        titleColor = Color(255, 64, 129), // Pink
        contentColor = Color(33, 33, 33),
        titleFont = "Impact",
        accentColor = Color(255, 64, 129)
    ),
    TECHNICAL(
        backgroundColor = Color(240, 248, 255), // Alice Blue
        titleColor = Color(0, 96, 100), // Dark Cyan
        contentColor = Color(38, 50, 56),
        titleFont = "Consolas",
        bodyFont = "Consolas"
    ),
    CYBERPUNK(
        backgroundColor = Color(10, 10, 20),
        titleColor = Color(0, 255, 255), // Cyan
        contentColor = Color(255, 0, 255), // Magenta
        accentColor = Color(255, 255, 0) // Yellow
    ),
    MATRIX(
        backgroundColor = Color.BLACK,
        titleColor = Color(0, 255, 0), // Bright Green
        contentColor = Color(200, 255, 200),
        titleFont = "Courier New"
    ),
    SAAS(
        backgroundColor = Color(250, 250, 255),
        titleColor = Color(100, 50, 250), // Blurple
        contentColor = Color.DARK_GRAY,
        accentColor = Color(50, 200, 150)
    ),

    // === 4. NATURE / ORGANIC ===
    NATURE(
        backgroundColor = Color(245, 245, 220), // Beige
        titleColor = Color(46, 125, 50), // Forest Green
        contentColor = Color(62, 39, 35),
        accentColor = Color(139, 69, 19)
    ),
    OCEAN(
        backgroundColor = Color(224, 255, 255), // Light Cyan
        titleColor = Color(0, 105, 148), // Sea Blue
        contentColor = Color(0, 51, 102),
        accentColor = Color(0, 191, 255)
    ),
    SUNSET(
        backgroundColor = Color(255, 229, 180), // Peach
        titleColor = Color(255, 69, 0), // Red Orange
        contentColor = Color(139, 0, 0),
        accentColor = Color(255, 215, 0)
    ),
    FOREST(
        backgroundColor = Color(20, 40, 20),
        titleColor = Color(144, 238, 144), // Light Green
        contentColor = Color(240, 255, 240)
    ),
    ROCKY(
        backgroundColor = Color(169, 169, 169), // Dark Gray
        titleColor = Color(47, 79, 79), // Dark Slate Gray
        contentColor = Color.BLACK
    ),

    // === 5. CREATIVE / ARTISTIC ===
    VIBRANT(
        backgroundColor = Color(255, 0, 100), // Hot Pink
        titleColor = Color.YELLOW,
        contentColor = Color.WHITE,
        titleFont = "Comic Sans MS" // Use carefully
    ),
    PASTEL(
        backgroundColor = Color(253, 253, 250),
        titleColor = Color(180, 160, 255), // Pastel Purple
        contentColor = Color(110, 110, 110),
        accentColor = Color(255, 182, 193) // Pastel Pink
    ),
    RETRO(
        backgroundColor = Color(255, 228, 196), // Bisque
        titleColor = Color(139, 69, 19), // Saddle Brown
        contentColor = Color(160, 82, 45),
        titleFont = "Georgia"
    ),
    MONOCHROME(
        backgroundColor = Color.LIGHT_GRAY,
        titleColor = Color.BLACK,
        contentColor = Color.DARK_GRAY,
        accentColor = Color.BLACK
    ),
    ELEGANT(
        backgroundColor = Color(20, 20, 20),
        titleColor = Color(212, 175, 55), // Gold
        contentColor = Color(240, 240, 240),
        titleFont = "Garamond"
    ),

    // === 6. MISC / EXPERIMENTAL ===
    HACKER(
        backgroundColor = Color.BLACK,
        titleColor = Color.RED,
        contentColor = Color.GREEN,
        titleFont = "Terminal"
    ),
    SPACE(
        backgroundColor = Color(10, 10, 40),
        titleColor = Color(200, 200, 255),
        contentColor = Color.WHITE,
        accentColor = Color(100, 50, 200)
    ),
    CANDY(
        backgroundColor = Color(255, 240, 245), // Lavender Blush
        titleColor = Color(255, 20, 147), // Deep Pink
        contentColor = Color(75, 0, 130) // Indigo
    ),
    MIDNIGHT(
        backgroundColor = Color(25, 25, 112), // Midnight Blue
        titleColor = Color(173, 216, 230), // Light Blue
        contentColor = Color.WHITE
    ),
    COFFEE(
        backgroundColor = Color(210, 180, 140), // Tan
        titleColor = Color(101, 67, 33), // Dark Brown
        contentColor = Color(60, 40, 20)
    ),
    NEON_NIGHT(
        backgroundColor = Color(10, 0, 20),
        titleColor = Color(0, 255, 200), // Teal Neon
        contentColor = Color(255, 0, 255) // Magenta Neon
    ),
    WARMTH(
        backgroundColor = Color(255, 250, 205), // Lemon Chiffon
        titleColor = Color(255, 140, 0), // Dark Orange
        contentColor = Color(139, 69, 19)
    )
}
