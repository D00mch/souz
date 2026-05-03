package ru.souz.backend.storage.memory

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.options.model.Option
import ru.souz.backend.options.model.OptionAnswer
import ru.souz.backend.options.model.OptionStatus
import ru.souz.backend.options.repository.OptionAnswerUpdateResult
import ru.souz.backend.options.repository.OptionRepository

class MemoryOptionRepository(
    maxEntries: Int,
) : OptionRepository {
    private val mutex = Mutex()
    private val options = boundedLruMap<OptionKey, Option>(maxEntries)
    private val pendingOptions = HashMap<OptionKey, Option>()

    constructor() : this(DEFAULT_MEMORY_REPOSITORY_MAX_ENTRIES)

    override suspend fun save(option: Option): Option = mutex.withLock {
        val key = OptionKey(option.userId, option.id)
        options[key] = option
        syncPendingOption(key, option)
        option
    }

    override suspend fun get(userId: String, optionId: UUID): Option? = mutex.withLock {
        optionFor(OptionKey(userId, optionId))
    }

    override suspend fun answerPending(
        userId: String,
        optionId: UUID,
        answer: OptionAnswer,
        answeredAt: Instant,
    ): OptionAnswerUpdateResult {
        return mutex.withLock {
            val key = OptionKey(userId, optionId)
            val current = optionFor(key) ?: return@withLock OptionAnswerUpdateResult.NotFound
            if (current.status != OptionStatus.PENDING) {
                return@withLock OptionAnswerUpdateResult.NotPending(current)
            }

            val updated = current.copy(
                status = OptionStatus.ANSWERED,
                answer = answer,
                answeredAt = answeredAt,
            )
            options[key] = updated
            syncPendingOption(key, updated)
            OptionAnswerUpdateResult.Updated(updated)
        }
    }

    override suspend fun listByExecution(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        limit: Int,
    ): List<Option> = mutex.withLock {
        allOptions()
            .asSequence()
            .filter { it.userId == userId && it.chatId == chatId && it.executionId == executionId }
            .sortedByDescending { it.createdAt }
            .take(limit)
            .toList()
    }

    private fun optionFor(key: OptionKey): Option? =
        options[key] ?: pendingOptions[key]

    private fun allOptions(): List<Option> =
        (options.values + pendingOptions.values)
            .distinctBy { it.id }

    private fun syncPendingOption(
        key: OptionKey,
        option: Option,
    ) {
        if (option.status == OptionStatus.PENDING) {
            pendingOptions[key] = option
        } else {
            pendingOptions.remove(key)
        }
    }
}

private data class OptionKey(
    val userId: String,
    val optionId: UUID,
)
