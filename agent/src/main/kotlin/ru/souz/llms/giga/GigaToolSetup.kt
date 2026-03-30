package ru.souz.llms.giga

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ru.souz.llms.GigaRequest
import ru.souz.llms.GigaResponse


interface GigaToolSetup {
    val fn: GigaRequest.Function
    suspend operator fun invoke(functionCall: GigaResponse.FunctionCall): GigaRequest.Message
}

val gigaJsonMapper = jacksonObjectMapper()
