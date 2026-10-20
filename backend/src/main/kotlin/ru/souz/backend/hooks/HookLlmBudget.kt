package ru.souz.backend.hooks

import ru.souz.backend.llm.quota.ExecutionQuotaManager
import ru.souz.backend.storage.postgres.postgresStorageMapper
import ru.souz.llms.LLMRequest
import ru.souz.llms.LlmProvider

/** Applied at the execution API, including retries, nested tools and resumed options. */
internal class HookLlmBudget(
    private val receipt: HookReceipt,
    private val store: HookStore,
    private val quotas: ExecutionQuotaManager,
) {
    fun limitRequest(request: LLMRequest.Chat): LLMRequest.Chat {
        if (postgresStorageMapper.writeValueAsBytes(request).size > 131_072) throw hookError(429, "hook_llm_input_limit")
        return request.copy(maxTokens = request.maxTokens.takeIf { it > 0 }?.coerceAtMost(4096) ?: 4096)
    }

    suspend fun recordUsage(tokens: Int) {
        store.recordTokens(receipt.id, tokens)
        quotas.recordTokenUsage(receipt.userId, tokens)
    }

    companion object {
        suspend fun <T> withProviderCall(budget: HookLlmBudget?, provider: LlmProvider, call: suspend () -> T): T {
            if (budget == null) return call()
            return budget.quotas.withProviderPermit(provider) {
                budget.quotas.checkRequestRate(budget.receipt.userId)
                budget.quotas.checkTokenQuota(budget.receipt.userId)
                budget.store.reserveLlmCall(budget.receipt)
                call()
            }
        }
    }
}
