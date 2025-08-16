package giga

import com.dumch.giga.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalRegexClassifierTest {
    private fun body(text: String): GigaRequest.Chat {
        val messages = ArrayDeque<GigaRequest.Message>().apply {
            add(GigaRequest.Message(GigaMessageRole.system, ""))
            add(GigaRequest.Message(GigaMessageRole.user, "History:\n"))
            add(GigaRequest.Message(GigaMessageRole.user, "New message:\n$text"))
        }
        return GigaRequest.Chat(
            model = "m",
            messages = messages,
            functions = emptyList(),
        )
    }

    @Test
    fun `classifies io creation`() = runBlocking {
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("создай файл"))
        assertEquals(GigaAgent.ToolCategory.IO, category)
    }

    @Test
    fun `classifies io read`() = runBlocking {
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("прочитай readme и используй grep"))
        assertEquals(GigaAgent.ToolCategory.IO, category)
    }

    @Test
    fun `classifies browser url`() = runBlocking {
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("открой http://example.com"))
        assertEquals(GigaAgent.ToolCategory.BROWSER, category)
    }

    @Test
    fun `classifies browser tabs`() = runBlocking {
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("переключи вкладку браузера"))
        assertEquals(GigaAgent.ToolCategory.BROWSER, category)
    }

    @Test
    fun `classifies config volume`() = runBlocking {
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("настрой громкость"))
        assertEquals(GigaAgent.ToolCategory.CONFIG, category)
    }

    @Test
    fun `classifies config speed`() = runBlocking {
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("config speed"))
        assertEquals(GigaAgent.ToolCategory.CONFIG, category)
    }

    @Test
    fun `classifies desktop window`() = runBlocking {
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("перемести окно приложения"))
        assertEquals(GigaAgent.ToolCategory.DESKTOP, category)
    }

    @Test
    fun `classifies desktop screenshot`() = runBlocking {
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("take screenshot of desktop"))
        assertEquals(GigaAgent.ToolCategory.DESKTOP, category)
    }

    @Test
    fun `returns null on tie`() = runBlocking {
        val classifier = LocalRegexClassifier()
        val category = classifier.classify(body("прочитай readme и открой http://example.com"))
        assertEquals(null, category)
    }
}
