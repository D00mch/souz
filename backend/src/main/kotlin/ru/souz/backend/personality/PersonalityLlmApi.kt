package ru.souz.backend.personality

import ru.souz.backend.settings.repository.UserSettingsRepository
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest

/** Only the conversational loop uses this adapter; saved history and auxiliary calls stay untouched. */
internal class PersonalityLlmApi(
    private val delegate: LLMChatAPI,
    private val settings: UserSettingsRepository,
    private val userId: String,
    private val canUpdate: Boolean,
) : LLMChatAPI by delegate {
    override suspend fun message(body: LLMRequest.Chat) = delegate.message(prepare(body))
    override suspend fun messageStream(body: LLMRequest.Chat) = delegate.messageStream(prepare(body))

    private suspend fun prepare(body: LLMRequest.Chat): LLMRequest.Chat {
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
