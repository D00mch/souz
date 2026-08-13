package ru.souz.llms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ModelResolutionTest {
    @Test
    fun `chat resolution normalizes names and distinguishes unavailable models`() {
        assertEquals(
            ModelResolution.Resolved(LLMModel.Max),
            resolveChatModel("  gigachat-2-max  "),
        )
        assertEquals(
            ModelResolution.Unknown("not-a-model"),
            resolveChatModel(" not-a-model "),
        )
        assertEquals(
            ModelResolution.UnsupportedProvider(LLMModel.Max, LlmProvider.GIGA),
            resolveChatModel("Max", supportedProviders = setOf(LlmProvider.OPENAI)),
        )
        assertEquals(
            ModelResolution.Unknown("GPT-5-NANO"),
            resolveChatModel(" GPT-5-NANO "),
        )
    }

    @Test
    fun `embedding resolution preserves default and configured alias preference`() {
        assertEquals(DEFAULT_EMBEDDINGS_MODEL, LLMRequest.Embeddings(input = listOf("text")).model)
        assertEquals(
            ModelResolution.Resolved(EmbeddingsModelSelection.Default),
            resolveEmbeddingsModel("  embeddings  ", configuredModel = EmbeddingsModel.QwenEmbeddings),
        )
        assertEquals(
            ModelResolution.Resolved(
                EmbeddingsModelSelection.Explicit(EmbeddingsModel.QwenEmbeddings)
            ),
            resolveEmbeddingsModel(" TEXT-EMBEDDING-V3 "),
        )
        assertEquals(
            ModelResolution.Unknown("unknown"),
            resolveEmbeddingsModel(" unknown "),
        )

        val ambiguous = assertIs<ModelResolution.Ambiguous<EmbeddingsModelSelection>>(
            resolveEmbeddingsModel("text-embedding-3-small")
        )
        assertEquals(
            listOf(
                EmbeddingsModelSelection.Explicit(EmbeddingsModel.AiTunnelEmbedding3Small),
                EmbeddingsModelSelection.Explicit(EmbeddingsModel.OpenAITextEmbedding3Small),
            ),
            ambiguous.candidates,
        )
        assertEquals(
            ModelResolution.Resolved(
                EmbeddingsModelSelection.Explicit(EmbeddingsModel.OpenAITextEmbedding3Small)
            ),
            resolveEmbeddingsModel(
                rawModel = " text-embedding-3-small ",
                configuredModel = EmbeddingsModel.OpenAITextEmbedding3Small,
            ),
        )
    }

    @Test
    fun `embedding resolution rejects unsupported configured and explicit providers`() {
        val backendProviders = LlmProvider.entries.toSet() - LlmProvider.GIGA
        assertEquals(
            ModelResolution.UnsupportedProvider(
                EmbeddingsModelSelection.Explicit(EmbeddingsModel.GigaEmbeddings),
                LlmProvider.GIGA,
            ),
            resolveEmbeddingsModel(
                rawModel = DEFAULT_EMBEDDINGS_MODEL,
                configuredModel = EmbeddingsModel.GigaEmbeddings,
                supportedProviders = backendProviders,
            ),
        )
        assertEquals(
            ModelResolution.UnsupportedProvider(
                EmbeddingsModelSelection.Explicit(EmbeddingsModel.GigaEmbeddings),
                LlmProvider.GIGA,
            ),
            resolveEmbeddingsModel(
                rawModel = "GigaEmbeddings",
                supportedProviders = backendProviders,
            ),
        )
    }
}
