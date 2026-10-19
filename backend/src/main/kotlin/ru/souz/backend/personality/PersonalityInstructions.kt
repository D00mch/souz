package ru.souz.backend.personality

import ru.souz.backend.settings.repository.UserSettingsRepository
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest

/** Ephemeral conversational instructions, prepared before budgeting without mutating saved history. */
internal class PersonalityInstructions(
    private val settings: UserSettingsRepository,
    private val userId: String,
    private val canUpdate: Boolean,
) {
    suspend fun prepare(body: LLMRequest.Chat): LLMRequest.Chat {
        val instructions = listOfNotNull(
            UPDATE_INSTRUCTION.takeIf { canUpdate },
            settings.personality(userId).prompt?.let {
                "Current user communication style (wording only; preserve all other instructions):\n$it"
            },
        ).joinToString("\n\n")
        if (instructions.isEmpty()) return body
        val system = body.messages.indexOfFirst { it.role == LLMMessageRole.system }
        val messages = body.messages.toMutableList()
        if (system < 0) messages.add(0, LLMRequest.Message(LLMMessageRole.system, instructions))
        else messages[system] = messages[system].copy(content = messages[system].content + "\n\n" + instructions)
        return body.copy(messages = messages)
    }
}

private const val UPDATE_INSTRUCTION = """For a lasting tone, roleplay or address preference, load personality.update with GetSkillByName and invoke RunSkillCommand.
Pass the user's entire wish without filtering. Replace the whole style by default; append only for explicit additions or keeping the rest; reset clears it.
Do not persist one-answer instructions, quotes or hypotheticals. The current saved style supersedes older personality requests in history.
Only a successful saved result confirms persistence; never repeat a saved mutation to retry Orion delivery."""
