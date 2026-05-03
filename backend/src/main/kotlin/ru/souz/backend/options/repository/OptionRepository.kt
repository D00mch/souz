package ru.souz.backend.options.repository

import java.time.Instant
import java.util.UUID
import ru.souz.backend.options.model.OptionAnswer
import ru.souz.backend.options.model.Option

interface OptionRepository {
    suspend fun save(option: Option): Option
    suspend fun get(userId: String, optionId: UUID): Option?
    suspend fun answerPending(
        userId: String,
        optionId: UUID,
        answer: OptionAnswer,
        answeredAt: Instant = Instant.now(),
    ): OptionAnswerUpdateResult

    suspend fun listByExecution(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        limit: Int = DEFAULT_LIMIT,
    ): List<Option>

    companion object {
        const val DEFAULT_LIMIT: Int = 50
    }
}

sealed interface OptionAnswerUpdateResult {
    data class Updated(val option: Option) : OptionAnswerUpdateResult

    data object NotFound : OptionAnswerUpdateResult

    data class NotPending(val option: Option) : OptionAnswerUpdateResult
}
