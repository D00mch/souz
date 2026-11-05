package ru.souz.backend.hooks

import ru.souz.backend.storage.postgres.postgresStorageMapper
import ru.souz.llms.LLMRequest

/** Applied at the execution API, including retries, nested tools and resumed options. */
internal class HookLlmBudget(
    private val receipt: HookReceipt,
    private val store: HookStore,
) {
    fun limitRequest(request: LLMRequest.Chat): LLMRequest.Chat {
        if (postgresStorageMapper.writeValueAsBytes(request).size > 131_072) throw hookError(429, "hook_llm_input_limit")
        return request.copy(maxTokens = request.maxTokens.takeIf { it > 0 }?.coerceAtMost(4096) ?: 4096)
    }

    suspend fun recordUsage(tokens: Int) {
        store.recordTokens(receipt.id, tokens)
    }

    suspend fun reserveCall() {
        store.reserveLlmCall(receipt)
    }
}
