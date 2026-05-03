package ru.souz.test

import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.ToolSetup

operator fun <Input> ToolSetup<Input>.invoke(input: Input): String = invoke(input, ToolInvocationMeta.Empty)

suspend fun <Input> ToolSetup<Input>.suspendInvoke(input: Input): String =
    suspendInvoke(input, ToolInvocationMeta.Empty)
