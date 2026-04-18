package ru.souz.llms

import kotlin.test.Test
import kotlin.test.assertFalse

class LLMRequestEmbeddingsSerializationTest {

    @Test
    fun `embeddings inputKind is not serialized`() {
        val json = restJsonMapper.writeValueAsString(
            LLMRequest.Embeddings(
                input = listOf("hello"),
                inputKind = EmbeddingInputKind.DOCUMENT,
            )
        )

        assertFalse(json.contains("inputKind"))
        assertFalse(json.contains("DOCUMENT"))
    }
}
