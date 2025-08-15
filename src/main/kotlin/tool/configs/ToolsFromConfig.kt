package com.dumch.tool.configs

import com.dumch.tool.*

class ToolsFromConfig {
    fun getTools(): List<ToolSetup<SimpleTextInput>> {
        val t = object : ToolSetup<SimpleTextInput> {
            override val name: String = "CreateNote"
            override val description: String = "Opens Notes and create new note with text"
            override val fewShotExamples = listOf(
                FewShotExample(
                    request = "Создай заметку, чтобы купить молоко в субботу",
                    params = mapOf("noteText" to "Купить молоко в субботу")
                )
            )
            override val returnParameters = ReturnParameters(
                properties = mapOf(
                    "result" to ReturnProperty("string", "Operation status")
                )
            )

            override fun invoke(input: SimpleTextInput): String {
                TODO()
            }
        }
        return listOf(t)
    }

    data class SimpleTextInput(
        @InputParamDescription("Text of note")
        val text: String
    )

    data class ToolConfigSetup(
        val name: String,
        val description: String,
    )
}