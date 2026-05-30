package ru.souz.llms

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

interface LLMToolSetup {
    val fn: LLMRequest.Function
    suspend operator fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message
    suspend fun invoke(
        functionCall: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta,
    ): LLMRequest.Message = invoke(functionCall)
}

val restJsonMapper = jacksonObjectMapper()
