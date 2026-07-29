package ru.souz.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

private val DarkSouzColors = createSouzColors(isDark = true, scheme = DarkColors)
private val LightSouzColors = createSouzColors(isDark = false, scheme = LightColors)

internal fun souzColors(isDark: Boolean): SouzColors =
    if (isDark) DarkSouzColors else LightSouzColors

private fun createSouzColors(isDark: Boolean, scheme: ColorScheme): SouzColors = SouzColors(
    glass = if (isDark) {
        GlassColors(
            heroBackground = listOf(Color(0xF21B1C20), Color(0xF21B1C20)),
            heroOverlay = Color(0xB0000000),
            heroBorder = listOf(
                Color(0x3AFFFFFF),
                Color(0x18FFFFFF),
                Color(0x14FFFFFF),
                Color(0x2AFFFFFF),
            ),
            innerBorder = Color(0x08FFFFFF),
            textPrimary = Color(0xD9FFFFFF),
        )
    } else {
        GlassColors(
            heroBackground = listOf(Color.White, Color(0xFFF3F3F0)),
            heroOverlay = Color.Transparent,
            heroBorder = listOf(
                Color(0x1F1F1F1D),
                Color(0x0A1F1F1D),
                Color(0x0A1F1F1D),
                Color(0x1F1F1F1D),
            ),
            innerBorder = Color(0x061F1F1D),
            textPrimary = Color(0xE61F1F1D),
        )
    },
    chat = ChatColors(
        userBubbleBackground = if (isDark) Color(0x5C3F434A) else scheme.surfaceVariant,
        userBubbleBorder = if (isDark) Color(0x29FFFFFF) else scheme.outlineVariant,
        pathChipBackground = if (isDark) Color(0x2625CAB0) else scheme.surfaceVariant,
        pathChipBorder = if (isDark) Color(0x8812E0B5) else scheme.outlineVariant,
        pathChipContent = if (isDark) Color(0xFF12E0B5) else scheme.onSurface,
    ),
    settings = SettingsColors(
        contentBackground = if (isDark) Color.Transparent else scheme.background,
        sidebarBackground = if (isDark) Color(0x5B3C4148) else Color(0xFFF1F1EE),
        sidebarBorder = if (isDark) Color(0x14FFFFFF) else scheme.outlineVariant,
        selectedNavigationBackground = if (isDark) Color(0x1FFFFFFF) else scheme.primaryContainer,
        selectedNavigationContent = if (isDark) Color.White else scheme.onPrimaryContainer,
        navigationContent = if (isDark) Color(0x99FFFFFF) else scheme.onSurfaceVariant,
        hoverBackground = if (isDark) Color(0x0DFFFFFF) else Color(0xFFE7E7E3),
        hoverContent = if (isDark) Color(0xE6FFFFFF) else scheme.onSurface,
        inputBackground = if (isDark) Color(0x0DFFFFFF) else scheme.surface,
        inputBorder = if (isDark) Color(0x14FFFFFF) else scheme.outlineVariant,
        content = if (isDark) Color(0xE6FFFFFF) else scheme.onSurface,
        secondaryContent = if (isDark) Color(0x80FFFFFF) else scheme.onSurfaceVariant,
        switchTrack = if (isDark) Color(0x24FFFFFF) else Color(0xFFD4D4CE),
        checkedSwitchTrack = scheme.primary,
        switchThumb = if (isDark) scheme.onPrimary else Color.White,
        segmentContainer = if (isDark) Color(0x14FFFFFF) else Color(0xFFE7E7E3),
        selectedSegmentBackground = if (isDark) Color(0x26FFFFFF) else scheme.primaryContainer,
        selectedSegmentContent = if (isDark) Color.White else scheme.onPrimaryContainer,
        segmentContent = if (isDark) Color(0x80FFFFFF) else scheme.onSurfaceVariant,
        primaryActionContainer = scheme.primary,
        primaryActionContent = scheme.onPrimary,
        secondaryActionContainer = if (isDark) Color(0x0DFFFFFF) else scheme.surface,
        secondaryActionBorder = if (isDark) Color(0x14FFFFFF) else scheme.outline,
        secondaryActionContent = if (isDark) Color(0xB3FFFFFF) else scheme.onSurface,
        secondaryActionHoverContainer = if (isDark) Color(0x14FFFFFF) else scheme.surfaceVariant,
        secondaryActionHoverBorder = if (isDark) Color(0x1FFFFFFF) else scheme.outline,
        destructiveActionContainer = Color.Transparent,
        destructiveActionBorder = scheme.error.copy(alpha = 0.4f),
        destructiveActionContent = scheme.error,
        accent = if (isDark) Color(0x99FFFFFF) else scheme.primary,
        accentHover = if (isDark) Color(0xCCFFFFFF) else scheme.primary.copy(alpha = 0.8f),
    ),
    graph = GraphColors(
        canvasBackground = Color.Transparent,
        nodeBackground = if (isDark) Color(0xFF1E1E1E) else scheme.surface,
        selectedNodeBackground = if (isDark) Color(0xFF163D40) else scheme.primaryContainer,
        nodeBorder = if (isDark) Color(0x33FFFFFF) else scheme.outline,
        selectedNodeBorder = if (isDark) Color(0xFF00E5FF) else scheme.primary,
        nodeContent = scheme.onSurface,
        selectedNodeContent = if (isDark) Color(0xFF00E5FF) else scheme.onPrimaryContainer,
        badgeBackground = if (isDark) Color(0xFF2C2C2C) else scheme.surfaceVariant,
        badgeBorder = if (isDark) Color(0x4DFFFFFF) else scheme.outline,
        badgeContent = scheme.onSurface,
        edge = if (isDark) Color(0x4DFFFFFF) else scheme.outline,
        highlightedEdge = if (isDark) Color(0xFF00E5FF) else scheme.primary,
        panelBackground = if (isDark) Color(0xFF171B20) else scheme.surface,
        panelBorder = if (isDark) Color(0x1AFFFFFF) else scheme.outlineVariant,
        itemBackground = if (isDark) Color(0x0DFFFFFF) else scheme.surfaceVariant,
        selectedItemBackground = if (isDark) Color(0x14FFFFFF) else scheme.primaryContainer,
        divider = if (isDark) Color(0x1AFFFFFF) else scheme.outlineVariant,
        primaryText = scheme.onSurface,
        secondaryText = scheme.onSurfaceVariant,
        codeBackground = if (isDark) Color(0xFF101010) else Color(0xFFF2F2EF),
        inputText = if (isDark) Color(0xFF81D4FA) else Color(0xFF245E86),
        outputText = if (isDark) Color(0xFFA5D6A7) else Color(0xFF276746),
        historyText = if (isDark) Color(0xFFFFCC80) else Color(0xFF7A5100),
        positiveText = if (isDark) Color(0xFFB9F6CA) else Color(0xFF276746),
        negativeText = if (isDark) Color(0xFFFF8A80) else scheme.error,
    ),
    memory = MemoryColors(
        screen = scheme.background,
        panel = scheme.surface,
        popover = scheme.surfaceVariant,
        card = scheme.surface.copy(alpha = 0.7f),
        cardHover = scheme.surface,
        selectedCard = scheme.primary.copy(alpha = 0.07f),
        cardBorder = scheme.outlineVariant,
        selectedBorder = scheme.primary.copy(alpha = 0.25f),
        accent = scheme.primary,
        divider = scheme.outlineVariant,
        textPrimary = scheme.onSurface,
        semantic = if (isDark) MemoryKindColors(Color(0xFF63B3ED), Color(0xFF1C2830))
        else MemoryKindColors(Color(0xFF245E86), Color(0xFFE4EEF3)),
        preference = if (isDark) MemoryKindColors(Color(0xFF00D9B3), Color(0xFF162B28))
        else MemoryKindColors(Color(0xFF0B6B58), Color(0xFFE2F2ED)),
        procedure = if (isDark) MemoryKindColors(Color(0xFFA78BFA), Color(0xFF282239))
        else MemoryKindColors(Color(0xFF5D4197), Color(0xFFEEE9F7)),
        projectRule = if (isDark) MemoryKindColors(Color(0xFFFB923C), Color(0xFF34251C))
        else MemoryKindColors(Color(0xFF8A3E00), Color(0xFFF8EAE0)),
        episodeNote = if (isDark) MemoryKindColors(Color(0xFF94A3B8), Color(0xFF25292E))
        else MemoryKindColors(Color(0xFF475569), Color(0xFFE8EBEF)),
        projectDecision = if (isDark) MemoryKindColors(Color(0xFFFBBF24), Color(0xFF312B19))
        else MemoryKindColors(Color(0xFF765500), Color(0xFFF5EDCF)),
        warning = if (isDark) MemoryKindColors(Color(0xFFFBBF24), Color(0xFF312B19))
        else MemoryKindColors(Color(0xFF765500), Color(0xFFF5EDCF)),
        danger = MemoryKindColors(scheme.onErrorContainer, scheme.errorContainer),
    ),
    ambientSuggestion = if (isDark) {
        AmbientSuggestionColors(
            background = Color(0xF216181C),
            border = Color(0x33FFC857),
            content = Color(0xE6FFFFFF),
            secondaryContent = Color(0x99FFFFFF),
            accent = Color(0xFFFFC857),
            accentContent = Color(0xFF18130A),
            secondaryActionBackground = Color(0x12FFFFFF),
        )
    } else {
        AmbientSuggestionColors(
            background = Color(0xFFFFF8E1),
            border = Color(0x668A5D00),
            content = Color(0xFF292720),
            secondaryContent = Color(0xFF615D52),
            accent = Color(0xFF8A5D00),
            accentContent = Color.White,
            secondaryActionBackground = Color(0xFFF1E9D3),
        )
    },
    tooltip = SurfaceColors(
        background = if (isDark) Color(0xE61A1C20) else scheme.surface,
        border = if (isDark) Color(0x40FFFFFF) else scheme.outlineVariant,
        content = if (isDark) Color(0xE6FFFFFF) else scheme.onSurface,
    ),
    neutralAttachment = SurfaceColors(
        background = if (isDark) Color(0x14FFFFFF) else scheme.surfaceVariant,
        border = if (isDark) Color(0x26FFFFFF) else scheme.outlineVariant,
        content = scheme.onSurfaceVariant,
    ),
    toolReview = ToolReviewColors(
        container = if (isDark) Color(0x10FFFFFF) else scheme.surface,
        border = if (isDark) Color(0x1FFFFFFF) else scheme.outlineVariant,
        itemContainer = if (isDark) Color(0x08FFFFFF) else scheme.surfaceVariant,
        content = if (isDark) Color(0xE6FFFFFF) else scheme.onSurface,
        secondaryContent = if (isDark) Color(0x99FFFFFF) else scheme.onSurfaceVariant,
        accent = if (isDark) Color(0xFFF59E0B) else Color(0xFF765500),
        accentContent = if (isDark) Color(0xFF18130A) else Color.White,
        positive = if (isDark) Color(0xFFB9F6CA) else Color(0xFF276746),
        negative = if (isDark) Color(0xFFFF8A80) else scheme.error,
        warning = if (isDark) Color(0xFFFFCC80) else Color(0xFF765500),
        info = if (isDark) Color(0xFF90CAF9) else Color(0xFF245E86),
        patchBackground = if (isDark) Color(0xFF101010) else Color(0xFFF2F2EF),
    ),
)
