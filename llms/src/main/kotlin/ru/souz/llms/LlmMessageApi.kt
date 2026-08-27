package ru.souz.llms

/** Minimal chat capability: a single non-streaming request/response call. */
interface LlmMessageApi {
    suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat
}
