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
 * Collects various desktop information and converts it to a list of data
 * ready for embedding.
 */
object DesktopDataExtractor {
    fun all(): List<StorredData> {
        val installed = runCatching {
            val json = ToolShowApps.invoke(ToolShowApps.Input(ToolShowApps.AppState.installed))
            val arr: List<Map<String, String>> = objectMapper.readValue(json)
            arr.map { StorredData(it["app-name"] ?: "", StorredType.INSTALLED_APPS) }
        }.getOrElse { emptyList() }

        val instructions = runCatching {
            val list = ConfigStore.get<ArrayList<ToolInstructionStore.Input>>(
                ToolInstructionStore.INSTUCTIONS_KEY,
                ArrayList(),
            )
            list.map { inp ->
                StorredData("${inp.name} -> ${inp.action}", StorredType.INSTRUCTIONS)
            }
        }.getOrElse { emptyList() }

        return installed + files().toList() + instructions + notes() + browserHistory(500)
    }

    fun files(): Sequence<StorredData> = runCatching {
        val res = ToolListFiles.invoke(ToolListFiles.Input(System.getenv("HOME"), 3))
        res.trim('[', ']')
            .splitToSequence(',')
            .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
            .filterNot { it.split('/').any { s -> s.startsWith('.') } }
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
                StorredData("$title, ${url.take(50)}, $date", StorredType.BROWSER_HISTORY)
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

fun main() {
    val logObjectMapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    println(
        logObjectMapper.writeValueAsString(
            DesktopDataExtractor.all()
        )
    )
}
