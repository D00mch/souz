package giga

import com.dumch.giga.*
import com.dumch.tool.LocalRegexClassifier
import com.dumch.tool.ToolCategory
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
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("Кодер, поправь ридми"))
        assertEquals(ToolCategory.CODER, category)
    }

    @Test
    fun `classifies coder read`() = runBlocking {
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("Можешь поправить баги в тестах?"))
        assertEquals(ToolCategory.CODER, category)
    }

    @Test
    fun `classifies browser url`() = runBlocking {
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("открой http://example.com"))
        assertEquals(ToolCategory.BROWSER, category)
    }

    @Test
    fun `classifies browser tabs`() = runBlocking {
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("Открой только что закрытую вкладку"))
        assertEquals(ToolCategory.BROWSER, category)
    }

    @Test
    fun `classifies config instruction`() = runBlocking {
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("Сохрани инструкцию: когда я говорю TG Шам, отправь Шамилю сообщение в телеграме со словом Привет"))
        assertEquals(ToolCategory.CONFIG, category)
    }

    @Test
    fun `classifies config volume and instruction`() = runBlocking {
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("Запомни инструкцию: когда я говорю «Ускорь», ускорь скорость речь на 40 слов в минуту"))
        assertEquals(ToolCategory.CONFIG, category)
    }

    @Test
    fun `classifies config speed`() = runBlocking {
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("Замедли скорость речи"))
        assertEquals(ToolCategory.CONFIG, category)
    }

    @Test
    fun `classifies desktop window`() = runBlocking {
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("перемести окно приложения"))
        assertEquals(ToolCategory.DESKTOP, category)
    }

    @Test
    fun `classifies io download`() = runBlocking {
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("скачай файл с только что отредактированным pdf файлом"))
        assertEquals(ToolCategory.IO, category)
    }

    @Test
    fun `classifies io screenshot`() = runBlocking {
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("Что на экране?"))
        assertEquals(ToolCategory.IO, category)
    }

    @Test
    fun `returns null on tie`() = runBlocking {
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("прочитай readme и открой http://example.com"))
        assertEquals(null, category)
    }
}
