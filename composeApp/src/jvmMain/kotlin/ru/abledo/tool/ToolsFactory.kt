package ru.abledo.tool

import ru.abledo.db.DesktopInfoRepository
import ru.abledo.giga.GigaToolSetup
import ru.abledo.giga.toGiga
import ru.abledo.tool.browser.ToolBrowserHotkeys
import ru.abledo.tool.browser.ToolCreateNewBrowserTab
import ru.abledo.tool.browser.ToolFocusOnTab
import ru.abledo.tool.browser.ToolSafariInfo
import ru.abledo.tool.coder.ToolRequestSelection
import ru.abledo.db.ConfigStore
import ru.abledo.tool.browser.ToolChromeInfo
import ru.abledo.tool.coder.ToolFindInFiles
import ru.abledo.tool.config.ToolInstructionStore
import ru.abledo.tool.config.ToolSoundConfig
import ru.abledo.tool.config.ToolSoundConfigDiff
import ru.abledo.tool.dataAnalytics.ToolCreatePlotFromCsv
import ru.abledo.tool.desktop.*
import ru.abledo.tool.files.*

class ToolsFactory(private val repo: DesktopInfoRepository) {
    val toolsByCategory: Map<ToolCategory, Map<String, GigaToolSetup>> by lazy {
        mapOf(
            ToolCategory.CODER to listOf(
                ToolReadFile.toGiga(),
                ToolListFiles.toGiga(),
                ToolFindInFiles.toGiga(),
                ToolNewFile.toGiga(),
                ToolDeleteFile.toGiga(),
                ToolModifyFile.toGiga(),
                ToolFindTextInFiles.toGiga(),
                ToolRequestSelection.toGiga(),
            ).associateBy { it.fn.name },

            ToolCategory.DATAANALYTICS to listOf(
                ToolCreatePlotFromCsv(ToolRunBashCommand).toGiga(),
                ToolUploadFile().toGiga(),
                ToolDownloadFile().toGiga(),
            ).associateBy { it.fn.name },

            ToolCategory.BROWSER to listOf(
                ToolCreateNewBrowserTab(ToolRunBashCommand).toGiga(),
                ToolSafariInfo(ToolRunBashCommand).toGiga(),
                ToolBrowserHotkeys().toGiga(),
                ToolFocusOnTab(ToolRunBashCommand).toGiga(),
                ToolChromeInfo(ToolRunBashCommand).toGiga(),
            ).associateBy { it.fn.name },

            ToolCategory.CONFIG to listOf(
                ToolSoundConfig(ConfigStore).toGiga(),
                ToolSoundConfigDiff(ConfigStore).toGiga(),
                ToolInstructionStore(ConfigStore, repo).toGiga(),
            ).associateBy { it.fn.name },

            ToolCategory.DESKTOP to listOf(
                ToolWindowsManager.toGiga(),
                //ToolHotkeyMac().toGiga(), // we should provide deliberate tools
                ToolOpenNote(ToolRunBashCommand).toGiga(),
                //ToolOpenTelegramSavedMessages(ToolRunBashCommand).toGiga(),
                //ToolMediaControl(ToolRunBashCommand).toGiga(),
                ToolOpen(ToolRunBashCommand).toGiga(),
                ToolCreateNote(ToolRunBashCommand).toGiga(),
                //ToolMinimizeWindows(ToolRunBashCommand).toGiga(),
                ToolSendTelegramMessage(ToolRunBashCommand).toGiga(),
//              ToolOpenFolder(ToolRunBashCommand).toGiga(), // ToolOpen can do it
//                ToolMouseClickMac().toGiga(),
//                ToolCollectButtons(ToolRunBashCommand).toGiga(), // too slow, only for mouse
//                ToolShowApps.toGiga(), // we get it by default anyway
            ).associateBy { it.fn.name },

//            ToolCategory.IO to listOf(
//                ToolUploadFile().toGiga(),
//                ToolDownloadFile().toGiga(),
////                ToolDesktopScreenShot().toGiga(),
//                ToolReadScreenText().toGiga(),
//            ).associateBy { it.fn.name },
        )
    }
}