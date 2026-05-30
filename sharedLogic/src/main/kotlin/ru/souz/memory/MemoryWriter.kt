package ru.souz.memory

import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.restJsonMapper

interface MemoryWriter {
    suspend fun extractCandidates(input: MemoryCaptureInput): List<MemoryFactCandidate>
}

class LlmMemoryWriter(
    private val api: LLMChatAPI,
    private val settingsProvider: SettingsProvider,
) : MemoryWriter {
    private val logger = LoggerFactory.getLogger(LlmMemoryWriter::class.java)

    override suspend fun extractCandidates(input: MemoryCaptureInput): List<MemoryFactCandidate> {
        val explicitRememberCandidate = buildExplicitRememberCandidate(input)
        if (explicitRememberCandidate != null) return listOf(explicitRememberCandidate)

        val response = api.message(
            LLMRequest.Chat(
                model = settingsProvider.gigaModel.alias,
                messages = listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.system,
                        content = WRITER_SYSTEM_PROMPT,
                    ),
                    LLMRequest.Message(
                        role = LLMMessageRole.user,
                        content = buildUserPrompt(input),
                    ),
                ),
                temperature = 0f,
                maxTokens = 1_000,
            )
        )

        return when (response) {
            is LLMResponse.Chat.Ok -> parseCandidates(response.choices.firstOrNull()?.message?.content.orEmpty())
            is LLMResponse.Chat.Error -> error("Memory writer failed: ${response.status} ${response.message}")
        }
    }

    private fun parseCandidates(raw: String): List<MemoryFactCandidate> {
        val json = raw.trim().extractJsonArray()
        if (json.isEmpty()) return emptyList()
        return runCatching {
            restJsonMapper.readValue<List<WriterCandidate>>(json)
                .map { candidate ->
                    MemoryFactCandidate(
                        shouldSave = candidate.shouldSave,
                        kind = candidate.kind,
                        title = candidate.title,
                        body = candidate.body,
                        scope = candidate.scopeType?.let { type ->
                            candidate.scopeId?.let { id -> MemoryScope(type = type, id = id) }
                        },
                        slotKey = candidate.slotKey,
                        confidence = candidate.confidence,
                        evidenceText = candidate.evidenceText,
                    )
                }
        }.onFailure { logger.warn("Failed to parse memory writer output: {}", it.message) }
            .getOrDefault(emptyList())
    }

    private fun buildUserPrompt(input: MemoryCaptureInput): String = buildString {
        appendLine("Primary scope: ${input.primaryScope.type}:${input.primaryScope.id}")
        appendLine("Available scopes: ${input.scopes.joinToString { "${it.type}:${it.id}" }}")
        appendLine("Conversation ID: ${input.conversationId.orEmpty()}")
        appendLine()
        appendLine("User message:")
        appendLine(input.userMessage.trim())
        appendLine()
        appendLine("Assistant message:")
        appendLine(input.assistantMessage.trim())
    }

    private fun String.extractJsonArray(): String {
        val trimmed = trim()
        if (trimmed.startsWith('[') && trimmed.endsWith(']')) return trimmed
        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1)
        }
        return ""
    }

    private data class WriterCandidate(
        val shouldSave: Boolean = false,
        val kind: MemoryFactKind = MemoryFactKind.SEMANTIC,
        val title: String = "",
        val body: String = "",
        val scopeType: String? = null,
        val scopeId: String? = null,
        val slotKey: String? = null,
        val confidence: Float = 0f,
        val evidenceText: String = "",
    )

    private companion object {
        private const val WRITER_SYSTEM_PROMPT = """
You are a conservative memory writer for a desktop AI agent.

Analyze the completed turn and extract only durable, reusable memory facts.

Create a memory fact only when the information will likely help in future conversations.

Good memory facts:
- stable user preferences
- project rules
- implementation decisions
- durable workflow instructions
- reusable procedures
- long-term project context

Keep each fact concise.
Prefer a short title and one short body sentence.
Avoid repeating surrounding conversation context.

Use the user's text as evidence.
Return JSON array only.

Each item:
{
  "shouldSave": true,
  "kind": "PREFERENCE|PROCEDURE|PROJECT_RULE|PROJECT_DECISION|SEMANTIC|EPISODE_NOTE",
  "title": "...",
  "body": "...",
  "scopeType": "global|project|thread|chat",
  "scopeId": "...",
  "slotKey": "stable_snake_case_key_or_null",
  "confidence": 0.0,
  "evidenceText": "short exact quote or close excerpt from the turn"
}

If there is no durable memory, return [].
"""
    }
}
