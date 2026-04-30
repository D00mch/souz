package ru.souz.backend.choices.model

import java.time.Instant
import java.util.UUID

data class ChoiceOption(
    val id: String,
    val label: String,
    val content: String? = null,
)

data class ChoiceAnswer(
    val selectedOptionIds: Set<String>,
    val freeText: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class Choice(
    val id: UUID,
    val userId: String,
    val chatId: UUID,
    val executionId: UUID,
    val kind: ChoiceKind,
    val title: String?,
    val selectionMode: String,
    val options: List<ChoiceOption>,
    val payload: Map<String, String>,
    val status: ChoiceStatus,
    val answer: ChoiceAnswer?,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val answeredAt: Instant?,
)
