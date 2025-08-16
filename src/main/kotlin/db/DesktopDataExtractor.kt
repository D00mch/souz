package com.dumch.db

import com.dumch.giga.objectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.dumch.tool.config.ConfigStore
import com.dumch.tool.config.ToolInstructionStore
import com.dumch.tool.desktop.ToolShowApps
import com.dumch.tool.files.ToolListFiles
import java.util.ArrayList

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
        val opened = runCatching {
            val json = ToolShowApps.invoke(ToolShowApps.Input(ToolShowApps.AppState.running))
            val arr: List<Map<String, String>> = objectMapper.readValue(json)
            arr.map { "Сейчас открыто приложение ${it["app-name"]}" }
        }.getOrElse { emptyList() }
        val dirs = runCatching {
            val res = ToolListFiles.invoke(ToolListFiles.Input(System.getenv("HOME"), 3))
            res.trim('[', ']').split(',')
                .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
                .map { "На моей PC есть папка $it" }
        }.getOrElse { emptyList() }
        val instructions = runCatching {
            val list = ConfigStore.get<ArrayList<ToolInstructionStore.Input>>(ToolInstructionStore.INSTUCTIONS_KEY, ArrayList())
            list.map { inp ->
                "Помни о такой инструкции: Когда я говорю: `${'$'}{inp.name}`, выполняй инструкцию: ${inp.action}"
            }
        }.getOrElse { emptyList() }
        return installed + opened + dirs + instructions
    }
}
