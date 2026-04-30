package ru.souz.backend.choices.repository

import java.util.UUID
import ru.souz.backend.choices.model.Choice

interface ChoiceRepository {
    suspend fun save(choice: Choice): Choice
    suspend fun get(userId: String, choiceId: UUID): Choice?
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
