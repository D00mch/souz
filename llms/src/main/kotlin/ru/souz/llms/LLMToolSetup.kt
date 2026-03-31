package ru.souz.llms

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper


interface LLMToolSetup {
    val fn: LLMRequest.Function
    suspend operator fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message
}

val restJsonMapper = jacksonObjectMapper()
