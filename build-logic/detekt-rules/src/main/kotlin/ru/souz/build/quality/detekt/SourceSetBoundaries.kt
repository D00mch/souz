package ru.souz.build.quality.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective

/** Keeps portable, core, backend, and shared UI production sources behind their host boundaries. */
class SourceSetBoundaries private constructor(
    config: Config,
    private val sourcePath: (KtFile) -> String,
) : Rule(
    config,
    "Production imports must respect Souz source-set and host boundaries.",
) {
    constructor(config: Config) : this(config, { file -> file.virtualFilePath })

    internal constructor(config: Config, sourcePath: String) : this(config, { sourcePath })

    override fun visitImportDirective(importDirective: KtImportDirective) {
        val importPath = importDirective.importPath?.pathStr?.removeSuffix(".*")
        if (importPath != null) {
            sourceSetBoundaryViolation(
                sourcePath = sourcePath(importDirective.containingKtFile),
                importPath = importPath,
                isAllUnder = importDirective.isAllUnder,
            )?.let { message -> report(Finding(Entity.from(importDirective), message)) }
        }
        super.visitImportDirective(importDirective)
    }
}

private data class SourceLocation(
    val module: String,
    val sourceSet: String,
) {
    val isProduction: Boolean = sourceSet == "main" || sourceSet.endsWith("Main")
}

private fun sourceSetBoundaryViolation(
    sourcePath: String,
    importPath: String,
    isAllUnder: Boolean,
): String? {
    val source = sourceLocation(sourcePath) ?: return null
    if (!source.isProduction) return null
    val renderedImport = importPath + if (isAllUnder) ".*" else ""

    if (source.sourceSet == "commonMain" && importPath.isPortableHostImport(isAllUnder)) {
        return "Import '$renderedImport' is host-specific; commonMain must remain portable. " +
            "Move the implementation to a platform source set."
    }
    if (
        source.module == "sharedUI" && source.sourceSet == "commonJvmMain" &&
        importPath.isSharedUiHostImport(isAllUnder)
    ) {
        return "Import '$renderedImport' is not allowed in :sharedUI commonJvmMain. " +
            "Move desktop or native integration to jvmMain and expose a host port."
    }
    if (source.module in CORE_MODULES && importPath.matchesAny(CORE_FORBIDDEN_PREFIXES)) {
        return "Import '$renderedImport' is not allowed in :${source.module} ${source.sourceSet}. " +
            "Core production code must not depend on Compose or host/backend implementations."
    }
    if (source.module == "backend" && importPath.matchesAny(BACKEND_FORBIDDEN_PREFIXES)) {
        return "Import '$renderedImport' is not allowed in :backend ${source.sourceSet}. " +
            "Backend production code must not depend on UI or desktop integrations."
    }
    return null
}

private fun sourceLocation(path: String): SourceLocation? {
    val normalized = path.replace('\\', '/')
    val match = SOURCE_LAYOUT.findAll(normalized).lastOrNull() ?: return null
    return SourceLocation(module = match.groupValues[1], sourceSet = match.groupValues[2])
}

private fun String.isPortableHostImport(isAllUnder: Boolean): Boolean =
    matchesAny(PORTABLE_HOST_PREFIXES) ||
        isUnreviewedAndroidXImport(isAllUnder) ||
        isDesktopWindowImport(isAllUnder) ||
        matchesAny(HOST_IMPLEMENTATION_PREFIXES) ||
        matchesAnySymbol(HOST_IMPLEMENTATION_SYMBOLS, isAllUnder)

private fun String.isSharedUiHostImport(isAllUnder: Boolean): Boolean =
    matchesAny(SHARED_UI_HOST_PREFIXES) ||
        isDesktopWindowImport(isAllUnder) ||
        matchesAny(HOST_IMPLEMENTATION_PREFIXES) ||
        matchesAnySymbol(HOST_IMPLEMENTATION_SYMBOLS, isAllUnder)

private fun String.isDesktopWindowImport(isAllUnder: Boolean): Boolean =
    when {
        this == COMPOSE_WINDOW_PACKAGE -> isAllUnder
        !startsWith("$COMPOSE_WINDOW_PACKAGE.") -> false
        else -> !PORTABLE_WINDOW_TYPES.any { type -> this == type || startsWith("$type.") }
    }

private fun String.isUnreviewedAndroidXImport(isAllUnder: Boolean): Boolean =
    (this == "androidx" || startsWith("androidx.")) &&
        (isAllUnder || this !in COMMON_MAIN_ALLOWED_ANDROIDX_IMPORTS)

private fun String.matchesAny(prefixes: List<String>): Boolean =
    prefixes.any { prefix -> this == prefix.removeSuffix(".") || startsWith(prefix) }

private fun String.matchesAnySymbol(symbols: Set<String>, isAllUnder: Boolean): Boolean =
    this in symbols ||
        isAllUnder && symbols.any { symbol -> symbol.substringBeforeLast('.') == this }

private val SOURCE_LAYOUT = Regex("(?:^|/)([^/]+)/src/([^/]+)/")

private val CORE_MODULES = setOf("graph-engine", "llms", "agent", "skill-oauth-api")

private const val COMPOSE_WINDOW_PACKAGE = "androidx.compose.ui.window"

private val PORTABLE_WINDOW_TYPES = setOf(
    "$COMPOSE_WINDOW_PACKAGE.Dialog",
    "$COMPOSE_WINDOW_PACKAGE.DialogProperties",
    "$COMPOSE_WINDOW_PACKAGE.Popup",
    "$COMPOSE_WINDOW_PACKAGE.PopupProperties",
)

// AndroidX packages mix common and platform declarations, so portable imports are reviewed by symbol.
private val COMMON_MAIN_ALLOWED_ANDROIDX_IMPORTS = setOf(
    "androidx.compose.animation.animateColorAsState",
    "androidx.compose.animation.core.FastOutSlowInEasing",
    "androidx.compose.animation.core.animateFloatAsState",
    "androidx.compose.animation.core.tween",
    "androidx.compose.foundation.BorderStroke",
    "androidx.compose.foundation.background",
    "androidx.compose.foundation.border",
    "androidx.compose.foundation.clickable",
    "androidx.compose.foundation.interaction.MutableInteractionSource",
    "androidx.compose.foundation.interaction.collectIsHoveredAsState",
    "androidx.compose.foundation.interaction.collectIsPressedAsState",
    "androidx.compose.foundation.layout.Arrangement",
    "androidx.compose.foundation.layout.Box",
    "androidx.compose.foundation.layout.BoxWithConstraints",
    "androidx.compose.foundation.layout.Column",
    "androidx.compose.foundation.layout.ColumnScope",
    "androidx.compose.foundation.layout.PaddingValues",
    "androidx.compose.foundation.layout.Row",
    "androidx.compose.foundation.layout.Spacer",
    "androidx.compose.foundation.layout.fillMaxSize",
    "androidx.compose.foundation.layout.fillMaxWidth",
    "androidx.compose.foundation.layout.height",
    "androidx.compose.foundation.layout.heightIn",
    "androidx.compose.foundation.layout.padding",
    "androidx.compose.foundation.layout.size",
    "androidx.compose.foundation.layout.width",
    "androidx.compose.foundation.layout.widthIn",
    "androidx.compose.foundation.rememberScrollState",
    "androidx.compose.foundation.shape.CircleShape",
    "androidx.compose.foundation.shape.RoundedCornerShape",
    "androidx.compose.foundation.text.BasicTextField",
    "androidx.compose.foundation.text.selection.DisableSelection",
    "androidx.compose.foundation.verticalScroll",
    "androidx.compose.material.icons.Icons",
    "androidx.compose.material.icons.filled.ArrowDropDown",
    "androidx.compose.material.icons.filled.Visibility",
    "androidx.compose.material.icons.filled.VisibilityOff",
    "androidx.compose.material.icons.outlined.Info",
    "androidx.compose.material.icons.outlined.Warning",
    "androidx.compose.material.icons.rounded.Check",
    "androidx.compose.material.icons.rounded.ContentCopy",
    "androidx.compose.material3.Button",
    "androidx.compose.material3.ButtonDefaults",
    "androidx.compose.material3.CircularProgressIndicator",
    "androidx.compose.material3.DropdownMenu",
    "androidx.compose.material3.DropdownMenuItem",
    "androidx.compose.material3.HorizontalDivider",
    "androidx.compose.material3.Icon",
    "androidx.compose.material3.IconButton",
    "androidx.compose.material3.MaterialTheme",
    "androidx.compose.material3.OutlinedButton",
    "androidx.compose.material3.Surface",
    "androidx.compose.material3.Text",
    "androidx.compose.material3.TextButton",
    "androidx.compose.runtime.Composable",
    "androidx.compose.runtime.Immutable",
    "androidx.compose.runtime.LaunchedEffect",
    "androidx.compose.runtime.getValue",
    "androidx.compose.runtime.mutableStateOf",
    "androidx.compose.runtime.remember",
    "androidx.compose.runtime.setValue",
    "androidx.compose.runtime.staticCompositionLocalOf",
    "androidx.compose.ui.Alignment",
    "androidx.compose.ui.Modifier",
    "androidx.compose.ui.draw.clip",
    "androidx.compose.ui.draw.scale",
    "androidx.compose.ui.focus.onFocusChanged",
    "androidx.compose.ui.graphics.Color",
    "androidx.compose.ui.graphics.SolidColor",
    "androidx.compose.ui.graphics.graphicsLayer",
    "androidx.compose.ui.graphics.vector.ImageVector",
    "androidx.compose.ui.platform.LocalClipboardManager",
    "androidx.compose.ui.text.AnnotatedString",
    "androidx.compose.ui.text.TextStyle",
    "androidx.compose.ui.text.font.FontFamily",
    "androidx.compose.ui.text.font.FontWeight",
    "androidx.compose.ui.text.input.PasswordVisualTransformation",
    "androidx.compose.ui.text.input.VisualTransformation",
    "androidx.compose.ui.text.style.TextAlign",
    "androidx.compose.ui.text.style.TextOverflow",
    "androidx.compose.ui.unit.Dp",
    "androidx.compose.ui.unit.dp",
    "androidx.compose.ui.unit.sp",
    "androidx.compose.ui.zIndex",
    "androidx.lifecycle.ViewModel",
    "androidx.lifecycle.viewModelScope",
) + PORTABLE_WINDOW_TYPES

private val PORTABLE_HOST_PREFIXES = listOf(
    "android.",
    "androidx.compose.foundation.window.",
    "androidx.compose.ui.awt.",
    "com.sun.jna.",
    "java.",
    "javax.",
    "kotlinx.cinterop.",
    "org.jetbrains.skiko.",
    "platform.",
)

private val DESKTOP_ONLY_SERVICE_PREFIXES = listOf(
    "ru.souz.service.audio.",
    "ru.souz.service.image.",
    "ru.souz.service.keys.",
    "ru.souz.service.permissions.",
)

private val DESKTOP_TOOL_PREFIXES = listOf(
    "ru.souz.tool.application.",
    "ru.souz.tool.browser.",
    "ru.souz.tool.calendar.",
    "ru.souz.tool.config.",
    "ru.souz.tool.desktop.",
    "ru.souz.tool.mail.",
    "ru.souz.tool.notes.",
    "ru.souz.tool.telegram.",
    "ru.souz.tool.textReplace.",
)

private val HOST_IMPLEMENTATION_PREFIXES = listOf(
    "ru.souz.backend.",
    "ru.souz.llms.local.",
    "ru.souz.ui.macos.",
) + DESKTOP_ONLY_SERVICE_PREFIXES + DESKTOP_TOOL_PREFIXES

private val HOST_IMPLEMENTATION_SYMBOLS = setOf(
    "ru.souz.App",
    "ru.souz.LocalWindowScope",
    "ru.souz.di.sharedUiDesktopDiModule",
    "ru.souz.di.sharedUiDiModule",
)

private val SHARED_UI_HOST_PREFIXES = listOf(
    "androidx.compose.foundation.window.",
    "androidx.compose.ui.awt.",
    "com.sun.jna.",
    "java.awt.",
    "javax.swing.",
    "org.jetbrains.skiko.",
)

private val CORE_FORBIDDEN_PREFIXES = listOf(
    "androidx.compose.",
    "com.sun.jna.",
    "java.awt.",
    "javax.swing.",
    "org.jetbrains.skiko.",
    "ru.souz.backend.",
    "ru.souz.di.",
    "ru.souz.llms.local.",
    "ru.souz.service.",
    "ru.souz.ui.",
) + DESKTOP_TOOL_PREFIXES

private val BACKEND_FORBIDDEN_PREFIXES = listOf(
    "androidx.compose.",
    "com.sun.jna.",
    "java.awt.",
    "javax.swing.",
    "org.jetbrains.skiko.",
    "ru.souz.di.",
    "ru.souz.ui.",
) + DESKTOP_ONLY_SERVICE_PREFIXES + DESKTOP_TOOL_PREFIXES
