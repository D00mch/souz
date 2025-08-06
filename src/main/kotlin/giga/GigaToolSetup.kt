package com.dumch.giga

import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolSetup
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.reflect.KCallable
import kotlin.reflect.full.declaredMembers
import kotlin.reflect.full.findAnnotation


interface GigaToolSetup {
    val fn: GigaRequest.Function
    operator fun invoke(functionCall: GigaResponse.FunctionCall): GigaRequest.Message
}

val gigaJsonMapper = jacksonObjectMapper()

inline fun <reified Input> ToolSetup<Input>.toGiga(): GigaToolSetup {
    val toolSetup = this
    return object : GigaToolSetup {
        override val fn: GigaRequest.Function = GigaRequest.Function(
            name = toolSetup.name,
            description = toolSetup.description,
            parameters = GigaRequest.Parameters(
                "object",
                properties = HashMap<String, GigaRequest.Property>().apply {
                    val clazz = Input::class
                    for (kProperty: KCallable<*> in clazz.declaredMembers) {
                        val annotation = kProperty.findAnnotation<InputParamDescription>() ?: continue
                        val description = annotation.value
                        val type = kProperty.returnType.toString().substringAfterLast(".").lowercase()
                        val gigaProperty = GigaRequest.Property(type, description)
                        put(kProperty.name, gigaProperty)
                    }
                }
            )
        )

        override fun invoke(
            functionCall: GigaResponse.FunctionCall,
        ): GigaRequest.Message {
            return try {
                val input: Input = gigaJsonMapper.convertValue(functionCall.arguments, Input::class.java)
                val toolResult = toolSetup.invoke(input)
                val gigaResult = gigaJsonMapper.writeValueAsString(
                    mapOf("result" to toolResult)
                )
                GigaRequest.Message(
                    role = GigaMessageRole.function,
                    content = gigaResult,
                )
            } catch (e: Exception) {
                e.toGigaToolMessage()
            }
        }
    }
}

fun Exception.toGigaToolMessage(): GigaRequest.Message {
    return GigaRequest.Message(
        role = GigaMessageRole.function,
        content = """{"result": "${message ?: toString()}"}""",
    )
}