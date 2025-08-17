package com.dumch.tool

import com.dumch.db.DesktopInfoRepository
import com.dumch.giga.GigaToolSetup
import com.dumch.giga.toGiga
import com.dumch.tool.browser.ToolBrowserHotkeys
import com.dumch.tool.browser.ToolCreateNewBrowserTab
import com.dumch.tool.browser.ToolSafariInfo
import com.dumch.tool.coder.ToolRequestSelection
import com.dumch.tool.config.ConfigStore
import com.dumch.tool.config.ToolInstructionStore
import com.dumch.tool.config.ToolSoundConfig
import com.dumch.tool.config.ToolSoundConfigDiff
import com.dumch.tool.desktop.ToolCollectButtons
import com.dumch.tool.desktop.ToolCreateNote
import com.dumch.tool.dataAnalytics.ToolCreatePlotFromCsv
import com.dumch.tool.desktop.ToolDesktopScreenShot
import com.dumch.tool.desktop.ToolDownloadFile
import com.dumch.tool.desktop.ToolHotkeyMac
import com.dumch.tool.desktop.ToolMediaControl
import com.dumch.tool.desktop.ToolMinimizeWindows
import com.dumch.tool.desktop.ToolMouseClickMac
import com.dumch.tool.desktop.ToolOpen
import com.dumch.tool.desktop.ToolOpenFolder
import com.dumch.tool.desktop.ToolReadScreenText
import com.dumch.tool.desktop.ToolSendTelegramMessage
import com.dumch.tool.desktop.ToolUploadFile
import com.dumch.tool.desktop.ToolWindowsManager
import com.dumch.tool.files.ToolDeleteFile
import com.dumch.tool.files.ToolFindTextInFiles
import com.dumch.tool.files.ToolListFiles
import com.dumch.tool.files.ToolModifyFile
import com.dumch.tool.files.ToolNewFile
import com.dumch.tool.files.ToolReadFile
import com.dumch.tool.browser.ToolFocusOnTab
import com.dumch.tool.desktop.ToolOpenNote
import com.dumch.tool.desktop.ToolOpenTelegramSavedMessages

class ToolsFactory(private val repo: DesktopInfoRepository) {
    val toolsByCategory: Map<ToolCategory, Map<String, GigaToolSetup>> by lazy {
        mapOf(
            ToolCategory.CODER to listOf(
                ToolReadFile.toGiga(),
                ToolListFiles.toGiga(),
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
            ).associateBy { it.fn.name },

            ToolCategory.CONFIG to listOf(
                ToolSoundConfig(ConfigStore).toGiga(),
                ToolSoundConfigDiff(ConfigStore).toGiga(),
                ToolInstructionStore(ConfigStore, repo).toGiga(),
            ).associateBy { it.fn.name },

            ToolCategory.DESKTOP to listOf(
                ToolWindowsManager.toGiga(),
                ToolMouseClickMac().toGiga(),
                ToolHotkeyMac().toGiga(),
                ToolOpenNote(ToolRunBashCommand).toGiga(),
                ToolOpenTelegramSavedMessages(ToolRunBashCommand).toGiga(),
                ToolMediaControl(ToolRunBashCommand).toGiga(),
                ToolCollectButtons(ToolRunBashCommand).toGiga(),
                ToolOpen(ToolRunBashCommand).toGiga(),
                ToolCreateNote(ToolRunBashCommand).toGiga(),
                ToolMinimizeWindows(ToolRunBashCommand).toGiga(),
                ToolOpenFolder(ToolRunBashCommand).toGiga(),
                ToolSendTelegramMessage(ToolRunBashCommand).toGiga(),
                // ToolShowApps.toGiga(), // we get it by default anyway
            ).associateBy { it.fn.name },

            ToolCategory.IO to listOf(
                ToolUploadFile().toGiga(),
                ToolDownloadFile().toGiga(),
                ToolDesktopScreenShot().toGiga(),
                ToolReadScreenText().toGiga(),
            ).associateBy { it.fn.name },
        )
    }
}