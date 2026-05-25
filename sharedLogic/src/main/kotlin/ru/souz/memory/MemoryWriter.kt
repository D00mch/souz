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
        val explicitRememberCandidate = explicitRememberCandidate(input)
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

    private fun explicitRememberCandidate(input: MemoryCaptureInput): MemoryFactCandidate? {
        if (!hasExplicitRememberIntent(input.userMessage)) return null
        val body = input.userMessage.trim()
            .removeRememberMarkers()
            .takeIf(String::isNotBlank)
            ?: return null
        return MemoryFactCandidate(
            shouldSave = true,
            kind = inferKind(body),
            title = body.substringBefore('\n').substringBefore('.').trim().take(96).ifBlank { "Remembered note" },
            body = body,
            scope = input.primaryScope,
            slotKey = body.toSlotKeyOrNull(),
            confidence = 0.75f,
            evidenceText = input.userMessage.trim().take(240),
        )
    }

    private fun inferKind(text: String): MemoryFactKind {
        val normalized = text.lowercase()
        return when {
            listOf("prefer", "предпоч", "хочу", "please use", "используй").any(normalized::contains) ->
                MemoryFactKind.PREFERENCE
            listOf("always", "всегда", "rule", "правило", "policy").any(normalized::contains) ->
                MemoryFactKind.PROJECT_RULE
            listOf("procedure", "workflow", "процед", "шаг").any(normalized::contains) ->
                MemoryFactKind.PROCEDURE
            else -> MemoryFactKind.SEMANTIC
        }
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

    private fun String.removeRememberMarkers(): String {
        val normalized = trim()
        val markers = listOf(
            "remember that",
            "remember",
            "from now on",
            "с этого момента",
            "запомни, что",
            "запомни",
            "запиши",
            "в будущем учитывай",
            "учитывай дальше"
        )
        val lower = normalized.lowercase()
        val marker = markers.firstOrNull(lower::contains) ?: return normalized
        val start = lower.indexOf(marker)
        return normalized.removeRange(start, start + marker.length)
            .trim()
            .trimStart(':', '-', ',', ' ')
    }

    private fun String.toSlotKeyOrNull(): String? =
        lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .takeIf { it.length in 4..64 }

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

enum class ExplicitMemoryIntent {
    SAVE,
    SKIP,
    NONE
}

fun parseExplicitMemoryIntent(text: String): ExplicitMemoryIntent {
    val normalized = text.lowercase()
    val negatives = listOf(
        "не запоминай",
        "не нужно запоминать",
        "don't remember",
        "do not remember",
        "don't save",
        "do not save",
    )
    if (negatives.any { normalized.contains(it) }) {
        return ExplicitMemoryIntent.SKIP
    }

    val positives = listOf(
        "запомни, что",
        "запомни",
        "remember that",
        "don't forget",
        "do not forget",
        "from now on",
        "с этого момента",
        "не забудь",
    )
    if (positives.any { normalized.contains(it) }) {
        return ExplicitMemoryIntent.SAVE
    }

    if (normalized.isExplicitForgetIntent()) {
        return ExplicitMemoryIntent.SKIP
    }

    return ExplicitMemoryIntent.NONE
}

fun hasExplicitRememberIntent(text: String): Boolean {
    return parseExplicitMemoryIntent(text) == ExplicitMemoryIntent.SAVE
}

private fun String.isExplicitForgetIntent(): Boolean {
    val trimmed = trim()
    if (trimmed == "forget" || trimmed == "забудь") return true
    return EXPLICIT_FORGET_PATTERNS.any { it.containsMatchIn(this) }
}

private val EXPLICIT_FORGET_PATTERNS = listOf(
    Regex("""\bforget\s+(?:this|that|it|everything|all this)\b"""),
    Regex("""\bforget\s+about\s+(?:this|that|it)\b"""),
    Regex("""\bзабудь\s+(?:это|все|всё|все это|всё это)\b"""),
    Regex("""\bзабудь\s+об\s+этом\b"""),
)
