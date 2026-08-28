package ru.souz.llms

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import java.util.*

object LLMResponse {

    data class Token(
        @field:JsonProperty("access_token") val accessToken: String,
        @field:JsonProperty("expires_at") val expiresAt: Date
    )

    sealed interface Chat {
        data class Ok(val choices: List<Choice>, val created: Long, val model: String, val usage: Usage) : Chat
        data class Error(val status: Int, val message: String) : Chat
    }

    data class Usage(
        @field:JsonProperty("prompt_tokens") val promptTokens: Int,
        @field:JsonProperty("completion_tokens") val completionTokens: Int,
        @field:JsonProperty("total_tokens") val totalTokens: Int,
        @field:JsonProperty("precached_prompt_tokens") val precachedTokens: Int
    )

    data class Choice(
        val message: Message,
        val index: Int,
        @field:JsonProperty("finish_reason") val finishReason: FinishReason?
    )

    data class Message(
        val content: String,
        val role: LLMMessageRole,
        @field:JsonProperty("function_call") val functionCall: FunctionCall? = null,
        @field:JsonProperty("functions_state_id") val functionsStateId: String?,
        @field:JsonProperty("reasoning_content") val reasoningContent: String? = null,
        val created: Long? = null,
        val name: String? = null,
    )

    data class FunctionCall(
        val name: String,
        @field:JsonDeserialize(using = FunctionCallArgumentsDeserializer::class)
        val arguments: Map<String, Any>
    )

    data class RecognizeResponse(
        val result: List<String> = emptyList(),
        val emotions: List<Emotion> = emptyList(),
        @field:JsonProperty("person_identity") val personIdentity: PersonIdentity? = null,
        val status: Int = 0
    )

    data class Emotion(
        val negative: Double,
        val neutral: Double,
        val positive: Double
    )

    data class PersonIdentity(
        val age: String,
        val gender: String,
        @field:JsonProperty("age_score") val ageScore: Double,
        @field:JsonProperty("gender_score") val genderScore: Double
    )

    data class UploadFile(
        val bytes: Long,
        @field:JsonProperty("created_at") val createdAt: Long,
        val filename: String,
        val id: String,
        @field:JsonProperty("object") val objectType: String,
        val purpose: String,
        @field:JsonProperty("access_policy") val accessPolicy: String,
    )

    sealed interface Embeddings {
        data class Ok(
            val data: List<Embedding>,
            val model: String,
            @field:JsonProperty("object") val objectType: String,
        ) : Embeddings

        data class Error(val status: Int, val message: String) : Embeddings
    }

    data class Embedding(
        val embedding: List<Double>,
        val index: Int,
        @field:JsonProperty("object") val objectType: String? = null,
    )

    data class BalanceItem(
        val usage: String,
        val value: Int,
    )

    sealed interface Balance {
        data class Ok(val balance: List<BalanceItem>) : Balance
        data class Error(val status: Int, val message: String) : Balance
    }

    @Suppress("EnumEntryName")
    enum class FinishReason { stop, length, function_call, blacklist, error }
}

enum class EmbeddingInputKind {
    QUERY,
    DOCUMENT,
}

fun String.toFinishReason(): LLMResponse.FinishReason? {
    if (this.isEmpty()) return null
    return runCatching { LLMResponse.FinishReason.valueOf(this) }.getOrNull()
}

const val DEFAULT_MAX_TOKENS = 16_000

enum class LlmProvider {
    GIGA,
    QWEN,
    AI_TUNNEL,
    ANTHROPIC,
    OPENAI,
    LOCAL,
    CODEX,
}

enum class VoiceRecognitionProvider {
    SALUTE_SPEECH,
    AI_TUNNEL,
    OPENAI,
    LOCAL_MACOS,
}

enum class LLMModel(
    val displayName: String,
    val alias: String,
    val provider: LlmProvider,
    val legacyAliases: Set<String> = emptySet(),
) {
    Lite("GigaChat Lite", "GigaChat-2", LlmProvider.GIGA, setOf("GigaChat")),
    Pro("GigaChat Pro", "GigaChat-2-Pro", LlmProvider.GIGA, setOf("GigaChat-Pro")),
    Max("GigaChat Max", "GigaChat-2-Max", LlmProvider.GIGA, setOf("GigaChat-Max")),
    Ultra("GigaChat Ultra", "GigaChat-3-Ultra", LlmProvider.GIGA),
    QwenFlash("Qwen Flash", "qwen-flash", LlmProvider.QWEN),
    QwenPlus("Qwen Plus", "qwen-plus", LlmProvider.QWEN),
    Qwen3OpenSource("Qwen3 open source", "qwen3-vl-32b-instruct", LlmProvider.QWEN),
    QwenMax("Qwen Max", "qwen-max", LlmProvider.QWEN),
    AiTunnelGpt4oMini("AiT.gpt-4o-mini", "gpt-4o-mini", LlmProvider.AI_TUNNEL),
    AiTunnelGpt54Mini("AiT.gpt-5.4-mini", "gpt-5.4-mini", LlmProvider.AI_TUNNEL),
    AiTunnelGemini36Flash("AiT.gemini-3.6-flash", "gemini-3.6-flash", LlmProvider.AI_TUNNEL),
    AiTunnelGemini35FlashLite("AiT.gemini-3.5-flash-lite", "gemini-3.5-flash-lite", LlmProvider.AI_TUNNEL),
    AiTunnelClaudeOpus48("AiT.claude-opus-4.8", "claude-opus-4.8", LlmProvider.AI_TUNNEL),
    AiTunnelClaudeHaiku("AiT.claude-haiku-4.5", "claude-haiku-4.5", LlmProvider.AI_TUNNEL),
    AiTunnelClaudeFable5("AiT.claude-fable-5", "claude-fable-5", LlmProvider.AI_TUNNEL),
    AiTunnelKimiK3("AiT.kimi-k3", "kimi-k3", LlmProvider.AI_TUNNEL),
    OpenAIGpt52("OpenAI GPT-5.2", "gpt-5.2", LlmProvider.OPENAI),
    OpenAIGpt5Mini("OpenAI GPT-5 mini", "gpt-5-mini", LlmProvider.OPENAI),
    OpenAICompatibleCustom("OpenAI-compatible custom", "openai-compatible-custom", LlmProvider.OPENAI),
    AnthropicOpus45("Claude Opus 4.5", "claude-opus-4-5", LlmProvider.ANTHROPIC),
    AnthropicOpus46("Claude Opus 4.6", "claude-opus-4-6", LlmProvider.ANTHROPIC),
    AnthropicSonnet45("Claude Sonnet 4.5", "claude-sonnet-4-5-20250929", LlmProvider.ANTHROPIC),
    AnthropicHaiku45("Claude Haiku 4.5", "claude-haiku-4-5-20251001", LlmProvider.ANTHROPIC),
    LocalQwen3_4B_Instruct_2507("Local Qwen3 4B Instruct", "local-qwen3-4b-instruct-2507", LlmProvider.LOCAL),
    LocalGemma4_E2B_It("Local Gemma 4 E2B Instruct", "local-gemma-4-e2b-it", LlmProvider.LOCAL),
    LocalGemma4_E4B_It("Local Gemma 4 E4B Instruct", "local-gemma-4-e4b-it", LlmProvider.LOCAL),
    CodexGpt56Sol("GPT-5.6 Sol (Codex)", "gpt-5.6-sol", LlmProvider.CODEX),
    CodexGpt56Terra("GPT-5.6 Terra (Codex)", "gpt-5.6-terra", LlmProvider.CODEX),
    CodexGpt56Luna("GPT-5.6 Luna (Codex)", "gpt-5.6-luna", LlmProvider.CODEX),
    CodexGpt55("GPT-5.5 (Codex)", "gpt-5.5", LlmProvider.CODEX),
    CodexGpt54("GPT-5.4 (Codex)", "gpt-5.4", LlmProvider.CODEX),
    CodexGpt53("GPT-5.3 Codex", "gpt-5.3-codex", LlmProvider.CODEX),
}

fun findLLMModel(value: String): LLMModel? {
    val normalized = value.trim()
    if (normalized.isEmpty()) return null
    return LLMModel.entries.firstOrNull { model ->
        model.name.equals(normalized, ignoreCase = true) ||
            model.alias.equals(normalized, ignoreCase = true) ||
            model.legacyAliases.any { it.equals(normalized, ignoreCase = true) }
    }
}

enum class EmbeddingsModel(
    val displayName: String,
    val alias: String,
    val provider: LlmProvider,
) {
    GigaEmbeddings("GigaChat", "Embeddings", LlmProvider.GIGA),
    QwenEmbeddings("Qwen", "text-embedding-v3", LlmProvider.QWEN),
    AiTunnelEmbedding3Small("AI-Tunnel: text-embedding-3-small", "text-embedding-3-small", LlmProvider.AI_TUNNEL),
    AiTunnelEmbeddingAda("AI-Tunnel: text-embedding-ada-002", "text-embedding-ada-002", LlmProvider.AI_TUNNEL),
    AiTunnelQwen3Embedding("AI-Tunnel: qwen3-embedding-8b", "qwen3-embedding-8b", LlmProvider.AI_TUNNEL),
    OpenAITextEmbedding3Small("OpenAI: text-embedding-3-small", "text-embedding-3-small", LlmProvider.OPENAI),
    LocalEmbeddingGemma300M("Local EmbeddingGemma 300M", "local-embeddinggemma-300m", LlmProvider.LOCAL),
}

enum class VoiceRecognitionModel(
    val displayName: String,
    val alias: String,
    val provider: VoiceRecognitionProvider,
) {
    SaluteSpeech("Salute Speech", "salute-speech", VoiceRecognitionProvider.SALUTE_SPEECH),
    AiTunnelGpt4oTranscribe("AI-Tunnel: gpt-4o-transcribe", "gpt-4o-transcribe", VoiceRecognitionProvider.AI_TUNNEL),
    AiTunnelGpt4oMiniTranscribe("AI-Tunnel: gpt-4o-mini-transcribe", "gpt-4o-mini-transcribe", VoiceRecognitionProvider.AI_TUNNEL),
    OpenAIGpt4oTranscribe("OpenAI: gpt-4o-transcribe", "gpt-4o-transcribe", VoiceRecognitionProvider.OPENAI),
    OpenAIGpt4oMiniTranscribe("OpenAI: gpt-4o-mini-transcribe", "gpt-4o-mini-transcribe", VoiceRecognitionProvider.OPENAI),
    OpenAIGptTranscribe("OpenAI: gpt-transcribe", "gpt-transcribe", VoiceRecognitionProvider.OPENAI),
    OpenAIWhisper1("OpenAI: whisper-1", "whisper-1", VoiceRecognitionProvider.OPENAI),
    LocalMacOsStt("Local MacOS STT", "local-macos-stt", VoiceRecognitionProvider.LOCAL_MACOS),
}

object LLMRequest {
    enum class LocalOutputFormat {
        ENVELOPE,
        RAW,
    }

    data class Chat(
        val model: String = LLMModel.Max.alias,
        val messages: List<Message>,
        @field:JsonProperty("function_call")
        val functionCall: Any = "auto",
        val functions: List<Function> = emptyList(),
        val temperature: Float? = null,
        @field:JsonInclude(JsonInclude.Include.NON_NULL)
        @field:JsonProperty("top_p") val topP: Float? = null,
        val stream: Boolean = false,
        @field:JsonProperty("max_tokens")
        val maxTokens: Int = DEFAULT_MAX_TOKENS,
        @field:JsonInclude(JsonInclude.Include.NON_NULL)
        @field:JsonProperty("repetition_penalty") val repetitionPenalty: Float? = null,
        @field:JsonProperty("update_interval") val updateInterval: Int? = 0,
        @field:JsonInclude(JsonInclude.Include.NON_NULL)
        @field:JsonProperty("reasoning_effort") val reasoningEffort: String? = null,
        @field:JsonInclude(JsonInclude.Include.NON_NULL)
        @field:JsonProperty("response_format") val responseFormat: Map<String, Any?>? = null,
        @get:JsonIgnore
        @field:JsonIgnore
        val isSummarization: Boolean = false,
        @get:JsonIgnore
        @field:JsonIgnore
        val localOutputFormat: LocalOutputFormat = LocalOutputFormat.ENVELOPE,
    ) {
        /**
         * OpenAI-compatible providers expect function results to provide call IDs, but Giga does not.
         */
        fun rmFnIds(): Chat = copy(
            messages = messages.map { m ->
                when (m.role) {
                    LLMMessageRole.function -> m.copy(functionsStateId = null)
                    else -> m
                }
            }
        )
    }

    data class Message(
        val role: LLMMessageRole,
        val content: String, // Could be String or FunctionCall object
        @field:JsonProperty("functions_state_id") val functionsStateId: String? = null,
        val attachments: List<String>? = null,
        val name: String? = null,
        @field:JsonInclude(JsonInclude.Include.NON_NULL)
        @field:JsonProperty("function_call") val functionCall: FunctionCall? = null,
    )

    data class FunctionCall(
        val name: String,
        val arguments: String,
    )

    data class Function(
        val name: String,
        val description: String = "",
        val parameters: Parameters = Parameters("object", emptyMap()),
        @field:JsonProperty("few_shot_examples") val fewShotExamples: List<FewShotExample>? = null,
        @field:JsonProperty("return_parameters") val returnParameters: Parameters? = null,
    )

    data class Parameters(
        val type: String,
        val properties: Map<String, Property> = emptyMap(),
        val required: List<String> = emptyList()
    )

    data class Property(
        val type: String,
        val description: String? = null,
        @field:JsonProperty("enum") val enum: List<String>? = null,
        @field:JsonInclude(JsonInclude.Include.NON_NULL)
        val items: Property? = null,
        @field:JsonInclude(JsonInclude.Include.NON_NULL)
        val properties: Map<String, Property>? = null,
    )

    data class FewShotExample(
        val request: String,
        val params: Map<String, Any>
    )

    data class Embeddings(
        val model: String = DEFAULT_EMBEDDINGS_MODEL,
        val input: List<String>,
        @get:JsonIgnore
        @field:JsonIgnore
        val inputKind: EmbeddingInputKind = EmbeddingInputKind.QUERY,
    )
}

@Suppress("EnumEntryName")
enum class LLMMessageRole { system, user, assistant, function, function_in_progress }

@Suppress("unused")
class LLMException(body: LLMResponse.Chat.Error, override val cause: Throwable? = null) : Exception(cause)

fun String.toSystemPromptMessage() = LLMRequest.Message(
    role = LLMMessageRole.system,
    content = this
)

operator fun LLMResponse.Usage.plus(usage: LLMResponse.Usage): LLMResponse.Usage = LLMResponse.Usage(
    promptTokens = this.promptTokens + usage.promptTokens,
    completionTokens = this.completionTokens + usage.completionTokens,
    totalTokens = this.totalTokens + usage.totalTokens,
    precachedTokens = this.precachedTokens + usage.precachedTokens
)

fun LLMResponse.Choice.toMessage(): LLMRequest.Message? {
    val msg = this.message
    val content: String = when {
        msg.functionCall != null -> msg.content
        msg.content.isNotBlank() -> msg.content
        else -> return null
    }
    return LLMRequest.Message(
        role = msg.role,
        content = content,
        functionsStateId = msg.functionsStateId,
        functionCall = msg.functionCall?.let {
            LLMRequest.FunctionCall(
                name = it.name,
                arguments = restJsonMapper.writeValueAsString(it.arguments),
            )
        },
    )
}

class FunctionCallArgumentsDeserializer : JsonDeserializer<Map<String, Any>>() {
    override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): Map<String, Any> {
        val codec = parser.codec
        val node = codec.readTree<JsonNode>(parser)
        val argumentsNode = if (node.isTextual) {
            restJsonMapper.readTree(node.asText())
        } else {
            node
        }
        if (!argumentsNode.isObject) return emptyMap()
        return restJsonMapper.convertValue(argumentsNode, Map::class.java)
            .entries
            .mapNotNull { (key, value) ->
                if (key is String && value != null) key to value else null
            }
            .toMap()
    }
}
