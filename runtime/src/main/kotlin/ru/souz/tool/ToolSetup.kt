package ru.souz.tool

import ru.souz.llms.ToolInvocationMeta

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
 *
 * Tool setups are singleton components. Request-scoped runtime data must not be captured in fields.
 *
 * Use [ToolInvocationMeta] for invocation-specific values such as:
 * - `userId`
 * - `conversationId`
 * - `requestId`
 * - locale/time-zone hints
 *
 * File-aware and runtime-aware tools should resolve the active sandbox, filesystem, or per-request
 * state at invocation time from [ToolInvocationMeta] instead of storing request-scoped dependencies.
 *
 * Desktop callers may use local-default metadata and scope resolution.
 * Backend callers must provide `userId` in [ToolInvocationMeta] so singleton tools can resolve the
 * correct user-scoped sandbox for each invocation.
 */
interface ToolSetup<Input> {

    val name: String
    val description: String

    val fewShotExamples: List<FewShotExample>
    val returnParameters: ReturnParameters

    operator fun invoke(input: Input, meta: ToolInvocationMeta): String

    suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String
}

interface ToolSetupWithAttachments<Input> : ToolSetup<Input> {
    val attachments: List<String>
}

class BadInputException(msg: String) : Exception(msg)
