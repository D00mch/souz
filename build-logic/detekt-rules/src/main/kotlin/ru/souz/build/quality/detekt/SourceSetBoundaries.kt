package ru.souz.build.quality.detekt

import com.intellij.psi.PsiElement
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPackageSymbol
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtUserType

/** Keeps production package declarations and references behind their source-set and host boundaries. */
class SourceSetBoundaries private constructor(
    config: Config,
    private val sourcePath: (KtFile) -> String,
    private val sourceRoots: List<SourceRoot>,
) : Rule(
    config,
    "Production package declarations and references must respect Souz source-set and host boundaries.",
), RequiresAnalysisApi {
    constructor(config: Config) : this(
        config,
        { file -> file.virtualFilePath },
        config.valueOrDefault("sourceRoots", emptyList<String>()).map(::sourceRoot),
    )

    internal constructor(
        config: Config,
        sourcePath: String,
        sourceRoots: List<String> = emptyList(),
    ) : this(config, { sourcePath }, sourceRoots.map(::sourceRoot))

    override fun visitImportDirective(importDirective: KtImportDirective) {
        val importPath = importDirective.importPath?.pathStr?.removeSuffix(".*")
        if (importPath != null) {
            reportBoundaryViolation(
                element = importDirective,
                referencePath = importPath,
                isAllUnder = importDirective.isAllUnder,
                subject = "Import",
            )
        }
        super.visitImportDirective(importDirective)
    }

    override fun visitPackageDirective(directive: KtPackageDirective) {
        directive.fqName.asString().takeIf(String::isNotEmpty)?.let { packagePath ->
            reportBoundaryViolation(
                element = directive,
                referencePath = packagePath,
                subject = "Package",
            )
        }
        super.visitPackageDirective(directive)
    }

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        visitQualifiedBoundaryReference(expression)
        super.visitDotQualifiedExpression(expression)
    }

    override fun visitSafeQualifiedExpression(expression: KtSafeQualifiedExpression) {
        visitQualifiedBoundaryReference(expression)
        super.visitSafeQualifiedExpression(expression)
    }

    private fun visitQualifiedBoundaryReference(expression: KtQualifiedExpression) {
        if (!expression.isNestedQualifiedReceiver() && !expression.isInsideDirective()) {
            expression.referencePath()?.let { referencePath ->
                reportBoundaryViolation(expression, referencePath, subject = "Reference")
            }
        }
    }

    override fun visitUserType(type: KtUserType) {
        if (type.parent !is KtUserType) {
            type.referencePath()?.let { referencePath ->
                reportBoundaryViolation(type, referencePath, subject = "Reference")
            }
        }
        super.visitUserType(type)
    }

    private fun reportBoundaryViolation(
        element: PsiElement,
        referencePath: String,
        isAllUnder: Boolean = false,
        subject: String,
    ) {
        val file = element.containingFile as? KtFile ?: return
        val message = sourceSetBoundaryViolation(
            sourcePath = sourcePath(file),
            sourceRoots = sourceRoots,
            referencePath = referencePath,
            isAllUnder = isAllUnder,
            subject = subject,
        ) ?: return
        if (
            (element is KtQualifiedExpression || element is KtUserType) &&
            element.rootReference()?.isQualifiedPackageReference(referencePath) != true
        ) {
            return
        }
        report(Finding(Entity.from(element), message))
    }
}

private data class SourceLocation(
    val module: String,
    val sourceSet: String,
    val isProduction: Boolean,
    val isHostMain: Boolean,
)

private data class SourceRoot(
    val path: String,
    val source: SourceLocation,
)

private fun sourceSetBoundaryViolation(
    sourcePath: String,
    sourceRoots: List<SourceRoot>,
    referencePath: String,
    isAllUnder: Boolean,
    subject: String,
): String? {
    val source = sourceLocation(sourcePath, sourceRoots) ?: return null
    if (!source.isProduction) return null
    val renderedReference = referencePath + if (isAllUnder) ".*" else ""

    if (source.sourceSet == "commonMain" && referencePath.isPortableHostReference(isAllUnder)) {
        return "$subject '$renderedReference' is host-specific; commonMain must remain portable. " +
            "Move the implementation to a platform source set."
    }
    if (
        source.module == "sharedUI" && !source.isHostMain &&
        referencePath.isSharedUiHostReference(isAllUnder)
    ) {
        return "$subject '$renderedReference' is not allowed in :sharedUI ${source.sourceSet}. " +
            "Move desktop or native integration to a host target main source set and expose a host port."
    }
    if (
        source.module in CORE_MODULES &&
        referencePath.isCoreForbiddenReference(isAllUnder)
    ) {
        return "$subject '$renderedReference' is not allowed in :${source.module} ${source.sourceSet}. " +
            "Core production code must not depend on Compose or host/backend implementations."
    }
    if (
        source.module == "backend" &&
        referencePath.isBackendForbiddenReference(isAllUnder)
    ) {
        return "$subject '$renderedReference' is not allowed in :backend ${source.sourceSet}. " +
            "Backend production code must not depend on UI or desktop integrations."
    }
    return null
}

private fun sourceLocation(path: String, roots: List<SourceRoot>): SourceLocation? {
    val normalized = path.replace('\\', '/')
    if (roots.isNotEmpty()) {
        val matchingRoots = roots
            .asSequence()
            .filter { root -> normalized == root.path || normalized.startsWith("${root.path}/") }
            .toList()
        val configuredSource = matchingRoots
            .filter { root -> root.source.isProduction }
            .ifEmpty { matchingRoots }
            .maxByOrNull { root -> root.path.length }
            ?.source
        if (configuredSource != null) return configuredSource
    }
    val match = SOURCE_LAYOUT.findAll(normalized).lastOrNull() ?: return null
    val sourceSet = match.groupValues[2]
    return SourceLocation(
        module = match.groupValues[1],
        sourceSet = sourceSet,
        isProduction = sourceSet == "main" || sourceSet.endsWith("Main"),
        isHostMain = sourceSet == "main" || sourceSet == "jvmMain",
    )
}

private fun sourceRoot(encoded: String): SourceRoot {
    val parts = encoded.split(SOURCE_ROOT_SEPARATOR)
    require(parts.size == SOURCE_ROOT_PARTS) { "Invalid SourceSetBoundaries source root '$encoded'." }
    return SourceRoot(
        path = parts[0].replace('\\', '/').trimEnd('/'),
        source = SourceLocation(
            module = parts[1],
            sourceSet = parts[2],
            isProduction = parts[3].toBooleanStrict(),
            isHostMain = parts[4].toBooleanStrict(),
        ),
    )
}

private fun String.isPortableHostReference(isAllUnder: Boolean): Boolean =
    matchesAny(PORTABLE_HOST_PREFIXES) ||
        matchesAny(JVM_ONLY_IMPLEMENTATION_PREFIXES) ||
        isUnreviewedAndroidXReference(isAllUnder) ||
        isDesktopWindowReference(isAllUnder) ||
        matchesAny(HOST_IMPLEMENTATION_PREFIXES) ||
        matchesAnySymbol(HOST_IMPLEMENTATION_SYMBOLS, isAllUnder) ||
        matchesAnySymbol(JVM_ONLY_IMPLEMENTATION_SYMBOLS, isAllUnder)

private fun String.isSharedUiHostReference(isAllUnder: Boolean): Boolean =
    matchesAny(SHARED_UI_HOST_PREFIXES) ||
        matchesAny(JVM_ONLY_IMPLEMENTATION_PREFIXES) ||
        isDesktopWindowReference(isAllUnder) ||
        matchesAny(HOST_IMPLEMENTATION_PREFIXES) ||
        matchesAnySymbol(HOST_IMPLEMENTATION_SYMBOLS, isAllUnder) ||
        matchesAnySymbol(JVM_ONLY_IMPLEMENTATION_SYMBOLS, isAllUnder)

private fun String.isDesktopWindowReference(isAllUnder: Boolean): Boolean =
    when {
        this == COMPOSE_WINDOW_PACKAGE -> true
        !startsWith("$COMPOSE_WINDOW_PACKAGE.") -> false
        else -> !PORTABLE_WINDOW_TYPES.any { type -> this == type || startsWith("$type.") }
    }

private fun String.isUnreviewedAndroidXReference(isAllUnder: Boolean): Boolean =
    (this == "androidx" || startsWith("androidx.")) &&
        (isAllUnder || COMMON_MAIN_ALLOWED_ANDROIDX_REFERENCES.none { symbol ->
            this == symbol || startsWith("$symbol.")
        })

private fun String.matchesAny(prefixes: List<String>): Boolean =
    prefixes.any { prefix -> this == prefix.removeSuffix(".") || startsWith(prefix) }

private fun String.matchesAnySymbol(symbols: Set<String>, isAllUnder: Boolean): Boolean =
    symbols.any { symbol ->
        this == symbol || startsWith("$symbol.") ||
            isAllUnder && symbol.substringBeforeLast('.') == this
    }

private fun String.isCoreForbiddenReference(isAllUnder: Boolean): Boolean =
    matchesAny(CORE_FORBIDDEN_PREFIXES) ||
        matchesAny(JVM_ONLY_IMPLEMENTATION_PREFIXES) ||
        matchesAnySymbol(HOST_IMPLEMENTATION_SYMBOLS, isAllUnder) ||
        matchesAnySymbol(JVM_ONLY_IMPLEMENTATION_SYMBOLS, isAllUnder)

private fun String.isBackendForbiddenReference(isAllUnder: Boolean): Boolean =
    matchesAny(BACKEND_FORBIDDEN_PREFIXES) || matchesAnySymbol(HOST_IMPLEMENTATION_SYMBOLS, isAllUnder)

private fun KtQualifiedExpression.referencePath(): String? = referenceSegments()?.joinToString(".")

private fun KtExpression.referenceSegments(): List<String>? = when (this) {
    is KtNameReferenceExpression -> listOf(getReferencedName())
    is KtQualifiedExpression -> {
        val receiver = receiverExpression.referenceSegments() ?: return null
        val selector = selectorExpression?.referenceSegments() ?: return null
        receiver + selector
    }
    is KtCallExpression -> calleeExpression?.referenceSegments()
    is KtParenthesizedExpression -> expression?.referenceSegments()
    else -> null
}

private fun KtUserType.referencePath(): String? =
    generateSequence(this) { type -> type.qualifier }
        .toList()
        .asReversed()
        .mapNotNull { type -> type.referencedName }
        .takeIf { segments -> segments.size > 1 }
        ?.joinToString(".")

private fun PsiElement.rootReference(): KtNameReferenceExpression? = when (this) {
    is KtQualifiedExpression -> receiverExpression.rootReference()
    is KtCallExpression -> calleeExpression?.rootReference()
    is KtParenthesizedExpression -> expression?.rootReference()
    is KtUserType ->
        generateSequence(this) { type -> type.qualifier }.last().referenceExpression as? KtNameReferenceExpression
    is KtNameReferenceExpression -> this
    else -> null
}

@OptIn(KaExperimentalApi::class)
private fun KtNameReferenceExpression.isQualifiedPackageReference(referencePath: String): Boolean {
    val symbol = analyze(this) { resolveSymbol() }
    if (symbol == null) return referencePath.substringBefore('.') in BOUNDARY_PACKAGE_ROOTS
    if (symbol is KaPackageSymbol) return true
    val resolvedPath = when (symbol) {
        is KaCallableSymbol -> symbol.callableId?.asSingleFqName()?.asString()
        is KaClassLikeSymbol -> symbol.classId?.asSingleFqName()?.asString()
        else -> null
    } ?: return false
    return referencePath == resolvedPath || referencePath.startsWith("$resolvedPath.")
}

private fun KtQualifiedExpression.isNestedQualifiedReceiver(): Boolean {
    var child: PsiElement = this
    var ancestor = child.parent
    while (
        ancestor is KtCallExpression && ancestor.calleeExpression == child ||
        ancestor is KtParenthesizedExpression && ancestor.expression == child
    ) {
        child = ancestor
        ancestor = ancestor.parent
    }
    return ancestor is KtQualifiedExpression && ancestor.receiverExpression == child
}

private fun PsiElement.isInsideDirective(): Boolean {
    var ancestor = parent
    while (ancestor != null && ancestor !is KtFile) {
        if (ancestor is KtImportDirective || ancestor is KtPackageDirective) return true
        ancestor = ancestor.parent
    }
    return false
}

private const val SOURCE_ROOT_SEPARATOR = '|'
private const val SOURCE_ROOT_PARTS = 5
private val SOURCE_LAYOUT = Regex("(?:^|/)([^/]+)/src/([^/]+)/")

private val CORE_MODULES = setOf("graph-engine", "llms", "agent", "skill-oauth-api")

private val BOUNDARY_PACKAGE_ROOTS = setOf(
    "android",
    "androidx",
    "com",
    "java",
    "javax",
    "kotlinx",
    "org",
    "platform",
    "ru",
)

private const val COMPOSE_WINDOW_PACKAGE = "androidx.compose.ui.window"

private val PORTABLE_WINDOW_TYPES = setOf(
    "$COMPOSE_WINDOW_PACKAGE.Dialog",
    "$COMPOSE_WINDOW_PACKAGE.DialogProperties",
    "$COMPOSE_WINDOW_PACKAGE.Popup",
    "$COMPOSE_WINDOW_PACKAGE.PopupProperties",
)

// AndroidX packages mix common and platform declarations, so portable imports are reviewed by symbol.
private val COMMON_MAIN_ALLOWED_ANDROIDX_REFERENCES = setOf(
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

private val JVM_ONLY_IMPLEMENTATION_PREFIXES = listOf(
    "ru.souz.llms.tunnel.",
    "ru.souz.runtime.di.",
    "ru.souz.runtime.sandbox.docker.",
    "ru.souz.runtime.sandbox.local.",
    "ru.souz.service.mcp.",
    "ru.souz.service.speech.",
    "ru.souz.service.telegram.",
    "ru.souz.tool.dataAnalytics.",
    "ru.souz.ui.approval.",
    "ru.souz.ui.graphlog.",
)

private val HOST_IMPLEMENTATION_SYMBOLS = setOf(
    "ru.souz.App",
    "ru.souz.LocalWindowScope",
    "ru.souz.ambient.LocalChatAmbientLocalLlm",
    "ru.souz.ambient.selectAmbientLocalModel",
    "ru.souz.db.DesktopDataExtractor",
    "ru.souz.db.DesktopInfoRepository",
    "ru.souz.db.asString",
    "ru.souz.di.mainDiModule",
    "ru.souz.di.sharedUiDesktopDiModule",
    "ru.souz.di.sharedUiDiModule",
    "ru.souz.main",
    "ru.souz.memory.ConfigStoreMemoryMaintenanceSettingsStore",
    "ru.souz.memory.DesktopConversationMemoryRuntime",
    "ru.souz.memory.DesktopMemoryContextProvider",
    "ru.souz.memory.DesktopMemoryMaintenanceBackgroundRunner",
    "ru.souz.memory.DesktopMemoryMaintenanceController",
    "ru.souz.memory.DesktopMemoryOwnerProvider",
    "ru.souz.memory.DesktopMemoryProjectContextProvider",
    "ru.souz.memory.MemoryMaintenanceSettingsStore",
    "ru.souz.memory.MemoryMaintenanceWorker",
    "ru.souz.memory.NoopDesktopMemoryProjectContextProvider",
    "ru.souz.memory.SqliteMemoryRepository",
    "ru.souz.service.speech.MacOsSpeechBridge",
    "ru.souz.service.speech.MacOsSpeechBridgeLoader",
    "ru.souz.service.telegram.PreferencesTelegramBotConfigProvider",
    "ru.souz.service.telegram.TelegramBotApi",
    "ru.souz.service.telegram.TelegramBotConfigProvider",
    "ru.souz.service.telegram.TelegramBotController",
    "ru.souz.service.telegram.TelegramBotFile",
    "ru.souz.service.telegram.TelegramBotFileResponse",
    "ru.souz.service.telegram.TelegramChat",
    "ru.souz.service.telegram.TelegramDocument",
    "ru.souz.service.telegram.TelegramMessage",
    "ru.souz.service.telegram.TelegramPlatformSupport",
    "ru.souz.service.telegram.TelegramService",
    "ru.souz.service.telegram.TelegramUpdate",
    "ru.souz.service.telegram.TelegramUpdatesResponse",
    "ru.souz.service.telegram.TelegramUser",
    "ru.souz.service.telegram.TelegramVoice",
    "ru.souz.tool.DesktopToolAvailabilityPolicy",
    "ru.souz.tool.ShellException",
    "ru.souz.tool.ToolRunBashCommand",
    "ru.souz.tool.ToolsFactory",
    "ru.souz.tool.files.ToolRequestSelection",
    "ru.souz.tool.main",
    "ru.souz.ui.DockWindowController",
    "ru.souz.ui.rememberDockWindowController",
    "ru.souz.ui.common.DesktopExternalLinkOpener",
    "ru.souz.ui.common.DraggableWindowArea",
    "ru.souz.ui.common.FinderService",
    "ru.souz.ui.common.LocalModelUiCoordinator",
    "ru.souz.ui.common.MAIN_WINDOW_MIN_HEIGHT_PX",
    "ru.souz.ui.common.MAIN_WINDOW_MIN_WIDTH_PX",
    "ru.souz.ui.common.applyMinWindowSize",
    "ru.souz.ui.common.openProviderLink",
    "ru.souz.ui.common.toUi",
    "ru.souz.ui.host.DesktopChatCommandInputSource",
    "ru.souz.ui.host.DesktopLocalModelUiHost",
    "ru.souz.ui.host.DesktopPathOpener",
    "ru.souz.ui.host.DesktopPrivacyPolicyOpener",
    "ru.souz.ui.host.DesktopSettingsHostPreferences",
    "ru.souz.ui.host.DesktopSupportLogService",
    "ru.souz.ui.host.DesktopTelegramSettingsHost",
    "ru.souz.ui.host.TelegramControlBot",
    "ru.souz.ui.host.TelegramControlIncomingMessage",
    "ru.souz.ui.host.TelegramUiService",
    "ru.souz.ui.main.ChatModeContent",
    "ru.souz.ui.main.MainScreen",
    "ru.souz.ui.main.MainScreenContent",
    "ru.souz.ui.main.PreviewChatMode",
    "ru.souz.ui.main.PreviewChatModeEmpty",
    "ru.souz.ui.main.PreviewSmartFocusGlass",
    "ru.souz.ui.main.usecases.DesktopAttachmentMetadataProvider",
    "ru.souz.ui.main.usecases.DesktopDroppedFilePathExtractor",
    "ru.souz.ui.main.usecases.DesktopPathPicker",
    "ru.souz.ui.main.usecases.VoiceInputUseCase",
    "ru.souz.ui.memory.MemoryScreen",
    "ru.souz.ui.settings.AgentDropdown",
    "ru.souz.ui.settings.CalendarDropdown",
    "ru.souz.ui.settings.EmbeddingsModelDropdown",
    "ru.souz.ui.settings.FoldersManagementScreen",
    "ru.souz.ui.settings.FunctionsSettingsContent",
    "ru.souz.ui.settings.GeneralSettingsContent",
    "ru.souz.ui.settings.KeysSettingsContent",
    "ru.souz.ui.settings.LogsView",
    "ru.souz.ui.settings.ModelDropdown",
    "ru.souz.ui.settings.ModelsSettingsContent",
    "ru.souz.ui.settings.SecuritySettingsContent",
    "ru.souz.ui.settings.SettingsRow",
    "ru.souz.ui.settings.SettingsScreen",
    "ru.souz.ui.settings.SettingsScreenMain",
    "ru.souz.ui.settings.SettingsScreenPreview",
    "ru.souz.ui.settings.SupportLogSender",
    "ru.souz.ui.settings.SupportSettingsContent",
    "ru.souz.ui.settings.TelegramLoginContent",
    "ru.souz.ui.settings.TelegramSettingsScreen",
    "ru.souz.ui.settings.TokensBalanceSection",
    "ru.souz.ui.settings.VoiceRecognitionModelDropdown",
    "ru.souz.ui.setup.SetupScreen",
    "ru.souz.ui.setup.SetupScreenContent",
    "ru.souz.ui.tools.ToolDetailsScreen",
    "ru.souz.ui.tools.ToolsScreen",
)

private val JVM_ONLY_IMPLEMENTATION_SYMBOLS = setOf(
    "ru.souz.ambient.DefaultAmbientTranscriptionService",
    "ru.souz.db.AesGcmSecretCodec",
    "ru.souz.db.ConfigStore",
    "ru.souz.db.SettingsProviderImpl",
    "ru.souz.db.VectorDB",
    "ru.souz.llms.openai.MissingOpenAiVoiceKeyException",
    "ru.souz.llms.openai.OpenAIVoiceAPI",
    "ru.souz.runtime.files.createDefaultFilesToolUtil",
    "ru.souz.runtime.sandbox.DefaultRuntimeSandboxFactory",
    "ru.souz.runtime.sandbox.RuntimeSandboxModeResolver",
    "ru.souz.tool.RuntimeToolsFactory",
    "ru.souz.tool.files.ToolExtractText",
    "ru.souz.tool.files.ToolReadPdfPages",
    "ru.souz.tool.runtimeToolsDiModule",
    "ru.souz.tool.web.ToolWebImageSearch",
    "ru.souz.tool.web.internal.WebImageDownloader",
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
