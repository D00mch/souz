package ru.souz.backend.hooks

import ru.souz.backend.llm.quota.ExecutionQuotaManager
import ru.souz.backend.storage.postgres.postgresStorageMapper
import ru.souz.llms.LLMRequest

/** Applied at the execution API, including retries, nested tools and resumed options. */
internal class HookLlmBudget(
    private val receipt: HookReceipt,
    private val store: HookStore,
    val quotas: ExecutionQuotaManager,
    private val summarizationParameters: String? = null,
) {
    suspend fun beforeCall(request: LLMRequest.Chat): LLMRequest.Chat {
        if (postgresStorageMapper.writeValueAsBytes(request).size > 131_072) throw hookError(429, "hook_llm_input_limit")
        beforeAuxiliaryCall()
        val configured = if (request.isSummarization && request.maxTokens == 0) {
            summarizationParameters?.let(postgresStorageMapper::readTree)
                ?.let { it["max_completion_tokens"] ?: it["max_tokens"] }?.asInt()
        } else request.maxTokens
        return request.copy(maxTokens = configured?.takeIf { it > 0 }?.coerceAtMost(4096) ?: 4096)
    }

    suspend fun beforeAuxiliaryCall() {
        quotas.checkRequestRate(receipt.userId)
        quotas.checkTokenQuota(receipt.userId)
        store.reserveLlmCall(receipt)
    }

    suspend fun recordUsage(tokens: Int) {
        store.recordTokens(receipt.id, tokens)
        quotas.recordTokenUsage(receipt.userId, tokens)
    }
}
