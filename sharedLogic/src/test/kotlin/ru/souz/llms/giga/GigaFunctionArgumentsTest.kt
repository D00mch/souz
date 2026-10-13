package ru.souz.llms.giga

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import ru.souz.llms.LLMResponse
import ru.souz.llms.restJsonMapper

class GigaFunctionArgumentsTest {
    @Test
    fun `ordinary and streaming calls preserve explicit null arguments in both wire formats`() {
        val arguments = restJsonMapper.readTree(
            """{"requiredNull":null,"optionalNull":null,"text":"hello","count":2,"enabled":false,"nested":{"value":null},"items":[null,"a"]}""",
        )
        for (encodedAsString in listOf(false, true)) {
            val encoded = if (encodedAsString) restJsonMapper.writeValueAsString(arguments.toString()) else arguments.toString()
            for (streaming in listOf(false, true)) {
                val messageKey = if (streaming) "delta" else "message"
                val response = """{"choices":[{"index":0,"$messageKey":{"role":"assistant","content":"","function_call":{"name":"client.nullable","arguments":$encoded}},"finish_reason":"function_call"}],"created":1,"model":"GigaChat","usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"precached_prompt_tokens":0}}"""
                val result = if (streaming) parseGigaStreamChunk(response)
                else restJsonMapper.readValue(response, LLMResponse.Chat.Ok::class.java)
                val call = assertIs<LLMResponse.Chat.Ok>(result).choices.single().message.functionCall!!

                assertEquals("client.nullable", call.name)
                assertEquals(arguments, restJsonMapper.valueToTree(call.arguments), "streaming=$streaming, encodedAsString=$encodedAsString")
            }
        }
    }
}
