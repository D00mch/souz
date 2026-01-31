package ru.gigadesk.tool.application

import ru.gigadesk.tool.*
import org.slf4j.LoggerFactory
import ru.gigadesk.tool.desktop.ToolOpenFolder
import ru.gigadesk.tool.files.FilesToolUtil
import ru.gigadesk.db.ConfigStore
import ru.gigadesk.db.SettingsProvider
import java.io.File

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
            " the one with the shortest path, but tell the user that there are other options."

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
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status")
        )
    )

    override fun invoke(input: Input): String {
        val fixedPath = input.target.replace("\$HOME", System.getenv("HOME"))
            .replace("\n","")
            .replace("\\r","")
            .replace("\\\n","")
            .replace("\\\\n","")
            .replace("{","")
            .replace("}","")

        return try {
            when {
                // Если есть слэш, значит это путь к файлу или папке
                fixedPath.contains('/') -> {
                    val isDir = !fixedPath.endsWith(".app") && File(fixedPath).isDirectory
                    if (isDir) {
                        bash.sh("""open -R "$fixedPath"""")
                    } else {
                        bash.sh("""open "$fixedPath"""")
                        "Done"
                    }
                }
                // Если есть точка (например, ru.keepcoder.Telegram или file.txt)
                fixedPath.contains('.') -> {
                    if (File(fixedPath).exists()) {
                        // Если это реальный файл, открываем как файл
                        bash.sh("""open "$fixedPath"""")
                    } else {
                        // Если файла нет, считаем, что это Bundle ID приложения
                        bash.sh("""open -b "$fixedPath"""")
                    }
                    "Done"
                }
                // Иначе ищем папку
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
}

fun main() {
    val filesToolUtil = FilesToolUtil(SettingsProvider(ConfigStore))
    // Тест запуска по Bundle ID
    val result = ToolOpen(ToolRunBashCommand, filesToolUtil).invoke(ToolOpen.Input("ru.keepcoder.Telegram"))
    println(result)
}