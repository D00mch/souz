package ru.gigadesk.db

import ru.gigadesk.giga.objectMapper
import ru.gigadesk.tool.ToolRunBashCommand
import ru.gigadesk.tool.browser.ToolSafariInfo
import com.fasterxml.jackson.module.kotlin.readValue
import ru.gigadesk.tool.browser.ToolChromeInfo
import ru.gigadesk.tool.browser.detectDefaultBrowser
import ru.gigadesk.tool.config.ToolInstructionStore
import ru.gigadesk.tool.config.ToolInstructionStore.Companion.buildInstruction
import ru.gigadesk.tool.application.ToolShowApps
import ru.gigadesk.tool.files.ToolListFiles
import java.util.ArrayList
import kotlin.collections.map
import ru.gigadesk.tool.browser.BrowserType

/**
 * Collects various desktop information and converts it to a list of data
 * ready for embedding.
 */
object DesktopDataExtractor {
    fun all(): List<StorredData> {
        val installed = runCatching {
            val json = ToolShowApps.invoke(ToolShowApps.Input(ToolShowApps.AppState.installed))
            val arr: List<Map<String, String>> = objectMapper.readValue(json)
            arr.map {
                val (appName, appBundleId) = it["app-name"] to it["app-bundle-id"]
                val text = "Приложение: $appName, bundleId: $appBundleId"
                StorredData(text, StorredType.INSTALLED_APPS)
            }
        }.getOrElse { emptyList() }

        val instructions = runCatching {
            val list = ConfigStore.get<ArrayList<ToolInstructionStore.Input>>(
                ToolInstructionStore.INSTUCTIONS_KEY,
                ArrayList(),
            )
            list.map { input ->
                StorredData(buildInstruction(input.name, input.action), StorredType.INSTRUCTIONS)
            }
        }.getOrElse { emptyList() }

        // Собираем всё вместе: приложения + файлы + браузер + история + инструкции
        return installed +
                files().toList() +
                browserHistory(50) +
                instructions +
                facts +
                notes()
    }

    fun files(): Sequence<StorredData> = runCatching {
        val res = ToolListFiles.invoke(ToolListFiles.Input(System.getenv("HOME"), 3))
        res.trim('[', ']')
            .splitToSequence(',')
            .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }    // skip empty lines
            .filterNot { it.split('/').any { s -> s.startsWith('.') } } // skip hidden files
            .map { path -> StorredData(path, StorredType.FILES) }
    }.getOrElse { emptySequence() }

    fun browserHistory(count: Int = 10): List<StorredData> {
        return runCatching {
            val browserType = ToolRunBashCommand.detectDefaultBrowser()

            val rawHistory = when (browserType) {
                BrowserType.CHROME -> {
                    ToolChromeInfo(ToolRunBashCommand).invoke(
                        ToolChromeInfo.Input(ToolChromeInfo.InfoType.history, count = count)
                    )
                }
                else -> {
                    // Используем Safari как дефолт
                    ToolSafariInfo(ToolRunBashCommand).invoke(
                        ToolSafariInfo.Input(ToolSafariInfo.InfoType.history, count = count)
                    )
                }
            }

            val lines = rawHistory.lines()
            val uniqueUrls = HashSet<String>()

            // Парсим результаты
            lines.mapNotNull { historyLine ->
                if (historyLine.isBlank()) return@mapNotNull null

                // limit = 3, чтобы разделители внутри заголовка не ломали логику
                val parts = historyLine.split("|", limit = 3)
                if (parts.size < 3) return@mapNotNull null

                val date = parts[0].trim()
                val url = parts[1].trim()
                val title = parts[2].trim()

                if (!uniqueUrls.add(url)) return@mapNotNull null

                StorredData("$title, ${url.take(100)}, $date", StorredType.BROWSER_HISTORY)
            }
        }.getOrElse {
            // e.printStackTrace() // Можно раскомментировать для отладки
            emptyList()
        }
    }

    fun notes(): List<StorredData> = runCatching {
        val script = """
set AppleScript's text item delimiters to linefeed
tell application "Notes" to set xs to name of notes
return xs as text
            """.trimIndent()
        val raw = ToolRunBashCommand.apple(script)
        raw.lines().map { StorredData(it, StorredType.NOTES) }
    }.getOrElse { emptyList() }

    val facts: List<StorredData> = listOf(
        "Поиск по хабр, (замени <query> на слово поиска): https://habr.com/ru/search/?q=<query>",
    ) .map { StorredData(it, StorredType.GENERAL_FACT) }
}

fun List<StorredData>.asString(): String = groupBy { it.type }.entries.joinToString(":\n\n") { (type, dataList) ->
    val prefix = when (type) {
        StorredType.FILES -> "Файлы на моём компьютере"
        StorredType.BROWSER_HISTORY -> "История браузера"
        StorredType.GENERAL_FACT -> "Полезные сведения"
        StorredType.NOTES -> "Заметки"
        StorredType.DEFAULT_BROWSER -> "Используемый браузер"
        StorredType.INSTALLED_APPS -> "Установленные приложения"
        StorredType.INSTRUCTIONS -> "Сохраненные инструкции — выполняй их, если услышишь одно слово"
    }
    "$prefix:\n${dataList.joinToString(";\n") { it.text }}"
}

fun main() {
    println(DesktopDataExtractor.browserHistory().asString())
}