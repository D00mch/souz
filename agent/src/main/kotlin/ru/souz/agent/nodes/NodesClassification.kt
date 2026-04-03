package ru.souz.agent.nodes

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import ru.souz.agent.graph.Node
import ru.souz.agent.state.AgentContext
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory
import ru.souz.tool.ToolCategory.*
import ru.souz.tool.UserMessageClassifier

internal class NodesClassification(
    private val settingsProvider: AgentSettingsProvider,
    private val logObjectMapper: ObjectMapper,
    private val apiClassifier: UserMessageClassifier,
    private val localClassifier: UserMessageClassifier,
    private val toolCatalog: AgentToolCatalog,
    private val toolsFilter: AgentToolsFilter,
) {
    private val l = LoggerFactory.getLogger(NodesClassification::class.java)

    private companion object {
        const val DEFAULT_HISTORY_WINDOW = 3
        const val EXPANDED_HISTORY_WINDOW = 4
        const val SHORT_MESSAGE_CHAR_THRESHOLD = 24
        const val SHORT_MESSAGE_WORD_THRESHOLD = 4
        val WORD_REGEX = Regex("""[\p{L}\p{N}]+(?:['’_-][\p{L}\p{N}]+)*""")
    }

    /**
     * Classifies the user input and selects tools for the current step.
     *
     * Modifies [AgentContext.activeTools] based on the classification algorithm and [AgentToolCatalog].
     */
    fun node(name: String = "select categories"): Node<String, String> = Node(name) { ctx: AgentContext<String> ->
        val categoryStates: Map<ToolCategory, Map<String, LLMToolSetup>> =
            toolsFilter.applyFilter(toolCatalog.toolsByCategory)
        val categories: List<ToolCategory> = classify(ctx.input, ctx.history, categoryStates)

        val categoriesToChoseFrom = if (categories.isEmpty() || categories.contains(HELP)) {
            categoryStates
        } else {
            categoryStates.filter { categories.contains(it.key) }
        }
        val functions: List<LLMRequest.Function> = categoriesToChoseFrom.flatMap { it.value.values }.map { it.fn }
        ctx.map(activeTools = functions) { it }
    }

    private suspend fun classify(
        userText: String,
        history: List<LLMRequest.Message>,
        categoryStates: Map<ToolCategory, Map<String, LLMToolSetup>>,
        retriesCount: Int = 2
    ): List<ToolCategory> {
        val body = buildClassifierBody(userText, history, categoryStates)
        val bodyJson = restJsonMapper.writeValueAsString(body)
        l.debug("Classifying user message: {}, \nbody: \n{}", userText, logObjectMapper.writeValueAsString(body))
        try {
            val localResult: UserMessageClassifier.Reply = localClassifier.classify(bodyJson)
            if (retriesCount <= 0) {
                return localResult.categories
            }

            val apiResult: UserMessageClassifier.Reply = apiClassifier.classify(bodyJson)
            if (apiResult.confidence > 50 || apiResult.categories.firstOrNull() == localResult.categories.firstOrNull()) {
                return apiResult.categories
            } else {
                l.info("Categories mismatch: Local: ${localResult}, API: ${apiResult}.")
                return emptyList()
            }
        } catch (e: Exception) {
            l.error("Error in apiClassifier: {}", e.message)
            return classify(userText, history, categoryStates, retriesCount.dec())
        }
    }

    private fun buildClassifierBody(
        userText: String,
        history: List<LLMRequest.Message>,
        categoryStates: Map<ToolCategory, Map<String, LLMToolSetup>>
    ): LLMRequest.Chat {
        val formattedHistory = historyForClassification(userText, history)
            .joinToString(separator = "\n\n") { message ->
                "${message.role.name.uppercase()}: ${message.content.trim()}"
            }
        val messages = listOf(
            LLMRequest.Message(LLMMessageRole.system, buildPrompt(categoryStates)),
            LLMRequest.Message(LLMMessageRole.user, "History:\n$formattedHistory\n"),
            LLMRequest.Message(LLMMessageRole.user, "New message:\n$userText"),
        )
        return LLMRequest.Chat(
            model = settingsProvider.gigaModel.alias,
            messages = messages,
            functions = emptyList(),
        )
    }

    private fun historyForClassification(
        userText: String,
        history: List<LLMRequest.Message>,
    ): List<LLMRequest.Message> {
        val conversationHistory = history
            .filterNot { it.role == LLMMessageRole.system }
            .filterNot { it.isInjectedContext() }
            .dropCurrentUserTurn(userText)

        val historyWindow = if (isUserPromptTooShort(userText)) {
            EXPANDED_HISTORY_WINDOW
        } else {
            DEFAULT_HISTORY_WINDOW
        }

        return conversationHistory.takeLast(historyWindow)
    }

    private fun LLMRequest.Message.isInjectedContext(): Boolean =
        role == LLMMessageRole.user && content.contains("<context>")

    private fun List<LLMRequest.Message>.dropCurrentUserTurn(userText: String): List<LLMRequest.Message> {
        val lastMessage = lastOrNull() ?: return this
        if (lastMessage.role != LLMMessageRole.user) return this
        if (lastMessage.content != userText) return this
        return dropLast(1)
    }

    private fun isUserPromptTooShort(userText: String): Boolean {
        val normalizedText = userText.trim()
        if (normalizedText.isBlank()) return false
        if (normalizedText.length <= SHORT_MESSAGE_CHAR_THRESHOLD) return true
        val wordsCount = WORD_REGEX.findAll(normalizedText).count()
        return wordsCount <= SHORT_MESSAGE_WORD_THRESHOLD
    }

    fun buildPrompt(toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>): String {
        val allowedCategories: Set<ToolCategory> = toolsByCategory.keys
        val categoriesInfoSection = allowedCategories.joinToString(
            prefix = "Категории:\n", separator = ";\n"
        ) { category: ToolCategory ->
            "- ${category.name}: ${category.description()}"
        }
        val examplesSection: String = allowedCategories.joinToString(
            prefix = "Примеры:\n", separator = ";\n"
        ) { category: ToolCategory ->
            val examples = category.examples().joinToString(separator = "; ") { it }
            "${category.name}: $examples"
        }

        return """
Твоя задача — выбрать минимальный, но достаточный набор категорий, для выполнения запроса пользователя.

Правила выбора:
1. Мысленно разложи запрос на шаги, условия и зависимые действия.
2. Если запрос составной ("сначала", "потом", "если"), учти категории, необходимые для каждого шага.
3. Выбирай категорию только если инструмент из неё действительно нужен, а не просто тема разговора пересекается с ней.
4. Если новый запрос сам по себе достаточно конкретен, опирайся прежде всего на него.
5. Если новый запрос короткий или в нем пропущен объект действия, восстанови недостающий контекст из недавней истории и только потом выбери категорию.
6. Не путай работу с файлами и работу с выделенным текстом: TEXT_REPLACE подходит только когда речь именно про текущий selection.

$categoriesInfoSection
$examplesSection

Проверка перед ответом:
- не повторяй категории
- не добавляй пояснений, кавычек или markdown
- если новый запрос короткий, сначала восстанови, что именно нужно сделать по истории, и только потом выбирай категорию

Формат ответа:
CATEGORY1,CATEGORY2 0-100

Примеры формата:
"Найди отчет в документах" -> "FILES 92"
"Проверь мои встречи на сегодня и отправь Олегу письмо с итогом" -> "CALENDAR,MAIL 95"
"Собери свежие источники по рынку ИИ и подготовь презентацию" -> "WEB_SEARCH,PRESENTATION 94"
"""
    }

    private fun ToolCategory.description(): String = when (this) {
        FILES -> """|навигация по файловой системе, чтение и поиск текста в файлах, 
                    |создание, удаление или изменение файлов и папок"""

        BROWSER -> """|веб-страницы, вкладки, или браузерные горячие клавиши, 
                      |а также открытие сайтов в браузере"""

        WEB_SEARCH -> """|поиск информации, фактов, новостей и источников в интернете, 
                         |как для простого ответа на вопрос, так и для многошагового ресерча, сравнения и подбора решений"""

        CONFIG -> "изменение или сохранение настроек, вроде скорости речи, запоминание и исполнение инструкций"
        DATA_ANALYTICS -> "работа с Excel, таблицами, xlsx файлами, анализ данных, сводные таблицы, графики, поиск значений в таблицах"
        CALENDAR -> "поиск, создание и удаление событий в календаре"
        MAIL -> "получение и отправка писем, список писем, чтение писем, ответ на письмо, прочтение сообщений из почты."
        NOTES -> "работа с заметками"
        APPLICATIONS -> "работа с приложениями"
        TEXT_REPLACE -> "работа с текстом, который сейчас выделен пользователем (находится под selection)"
        CALCULATOR -> "выполнение математических операций, подсчет выражений"
        CHAT -> "вопрос на общие знания, не относящиеся к работе с рабочим столом, или просто болтовня"
        TELEGRAM -> "действия в Telegram (TG): чтение входящих, отправка сообщений, поиск по истории, изменение состояния чатов"
        DESKTOP -> "работа с экраном, скриншотами и записью видео с экрана"
        PRESENTATION -> "создание презентаций ppxt, чтение слайдов, работа с powerpoint"
        HELP -> "вопрос о возможностях приложения, что оно умеет, какие есть функции, помощь"
    }.trimMargin().trimIndent()

    private fun ToolCategory.examples(): List<String> = when (this) {
        FILES -> listOf(
            "покажи содержимое файла README",
            "найди слово \"ошибка\" в логах в папке Downloads",
            "отредактируй файл",
            "какие файлы находятся в загрузках",
            "что находится в документа <Book name>",
            "открой папку Загрузки",
        )

        BROWSER -> listOf(
            "открой сайт сбербанка",
            "найди в закладках обзор фондового рынка",
            "переключи вкладку на YouTube",
            "открой новую вкладку",
            "поищи в истории браузера",
            "какие сайты я чаще всего посещаю"
        )

        WEB_SEARCH -> listOf(
            "какая погода в Таллине",
            "проведи исследование про ИИ во Франции",
            "найди последние новости про ИИ",
            "собери источники по кибербезопасности",
            "найди изображения для презентации",
            "извлеки текст со страницы отчета",
            "помоги создать презентацию на основе данных"
        )

        CONFIG -> listOf(
            "запомни: когда я говорю \"тишина\" — уменьшай громкость на 20%",
            "включи режим разработчика",
            "измени язык интерфейса на английский",
            "покажи текущие настройки приложения"
        )

        NOTES -> listOf(
            "создай заметку",
            "найди заметку",
        )

        APPLICATIONS -> listOf(
            "открой приложение Хром",
            "открой приложение Outlook",
            "какие приложения сейчас открыты",
        )

        DATA_ANALYTICS -> listOf(
            "построй график дохода по клиенту за последние 6 месяцев",
            "посчитай средний чек по дням и покажи таблицу",
            "сделай сводную: расходы по категориям за ноябрь",
            "найди аномалии в продажах за последнюю неделю",
            "создай эксель таблицу отчёт с колонками: имя, должность, зарплата",
            "объедини все xlsx файлы из папки отчёты в один",
            "удали строки из эксельки где цена меньше 1000",
            "покажи структуру таблицы sales.xlsx",
        )

        CALENDAR -> listOf(
            "что у меня сегодня по плану",
            "поставь встречу завтра в 15:00 на 30 минут: созвон с Артуром",
            "перенеси встречу \"демо\" на пятницу на 11:00",
            "когда у меня ближайшее свободное окно на час?"
        )

        MAIL -> listOf(
            "какие письма у меня непрочитанные",
            "найди письмо от Артура про договор",
            "ответь на письмо Артура: \"Спасибо, получил\"",
            "сделай краткое резюме последнего письма от банка"
        )

        TEXT_REPLACE -> listOf(
            "исправь грамматические ошибки в выделенном тексте",
            "сделай текст, который я заселектил, более официальным",
            "можешь перевести выделенный текст на Русский"
        )

        CALCULATOR -> listOf(
            "сколько будет 25 * 4",
            "посчитай 128 / 4 + 10",
            "корень из 144",
        )

        CHAT -> listOf(
            "как дела",
            "кто такой Шерлок Холмс",
            "сколько градусов по Цельсию в 80 по Фаренгейту",
            "как мне разбогатеть",
            "приведи пример кода",
        )

        TELEGRAM -> listOf(
            "прочитай непрочитанные сообщения в телеграме",
            "напиши Васе в телеграм: буду через 15 минут",
            "найди в телеграме где обсуждали созвон",
            "архивируй чат с каналом Новости",
        )

        DESKTOP -> listOf(
            "сделай скриншот",
            "запиши видео с экрана",
            "сфоткай экран",
            "включи запись экрана",
        )

        PRESENTATION -> listOf(
            "создай презентацию про ИИ",
            "прочитай слайды из файла presentation.pptx",
            "добавь слайд с заголовком",
        )

        HELP -> listOf(
            "что ты умеешь",
            "какие у тебя функции",
            "помощь",
            "что ты можешь делать",
        )
    }
}
