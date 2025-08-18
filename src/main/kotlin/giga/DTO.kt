package com.dumch.giga

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.*

object GigaResponse {

    data class Token(
        @JsonProperty("access_token") val accessToken: String,
        @JsonProperty("expires_at") val expiresAt: Date
    )

    sealed interface Chat {
        data class Ok(val choices: List<Choice>, val created: Long, val model: String, val usage: Usage) : Chat
        data class Error(val status: Int, val message: String) : Chat
    }

    data class Usage(
        @JsonProperty("prompt_tokens") val promptTokens: Int,
        @JsonProperty("completion_tokens") val completionTokens: Int,
        @JsonProperty("total_tokens") val totalTokens: Int,
        @JsonProperty("precached_prompt_tokens") val precachedTokens: Int
    )

    data class Choice(
        val message: Message,
        val index: Int,
        @JsonProperty("finish_reason") val finishReason: FinishReason?
    )

    data class Message(
        val content: String,
        val role: GigaMessageRole,
        @JsonProperty("function_call") val functionCall: FunctionCall? = null,
        @JsonProperty("functions_state_id") val functionsStateId: String?,
    )

    data class FunctionCall(
        val name: String,
        val arguments: Map<String, Any>
    )

    data class RecognizeResponse(
        val result: List<String>,
        val emotions: List<Emotion>,
        @JsonProperty("person_identity") val personIdentity: PersonIdentity,
        val status: Int
    )

    data class Emotion(
        val negative: Double,
        val neutral: Double,
        val positive: Double
    )

    data class PersonIdentity(
        val age: String,
        val gender: String,
        @JsonProperty("age_score") val ageScore: Double,
        @JsonProperty("gender_score") val genderScore: Double
    )

    data class UploadFile(
        val bytes: Long,
        @JsonProperty("created_at") val createdAt: Long,
        val filename: String,
        val id: String,
        @JsonProperty("object") val objectType: String,
        val purpose: String,
        @JsonProperty("access_policy") val accessPolicy: String,
    )

    sealed interface Embeddings {
        data class Ok(
            val data: List<Embedding>,
            val model: String,
            @JsonProperty("object") val objectType: String,
        ) : Embeddings

        data class Error(val status: Int, val message: String) : Embeddings
    }

    data class Embedding(
        val embedding: List<Double>,
        val index: Int,
        @JsonProperty("object") val objectType: String? = null,
    )

    enum class FinishReason { stop, length, function_call, blacklist, error }
}

fun String.toFinishReason(): GigaResponse.FinishReason? {
    if (this.isEmpty()) return null
    return runCatching { GigaResponse.FinishReason.valueOf(this) }.getOrNull()
}

enum class GigaModel(val alias: String, val maxTokens: Int) {
    Lite("GigaChat-2", 32768),
    Pro("GigaChat-Pro", 32768),
    Max("GigaChat-Max", 32768),
}

object GigaRequest {
    data class Chat(
        val model: String = GigaModel.Max.alias,
        val messages: List<Message>,
        @JsonProperty("function_call")
        val functionCall: String = "auto",
        val functions: List<Function> = emptyList(),
        val temperature: Float? = null,
        val stream: Boolean = false,
        val maxTokens: Int = 24_228,
        @JsonProperty("update_interval") val updateInterval: Int? = 1,
    )

    data class Message(
        val role: GigaMessageRole,
        val content: String, // Could be String or FunctionCall object
        @JsonProperty("functions_state_id") val functionsStateId: String? = null,
        val attachments: List<String>? = null,
    )

    data class Function(
        val name: String,
        val description: String,
        val parameters: Parameters,
        @JsonProperty("few_shot_examples") val fewShotExamples: List<FewShotExample> = emptyList(),
        @JsonProperty("return_parameters") val returnParameters: Parameters
    )

    data class Parameters(
        val type: String,
        val properties: Map<String, Property>,
        val required: List<String> = emptyList()
    )

    data class Property(
        val type: String,
        val description: String? = null,
        @JsonProperty("enum") val enum: List<String>? = null
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