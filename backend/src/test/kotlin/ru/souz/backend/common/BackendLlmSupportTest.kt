package ru.souz.backend.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider

class BackendLlmSupportTest {
    @Test
    fun `backend provider policy excludes Giga from every capability`() {
        assertEquals(LlmProvider.entries.toSet() - LlmProvider.GIGA, BackendLlmSupport.chatProviders)
        assertEquals(
            LLMModel.entries.filterTo(linkedSetOf()) { it.provider != LlmProvider.GIGA },
            BackendLlmSupport.chatModels,
        )
        assertFalse(LlmProvider.GIGA in BackendLlmSupport.userManagedKeyProviders)
        assertFalse(LlmProvider.GIGA in BackendLlmSupport.embeddingProviders)
        assertEquals(LLMModel.QwenFlash, BackendLlmSupport.fallbackChatModel)
    }

    @Test
    fun `backend provider policy separates user keys from embedding support`() {
        assertEquals(
            setOf(LlmProvider.QWEN, LlmProvider.AI_TUNNEL, LlmProvider.ANTHROPIC, LlmProvider.OPENAI),
            BackendLlmSupport.userManagedKeyProviders,
        )
        assertEquals(
            setOf(LlmProvider.QWEN, LlmProvider.AI_TUNNEL, LlmProvider.OPENAI, LlmProvider.LOCAL),
            BackendLlmSupport.embeddingProviders,
        )
    }
}
