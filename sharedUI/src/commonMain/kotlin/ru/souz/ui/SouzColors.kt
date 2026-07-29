package ru.souz.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class GlassColors(
    val heroBackground: List<Color>,
    val heroOverlay: Color,
    val heroBorder: List<Color>,
    val innerBorder: Color,
    val textPrimary: Color,
)

@Immutable
data class ChatColors(
    val userBubbleBackground: Color,
    val userBubbleBorder: Color,
    val pathChipBackground: Color,
    val pathChipBorder: Color,
    val pathChipContent: Color,
)

@Immutable
data class SettingsColors(
    val contentBackground: Color,
    val sidebarBackground: Color,
    val sidebarBorder: Color,
    val selectedNavigationBackground: Color,
    val selectedNavigationContent: Color,
    val navigationContent: Color,
    val hoverBackground: Color,
    val hoverContent: Color,
    val inputBackground: Color,
    val inputBorder: Color,
    val content: Color,
    val secondaryContent: Color,
    val switchTrack: Color,
    val checkedSwitchTrack: Color,
    val switchThumb: Color,
    val segmentContainer: Color,
    val selectedSegmentBackground: Color,
    val selectedSegmentContent: Color,
    val segmentContent: Color,
    val primaryActionContainer: Color,
    val primaryActionContent: Color,
    val secondaryActionContainer: Color,
    val secondaryActionBorder: Color,
    val secondaryActionContent: Color,
    val secondaryActionHoverContainer: Color,
    val secondaryActionHoverBorder: Color,
    val destructiveActionContainer: Color,
    val destructiveActionBorder: Color,
    val destructiveActionContent: Color,
    val accent: Color,
    val accentHover: Color,
)

@Immutable
data class GraphColors(
    val canvasBackground: Color,
    val nodeBackground: Color,
    val selectedNodeBackground: Color,
    val nodeBorder: Color,
    val selectedNodeBorder: Color,
    val nodeContent: Color,
    val selectedNodeContent: Color,
    val badgeBackground: Color,
    val badgeBorder: Color,
    val badgeContent: Color,
    val edge: Color,
    val highlightedEdge: Color,
    val panelBackground: Color,
    val panelBorder: Color,
    val itemBackground: Color,
    val selectedItemBackground: Color,
    val divider: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val codeBackground: Color,
    val inputText: Color,
    val outputText: Color,
    val historyText: Color,
    val positiveText: Color,
    val negativeText: Color,
)

@Immutable
data class MemoryKindColors(
    val content: Color,
    val container: Color,
)

@Immutable
data class MemoryColors(
    val screen: Color,
    val panel: Color,
    val popover: Color,
    val card: Color,
    val cardHover: Color,
    val selectedCard: Color,
    val cardBorder: Color,
    val selectedBorder: Color,
    val accent: Color,
    val divider: Color,
    val textPrimary: Color,
    val semantic: MemoryKindColors,
    val preference: MemoryKindColors,
    val procedure: MemoryKindColors,
    val projectRule: MemoryKindColors,
    val episodeNote: MemoryKindColors,
    val projectDecision: MemoryKindColors,
    val warning: MemoryKindColors,
    val danger: MemoryKindColors,
)

@Immutable
data class AmbientSuggestionColors(
    val background: Color,
    val border: Color,
    val content: Color,
    val secondaryContent: Color,
    val accent: Color,
    val accentContent: Color,
    val secondaryActionBackground: Color,
)

@Immutable
data class SurfaceColors(
    val background: Color,
    val border: Color,
    val content: Color,
)

@Immutable
data class ToolReviewColors(
    val container: Color,
    val border: Color,
    val itemContainer: Color,
    val content: Color,
    val secondaryContent: Color,
    val accent: Color,
    val accentContent: Color,
    val positive: Color,
    val negative: Color,
    val warning: Color,
    val info: Color,
    val patchBackground: Color,
)

@Immutable
data class SouzColors(
    val glass: GlassColors,
    val chat: ChatColors,
    val settings: SettingsColors,
    val graph: GraphColors,
    val memory: MemoryColors,
    val ambientSuggestion: AmbientSuggestionColors,
    val tooltip: SurfaceColors,
    val neutralAttachment: SurfaceColors,
    val toolReview: ToolReviewColors,
)

internal val LocalSouzColors = staticCompositionLocalOf<SouzColors> {
    error("SouzColors are not provided")
}

val MaterialTheme.souzColors: SouzColors
    @Composable get() = LocalSouzColors.current

val MaterialTheme.memoryColors: MemoryColors
    @Composable get() = souzColors.memory
