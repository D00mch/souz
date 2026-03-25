package ru.souz.tool.application

import org.slf4j.LoggerFactory
import ru.souz.db.ConfigStore
import ru.souz.db.SettingsProviderImpl
import ru.souz.tool.*
import ru.souz.tool.desktop.ToolOpenFolder
import ru.souz.tool.files.FilesToolUtil
import java.awt.Desktop
import java.io.File
import java.net.URI

class ToolOpen(
    private val bash: ToolRunBashCommand,
    private val filesToolUtil: FilesToolUtil,
) : ToolSetup<ToolOpen.Input> {
    private val l = LoggerFactory.getLogger(ToolOpen::class.java)

    data class Input(
        @InputParamDescription("Bundle id, like `com.jetbrains.intellij.ce`, " +
                "path to a file or folder like `app/file/folder`, or just a name like `Downloads`")
        val target: String
    )

    override val name: String = "Open"
    override val description: String = "Opens apps, files or folders. If you have two candidates to open, choose" +
            " the one with the shortest path, but tell the user that there are other options." +
            " before run application, you must check if the app is installed on the system and its app-bundle-id."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Открой Safari",
            params = mapOf("target" to "com.apple.Safari")
        ),
        FewShotExample(
            request = "Запусти погоду",
            params = mapOf("target" to "/System/Applications/Weather.app")
        ),
        FewShotExample(
            request = "Открой загрузки",
            params = mapOf("target" to "~/path/to/Downloads")
        ),
        FewShotExample(
            request = "Открой Телеграм",
            params = mapOf("target" to "ru.keepcoder.Telegram")
        ),
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status")
        )
    )

    override fun describeAction(input: Input): ToolActionDescriptor? {
        val fixedPath = filesToolUtil.applyDefaultEnvs(input.target).trim()
        if (fixedPath.startsWith("http://") || fixedPath.startsWith("https://")) {
            return ToolActionKind.OPEN_LINK.action()
        }

        val file = File(fixedPath)
        return when {
            file.exists() && file.isDirectory -> ToolActionKind.OPEN_FOLDER.folderAction(fixedPath)
            file.exists() -> ToolActionKind.OPEN_FILE.fileAction(fixedPath)
            fixedPath.endsWith(".app") || fixedPath.contains('.') -> ToolActionKind.OPEN_APPLICATION.action()
            else -> ToolActionKind.OPEN_FOLDER.appTargetAction(input.target)
        }
    }


    override fun invoke(input: Input): String {
        val desktop = Desktop.getDesktop().takeIf { Desktop.isDesktopSupported() }
        val fixedPath = filesToolUtil.applyDefaultEnvs(input.target)
            .replace("\n","")
            .replace("\\r","")
            .replace("\\\n","")
            .replace("\\\\n","")
            .replace("{","")
            .replace("}","")

        if (fixedPath.startsWith("http://") || fixedPath.startsWith("https://")) {
            if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(URI(fixedPath))
                return "Done"
            }
        }

        val f = File(fixedPath)
        if (f.exists()) {
            if (desktop != null && desktop.isSupported(Desktop.Action.OPEN)) {
                desktop.open(f) // opens file with default app OR opens folder in file manager
                return "Done"
            }
        }

        return openViaOsFallback(fixedPath)
    }

    private fun openViaOsFallback(fixedPath: String): String = try {
        when {
            fixedPath.contains('/') -> {
                val isDir = !fixedPath.endsWith(".app") && File(fixedPath).isDirectory
                if (isDir) {
                    bash.sh("""open -R "$fixedPath"""")
                } else {
                    bash.sh("""open "$fixedPath"""")
                }
                "Done"
            }

            fixedPath.contains('.') -> {
                if (File(fixedPath).exists()) {
                    bash.sh("""open "$fixedPath"""")
                } else {
                    bash.sh("""open -b "$fixedPath"""")
                }
                "Done"
            }

            else -> {
                ToolOpenFolder(bash, filesToolUtil).invoke(ToolOpenFolder.Input(File(fixedPath).name))
                "Done"
            }
        }
    } catch (e: Exception) {
        l.error("Error opening '$fixedPath': ${e.message}")
        ToolOpenFolder(bash, filesToolUtil).invoke(ToolOpenFolder.Input(File(fixedPath).name))
    }
}

fun main() {
    val filesToolUtil = FilesToolUtil(SettingsProviderImpl(ConfigStore))
    val result = ToolOpen(ToolRunBashCommand, filesToolUtil).invoke(ToolOpen.Input("ru.keepcoder.Telegram"))
    println(result)
}
