package ru.souz.tool.browser

import ru.souz.tool.*

class ToolFocusOnTab(private val bash: ToolRunBashCommand) : ToolSetup<ToolFocusOnTab.Input> {
    data class Input(
        @InputParamDescription("The tab index to switch to (1..9)")
        val tab: Int,
    )

    override val name: String = "FocusOnTab"
    override val description: String = "Focuses the Safari tab with the given index using hotkeys."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Перейди на вторую вкладку Safari",
            params = mapOf("tab" to 2),
        ),
        FewShotExample(
            request = "Перейди на вкладку с Яндекс Музыка",
            params = mapOf("tab" to 1),
        ),
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status, e.g., 'Done'"),
        ),
    )

    override fun invoke(input: Input): String {
        if (input.tab !in 1..9) throw BadInputException("tab must be between 1 and 9")
        val tabIndex = input.tab
        bash.invoke(
            ToolRunBashCommand.Input(
                """osascript <<EOF
                tell application "Safari"
                    activate
                end tell
                delay 0.1
                tell application "System Events"
                    keystroke ${input.tab} using command down
                end tell
                EOF""".trimIndent(),
            ),
        )
        return "Done"
    }
}

fun main() {
    val tool = ToolFocusOnTab(ToolRunBashCommand)
    println(tool.invoke(ToolFocusOnTab.Input(1)))
}