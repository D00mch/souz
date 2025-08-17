package com.dumch.tool.desktop

import com.dumch.tool.*
import org.slf4j.LoggerFactory

object ToolWindowsManager : ToolSetup<ToolWindowsManager.Input> {
    private val l = LoggerFactory.getLogger(ToolWindowsManager::class.java)

    data class Input(
        @InputParamDescription("Action to perform")
        val action: Action,
        @InputParamDescription("Additional parameters, e.g., \"+50\" for resize_up action")
        val meta: String,
    )

    enum class Action {
        layout_tiles_vertical,
        layout_tiles_horizontal,
        layout_fullscreen,
        focus_left,
        focus_right,
        focus_up,
        focus_app,
        focus_down,
        move_left,
        move_right,
        move_up,
        move_down,
        resize_width,
        resize_height,
        resize_smart,
        go_to_workspace,
        move_app_to_workspace,
    }

    override val name: String = "WindowsManager"
    override val description: String = "Allows to control windows manager: change window size, move, focus, " +
            "layout, etc."

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Расположи окна вертикально",
            params = mapOf("action" to Action.layout_tiles_vertical, "meta" to "")
        ),
        FewShotExample(
            request = "Расположи приложения по горизонтали",
            params = mapOf("action" to Action.layout_tiles_horizontal, "meta" to "")
        ),
        FewShotExample(
            request = "Переключи окна в стэк",
            params = mapOf("action" to Action.layout_fullscreen, "meta" to "")
        ),
        FewShotExample(
            request = "Переключи на полноэкранный режим",
            params = mapOf("action" to Action.layout_fullscreen, "meta" to "")
        ),
        FewShotExample(
            request = "Перенеси фокус на окно левее",
            params = mapOf("action" to Action.focus_left, "meta" to "")
        ),
        FewShotExample(
            request = "Перейди на правое приложение",
            params = mapOf("action" to Action.focus_right, "meta" to "")
        ),
        FewShotExample(
            request = "Сделай окошко немного пошире",
            params = mapOf("action" to Action.resize_width, "meta" to "+50")
        ),
        FewShotExample(
            request = "Сделай окно меньше",
            params = mapOf("action" to Action.resize_smart, "meta" to "-150")
        ),
        FewShotExample(
            request = "Перенеси приложение на первый экран",
            params = mapOf("action" to Action.move_app_to_workspace, "meta" to "1")
        ),
        FewShotExample(
            request = "Перекинь апп на первый workspace",
            params = mapOf("action" to Action.move_app_to_workspace, "meta" to "1")
        ),
        FewShotExample(
            request = "Переведи фокус на приложение Хром",
            params = mapOf("action" to Action.focus_app, "meta" to "com.google.Chrome")
        )
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Command result text.")
        )
    )

    override fun invoke(input: Input): String {
        val cmd = when(input.action) {
            Action.layout_tiles_horizontal -> "layout tiles horizontal"
            Action.layout_tiles_vertical -> "layout tiles vertical"
            Action.layout_fullscreen -> "layout accordion horizontal vertical"
            Action.focus_left -> "focus left"
            Action.focus_right -> "focus right"
            Action.focus_up -> "focus up"
            Action.focus_down -> "focus down"
            Action.focus_app -> return ToolOpen(ToolRunBashCommand).invoke(ToolOpen.Input(input.meta))
            Action.move_left -> "move left"
            Action.move_right -> "move right"
            Action.move_up -> "move up"
            Action.move_down -> "move down"
            Action.resize_width -> "resize width ${input.meta}"
            Action.resize_height -> "resize down ${input.meta}"
            Action.resize_smart -> "resize smart ${input.meta}"
            Action.go_to_workspace -> "workspace ${input.meta}"
            Action.move_app_to_workspace -> "move-node-to-workspace ${input.meta}"
        }
        try {
            l.info("Executing command: $cmd")
            runAerospace(*cmd.split(" ").toTypedArray())
            return "Done"
        } catch (e: Exception) {
            l.error("ToolWindowsManager failed", e)
            throw RuntimeException("ToolWindowsManager failed: ${e.message}", e)
        }
    }

    fun runAerospace(vararg args: String, timeoutMs: Long = 500): String {
        val uid = ProcessBuilder("/usr/bin/id", "-u")
            .redirectErrorStream(true)
            .start()
            .apply { outputStream.close() }
            .inputStream.bufferedReader().readText().trim()

        val cmd = listOf("/bin/launchctl", "asuser", uid, "/opt/homebrew/bin/aerospace") + args
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        p.outputStream.close() // important: close stdin
        val out = p.inputStream.bufferedReader()
        if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            error("aerospace timed out after ${timeoutMs}ms")
        }
        return out.readText()
    }
}

// Usage
fun main() {
    val t = ToolWindowsManager
//    println(t.invoke(ToolWindowsManager.Input(ToolWindowsManager.Action.list_apps, "")))
//    println(t.invoke(ToolWindowsManager.Input(ToolWindowsManager.Action.move_app_to_workspace, "2")))
    println(t.invoke(ToolWindowsManager.Input(ToolWindowsManager.Action.focus_app, "com.google.Chrome")))
//    t.invoke(ToolWindowsManager.Input(ToolWindowsManager.Action.go_to_workspace, "2"))

//    t.invoke(ToolWindowsManager.Input(ToolWindowsManager.Action.move_left, ""))
//    t.invoke(ToolWindowsManager.Input(ToolWindowsManager.Action.layout_tiles_horizontal, ""))
//    t.invoke(ToolWindowsManager.Input(ToolWindowsManager.Action.resize_width, "-70"))
}
