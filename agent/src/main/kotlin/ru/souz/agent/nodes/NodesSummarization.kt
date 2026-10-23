package ru.souz.agent.nodes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMException
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse

/** Compacts only the completed prefix, preserving system instructions and the entire active exchange. */
internal suspend fun LLMChatAPI.compactHistory(
    request: LLMRequest.Chat,
    activeExchangeStartIndex: Int,
): List<LLMRequest.Message> {
    val previous = request.messages.take(activeExchangeStartIndex)
    if (previous.none { it.role != LLMMessageRole.system }) return request.messages

    val body = request.copy(
        messages = previous + LLMRequest.Message(LLMMessageRole.user, SUMMARIZATION_PROMPT),
        functions = emptyList(),
        maxTokens = SUMMARIZATION_MAX_OUTPUT_TOKENS,
        isSummarization = true,
    )
    // Keep history intact if the complete summary request cannot fit.
    if (body.exceedsContextBudget(requireNotNull(request.contextSize))) return request.messages
    val response = withContext(Dispatchers.IO) { message(body) }
    if (response is LLMResponse.Chat.Error) throw LLMException(response)
    val choice = (response as LLMResponse.Chat.Ok).choices.lastOrNull { it.message.content.isNotBlank() }
        ?: throw LLMException(LLMResponse.Chat.Error(-1, "Summarization returned no text."))
    if (choice.finishReason == LLMResponse.FinishReason.length) {
        throw LLMException(LLMResponse.Chat.Error(-1, "Summarization reached its output limit."))
    }
    val summary = choice.message.content
    val message = LLMRequest.Message(LLMMessageRole.assistant, "$SUMMARIZATION_PREFIX:\n$summary",
        files = previous.flatMap { it.files + it.replyFiles }.distinctBy { it.file })
    return previous.filter { it.role == LLMMessageRole.system } + message + request.messages.drop(activeExchangeStartIndex)
}

private const val SUMMARIZATION_PROMPT = """
Ты — модуль управления памятью для автономного AI-агента.
Текущая сессия переполнена, и нам необходимо создать "Точку сохранения" (Save Point) для переноса в новый контекст.
Проанализируй всю историю диалога и сгенерируй СЖАТОЕ техническое саммари.
Твоя задача — отбросить "светскую беседу" и сохранить только факты, необходимые для продолжения работы.
Используй строго следующую структуру для ответа:

---
# MEMORY DUMP [TIMESTAMP]

## 1. Глобальная Цель (Global Goal)
[Кратко: Чего мы добиваемся в конечном итоге? Например: "Написать парсер логов и вывести отчет в Excel"]

## 2. Активное Окружение (Environment State)
* Рабочая директории: [Укажи пути, над которыми велась работа]
* Активные файлы: [Список файлов, которые мы создали, редактировали или читали. Укажи их состояние: "готов", "с ошибкой", "черновик"]
* Использованные инструменты: [Какие тулы мы использовали в процессе работы]

## 3. Выполненные шаги (Execution Log)
* [Шаг 1: Успех]
* [Шаг 2: Успех]
* [Шаг 3: Ошибка -> Исправлено]
* (Пиши только те шаги, которые влияют на текущее состояние. Неудачные попытки, которые мы уже исправили, можно опустить, если они не несут урока).

## 4. Критические данные (Critical Data)
* [Если были важные инструкции от пользователя, например "Не используй библиотеку Pandas", запиши это здесь]

## 5. Текущая проблема и Следующий шаг (Immediate Action in any)
* На чем остановились: [Конкретное место затыка или последний вывод]
* План действий: [Что агент должен сделать сразу после перезагрузки памяти]
---

ВАЖНО:
- Не используй общие фразы ("мы искали файл"). Пиши конкретику ("мы искали файл 100 ошибок в го в папке /Downloads").
- Сохраняй все пути к файлам и названия функций точно как в оригинале.
"""

private const val SUMMARIZATION_PREFIX = """
Предыдущая сессия была сжата. Вот состояние памяти (Memory Dump), с которого ты должен продолжить работу. 
Восстанови контекст и сразу приступай к выполнению пункта 'Следующий шаг'"
"""
