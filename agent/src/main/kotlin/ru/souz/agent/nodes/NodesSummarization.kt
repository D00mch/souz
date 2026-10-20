package ru.souz.agent.nodes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.agent.graph.Node
import ru.souz.agent.graph.buildGraph
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMException
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.toMessage
import ru.souz.llms.toSystemPromptMessage
import ru.souz.llms.restJsonMapper

/**
 * Nodes responsible for summarizing conversation history.
 */
internal class NodesSummarization(
    private val llmApi: LLMChatAPI,
    private val settingsProvider: AgentSettingsProvider,
    private val conversationInstructions: suspend () -> String = { "" },
) {
    private val l = LoggerFactory.getLogger(NodesSummarization::class.java)

    /**
     * Summarizes the current history when it grows too large.
     * Updates [AgentContext.history] and [AgentContext.input] based on summarization result.
     */
    fun summarize(
        name: String = "Summarize or return",
    ): Node<LLMResponse.Chat.Ok, String> = buildGraph(name) {
        // nodes
        val summarize: Node<LLMResponse.Chat.Ok, LLMResponse.Chat.Ok> = nodeSummarize()
        val summaryToHistory: Node<LLMResponse.Chat.Ok, String> = summaryToHistory()
        val respToString: Node<LLMResponse.Chat.Ok, String> = NodesPlain.responseToString()

        // graph
        nodeInput.edgeTo { ctx ->
            val contextWindow = minOf(ctx.settings.contextSize, settingsProvider.summarizationContextSize ?: Int.MAX_VALUE)
            val request = ctx.toGigaRequest(ctx.history).withConversationInstructions(conversationInstructions())
            if (request.exceedsContextBudget(contextWindow)) summarize else respToString
        }
        summarize.edgeTo(summaryToHistory)
        summaryToHistory.edgeTo(nodeFinish)
        respToString.edgeTo(nodeFinish)
    }

    /** Updates [AgentContext.input] based on [AgentContext.history]. */
    private fun nodeSummarize(name: String = "llmSummarize"): Node<LLMResponse.Chat.Ok, LLMResponse.Chat.Ok> =
        Node(name, retryable = true) { ctx ->
            ctx.map { summarizeRequest(ctx.toGigaRequest(ctx.history)) }
        }

    /** Compact only completed history; retain the current user input and its whole tool exchange. */
    suspend fun compactHistory(request: LLMRequest.Chat): List<LLMRequest.Message> {
        val currentTurn = request.messages.indexOfLast { it.role == LLMMessageRole.user }
        if (currentTurn <= 0) return request.messages
        val previous = request.messages.take(currentTurn)
        if (previous.none { it.role != LLMMessageRole.system }) return request.messages
        val summary = summarizeRequest(request.copy(messages = previous)).toSummary()
        return previous.filter { it.role == LLMMessageRole.system } + summary + request.messages.drop(currentTurn)
    }

    private suspend fun summarizeRequest(body: LLMRequest.Chat): LLMResponse.Chat.Ok {
        val response = withContext(Dispatchers.IO) {
            llmApi.message(body.copy(
                messages = body.messages + LLMRequest.Message(LLMMessageRole.user, SUMMARIZATION_PROMPT),
                functions = emptyList(), isSummarization = true,
            ))
        }
        return when (response) {
            is LLMResponse.Chat.Error -> throw LLMException(response)
            is LLMResponse.Chat.Ok -> response
        }
    }

    private fun summaryToHistory(name: String = "summary->history"): Node<LLMResponse.Chat.Ok, String> =
        Node(name) { ctx ->
            val summary = ctx.input.toSummary()
            val response = ctx.history.last()
            l.info("Summarization\n\n{}", summary.content)
            ctx.map(history = listOf(ctx.systemPrompt.toSystemPromptMessage(), summary, response)) { response.content }
        }
}

private fun LLMResponse.Chat.Ok.toSummary(): LLMRequest.Message =
    choices.mapNotNull { it.toMessage() }.last().let { it.copy(content = "$SUMMARIZATION_PREFIX:\n${it.content}") }

// Include tool schemas and call arguments as well as text; leave 20% for the reply and estimation error.
internal fun LLMRequest.Chat.estimatedInputTokens(): Int =
    (messages.sumOf { message ->
        16 + message.content.length + message.name.orEmpty().length +
            (message.functionCall?.let { it.name.length + it.arguments.length } ?: 0)
    } +
        restJsonMapper.writeValueAsString(functions).length + 3) / 4

internal fun LLMRequest.Chat.exceedsContextBudget(contextWindow: Int = maxTokens): Boolean =
    estimatedInputTokens() >= contextWindow * 0.8

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
