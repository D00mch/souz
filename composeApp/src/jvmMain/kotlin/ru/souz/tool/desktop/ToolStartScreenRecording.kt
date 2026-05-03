package ru.souz.tool.desktop

import ru.souz.llms.ToolInvocationMeta

import ru.souz.tool.*

class ToolStartScreenRecording(
    private val bash: ToolRunBashCommand,
) : ToolSetup<ToolStartScreenRecording.Input> {

    data class Input(
        @InputParamDescription("No parameters needed")
        val ignored: String = ""
    )

    override val name: String = "StartScreenRecording"
    override val description: String = "Opens the system screen recording utility (analogous to Cmd+Shift+5 on macOS). " +
            "Does not start recording automatically, just shows the controls."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Запиши экран",
            params = mapOf()
        ),
        FewShotExample(
            request = "Включи запись видео",
            params = mapOf()
        ),
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Status message")
        )
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String {
        bash.sh("open -a Screenshot")
        return "Screen recording utility launched. User must click 'Record' to start."
    }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String = invoke(input, meta)
}
