package tool

import ru.gigadesk.giga.GigaMessageRole
import ru.gigadesk.giga.GigaRequest
import ru.gigadesk.giga.gigaJsonMapper
import ru.gigadesk.tool.LocalRegexClassifier
import ru.gigadesk.tool.ToolCategory
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalRegexClassifierTest {
    private fun body(text: String): String {
        val messages = ArrayDeque<GigaRequest.Message>().apply {
            add(GigaRequest.Message(GigaMessageRole.system, ""))
            add(GigaRequest.Message(GigaMessageRole.user, "History:\n"))
            add(GigaRequest.Message(GigaMessageRole.user, "New message:\n$text"))
        }
        val chat = GigaRequest.Chat(
            model = "m",
            messages = messages,
            functions = emptyList(),
        )
        return gigaJsonMapper.writeValueAsString(chat)
    }

    @Test
    fun `classifies coder creation`() = runBlocking {
        val classifier = LocalRegexClassifier
        val category = classifier.classify(body("Поправь файл ридми")).category
        assertEquals(ToolCategory.FILES, category)
    }

    @Test
    fun `classifies coder read`() = runBlocking {
        val classifier = LocalRegexClassifier
        val category = classifier.classify(body("Можешь поправить текст в zsh"))
            .category
        assertEquals(ToolCategory.FILES, category)
    }

    @Test
    fun `classifies browser url`() = runBlocking {
        val classifier = LocalRegexClassifier
        val category = classifier.classify(body("открой http://example.com"))
            .category
        assertEquals(ToolCategory.BROWSER, category)
    }

    @Test
    fun `classifies browser tabs`() = runBlocking {
        val classifier = LocalRegexClassifier
        val category = classifier.classify(body("Открой только что закрытую вкладку"))
            .category
        assertEquals(ToolCategory.BROWSER, category)
    }

    @Test
    fun `classifies config volume and instruction`() = runBlocking {
        val classifier = LocalRegexClassifier
        val category =
            classifier.classify(body("Запомни инструкцию: когда я говорю «Ускорь», ускорь скорость речь на 40 слов в минуту"))
                .category
        assertEquals(ToolCategory.CONFIG, category)
    }

    @Test
    fun `classifies config speed`() = runBlocking {
        val classifier = LocalRegexClassifier
        val category = classifier.classify(body("Замедли скорость речи"))
            .category
        assertEquals(ToolCategory.CONFIG, category)
    }

    @Test
    fun `classifies desktop window`() = runBlocking {
        val classifier = LocalRegexClassifier
        val category = classifier.classify(body("Какие приложения сейчас запущены"))
            .category
        assertEquals(ToolCategory.APPLICATIONS, category)
    }

    @Test
    fun `returns null on tie`() = runBlocking {
        val classifier = LocalRegexClassifier
        val category = classifier.classify(body("прочитай readme и открой example.com"))
            .category
        assertEquals(null, category)
    }
    
    @Test
    fun `classifies provided phrases`() = runBlocking {
        val classifier = LocalRegexClassifier
        val cases = listOf(
            "Открой приложение Интеллиджи Айдеа" to ToolCategory.APPLICATIONS,
            "Открой браузер" to ToolCategory.BROWSER,
            "Открой сайт сбера" to ToolCategory.BROWSER,
            "Найди в закладках и открой страницу с обзором фондового рынка" to ToolCategory.BROWSER,
            "Расскажи кратко о чем рассказано на текущей странице" to ToolCategory.BROWSER,
            "Открой папку семья" to ToolCategory.FILES,
            "Открой папку отчеты" to ToolCategory.FILES,
            "Построй график дохода по клиенту из файла сейлз репорт" to ToolCategory.DATAANALYTICS,
            "Добавь заметку - купить пивка" to ToolCategory.NOTES,
            "Открой заметку демо" to ToolCategory.NOTES,
        )

        for ((text, expected) in cases) {
            val category = classifier.classify(body(text))
                .category
            assertEquals(expected, category, text)
        }
    }
}