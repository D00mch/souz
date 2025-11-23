package ru.abledo.db

import ru.abledo.giga.objectMapper
import ru.abledo.tool.ToolRunBashCommand
import ru.abledo.tool.browser.ToolSafariInfo
import com.fasterxml.jackson.module.kotlin.readValue
import ru.abledo.tool.config.ToolInstructionStore
import ru.abledo.tool.config.ToolInstructionStore.Companion.buildInstruction
import ru.abledo.tool.desktop.ToolShowApps
import ru.abledo.tool.files.ToolListFiles
import java.util.ArrayList
import kotlin.collections.map

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

        return installed + files().toList() + instructions + browserHistory(50) // + notes()
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
            val lines = ToolSafariInfo(ToolRunBashCommand).invoke(
                ToolSafariInfo.Input(
                    ToolSafariInfo.InfoType.history,
                    count = count
                )
            ).lines()
            val uniqueUrls: HashSet<String> = HashSet()
            lines.mapNotNull { historyLine ->
                val (date, url, title) = historyLine.split("|")
                if (!uniqueUrls.add(url)) return@mapNotNull null
                StorredData("$title, ${url.take(100)}, $date", StorredType.BROWSER_HISTORY)
            }
        }.getOrElse { emptyList() }
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
}


fun List<StorredData>.asString(): String = groupBy { it.type }.entries.joinToString(", ") { (type, dataList) ->
    val prefix = when (type) {
        StorredType.FILES -> "Файлы на моём компьютере"
        StorredType.BROWSER_HISTORY -> "История браузера"
        StorredType.NOTES -> "Заметки"
        StorredType.INSTALLED_APPS -> "Установленные приложения"
        StorredType.INSTRUCTIONS -> "Сохраненные инструкции — выполняй их, если услышишь одно слово"
    }
    "$prefix: ${dataList.map { it.text }}"
}

fun main() {
    println(DesktopDataExtractor.all().asString())
}
