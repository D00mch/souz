package ru.souz.backend.storage.memory

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.choices.model.Choice
import ru.souz.backend.choices.repository.ChoiceRepository

class MemoryChoiceRepository : ChoiceRepository {
    private val mutex = Mutex()
    private val choices = LinkedHashMap<ChoiceKey, Choice>()

    override suspend fun save(choice: Choice): Choice = mutex.withLock {
        choices[ChoiceKey(choice.userId, choice.id)] = choice
        choice
    }

    override suspend fun get(userId: String, choiceId: UUID): Choice? = mutex.withLock {
        choices[ChoiceKey(userId, choiceId)]
    }

    override suspend fun listByExecution(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        limit: Int,
    ): List<Choice> = mutex.withLock {
        choices.values
            .asSequence()
            .filter { it.userId == userId && it.chatId == chatId && it.executionId == executionId }
            .sortedByDescending { it.createdAt }
            .take(limit)
            .toList()
    }
}

private data class ChoiceKey(
    val userId: String,
    val choiceId: UUID,
)
