package ru.souz.llms.giga

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import ru.souz.llms.LLMResponse

class GigaChatApiContractTest {
    @Test
    fun `stream chunks keep reasoning separate from answer content`() {
        val reasoningChunk = parseGigaStreamChunk(
            """
                {
                  "choices": [
                    {
                      "index": 0,
                      "delta": {
                        "role": "assistant",
                        "reasoning_content": "Compare the available evidence.",
                        "content": null
                      },
                      "finish_reason": null
                    }
                  ],
                  "created": 1739900000,
                  "model": "GigaChat-3-Ultra"
                }
            """.trimIndent()
        ) as LLMResponse.Chat.Ok
        val answerChunk = parseGigaStreamChunk(
            """
                {
                  "choices": [
                    {
                      "index": 0,
                      "delta": {
                        "reasoning_content": null,
                        "content": "The evidence supports option A."
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "created": 1739900001,
                  "model": "GigaChat-3-Ultra"
                }
            """.trimIndent()
        ) as LLMResponse.Chat.Ok

        assertEquals("Compare the available evidence.", reasoningChunk.choices.single().message.reasoningContent)
        assertEquals("", reasoningChunk.choices.single().message.content)
        assertNull(answerChunk.choices.single().message.reasoningContent)
        assertEquals("The evidence supports option A.", answerChunk.choices.single().message.content)
    }
}
