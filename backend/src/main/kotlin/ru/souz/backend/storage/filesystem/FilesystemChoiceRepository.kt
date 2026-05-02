package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import ru.souz.backend.choices.model.Choice
import ru.souz.backend.choices.model.ChoiceAnswer
import ru.souz.backend.choices.model.ChoiceStatus
import ru.souz.backend.choices.repository.ChoiceAnswerUpdateResult
import ru.souz.backend.choices.repository.ChoiceRepository

class FilesystemChoiceRepository(
    dataDir: java.nio.file.Path,
    mapper: ObjectMapper = filesystemStorageObjectMapper(),
) : BaseFilesystemRepository(dataDir, mapper), ChoiceRepository {

    override suspend fun save(choice: Choice): Choice = withFileLock { appendChoice(choice) }

    override suspend fun get(userId: String, choiceId: UUID): Choice? =
        withFileLock { loadAllChoices(userId).firstOrNull { it.id == choiceId } }

    override suspend fun answerPending(
        userId: String,
        choiceId: UUID,
        answer: ChoiceAnswer,
        answeredAt: Instant,
    ): ChoiceAnswerUpdateResult =
        withFileLock {
            val current = loadAllChoices(userId).firstOrNull { it.id == choiceId }
                ?: return@withFileLock ChoiceAnswerUpdateResult.NotFound
            if (current.status != ChoiceStatus.PENDING) {
                return@withFileLock ChoiceAnswerUpdateResult.NotPending(current)
            }

            val updated = current.copy(
                status = ChoiceStatus.ANSWERED,
                answer = answer,
                answeredAt = answeredAt,
            )
            appendChoice(updated)
            ChoiceAnswerUpdateResult.Updated(updated)
        }

    override suspend fun listByExecution(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        limit: Int,
    ): List<Choice> =
        withFileLock {
            loadChoices(userId, chatId)
                .filter { it.executionId == executionId }
                .sortedByDescending { it.createdAt }
                .take(limit)
        }

    private fun appendChoice(choice: Choice): Choice {
        mapper.appendJsonValue(
            target = layout.choicesFile(choice.userId, choice.chatId),
            value = choice.toStored(),
        )
        return choice
    }

    private fun loadChoices(userId: String, chatId: UUID): List<Choice> =
        mapper.readJsonLines<StoredChoice>(layout.choicesFile(userId, chatId))
            .map(StoredChoice::toDomain)
            .associateBy { it.id }
            .values
            .sortedByDescending { it.createdAt }

    private fun loadAllChoices(userId: String): List<Choice> =
        mapper.readJsonLinesFromChatDirectories<StoredChoice>(layout, userId, "choices.jsonl")
            .map(StoredChoice::toDomain)
            .associateBy { it.id }
            .values
            .sortedByDescending { it.createdAt }
}
