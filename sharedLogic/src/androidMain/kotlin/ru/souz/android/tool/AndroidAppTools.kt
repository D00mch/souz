package ru.souz.android.tool

import android.app.ActivityManager
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.giga.toGiga
import ru.souz.llms.restJsonMapper
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.PortableRuntimeToolsFactory
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolCategory
import ru.souz.tool.ToolSetup
import java.util.Locale

private const val STAR_LAUNCHER_CATEGORY = "ru.sberdevices.category.STAR_LAUNCHER"

class AndroidToolsFactory(
    private val portableToolsFactory: PortableRuntimeToolsFactory,
    private val toolShowApps: ToolShowAndroidApps,
    private val toolOpen: ToolOpenAndroid,
    private val toolMediaControl: ToolMediaControl,
    private val toolAndroidInput: ToolAndroidInput,
) : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> by lazy {
        ToolCategory.entries.associateWith { category ->
            val base = portableToolsFactory.toolsByCategory[category].orEmpty()
            val androidTools = when (category) {
                ToolCategory.APPLICATIONS -> listOf(
                    toolShowApps.toGiga(),
                    toolOpen.toGiga(),
                    toolMediaControl.toGiga(),
                )

                ToolCategory.DESKTOP -> listOf(
                    toolAndroidInput.toGiga(),
                )

                else -> emptyList()
            }.associateBy { it.fn.name }
            base + androidTools
        }
    }
}

class ToolShowAndroidApps(
    context: Context,
) : ToolSetup<ToolShowAndroidApps.Input> {
    private val appContext = context.applicationContext
    private val packageManager: PackageManager = appContext.packageManager

    data class Input(
        @InputParamDescription("What apps to show: installed, launchable, or running.")
        val state: AppState,
    )

    enum class AppState {
        installed,
        launchable,
        running,
    }

    override val name: String = "ShowApps"
    override val description: String =
        "Shows Android apps visible to Souz. Use launchable to find apps that can be opened, including TV launcher and Star Launcher apps."

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Покажи приложения, которые можно открыть",
            params = mapOf("state" to AppState.launchable),
        ),
        FewShotExample(
            request = "Покажи установленные приложения на устройстве",
            params = mapOf("state" to AppState.installed),
        ),
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty(
                "string",
                "JSON list of Android apps with package, label, activity, categories, and running process data when available.",
            ),
        ),
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String {
        val launchable = launchableActivities(packageManager)
        val result = when (input.state) {
            AppState.launchable -> launchable.map { it.toDto(packageManager) }.distinctBy { it.component }
            AppState.installed -> installedApps(packageManager, launchable)
            AppState.running -> runningApps(appContext, packageManager)
        }
        return restJsonMapper.writeValueAsString(result)
    }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String = invoke(input, meta)
}

class ToolOpenAndroid(
    context: Context,
) : ToolSetup<ToolOpenAndroid.Input> {
    private val appContext = context.applicationContext
    private val packageManager: PackageManager = appContext.packageManager

    data class Input(
        @InputParamDescription(
            "Android package name, flattened component, app label, or URI/deep link. " +
                "Examples: ru.sberdevices.music, ru.souz.android/.MainActivity, music://start, https://example.com.",
        )
        val target: String,
    )

    override val name: String = "Open"
    override val description: String =
        "Opens an Android app, component, or deep link. Supports normal launcher, Android TV leanback, and Sber Star Launcher activities."

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Открой музыку",
            params = mapOf("target" to "ru.sberdevices.music"),
        ),
        FewShotExample(
            request = "Открой персональную волну в музыке",
            params = mapOf("target" to "music://start"),
        ),
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Open status and resolved Android component or URI."),
        ),
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String {
        val target = input.target.trim()
        if (target.isEmpty()) return "Error: target must not be empty"

        return runCatching {
            val launch = resolveLaunch(target)
            appContext.startActivity(launch.intent)
            "Started ${launch.description}"
        }.getOrElse { error ->
            "Error opening '$target': ${error.message ?: error::class.java.simpleName}"
        }
    }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String = invoke(input, meta)

    private fun resolveLaunch(target: String): LaunchIntent {
        val component = parseComponent(target)
        if (component != null) {
            return LaunchIntent(
                intent = Intent(Intent.ACTION_MAIN)
                    .setComponent(component)
                    .addFlags(openFlags()),
                description = component.flattenToShortString(),
            )
        }

        if (looksLikeUri(target)) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target))
                .addFlags(openFlags())
            val resolved = resolveActivity(intent)
            return LaunchIntent(
                intent = intent,
                description = target + resolved?.let { " via ${it.activityInfo.packageName}/${it.activityInfo.name}" }.orEmpty(),
            )
        }

        packageManager.getLaunchIntentForPackage(target)?.let { intent ->
            return LaunchIntent(
                intent = intent.addFlags(openFlags()),
                description = target,
            )
        }

        val candidate = resolveLaunchableCandidate(target)
            ?: error("No launchable Android activity found. Try ShowApps with state=launchable.")
        val intent = Intent(Intent.ACTION_MAIN)
            .setComponent(candidate.componentName)
            .addFlags(openFlags())
        candidate.category?.let(intent::addCategory)
        return LaunchIntent(
            intent = intent,
            description = "${candidate.packageName}/${candidate.activityName}",
        )
    }

    private fun resolveLaunchableCandidate(target: String): LaunchableActivity? {
        val normalized = target.normalizeForMatch()
        val candidates = launchableActivities(packageManager)
        return candidates.firstOrNull { it.packageName == target } ?: candidates.firstOrNull {
            it.component == target || it.component.endsWith("/$target")
        } ?: candidates.firstOrNull {
            it.label.normalizeForMatch() == normalized
        } ?: candidates.firstOrNull {
            it.packageName.normalizeForMatch().contains(normalized) ||
                it.label.normalizeForMatch().contains(normalized)
        }
    }

    private fun resolveActivity(intent: Intent): ResolveInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, 0)
        }
}

class ToolMediaControl(
    context: Context,
) : ToolSetup<ToolMediaControl.Input> {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    data class Input(
        @InputParamDescription("Media command to send to the active Android media session.")
        val command: MediaCommand,
    )

    enum class MediaCommand {
        PLAY,
        PAUSE,
        PLAY_PAUSE,
        STOP,
        NEXT,
        PREVIOUS,
        FAST_FORWARD,
        REWIND,
    }

    override val name: String = "MediaControl"
    override val description: String =
        "Sends Android media key commands to the active media session. Use this for music playback control."

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Поставь музыку на паузу",
            params = mapOf("command" to MediaCommand.PAUSE),
        ),
        FewShotExample(
            request = "Следующий трек",
            params = mapOf("command" to MediaCommand.NEXT),
        ),
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Media command status."),
        ),
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String {
        val keyCode = when (input.command) {
            MediaCommand.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaCommand.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaCommand.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaCommand.STOP -> KeyEvent.KEYCODE_MEDIA_STOP
            MediaCommand.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaCommand.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            MediaCommand.FAST_FORWARD -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            MediaCommand.REWIND -> KeyEvent.KEYCODE_MEDIA_REWIND
        }
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return "Sent media command ${input.command}"
    }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String = invoke(input, meta)
}

class ToolAndroidInput(
    context: Context,
) : ToolSetup<ToolAndroidInput.Input> {
    private val appContext = context.applicationContext

    data class Input(
        @InputParamDescription("Input action to send: KEY, TAP, TEXT, or HOME.")
        val action: Action,
        @InputParamDescription("Key name for KEY action, such as DPAD_CENTER, DPAD_LEFT, BACK, MENU, ENTER, or MEDIA_PLAY_PAUSE.")
        val key: String? = null,
        @InputParamDescription("X coordinate for TAP action.")
        val x: Int? = null,
        @InputParamDescription("Y coordinate for TAP action.")
        val y: Int? = null,
        @InputParamDescription("Text for TEXT action.")
        val text: String? = null,
    )

    enum class Action {
        KEY,
        TAP,
        TEXT,
        HOME,
    }

    override val name: String = "AndroidInput"
    override val description: String =
        "Best-effort Android input for the current screen. HOME is reliable; KEY/TAP/TEXT may require privileged input injection on other apps."

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Нажми OK на Android экране",
            params = mapOf("action" to Action.KEY, "key" to "DPAD_CENTER"),
        ),
        FewShotExample(
            request = "Вернись на главный экран Android",
            params = mapOf("action" to Action.HOME),
        ),
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Input status or permission limitation."),
        ),
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String =
        runBlocking { suspendInvoke(input, meta) }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String = when (input.action) {
        Action.HOME -> {
            val homeIntent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(homeIntent)
            "Opened Android home screen"
        }

        Action.KEY -> withContext(Dispatchers.Default) {
            val keyName = input.key?.trim().orEmpty()
            if (keyName.isEmpty()) return@withContext "Error: key is required for KEY action"
            injectWithInstrumentation("key $keyName") {
                sendKeyDownUpSync(resolveKeyCode(keyName))
            }
        }

        Action.TEXT -> withContext(Dispatchers.Default) {
            val text = input.text.orEmpty()
            if (text.isEmpty()) return@withContext "Error: text is required for TEXT action"
            injectWithInstrumentation("text") {
                sendStringSync(text)
            }
        }

        Action.TAP -> withContext(Dispatchers.Default) {
            val x = input.x ?: return@withContext "Error: x is required for TAP action"
            val y = input.y ?: return@withContext "Error: y is required for TAP action"
            injectWithInstrumentation("tap $x,$y") {
                val downTime = SystemClock.uptimeMillis()
                sendPointerSync(
                    MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0),
                )
                sendPointerSync(
                    MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0),
                )
            }
        }
    }

    private fun injectWithInstrumentation(label: String, block: Instrumentation.() -> Unit): String =
        runCatching {
            Instrumentation().apply(block)
            "Sent Android input: $label"
        }.getOrElse { error ->
            "Android input '$label' was not injected: ${error.message ?: error::class.java.simpleName}. " +
                "Input injection into other apps usually requires accessibility, shell/root, or privileged permissions."
        }
}

private data class LaunchIntent(
    val intent: Intent,
    val description: String,
)

private data class LaunchableActivity(
    val packageName: String,
    val activityName: String,
    val label: String,
    val category: String?,
) {
    val componentName: ComponentName = ComponentName(packageName, activityName)
    val component: String = "$packageName/$activityName"

    fun toDto(packageManager: PackageManager): AppDto = AppDto(
        packageName = packageName,
        appName = label.ifBlank { packageName },
        activity = activityName,
        component = component,
        categories = listOfNotNull(category),
        system = runCatching {
            packageManager.getApplicationInfoCompat(packageName).isSystemApp
        }.getOrDefault(false),
        enabled = runCatching {
            packageManager.getApplicationInfoCompat(packageName).enabled
        }.getOrDefault(true),
        launchable = true,
    )
}

private data class AppDto(
    @field:JsonProperty("app-bundle-id") val packageName: String,
    @field:JsonProperty("app-name") val appName: String,
    @field:JsonProperty("activity") val activity: String? = null,
    @field:JsonProperty("component") val component: String? = null,
    @field:JsonProperty("categories") val categories: List<String> = emptyList(),
    @field:JsonProperty("system") val system: Boolean? = null,
    @field:JsonProperty("enabled") val enabled: Boolean? = null,
    @field:JsonProperty("launchable") val launchable: Boolean? = null,
    @field:JsonProperty("app-pid") val pid: Int? = null,
    @field:JsonProperty("importance") val importance: Int? = null,
)

private fun installedApps(
    packageManager: PackageManager,
    launchable: List<LaunchableActivity>,
): List<AppDto> {
    val launchableByPackage = launchable.groupBy { it.packageName }
    return packageManager.getInstalledApplicationsCompat().map { app ->
        val launchableActivities = launchableByPackage[app.packageName].orEmpty()
        AppDto(
            packageName = app.packageName,
            appName = app.loadLabel(packageManager).toString().ifBlank { app.packageName },
            activity = launchableActivities.firstOrNull()?.activityName,
            component = launchableActivities.firstOrNull()?.component,
            categories = launchableActivities.mapNotNull { it.category }.distinct(),
            system = app.isSystemApp,
            enabled = app.enabled,
            launchable = launchableActivities.isNotEmpty(),
        )
    }.sortedWith(compareBy<AppDto> { it.appName.lowercase(Locale.ROOT) }.thenBy { it.packageName })
}

private fun runningApps(
    context: Context,
    packageManager: PackageManager,
): List<AppDto> {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    return activityManager.runningAppProcesses.orEmpty().map { process ->
        val packageName = process.pkgList.firstOrNull() ?: process.processName
        val label = runCatching {
            packageManager.getApplicationInfoCompat(packageName).loadLabel(packageManager).toString()
        }.getOrNull()
        AppDto(
            packageName = packageName,
            appName = label.orEmpty().ifBlank { process.processName },
            pid = process.pid,
            importance = process.importance,
        )
    }.distinctBy { "${it.packageName}:${it.pid}" }
}

private fun launchableActivities(packageManager: PackageManager): List<LaunchableActivity> {
    val categories = listOf(
        Intent.CATEGORY_LAUNCHER,
        Intent.CATEGORY_LEANBACK_LAUNCHER,
        STAR_LAUNCHER_CATEGORY,
    )
    return categories.flatMap { category ->
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        packageManager.queryIntentActivitiesCompat(intent).map { resolveInfo ->
            val activity = resolveInfo.activityInfo
            LaunchableActivity(
                packageName = activity.packageName,
                activityName = activity.name,
                label = resolveInfo.loadLabel(packageManager).toString().ifBlank {
                    activity.loadLabel(packageManager).toString().ifBlank { activity.packageName }
                },
                category = category,
            )
        }
    }.distinctBy { "${it.packageName}/${it.activityName}/${it.category}" }
}

private fun PackageManager.queryIntentActivitiesCompat(intent: Intent): List<ResolveInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        queryIntentActivities(intent, 0)
    }

private fun PackageManager.getInstalledApplicationsCompat(): List<ApplicationInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        getInstalledApplications(0)
    }

private fun PackageManager.getApplicationInfoCompat(packageName: String): ApplicationInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        getApplicationInfo(packageName, 0)
    }

private val ApplicationInfo.isSystemApp: Boolean
    get() = flags and ApplicationInfo.FLAG_SYSTEM != 0 || flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0

private fun parseComponent(target: String): ComponentName? {
    if (!target.contains('/')) return null
    return ComponentName.unflattenFromString(target)
}

private fun looksLikeUri(target: String): Boolean =
    target.startsWith("http://", ignoreCase = true) ||
        target.startsWith("https://", ignoreCase = true) ||
        target.substringBefore(':', missingDelimiterValue = "").matches(Regex("[a-zA-Z][a-zA-Z0-9+.-]*"))

private fun openFlags(): Int =
    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED

private fun String.normalizeForMatch(): String =
    trim().lowercase(Locale.ROOT)

private fun resolveKeyCode(rawKey: String): Int {
    val normalized = rawKey.trim().uppercase(Locale.ROOT).removePrefix("KEYCODE_")
    return when (normalized) {
        "OK", "CENTER", "SELECT" -> KeyEvent.KEYCODE_DPAD_CENTER
        "LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT
        "RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
        "UP" -> KeyEvent.KEYCODE_DPAD_UP
        "DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
        else -> {
            val keyCode = KeyEvent.keyCodeFromString("KEYCODE_$normalized")
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                error("Unknown Android key: $rawKey")
            }
            keyCode
        }
    }
}
