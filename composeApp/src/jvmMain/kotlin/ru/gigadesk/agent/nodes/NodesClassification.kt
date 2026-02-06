package ru.gigadesk.agent.nodes

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import ru.gigadesk.agent.engine.AgentContext
import ru.gigadesk.agent.engine.Node
import ru.gigadesk.giga.GigaMessageRole
import ru.gigadesk.giga.GigaRequest
import ru.gigadesk.giga.GigaToolSetup
import ru.gigadesk.giga.gigaJsonMapper
import ru.gigadesk.db.SettingsProvider
import ru.gigadesk.tool.ToolCategory
import ru.gigadesk.tool.ToolCategory.*
import ru.gigadesk.tool.ToolsFactory
import ru.gigadesk.tool.ToolsSettings
import ru.gigadesk.tool.UserMessageClassifier

class NodesClassification(
    private val settingsProvider: SettingsProvider,
    private val logObjectMapper: ObjectMapper,
    private val apiClassifier: UserMessageClassifier,
    private val localClassifier: UserMessageClassifier,
    private val toolsFactory: ToolsFactory,
    private val toolsSettings: ToolsSettings,
) {
    private val l = LoggerFactory.getLogger(NodesClassification::class.java)

    /**
     * Classifies the user input and selects tools for the current step.
     *
     * Modifies [AgentContext.activeTools] based on the classification algorithm and [ToolsSettings].
     */
    fun node(name: String = "select categories"): Node<String, String> = Node(name) { ctx: AgentContext<String> ->
        val categoryStates: Map<ToolCategory, Map<String, GigaToolSetup>> =
            toolsSettings.applyFilter(toolsFactory.toolsByCategory)
        val categories: List<ToolCategory> = classify(ctx.input, ctx.history, categoryStates)

        val categoriesToChoseFrom = if (categories.isEmpty()) {
            categoryStates
        } else {
            categoryStates.filter { categories.contains(it.key) }
        }
        val functions: List<GigaRequest.Function> = categoriesToChoseFrom.flatMap { it.value.values }.map { it.fn }
        ctx.map(activeTools = functions) { it }
    }

    private suspend fun classify(
        userText: String,
        history: List<GigaRequest.Message>,
        categoryStates: Map<ToolCategory, Map<String, GigaToolSetup>>,
        retriesCount: Int = 2
    ): List<ToolCategory> {
        val body = buildClassifierBody(userText, history, categoryStates)
        val bodyJson = gigaJsonMapper.writeValueAsString(body)
        l.debug("Classifying user message: {}, \nbody: \n{}", userText, logObjectMapper.writeValueAsString(body))
        try {
            val localResult: UserMessageClassifier.Reply = localClassifier.classify(bodyJson)
            if (retriesCount <= 0) return localResult.categories

            val apiResult: UserMessageClassifier.Reply = apiClassifier.classify(bodyJson)
            val (localCategories, _) = localClassifier.classify(bodyJson)
            if (apiResult.confidence > 50 || apiResult.categories.firstOrNull() == localCategories.firstOrNull()) {
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
        history: List<GigaRequest.Message>,
        categoryStates: Map<ToolCategory, Map<String, GigaToolSetup>>
    ): GigaRequest.Chat {
        val smallHistory = history
            .takeLast(if (history.size > 3) 2 else 0)
            .joinToString("\n") { it.content }
        val messages = listOf(
            GigaRequest.Message(GigaMessageRole.system, buildPrompt(categoryStates)),
            GigaRequest.Message(GigaMessageRole.user, "History:\n$smallHistory\n"),
            GigaRequest.Message(GigaMessageRole.user, "New message:\n$userText"),
        )
        return GigaRequest.Chat(
            model = settingsProvider.gigaModel.alias,
            messages = messages,
            functions = emptyList(),
        )
    }

    fun buildPrompt(toolsByCategory: Map<ToolCategory, Map<String, GigaToolSetup>>): String {
        val allowedCategories: Set<ToolCategory> = toolsByCategory.keys
        val categoriesInfoSection = allowedCategories.joinToString(
            prefix = "Категории:\n",
            separator = ";\n"
        ) { category: ToolCategory ->
            "- ${category.name}: ${category.description()}"
        }
        val examplesSection: String = allowedCategories.joinToString(
            prefix = "Примеры:\n",
            separator = ";\n"
        ) { category: ToolCategory ->
            val examples = category.examples().joinToString(separator = "; ") { it }
            "${category.name}: $examples"
        }

        return """
Ты — алгоритм классификации. Выбери категорию запроса.
$categoriesInfoSection
$examplesSection
Ответь в формате: `<категория1>,<категория2>,...,<категорияN> <число, показывающее уверенность в правильном выборе>.
Категории представляются именами: ${allowedCategories.joinToString(",") { it.name }}, первая категория — основаня.
Число — от 0 до 100. 0 — вообще не уверен, 50 — сомневаешься, 75 — почти уверен, 100 — абсолютно уверен.
3 примера ответа ниже:
FILES 80
CALENDAR,MAIL,BROWSER 70
CHAT 100"""
    }

    private fun ToolCategory.description(): String = when(this) {
        FILES -> """|навигация по файловой системе, чтение и поиск текста в файлах, 
                    |создание, удаление или изменение файлов и папок"""

        BROWSER -> """|веб-страницы, вкладки, или браузерные горячие клавиши, 
                      |или когда надо получить общую информацию о новостях или погоде"""

        CONFIG -> "изменение или сохранение настроек, вроде скорости речи, запоминание и исполнение инструкций"
        DATAANALYTICS -> "работа с Excel, таблицами, xlsx файлами, анализ данных, сводные таблицы, графики, поиск значений в таблицах"
        CALENDAR -> "создание и удаление событий в календаре"
        MAIL -> "получение и отправка писем, список писем, чтение писем, ответ на письмо, прочтение сообщений из почты."
        NOTES -> "работа с заметками"
        APPLICATIONS -> "работа с приложениями"
        TEXT_REPLACE -> "работа с текстом, который сейчас выделен пользователем (находится под selection)"
        CHAT -> "вопрос на общие знания, не относящиеся к работе с рабочим столом, или просто болтовня"
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
            "посмотри погоду в браузере",
            "нагугли популярные статьи по X или найди что-то похожее в истории",
            "какие последние новости"
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

        DATAANALYTICS -> listOf(
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

        CHAT -> listOf(
            "как дела",
            "кто такой Шерлок Холмс",
            "сколько градусов по Цельсию в 80 по Фаренгейту",
            "как мне разбогатеть",
            "приведи пример кода",
        )
    }
}
