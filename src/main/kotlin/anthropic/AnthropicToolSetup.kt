package com.dumch.anthropic

import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolSetup
import kotlin.reflect.KCallable
import kotlin.reflect.full.declaredMembers
import kotlin.reflect.full.findAnnotation

interface AnthropicToolSetup {
    val tool: Tool
    operator fun invoke(toolUse: ToolUseBlock): ToolResultBlockParam
}

val anthropicJsonMapper = jsonMapper()

inline fun <reified Input> ToolSetup<Input>.toAnthropic(): AnthropicToolSetup {
    val toolSetup = this
    val inputSchema: Tool.InputSchema = HashMap<String, Any>().let { schema ->
        val clazz = Input::class
        for (property: KCallable<*> in clazz.declaredMembers) {
            // We're not afraid of reflection here — it only runs once at startup and doesn't affect runtime.
            val annotation = property.findAnnotation<InputParamDescription>() ?: continue
            val description = annotation.value
            val type = property.returnType.toString().substringAfterLast(".").lowercase()
            val desc = mapOf("type" to type, "description" to description)
            schema[property.name] = desc
        }
        Tool.InputSchema.builder()
            .properties(JsonValue.from(schema))
            .build()
    }

    return object : AnthropicToolSetup {
        override val tool: Tool = Tool.builder()
            .name(toolSetup.name)
            .description(toolSetup.description)
            .inputSchema(inputSchema)
            .build()

        override fun invoke(toolUse: ToolUseBlock): ToolResultBlockParam {
            try {
                val input: JsonValue = toolUse._input()
                val typed: Input = anthropicJsonMapper.convertValue(input, Input::class.java)
                val result = toolSetup.invoke(typed)
                return ToolResultBlockParam.builder()
                    .content(result)
                    .toolUseId(toolUse.id())
                    .isError(false)
                    .build()
            } catch (e: Exception) {
                // TODO: proper logging should be implemented
                println(e)
                return ToolResultBlockParam.Companion.builder()
                    .content("Unpredicted exception with the tool '$name': ${e.message}")
                    .isError(true)
                    .build()
            }
        }
    }
}
