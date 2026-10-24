package ru.souz.llms.runtime

import com.fasterxml.jackson.databind.node.TextNode
import ru.souz.jev.JevClient
import ru.souz.agent.skills.SkillClassifier
import ru.souz.agent.skills.SkillId
import ru.souz.agent.spi.TaskComplexityClassifier
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest

class JevClassifier(
    private val client: JevClient,
    private val threshold: Double = 0.5,
) : SkillClassifier, TaskComplexityClassifier {
    init {
        require(threshold in 0.0..1.0) { "JEV_THRESHOLD must be a number within [0, 1]" }
    }

    override suspend fun selectSkills(request: LLMRequest.Chat, descriptions: Map<SkillId, String>): Set<SkillId> {
        val selected = select(jevState(request.messages, historyLimit = 2), "Skill", descriptions.mapKeys { it.key.value })
        return descriptions.keys.filterTo(mutableSetOf()) { it.value in selected }
    }

    /** One choice over the last two messages; "complex" wins when it is at least as likely as "simple". */
    override suspend fun isComplex(request: LLMRequest.Chat): Boolean {
        val state = TextNode(jevState(request.messages, historyLimit = 2))
        return client.choose(state, COMPLEXITY_QUESTION, COMPLEXITY_OPTIONS).getValue(COMPLEX) >= 0.5
    }

    /** One Noul question per option; options scoring strictly above [threshold] are selected. */
    private suspend fun select(state: String, kind: String, options: Map<String, String>): Set<String> {
        val questions = options.mapValues { (name, description) ->
            "Does fulfilling the latest user request require this $kind: $name ($description)?"
        }
        return client.evaluate(TextNode(state), questions, criteria = options.mapValues { SIDES })
            .filterValues { it > threshold }.keys
    }

    private companion object {
        const val COMPLEX = "complex"
        const val COMPLEXITY_QUESTION = "Какая модель нужна, чтобы выполнить последний запрос пользователя?"
        val COMPLEXITY_OPTIONS = mapOf(
            "simple" to "лёгкая модель справится: болтовня, короткий фактический вопрос, одно понятное действие " +
                "с ясными параметрами (одна встреча, одно письмо, один файл), короткий перевод или правка, простой подсчёт",
            COMPLEX to "нужна сильная модель: несколько зависимых шагов или планирование, анализ, сравнение или вывод " +
                "по многим данным, длинный или ответственный текст (документ, договор, код), рассуждение " +
                "с неоднозначностью или ценой ошибки",
        )
        val SIDES = mapOf(
            "true" to "the request or at least one of its steps needs it",
            "false" to "no step of the request needs this; a mentioned topic alone does not count",
        )
    }
}

/**
 * Plain-text Jev and FRIDA state: the last user or assistant message of [messages] (the new user message) first,
 * then up to [historyLimit] earlier non-empty ones, newest first, each cut to its first 300 characters.
 * Empty assistant messages that only call a tool are skipped.
 */
fun jevState(messages: List<LLMRequest.Message>, historyLimit: Int = Int.MAX_VALUE): String {
    val conversation = messages.filter { it.role == LLMMessageRole.user || it.role == LLMMessageRole.assistant }
    val message = conversation.lastOrNull()?.content.orEmpty()
    val history = conversation.dropLast(1).filter { it.content.isNotBlank() }.takeLast(historyLimit).asReversed()
    if (history.isEmpty()) return message
    return "New user message:\n$message\n\nPrevious messages, newest first:\n" + history.joinToString("\n") {
        "${if (it.role == LLMMessageRole.user) "User" else "Assistant"}: ${it.content.take(300).trim()}"
    }
}
