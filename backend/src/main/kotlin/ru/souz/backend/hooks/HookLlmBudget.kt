package ru.souz.backend.hooks

import ru.souz.backend.llm.quota.ExecutionQuotaManager
import ru.souz.backend.storage.postgres.postgresStorageMapper
import ru.souz.llms.LLMRequest

/** Applied at the execution API, including retries, nested tools and resumed options. */
internal class HookLlmBudget(
    private val receipt: HookReceipt,
    private val store: HookStore,
    val quotas: ExecutionQuotaManager,
) {
    val userId: String get() = receipt.userId

    suspend fun beforeCall(request: LLMRequest.Chat): LLMRequest.Chat {
        if (postgresStorageMapper.writeValueAsBytes(request).size > 131_072) throw hookError(429, "hook_llm_input_limit")
        beforeAuxiliaryCall()
        return request.copy(maxTokens = request.maxTokens.takeIf { it in 1..4096 } ?: 4096)
    }

    suspend fun beforeAuxiliaryCall() {
        quotas.checkRequestRate(userId)
        quotas.checkTokenQuota(userId)
        store.reserveLlmCall(receipt)
    }

    suspend fun recordUsage(tokens: Int) {
        store.recordTokens(receipt.id, tokens)
        quotas.recordTokenUsage(userId, tokens)
    }
}
