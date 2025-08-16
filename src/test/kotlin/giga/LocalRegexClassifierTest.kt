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
        val category = classifier.classify(body("создай файл"))
        assertEquals(ToolCategory.CODER, category)
    }

    @Test
    fun `classifies coder read`() = runBlocking {
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("прочитай readme и используй grep"))
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
        val category = classifier.classify(body("переключи вкладку браузера"))
        assertEquals(ToolCategory.BROWSER, category)
    }

    @Test
    fun `classifies config volume`() = runBlocking {
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("настрой громкость"))
        assertEquals(ToolCategory.CONFIG, category)
    }

    @Test
    fun `classifies config speed`() = runBlocking {
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("config speed"))
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
        val category = classifier.classify(body("скачай файл"))
        assertEquals(ToolCategory.IO, category)
    }

    @Test
    fun `classifies io screenshot`() = runBlocking {
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("сделай скриншот"))
        assertEquals(ToolCategory.IO, category)
    }

    @Test
    fun `returns null on tie`() = runBlocking {
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("прочитай readme и открой http://example.com"))
        assertEquals(null, category)
    }
}
