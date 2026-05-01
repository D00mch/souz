package ru.souz.backend.choices.repository

import java.time.Instant
import java.util.UUID
import ru.souz.backend.choices.model.ChoiceAnswer
import ru.souz.backend.choices.model.Choice

interface ChoiceRepository {
    suspend fun save(choice: Choice): Choice
    suspend fun get(userId: String, choiceId: UUID): Choice?
    suspend fun answerPending(
        userId: String,
        choiceId: UUID,
        answer: ChoiceAnswer,
        answeredAt: Instant = Instant.now(),
    ): ChoiceAnswerUpdateResult

    suspend fun listByExecution(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        limit: Int = DEFAULT_LIMIT,
    ): List<Choice>

    companion object {
        const val DEFAULT_LIMIT: Int = 50
    }
}

sealed interface ChoiceAnswerUpdateResult {
    data class Updated(val choice: Choice) : ChoiceAnswerUpdateResult

    data object NotFound : ChoiceAnswerUpdateResult

    data class NotPending(val choice: Choice) : ChoiceAnswerUpdateResult
}
