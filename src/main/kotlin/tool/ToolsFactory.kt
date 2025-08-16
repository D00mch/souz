package com.dumch.tool

import com.dumch.giga.GigaAgent.ToolCategory
import com.dumch.giga.GigaToolSetup
import com.dumch.giga.toGiga
import com.dumch.tool.browser.ToolBrowserHotkeys
import com.dumch.tool.browser.ToolCreateNewBrowserTab
import com.dumch.tool.browser.ToolSafariInfo
import com.dumch.tool.config.ConfigStore
import com.dumch.tool.config.ToolInstructionStore
import com.dumch.tool.config.ToolSoundConfig
import com.dumch.tool.config.ToolSoundConfigDiff
import com.dumch.tool.desktop.ToolCollectButtons
import com.dumch.tool.desktop.ToolCreateNote
import com.dumch.tool.desktop.ToolDesktopScreenShot
import com.dumch.tool.desktop.ToolDownloadFile
import com.dumch.tool.desktop.ToolHotkeyMac
import com.dumch.tool.desktop.ToolMediaControl
import com.dumch.tool.desktop.ToolMinimizeWindows
import com.dumch.tool.desktop.ToolMouseClickMac
import com.dumch.tool.desktop.ToolOpen
import com.dumch.tool.desktop.ToolOpenFolder
import com.dumch.tool.desktop.ToolSendTelegramMessage
import com.dumch.tool.desktop.ToolUploadFile
import com.dumch.tool.desktop.ToolWindowsManager
import com.dumch.tool.files.ToolDeleteFile
import com.dumch.tool.files.ToolFindTextInFiles
import com.dumch.tool.files.ToolListFiles
import com.dumch.tool.files.ToolModifyFile
import com.dumch.tool.files.ToolNewFile
import com.dumch.tool.files.ToolReadFile

object ToolsFactory {
    val toolsByCategory: Map<ToolCategory, Map<String, GigaToolSetup>> by lazy {
        mapOf(
            ToolCategory.IO to listOf(
                ToolReadFile.toGiga(),
                ToolListFiles.toGiga(),
                ToolNewFile.toGiga(),
                ToolDeleteFile.toGiga(),
                ToolModifyFile.toGiga(),
                ToolFindTextInFiles.toGiga(),
            ).associateBy { it.fn.name },

            ToolCategory.BROWSER to listOf(
                ToolCreateNewBrowserTab(ToolRunBashCommand).toGiga(),
                ToolSafariInfo(ToolRunBashCommand).toGiga(),
                ToolBrowserHotkeys().toGiga(),
            ).associateBy { it.fn.name },

            ToolCategory.CONFIG to listOf(
                ToolSoundConfig(ConfigStore).toGiga(),
                ToolSoundConfigDiff(ConfigStore).toGiga(),
                ToolInstructionStore(ConfigStore).toGiga(),
            ).associateBy { it.fn.name },

            ToolCategory.DESKTOP to listOf(
                ToolWindowsManager.toGiga(),
                ToolMouseClickMac().toGiga(),
                ToolHotkeyMac().toGiga(),
                ToolUploadFile().toGiga(),
                ToolDownloadFile().toGiga(),
                ToolMediaControl(ToolRunBashCommand).toGiga(),
                ToolDesktopScreenShot().toGiga(),
                ToolCollectButtons(ToolRunBashCommand).toGiga(),
                ToolOpen(ToolRunBashCommand).toGiga(),
                ToolCreateNote(ToolRunBashCommand).toGiga(),
                ToolMinimizeWindows(ToolRunBashCommand).toGiga(),
                ToolOpenFolder(ToolRunBashCommand).toGiga(),
                ToolSendTelegramMessage(ToolRunBashCommand).toGiga(),
                // ToolShowApps.toGiga(), // we get it by default anyway
            ).associateBy { it.fn.name },
        )
    }
}