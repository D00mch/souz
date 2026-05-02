package ru.souz.backend.storage.memory

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.choices.model.Choice
import ru.souz.backend.choices.model.ChoiceAnswer
import ru.souz.backend.choices.model.ChoiceStatus
import ru.souz.backend.choices.repository.ChoiceAnswerUpdateResult
import ru.souz.backend.choices.repository.ChoiceRepository

class MemoryChoiceRepository(
    maxEntries: Int,
) : ChoiceRepository {
    private val mutex = Mutex()
    private val choices = boundedLruMap<ChoiceKey, Choice>(maxEntries)
    private val pendingChoices = HashMap<ChoiceKey, Choice>()

    constructor() : this(DEFAULT_MEMORY_REPOSITORY_MAX_ENTRIES)

    override suspend fun save(choice: Choice): Choice = mutex.withLock {
        val key = ChoiceKey(choice.userId, choice.id)
        choices[key] = choice
        syncPendingChoice(key, choice)
        choice
    }

    override suspend fun get(userId: String, choiceId: UUID): Choice? = mutex.withLock {
        choiceFor(ChoiceKey(userId, choiceId))
    }

    override suspend fun answerPending(
        userId: String,
        choiceId: UUID,
        answer: ChoiceAnswer,
        answeredAt: Instant,
    ): ChoiceAnswerUpdateResult {
        return mutex.withLock {
            val key = ChoiceKey(userId, choiceId)
            val current = choiceFor(key) ?: return@withLock ChoiceAnswerUpdateResult.NotFound
            if (current.status != ChoiceStatus.PENDING) {
                return@withLock ChoiceAnswerUpdateResult.NotPending(current)
            }

            val updated = current.copy(
                status = ChoiceStatus.ANSWERED,
                answer = answer,
                answeredAt = answeredAt,
            )
            choices[key] = updated
            syncPendingChoice(key, updated)
            ChoiceAnswerUpdateResult.Updated(updated)
        }
    }

    override suspend fun listByExecution(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        limit: Int,
    ): List<Choice> = mutex.withLock {
        allChoices()
            .asSequence()
            .filter { it.userId == userId && it.chatId == chatId && it.executionId == executionId }
            .sortedByDescending { it.createdAt }
            .take(limit)
            .toList()
    }

    private fun choiceFor(key: ChoiceKey): Choice? =
        choices[key] ?: pendingChoices[key]

    private fun allChoices(): List<Choice> =
        (choices.values + pendingChoices.values)
            .distinctBy { it.id }

    private fun syncPendingChoice(
        key: ChoiceKey,
        choice: Choice,
    ) {
        if (choice.status == ChoiceStatus.PENDING) {
            pendingChoices[key] = choice
        } else {
            pendingChoices.remove(key)
        }
    }
}

private data class ChoiceKey(
    val userId: String,
    val choiceId: UUID,
)
