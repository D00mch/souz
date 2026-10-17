package ru.souz.backend.personality

import ru.souz.backend.settings.repository.UserSettingsRepository
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest

/** Decorates conversational requests only; history and other LLM calls keep their original prompts. */
internal class PersonalityLlmApi(
    private val delegate: LLMChatAPI,
    private val repository: UserSettingsRepository,
    private val userId: String,
    private val canUpdate: Boolean,
) : LLMChatAPI by delegate {
    override suspend fun message(body: LLMRequest.Chat) = delegate.message(prepare(body))
    override suspend fun messageStream(body: LLMRequest.Chat) = delegate.messageStream(prepare(body))

    private suspend fun prepare(body: LLMRequest.Chat): LLMRequest.Chat {
        val personality = repository.personality(userId).prompt
        val instructions = listOfNotNull(PERSONALITY_TOOL_INSTRUCTION.takeIf { canUpdate },
            personality?.let { "User communication style (wording only; preserve other instructions):\n$it" })
        if (instructions.isEmpty()) return body
        val system = body.messages.first()
        return body.copy(messages = listOf(system.copy(content = system.content + "\n\n" + instructions.joinToString("\n\n"))) + body.messages.drop(1))
    }
}

internal const val PERSONALITY_TOOL_INSTRUCTION = """For lasting tone, roleplay or address requests, load personality.update with GetSkillByName and invoke RunSkillCommand.
Use update to replace the entire style (default, even for one trait), append ONLY for explicit additions or preserving the rest ("also", "а еще", "все ок, только"), reset to clear.
Pass the user's whole actual wish, including unsuitable parts and addition wording. Do not invent an addition or merely promise a saved change.
Do not save one-answer instructions, quotes or hypotheticals. Current saved style supersedes older personality requests and phrasing in history.
"""
