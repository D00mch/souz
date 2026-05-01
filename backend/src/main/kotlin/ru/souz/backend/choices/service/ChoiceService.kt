package ru.souz.backend.choices.service

import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID
import ru.souz.backend.choices.model.Choice
import ru.souz.backend.choices.model.ChoiceAnswer
import ru.souz.backend.choices.model.ChoiceStatus
import ru.souz.backend.choices.repository.ChoiceAnswerUpdateResult
import ru.souz.backend.choices.repository.ChoiceRepository
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.service.AgentExecutionService
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.http.featureDisabledV1
import ru.souz.backend.http.invalidV1Request

data class AnswerChoiceResult(
    val choice: Choice,
    val execution: AgentExecution,
)

class ChoiceService(
    private val choiceRepository: ChoiceRepository,
    private val executionService: AgentExecutionService,
    private val featureFlags: BackendFeatureFlags,
) {
    suspend fun answer(
        userId: String,
        choiceId: UUID,
        selectedOptionIds: Collection<String>,
        freeText: String?,
        metadata: Map<String, String>,
    ): AnswerChoiceResult {
        requireChoicesEnabled()
        val choice = choiceRepository.get(userId, choiceId)
            ?: throw choiceNotFound()
        validatePendingChoice(choice)

        val normalizedAnswer = ChoiceAnswer(
            selectedOptionIds = normalizeSelectedOptionIds(selectedOptionIds),
            freeText = freeText?.trim()?.takeIf { it.isNotEmpty() },
            metadata = normalizeMetadata(metadata),
        )
        validateAnswer(choice, normalizedAnswer)

        val updatedChoice = when (
            val result = choiceRepository.answerPending(
                userId = userId,
                choiceId = choiceId,
                answer = normalizedAnswer,
                answeredAt = Instant.now(),
            )
        ) {
            is ChoiceAnswerUpdateResult.Updated -> result.choice
            ChoiceAnswerUpdateResult.NotFound -> throw choiceNotFound()
            is ChoiceAnswerUpdateResult.NotPending -> throw invalidV1Request("Choice is not pending.")
        }

        return AnswerChoiceResult(
            choice = updatedChoice,
            execution = executionService.resumeChoice(updatedChoice),
        )
    }

    private suspend fun validatePendingChoice(choice: Choice) {
        if (choice.status != ChoiceStatus.PENDING) {
            throw invalidV1Request("Choice is not pending.")
        }
        val expiresAt = choice.expiresAt ?: return
        if (!expiresAt.isAfter(Instant.now())) {
            choiceRepository.save(choice.copy(status = ChoiceStatus.EXPIRED))
            throw invalidV1Request("Choice has expired.")
        }
    }

    private fun validateAnswer(
        choice: Choice,
        answer: ChoiceAnswer,
    ) {
        val validOptionIds = choice.options.map { it.id }.toSet()
        if (!answer.selectedOptionIds.all(validOptionIds::contains)) {
            throw invalidV1Request("selectedOptionIds must reference known choice options.")
        }

        when (choice.selectionMode) {
            "single" -> if (answer.selectedOptionIds.size != 1) {
                throw invalidV1Request("selectionMode=single requires exactly one selected option.")
            }

            "multiple" -> if (answer.selectedOptionIds.isEmpty()) {
                throw invalidV1Request("selectionMode=multiple requires at least one selected option.")
            }

            else -> throw invalidV1Request("Unsupported selectionMode.")
        }
    }

    private fun normalizeSelectedOptionIds(selectedOptionIds: Collection<String>): Set<String> {
        val normalized = linkedSetOf<String>()
        selectedOptionIds.forEach { optionId ->
            val trimmed = optionId.trim()
            if (trimmed.isEmpty()) {
                throw invalidV1Request("selectedOptionIds must not contain blank values.")
            }
            normalized += trimmed
        }
        if (normalized.isEmpty()) {
            throw invalidV1Request("selectedOptionIds must not be empty.")
        }
        return normalized
    }

    private fun normalizeMetadata(metadata: Map<String, String>): Map<String, String> =
        buildMap {
            metadata.forEach { (key, value) ->
                val normalizedKey = key.trim()
                if (normalizedKey.isEmpty()) {
                    throw invalidV1Request("metadata keys must not be blank.")
                }
                put(normalizedKey, value.trim())
            }
        }

    private fun requireChoicesEnabled() {
        if (!featureFlags.choices) {
            throw featureDisabledV1("Choices feature is disabled.")
        }
    }

    private fun choiceNotFound(): BackendV1Exception =
        BackendV1Exception(
            status = HttpStatusCode.NotFound,
            code = "choice_not_found",
            message = "Choice not found.",
        )
}
