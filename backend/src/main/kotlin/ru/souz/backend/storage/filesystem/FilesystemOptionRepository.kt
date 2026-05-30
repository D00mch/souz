package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import ru.souz.backend.options.model.Option
import ru.souz.backend.options.model.OptionAnswer
import ru.souz.backend.options.model.OptionStatus
import ru.souz.backend.options.repository.OptionAnswerUpdateResult
import ru.souz.backend.options.repository.OptionRepository

class FilesystemOptionRepository(
    dataDir: java.nio.file.Path,
    mapper: ObjectMapper = filesystemStorageObjectMapper(),
) : BaseFilesystemRepository(dataDir, mapper), OptionRepository {

    override suspend fun save(option: Option): Option = withFileLock { appendOption(option) }

    override suspend fun get(userId: String, optionId: UUID): Option? =
        withFileLock { loadAllOptions(userId).firstOrNull { it.id == optionId } }

    override suspend fun answerPending(
        userId: String,
        optionId: UUID,
        answer: OptionAnswer,
        answeredAt: Instant,
    ): OptionAnswerUpdateResult =
        withFileLock {
            val current = loadAllOptions(userId).firstOrNull { it.id == optionId }
                ?: return@withFileLock OptionAnswerUpdateResult.NotFound
            if (current.status != OptionStatus.PENDING) {
                return@withFileLock OptionAnswerUpdateResult.NotPending(current)
            }

            val updated = current.copy(
                status = OptionStatus.ANSWERED,
                answer = answer,
                answeredAt = answeredAt,
            )
            appendOption(updated)
            OptionAnswerUpdateResult.Updated(updated)
        }

    override suspend fun listByExecution(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        limit: Int,
    ): List<Option> =
        withFileLock {
            loadOptions(userId, chatId)
                .filter { it.executionId == executionId }
                .sortedByDescending { it.createdAt }
                .take(limit)
        }

    private fun appendOption(option: Option): Option {
        mapper.appendJsonValue(
            target = layout.optionsFile(option.userId, option.chatId),
            value = option.toStored(),
        )
        return option
    }

    private fun loadOptions(userId: String, chatId: UUID): List<Option> =
        (
            mapper.readJsonLines<StoredOption>(layout.optionsFile(userId, chatId)) +
                mapper.readJsonLines<StoredOption>(layout.legacyOptionsFile(userId, chatId))
            )
            .map(StoredOption::toDomain)
            .associateBy { it.id }
            .values
            .sortedByDescending { it.createdAt }

    private fun loadAllOptions(userId: String): List<Option> =
        (
            mapper.readJsonLinesFromChatDirectories<StoredOption>(layout, userId, "options.jsonl") +
                mapper.readJsonLinesFromChatDirectories<StoredOption>(layout, userId, "choices.jsonl")
            )
            .map(StoredOption::toDomain)
            .associateBy { it.id }
            .values
            .sortedByDescending { it.createdAt }
}
