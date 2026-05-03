package ru.souz.backend.options.model

import java.time.Instant
import java.util.UUID

data class OptionItem(
    val id: String,
    val label: String,
    val content: String? = null,
)

data class OptionAnswer(
    val selectedOptionIds: Set<String>,
    val freeText: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class Option(
    val id: UUID,
    val userId: String,
    val chatId: UUID,
    val executionId: UUID,
    val kind: OptionKind,
    val title: String?,
    val selectionMode: String,
    val options: List<OptionItem>,
    val payload: Map<String, String>,
    val status: OptionStatus,
    val answer: OptionAnswer?,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val answeredAt: Instant?,
)
