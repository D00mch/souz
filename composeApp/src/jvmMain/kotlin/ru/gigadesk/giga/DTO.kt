package ru.gigadesk.giga

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.*

object GigaResponse {

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
        val role: GigaMessageRole,
        @field:JsonProperty("function_call") val functionCall: FunctionCall? = null,
        @field:JsonProperty("functions_state_id") val functionsStateId: String?,
    )

    data class FunctionCall(
        val name: String,
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

    enum class FinishReason { stop, length, function_call, blacklist, error }
}

fun String.toFinishReason(): GigaResponse.FinishReason? {
    if (this.isEmpty()) return null
    return runCatching { GigaResponse.FinishReason.valueOf(this) }.getOrNull()
}

const val MAX_TOKENS = 8192

enum class LlmProvider {
    GIGA,
    QWEN,
    AI_TUNNEL,
}

enum class EmbeddingsProvider(val displayName: String) {
    GIGA("GigaChat"),
    QWEN("Qwen"),
    AI_TUNNEL("AI Tunnel"),
}

enum class GigaModel(
    val displayName: String,
    val alias: String,
    val maxTokens: Int,
    val provider: LlmProvider,
) {
    Lite("GigaChat Lite", "GigaChat-2", MAX_TOKENS, LlmProvider.GIGA),
    Pro("GigaChat Pro", "GigaChat-Pro", MAX_TOKENS, LlmProvider.GIGA),
    Max("GigaChat Max", "GigaChat-Max", MAX_TOKENS, LlmProvider.GIGA),
    QwenFlash("Qwen Flash", "qwen-flash", 32_768, LlmProvider.QWEN),
    QwenPlus("Qwen Plus", "qwen-plus", 32_768, LlmProvider.QWEN),
    QwenMax("Qwen Max", "qwen-max", 32_768, LlmProvider.QWEN),
    AiTunnel("AI Tunnel", "ai-tunnel", 128_000, LlmProvider.AI_TUNNEL),
}

object GigaRequest {
    data class Chat(
        val model: String = GigaModel.Max.alias,
        val messages: List<Message>,
        @field:JsonProperty("function_call")
        val functionCall: String = "auto",
        val functions: List<Function> = emptyList(),
        val temperature: Float? = null,
        val stream: Boolean = false,
        val maxTokens: Int = MAX_TOKENS,
        @field:JsonProperty("update_interval") val updateInterval: Int? = 1,
    )

    data class Message(
        val role: GigaMessageRole,
        val content: String, // Could be String or FunctionCall object
        @field:JsonProperty("functions_state_id") val functionsStateId: String? = null,
        val attachments: List<String>? = null,
        val name: String? = null,
    )

    data class Function(
        val name: String,
        val description: String,
        val parameters: Parameters,
        @field:JsonProperty("few_shot_examples") val fewShotExamples: List<FewShotExample>? = null,
        @field:JsonProperty("return_parameters") val returnParameters: Parameters? = null,
    )

    data class Parameters(
        val type: String,
        val properties: Map<String, Property>,
        val required: List<String> = emptyList()
    )

    data class Property(
        val type: String,
        val description: String? = null,
        @field:JsonProperty("enum") val enum: List<String>? = null
    )

    data class FewShotExample(
        val request: String,
        val params: Map<String, Any>
    )

    data class Embeddings(
        val model: String = "Embeddings",
        val input: List<String>,
    )
}

@Suppress("EnumEntryName")
enum class GigaMessageRole { system, user, assistant, function }

val objectMapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

@Suppress("unused")
class GigaException(body: GigaResponse.Chat.Error, override val cause: Throwable? = null) : Exception(cause)

fun String.toSystemPromptMessage() = GigaRequest.Message(
    role = GigaMessageRole.system,
    content = this
)

operator fun GigaResponse.Usage.plus(usage: GigaResponse.Usage): GigaResponse.Usage = GigaResponse.Usage(
    promptTokens = this.promptTokens + usage.promptTokens,
    completionTokens = this.completionTokens + usage.completionTokens,
    totalTokens = this.totalTokens + usage.totalTokens,
    precachedTokens = this.precachedTokens + usage.precachedTokens
)

fun GigaResponse.Choice.toMessage(): GigaRequest.Message? {
    val msg = this.message
    val content: String = when {
        msg.content.isNotBlank() -> msg.content
        msg.functionCall != null -> gigaJsonMapper.writeValueAsString(
            mapOf("name" to msg.functionCall.name, "arguments" to msg.functionCall.arguments)
        )
        else -> return null
    }
    return GigaRequest.Message(
        role = msg.role,
        content = content,
        functionsStateId = msg.functionsStateId
    )
}
