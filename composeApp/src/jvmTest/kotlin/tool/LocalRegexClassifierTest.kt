package tool

import ru.souz.giga.GigaMessageRole
import ru.souz.giga.GigaRequest
import ru.souz.giga.gigaJsonMapper
import ru.souz.tool.LocalRegexClassifier
import ru.souz.tool.ToolCategory
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
        val categories = classifier.classify(body("Поправь файл ридми")).categories
        assertEquals(listOf(ToolCategory.FILES), categories)
    }

    @Test
    fun `classifies coder read`() = runBlocking {
        val classifier = LocalRegexClassifier
        val categories = classifier.classify(body("Можешь поправить текст в zsh"))
            .categories
        assertEquals(listOf(ToolCategory.FILES), categories)
    }

    @Test
    fun `classifies browser url`() = runBlocking {
        val classifier = LocalRegexClassifier
        val categories = classifier.classify(body("открой http://example.com"))
            .categories
        assertEquals(listOf(ToolCategory.BROWSER, ToolCategory.APPLICATIONS), categories)
    }

    @Test
    fun `classifies browser tabs`() = runBlocking {
        val classifier = LocalRegexClassifier
        val categories = classifier.classify(body("Открой только что закрытую вкладку"))
            .categories
        assertEquals(listOf(ToolCategory.BROWSER, ToolCategory.APPLICATIONS), categories)
    }

    @Test
    fun `classifies config volume and instruction`() = runBlocking {
        val classifier = LocalRegexClassifier
        val categories =
            classifier.classify(body("Запомни инструкцию: когда я говорю «Ускорь», ускорь скорость речь на 40 слов в минуту"))
                .categories
        assertEquals(listOf(ToolCategory.CONFIG), categories)
    }

    @Test
    fun `classifies config speed`() = runBlocking {
        val classifier = LocalRegexClassifier
        val categories = classifier.classify(body("Замедли скорость речи"))
            .categories
        assertEquals(listOf(ToolCategory.CONFIG), categories)
    }

    @Test
    fun `classifies desktop window`() = runBlocking {
        val classifier = LocalRegexClassifier
        val categories = classifier.classify(body("Какие приложения сейчас запущены"))
            .categories
        assertEquals(listOf(ToolCategory.APPLICATIONS), categories)
    }

    @Test
    fun `returns null on tie`() = runBlocking {
        val classifier = LocalRegexClassifier
        val categories = classifier.classify(body("прочитай readme и открой example.com"))
            .categories
        assertEquals(listOf(ToolCategory.FILES, ToolCategory.APPLICATIONS, ToolCategory.MAIL), categories)
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
            val categories = classifier.classify(body(text))
                .categories
            assertEquals(expected, categories.first(), text)
        }
    }
}
