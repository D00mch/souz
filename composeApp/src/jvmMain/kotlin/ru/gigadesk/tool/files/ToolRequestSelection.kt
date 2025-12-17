package ru.gigadesk.tool.files

import ru.gigadesk.keys.MrRobot
import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolSetup

object ToolRequestSelection : ToolSetup<ToolRequestSelection.Input> {
    data class Input(
        @InputParamDescription("Allows to send only path back, true by default")
        val allowOnlyPath: Boolean = true
    )

    override val name: String = "ExplainSelection"
    override val description: String = "Use this to get the current cursor selection and path"
    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Что это за код вкратце?",
            params = mapOf("allowOnlyPath" to true)
        ),
        FewShotExample(
            request = "Расскажи подробнее о коде в selection",
            params = mapOf("allowOnlyPath" to true)
        ),
        FewShotExample(
            request = "Что делает эта функция?",
            params = mapOf("allowOnlyPath" to true)
        ),
    )
    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "path" to ReturnProperty("string", "Path to the file"),
            "code" to ReturnProperty("string", "Selected code"),
        )
    )

    override fun invoke(input: Input): String {
        MrRobot.hotKeys("cmd", "c")
        val code = MrRobot.clipboardGet()
        MrRobot.hotKeys("cmd", "shift", "c")
        val path = MrRobot.clipboardGet()
        return """
 path: $path
 
 ```
 $code
 ```
        """.trimIndent()
    }
}