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
        isUnreviewedAndroidXImport() ||
        isDesktopWindowImport(isAllUnder) ||
        matchesAny(HOST_IMPLEMENTATION_PREFIXES) ||
        this in HOST_IMPLEMENTATION_TYPES

private fun String.isSharedUiHostImport(isAllUnder: Boolean): Boolean =
    matchesAny(SHARED_UI_HOST_PREFIXES) ||
        isDesktopWindowImport(isAllUnder) ||
        matchesAny(HOST_IMPLEMENTATION_PREFIXES) ||
        this in HOST_IMPLEMENTATION_TYPES

private fun String.isDesktopWindowImport(isAllUnder: Boolean): Boolean =
    when {
        this == COMPOSE_WINDOW_PACKAGE -> isAllUnder
        !startsWith("$COMPOSE_WINDOW_PACKAGE.") -> false
        else -> !PORTABLE_WINDOW_TYPES.any { type -> this == type || startsWith("$type.") }
    }

private fun String.isUnreviewedAndroidXImport(): Boolean =
    matchesAny(COMMON_MAIN_FORBIDDEN_ANDROIDX_PREFIXES) ||
        startsWith("androidx.") && !matchesAny(COMMON_MAIN_ALLOWED_ANDROIDX_PREFIXES)

private fun String.matchesAny(prefixes: List<String>): Boolean =
    prefixes.any { prefix -> this == prefix.removeSuffix(".") || startsWith(prefix) }

private val SOURCE_LAYOUT = Regex("(?:^|/)([^/]+)/src/([^/]+)/")

private val CORE_MODULES = setOf("graph-engine", "llms", "agent", "skill-oauth-api")

private const val COMPOSE_WINDOW_PACKAGE = "androidx.compose.ui.window"

private val COMMON_MAIN_ALLOWED_ANDROIDX_PREFIXES = listOf("androidx.compose.")

private val COMMON_MAIN_FORBIDDEN_ANDROIDX_PREFIXES = listOf("androidx.compose.desktop.")

private val PORTABLE_WINDOW_TYPES = setOf(
    "$COMPOSE_WINDOW_PACKAGE.Dialog",
    "$COMPOSE_WINDOW_PACKAGE.DialogProperties",
    "$COMPOSE_WINDOW_PACKAGE.Popup",
    "$COMPOSE_WINDOW_PACKAGE.PopupProperties",
)

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

private val HOST_IMPLEMENTATION_TYPES = setOf(
    "ru.souz.App",
    "ru.souz.WindowLocals",
    "ru.souz.di.SharedUiDiModule",
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
