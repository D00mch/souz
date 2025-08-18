package com.dumch.tool.coder

import com.dumch.giga.objectMapper
import com.dumch.keys.MrRobot
import com.dumch.tool.FewShotExample
import com.dumch.tool.InputParamDescription
import com.dumch.tool.ReturnParameters
import com.dumch.tool.ReturnProperty
import com.dumch.tool.ToolSetup

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