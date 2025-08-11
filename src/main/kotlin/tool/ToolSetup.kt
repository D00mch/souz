package com.dumch.tool

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class InputParamDescription(val value: String)

data class FewShotExample(val request: String, val params: Map<String, Any>)

data class ReturnProperty(val type: String, val description: String? = null)

data class ReturnParameters(
    val type: String = "object",
    val properties: Map<String, ReturnProperty>
)

/**
 * [Input] should be a data class with all the properties annotated with the [InputParamDescription]
 * TODO: add compile time check for the above rule
 */
interface ToolSetup<Input> {

    val name: String
    val description: String

    val fewShotExamples: List<FewShotExample>
    val returnParameters: ReturnParameters

    operator fun invoke(input: Input): String
    suspend fun suspendInvoke(input: Input): String = invoke(input)
}

interface ToolSetupWithAttachments<Input> : ToolSetup<Input> {
    val attachments: List<String>
}

class BadInputException(msg: String) : Exception(msg)
