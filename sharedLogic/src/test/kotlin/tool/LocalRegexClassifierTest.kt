package tool

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.tool.LocalRegexClassifier
import ru.souz.tool.ToolCategory.*
import kotlin.test.assertEquals

class LocalRegexClassifierTest {
    private fun body(text: String) = LLMRequest.Chat(
        model = "m",
        messages = listOf(
            LLMRequest.Message(LLMMessageRole.system, ""),
            LLMRequest.Message(LLMMessageRole.user, "History:\n"),
            LLMRequest.Message(LLMMessageRole.user, "New message:\n$text"),
        ),
    )

    @TestFactory
    fun `classifies exact category lists`() = listOf(
        "Поправь файл ридми" to listOf(FILES),
        "Можешь поправить текст в zsh" to listOf(FILES),
        "Посмотри изображение /Users/user/Pictures/cat.png и опиши что на нем" to listOf(IMAGE),
        "открой http://example.com" to listOf(BROWSER, APPLICATIONS),
        "Открой только что закрытую вкладку" to listOf(BROWSER, APPLICATIONS),
        "Запомни инструкцию: когда я говорю «Ускорь», ускорь скорость речь на 40 слов в минуту" to listOf(CONFIG),
        "Замедли скорость речи" to listOf(CONFIG),
        "Какие приложения сейчас запущены" to listOf(APPLICATIONS),
        "прочитай readme и открой example.com" to listOf(FILES, APPLICATIONS, MAIL),
    ).map { (text, expected) ->
        dynamicTest(text) {
            runBlocking { assertEquals(expected, LocalRegexClassifier.classify(body(text)).categories) }
        }
    }

    @TestFactory
    fun `classifies primary categories`() = listOf(
        "Открой приложение Интеллиджи Айдеа" to APPLICATIONS,
        "Открой браузер" to BROWSER,
        "Открой сайт сбера" to BROWSER,
        "Найди в закладках и открой страницу с обзором фондового рынка" to BROWSER,
        "Расскажи кратко о чем рассказано на текущей странице" to BROWSER,
        "Открой папку семья" to FILES,
        "Открой папку отчеты" to FILES,
        "Построй график дохода по клиенту из файла сейлз репорт" to DATA_ANALYTICS,
        "Добавь заметку - купить пивка" to NOTES,
        "Открой заметку демо" to NOTES,
        "Что ты умеешь?" to HELP,
        "Какие у тебя есть возможности?" to HELP,
        "help" to HELP,
        "What can you do?" to HELP,
        "Какая погода в Таллине" to WEB_SEARCH,
        "Нужно найти подходящую библиотеку для создания презентаций" to WEB_SEARCH,
        "Перешли это в другой канал" to CHANNEL_MESSAGING,
        "Какие у меня есть каналы для пересылки сообщений" to CHANNEL_MESSAGING,
        "Перешли это в телеграм" to CHANNEL_MESSAGING,
    ).map { (text, expected) ->
        dynamicTest(text) {
            runBlocking { assertEquals(expected, LocalRegexClassifier.classify(body(text)).categories.first()) }
        }
    }
}
