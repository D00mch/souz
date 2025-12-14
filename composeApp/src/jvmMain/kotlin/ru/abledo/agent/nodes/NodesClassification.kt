package ru.abledo.agent.nodes

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import ru.abledo.agent.engine.AgentContext
import ru.abledo.agent.engine.Node
import ru.abledo.giga.GigaMessageRole
import ru.abledo.giga.GigaModel
import ru.abledo.giga.GigaRequest
import ru.abledo.giga.GigaToolSetup
import ru.abledo.giga.gigaJsonMapper
import ru.abledo.tool.ToolCategory
import ru.abledo.tool.ToolsFactory
import ru.abledo.tool.ToolsSettings
import ru.abledo.tool.UserMessageClassifier

class NodesClassification(
    private val model: GigaModel,
    private val logObjectMapper: ObjectMapper,
    private val apiClassifier: UserMessageClassifier,
    private val localClassifier: UserMessageClassifier,
    private val toolsFactory: ToolsFactory,
    private val toolsSettings: ToolsSettings,
) {
    private val l = LoggerFactory.getLogger(NodesClassification::class.java)

    /**
     * Choose correct tools. Depends on the Classification algorithm and [ToolsSettings]
     */
    val node: Node<String, String> = Node("classify") { ctx: AgentContext<String> ->
        val categoryStates: Map<ToolCategory, Map<String, GigaToolSetup>> =
            toolsSettings.applyFilter(toolsFactory.toolsByCategory)
        val category = classify(ctx.input, ctx.history, categoryStates)

        val functions: List<GigaRequest.Function> = if (category == null) {
            categoryStates.flatMap { it.value.values }.map { it.fn }
        } else {
            categoryStates[category]!!.values.map { it.fn }
        }
        ctx.map(activeTools = functions) { it }
    }

    private suspend fun classify(
        userText: String,
        history: List<GigaRequest.Message>,
        categoryStates: Map<ToolCategory, Map<String, GigaToolSetup>>
    ): ToolCategory? {
        val body = buildClassifierBody(userText, history, categoryStates)
        val bodyJson = gigaJsonMapper.writeValueAsString(body)
        l.debug("Classifying user message: {}, \nbody: \n{}", userText, logObjectMapper.writeValueAsString(body))
        return try {
            val categoryByLocal = localClassifier.classify(bodyJson)
            val categoryByApi = apiClassifier.classify(bodyJson)
            if (categoryByApi != categoryByLocal) {
                l.info("Categories do not match: Local: {}, API: {}", categoryByLocal, categoryByApi)
                null
            } else {
                categoryByLocal
            }
        } catch (e: Exception) {
            l.error("Error in apiClassifier: {}", e.message)
            null
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
            model = model.alias,
            messages = messages,
            functions = emptyList(),
        )
    }

    fun buildPrompt(toolsByCategory: Map<ToolCategory, Map<String, GigaToolSetup>>): String {
        val allowedCategories = toolsByCategory.keys
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
            Ответь с только одним словом: ${allowedCategories.joinToString(",") { it.name }}
        """.trimIndent()

    }

    private fun ToolCategory.description(): String = when(this) {
        ToolCategory.FILES -> """
навигация по файловой системе, чтение и поиск текста в файлах, создание, удаление или изменение файлов и папок"""

        ToolCategory.BROWSER -> """
веб-страницы, вкладки, или браузерные горячие клавиши, или когда надо получить общую информацию о новостях или погоде"""

        ToolCategory.CONFIG -> """
изменение или сохранение настроек, вроде скорости речи, запоминание и исполнение инструкций"""

        ToolCategory.DATAANALYTICS -> """
когда надо создать график или найти корреляцию между двумя переменными"""

        ToolCategory.CALENDAR -> """
создание и удаление событий в календаре"""

        ToolCategory.MAIL -> """
получение и отправка писем, список писем, чтение писем, ответ на письмо, прочтение сообщений из почты"""

        ToolCategory.NOTES -> """
работа с заметками"""

        ToolCategory.APPLICATIONS -> """
работа с приложениями"""
    }
        .trimIndent()

    private fun ToolCategory.examples(): List<String> = when (this) {
        ToolCategory.FILES -> listOf(
            "покажи содержимое файла README",
            "найди слово \"ошибка\" в логах в папке Downloads",
            "отредактируй файл",
            "какие файлы находятся в загрузках",
            "что находится в документа <Book name>",
            "открой папку Загрузки",
        )

        ToolCategory.BROWSER -> listOf(
            "открой сайт сбербанка",
            "найди в закладках обзор фондового рынка",
            "расскажи кратко о чем рассказано на текущей странице",
            "скачай с текущей страницы pdf и сохрани в Загрузки"
        )

        ToolCategory.CONFIG -> listOf(
            "запомни: когда я говорю \"тишина\" — уменьшай громкость на 20%",
            "включи режим разработчика",
            "измени язык интерфейса на английский",
            "покажи текущие настройки приложения"
        )

        ToolCategory.NOTES -> listOf(
            "создай заметку",
            "найди заметку"
        )

        ToolCategory.APPLICATIONS -> listOf(
            "открой приложение Хром",
            "открой приложение Outlook",
            "какие приложения сейчас открыты",
        )

        ToolCategory.DATAANALYTICS -> listOf(
            "построй график дохода по клиенту за последние 6 месяцев",
            "посчитай средний чек по дням и покажи таблицу",
            "сделай сводную: расходы по категориям за ноябрь",
            "найди аномалии в продажах за последнюю неделю"
        )

        ToolCategory.CALENDAR -> listOf(
            "что у меня сегодня по плану",
            "поставь встречу завтра в 15:00 на 30 минут: созвон с Артуром",
            "перенеси встречу \"демо\" на пятницу на 11:00",
            "когда у меня ближайшее свободное окно на час?"
        )

        ToolCategory.MAIL -> listOf(
            "какие письма у меня непрочитанные",
            "найди письмо от Артура про договор",
            "ответь на письмо Артура: \"Спасибо, получил\"",
            "сделай краткое резюме последнего письма от банка"
        )
    }
}