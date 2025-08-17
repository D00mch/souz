package com.dumch.db

import com.dumch.giga.objectMapper
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.browser.ToolSafariInfo
import com.fasterxml.jackson.module.kotlin.readValue
import com.dumch.tool.config.ConfigStore
import com.dumch.tool.config.ToolInstructionStore
import com.dumch.tool.desktop.ToolShowApps
import com.dumch.tool.files.ToolListFiles
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.ArrayList
import kotlin.collections.map

/**
 * Collects various desktop information and converts it to a list of strings
 * ready for embedding.
 */
object DesktopDataExtractor {
    fun extract(): List<String> {
        val installed = runCatching {
            val json = ToolShowApps.invoke(ToolShowApps.Input(ToolShowApps.AppState.installed))
            val arr: List<Map<String, String>> = objectMapper.readValue(json)
            arr.map { "У меня есть установленное приложение ${it["app-name"]}" }
        }.getOrElse { emptyList() }

        val dirs = runCatching {
            val res = ToolListFiles.invoke(ToolListFiles.Input(System.getenv("HOME"), 3))
            res.trim('[', ']').split(',')
                .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
                .map { "На моём PC есть папка $it" }
        }.getOrElse { emptyList() }

        val instructions = runCatching {
            val list = ConfigStore.get<ArrayList<ToolInstructionStore.Input>>(ToolInstructionStore.INSTUCTIONS_KEY, ArrayList())
            list.map { inp ->
                "Помни о такой инструкции: Когда я говорю: `${inp.name}`, выполняй инструкцию: ${inp.action}"
            }
        }.getOrElse { emptyList() }

        return installed + dirs + instructions + notes() + browserHistory(500)
    }

    fun browserHistory(count: Int = 10): List<String> {
        return runCatching {
            val lines = ToolSafariInfo(ToolRunBashCommand).invoke(
                ToolSafariInfo.Input(
                    ToolSafariInfo.InfoType.history,
                    count = count
                )
            )
                .lines()
            lines.map { historyLine ->
                val (date, url, title) = historyLine.split("|")
                "В истории браузера есть запись: $title, url: $url, дата: $date"
            }
        }.getOrElse { emptyList() }
    }

    fun notes(): List<String> {
        return runCatching {
            val script = """
                tell application "Notes"
                    set allNotes to name of every note
                    return allNotes
                end tell
            """.trimIndent()

            ToolRunBashCommand.apple(script)
                .split(",")
                .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
                .map { "У меня есть заметка: $it" }
        }.getOrElse { emptyList() }
    }
}

fun main() {
    val logObjectMapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    println(
        logObjectMapper.writeValueAsString(
            DesktopDataExtractor.extract()
        )
    )
}