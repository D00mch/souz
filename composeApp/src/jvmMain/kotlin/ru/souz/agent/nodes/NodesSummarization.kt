package ru.souz.agent.nodes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.agent.engine.AgentContext
import ru.souz.agent.engine.Node
import ru.souz.agent.engine.buildGraph
import ru.souz.giga.GigaMessageRole
import ru.souz.giga.GigaException
import ru.souz.giga.GigaRequest
import ru.souz.giga.GigaResponse
import ru.souz.giga.GigaChatAPI
import ru.souz.giga.toMessage
import ru.souz.giga.toSystemPromptMessage
import kotlin.math.ceil

/**
 * Nodes responsible for summarizing conversation history.
 */
class NodesSummarization(
    private val llmApi: GigaChatAPI,
    private val nodesCommon: NodesCommon,
) {
    private val l = LoggerFactory.getLogger(NodesSummarization::class.java)

    /**
     * Summarizes the current history when it grows too large.
     * Updates [AgentContext.history] and [AgentContext.input] based on summarization result.
     */
    fun summarize(
        name: String = "Summarize or return",
    ): Node<GigaResponse.Chat.Ok, String> = buildGraph(name) {
        // nodes
        val summarize: Node<GigaResponse.Chat.Ok, GigaResponse.Chat.Ok> = nodeSummarize()
        val summaryToHistory = summaryToHistory<GigaResponse.Chat.Ok>()
        val respToString: Node<GigaResponse.Chat.Ok, String> = nodesCommon.responseToString()

        // graph
        nodeInput.edgeTo { ctx -> if (ctx.historyIsTooBig()) summarize else respToString }
        summarize.edgeTo(summaryToHistory)
        summaryToHistory.edgeTo(respToString)
        respToString.edgeTo(nodeFinish)
    }

    /** Updates [AgentContext.input] based on [AgentContext.history]. */
    private fun nodeSummarize(name: String = "llmSummarize"): Node<GigaResponse.Chat.Ok, GigaResponse.Chat.Ok> =
        Node(name) { ctx ->
            val summaryResponse: GigaResponse.Chat = withContext(Dispatchers.IO) {
                val conversation = ArrayList(ctx.history).apply {
                    add(
                        GigaRequest.Message(
                            role = GigaMessageRole.user,
                            content = SUMMARIZATION_PROMPT,
                        )
                    )
                }
                val request = ctx.toGigaRequest(conversation).copy(functions = emptyList())
                llmApi.message(request)
            }

            when (summaryResponse) {
                is GigaResponse.Chat.Error -> throw GigaException(summaryResponse)
                is GigaResponse.Chat.Ok -> ctx.map { summaryResponse }
            }
        }

    private inline fun <reified T> summaryToHistory(name: String = "summary->history"): Node<GigaResponse.Chat.Ok, T> =
        Node(name) { ctx ->
            val msg: GigaRequest.Message = ctx.input.choices.mapNotNull { it.toMessage() }.last()
            val msgPlus = msg.copy(content = "$SUMMARIZATION_PREFIX:\n${msg.content}")
            val newHistory = listOf(ctx.systemPrompt.toSystemPromptMessage(), msgPlus)
            l.info("Summarization\n\n${msgPlus.content}")
            ctx.map(history = newHistory)
        }
}

private const val HISTORY_SUMMARIZE_THRESHOLD = 0.8
private const val APPROX_CHARS_PER_TOKEN = 4.0

private fun String.estimateTokenCount(): Int = ceil(length / APPROX_CHARS_PER_TOKEN).toInt()

private fun AgentContext<*>.historyIsTooBig(
    threshold: Double = HISTORY_SUMMARIZE_THRESHOLD,
): Boolean {
    val contextWindow = settings.contextSize
    val estimatedTokens = systemPrompt.estimateTokenCount() +
        history.sumOf { it.content.estimateTokenCount() }
    return estimatedTokens >= contextWindow * threshold
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
