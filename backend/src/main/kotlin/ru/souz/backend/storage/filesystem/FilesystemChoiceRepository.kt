package ru.souz.backend.storage.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.choices.model.Choice
import ru.souz.backend.choices.model.ChoiceAnswer
import ru.souz.backend.choices.model.ChoiceStatus
import ru.souz.backend.choices.repository.ChoiceAnswerUpdateResult
import ru.souz.backend.choices.repository.ChoiceRepository

class FilesystemChoiceRepository(
    dataDir: java.nio.file.Path,
    private val mapper: ObjectMapper = filesystemStorageObjectMapper(),
) : ChoiceRepository {
    private val mutex = Mutex()
    private val layout = FilesystemStorageLayout(dataDir)

    override suspend fun save(choice: Choice): Choice = mutex.withLock {
        filesystemIo {
            appendChoice(choice)
        }
    }

    override suspend fun get(userId: String, choiceId: UUID): Choice? = mutex.withLock {
        filesystemIo {
            loadAllChoices(userId).firstOrNull { it.id == choiceId }
        }
    }

    override suspend fun answerPending(
        userId: String,
        choiceId: UUID,
        answer: ChoiceAnswer,
        answeredAt: Instant,
    ): ChoiceAnswerUpdateResult = mutex.withLock {
        filesystemIo {
            val current = loadAllChoices(userId).firstOrNull { it.id == choiceId }
                ?: return@filesystemIo ChoiceAnswerUpdateResult.NotFound
            if (current.status != ChoiceStatus.PENDING) {
                return@filesystemIo ChoiceAnswerUpdateResult.NotPending(current)
            }

            val updated = current.copy(
                status = ChoiceStatus.ANSWERED,
                answer = answer,
                answeredAt = answeredAt,
            )
            appendChoice(updated)
            ChoiceAnswerUpdateResult.Updated(updated)
        }
    }

    override suspend fun listByExecution(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        limit: Int,
    ): List<Choice> = mutex.withLock {
        filesystemIo {
            loadChoices(userId, chatId)
                .filter { it.executionId == executionId }
                .sortedByDescending { it.createdAt }
                .take(limit)
        }
    }

    private fun appendChoice(choice: Choice): Choice {
        appendJsonLine(
            target = layout.choicesFile(choice.userId, choice.chatId),
            line = mapper.writeValueAsString(choice.toStored()),
        )
        return choice
    }

    private fun loadChoices(userId: String, chatId: UUID): List<Choice> =
        readLinesIfExists(layout.choicesFile(userId, chatId))
            .map { mapper.readValue<StoredChoice>(it).toDomain() }
            .associateBy { it.id }
            .values
            .sortedByDescending { it.createdAt }

    private fun loadAllChoices(userId: String): List<Choice> =
        layout.chatDirectories(userId)
            .flatMap { chatDirectory ->
                readLinesIfExists(chatDirectory.resolve("choices.jsonl"))
                    .map { mapper.readValue<StoredChoice>(it).toDomain() }
            }
            .associateBy { it.id }
            .values
            .sortedByDescending { it.createdAt }
}
