package ru.souz.tool.mail

import ru.souz.llms.ToolInvocationMeta

import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.ToolSetup

class ToolMailListMessages(private val bash: ToolRunBashCommand) : ToolSetup<ToolMailListMessages.Input> {
    data class Input(
        @InputParamDescription("Number of messages to list (default 10)")
        val count: Int? = 10,
    )

    override val name: String = "MailListMessages"
    override val description: String =
        "List the latest messages in the Inbox. Output includes Date and AgeDays. For urgent/important checks, treat messages as urgent only when they are relevant to the current date (typically AgeDays <= 3) and never by subject keywords alone."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Перечисли последние пять писем",
            params = mapOf("count" to 5)
        ),
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Mail operation result")
        )
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String {
        return bash.sh(MailAppleScriptCommands.listMessagesCommand(input.count ?: 10))
    }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String = invoke(input, meta)
}

fun main() {
    val result = ToolMailListMessages(ToolRunBashCommand).invoke(ToolMailListMessages.Input(10), ToolInvocationMeta.Empty)
    println(result)
}
