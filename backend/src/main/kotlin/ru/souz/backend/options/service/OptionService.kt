package ru.souz.backend.options.service

import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID
import ru.souz.backend.options.model.Option
import ru.souz.backend.options.model.OptionAnswer
import ru.souz.backend.options.model.OptionStatus
import ru.souz.backend.options.repository.OptionAnswerUpdateResult
import ru.souz.backend.options.repository.OptionRepository
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.service.AgentExecutionService
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.http.featureDisabledV1
import ru.souz.backend.http.invalidV1Request

data class AnswerOptionResult(
    val option: Option,
    val execution: AgentExecution,
)

class OptionService(
    private val optionRepository: OptionRepository,
    private val executionService: AgentExecutionService,
    private val featureFlags: BackendFeatureFlags,
) {
    suspend fun answer(
        userId: String,
        optionId: UUID,
        selectedOptionIds: Collection<String>,
        freeText: String?,
        metadata: Map<String, String>,
    ): AnswerOptionResult {
        requireOptionsEnabled()
        val option = optionRepository.get(userId, optionId)
            ?: throw optionNotFound()
        validatePendingOption(option)

        val normalizedAnswer = OptionAnswer(
            selectedOptionIds = normalizeSelectedOptionIds(selectedOptionIds),
            freeText = freeText?.trim()?.takeIf { it.isNotEmpty() },
            metadata = normalizeMetadata(metadata),
        )
        validateAnswer(option, normalizedAnswer)

        val updatedOption = when (
            val result = optionRepository.answerPending(
                userId = userId,
                optionId = optionId,
                answer = normalizedAnswer,
                answeredAt = Instant.now(),
            )
        ) {
            is OptionAnswerUpdateResult.Updated -> result.option
            OptionAnswerUpdateResult.NotFound -> throw optionNotFound()
            is OptionAnswerUpdateResult.NotPending -> throw invalidV1Request("Option is not pending.")
        }

        return AnswerOptionResult(
            option = updatedOption,
            execution = executionService.resumeOption(updatedOption),
        )
    }

    private suspend fun validatePendingOption(option: Option) {
        if (option.status != OptionStatus.PENDING) {
            throw invalidV1Request("Option is not pending.")
        }
        val expiresAt = option.expiresAt ?: return
        if (!expiresAt.isAfter(Instant.now())) {
            optionRepository.save(option.copy(status = OptionStatus.EXPIRED))
            throw invalidV1Request("Option has expired.")
        }
    }

    private fun validateAnswer(
        option: Option,
        answer: OptionAnswer,
    ) {
        val validOptionIds = option.options.map { it.id }.toSet()
        if (!answer.selectedOptionIds.all(validOptionIds::contains)) {
            throw invalidV1Request("selectedOptionIds must reference known options.")
        }

        when (option.selectionMode) {
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

    private fun requireOptionsEnabled() {
        if (!featureFlags.options) {
            throw featureDisabledV1("Options feature is disabled.")
        }
    }

    private fun optionNotFound(): BackendV1Exception =
        BackendV1Exception(
            status = HttpStatusCode.NotFound,
            code = "option_not_found",
            message = "Option not found.",
        )
}
