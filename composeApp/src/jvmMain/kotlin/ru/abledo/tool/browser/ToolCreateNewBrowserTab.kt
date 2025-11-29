package ru.abledo.tool.browser

import ru.abledo.tool.*
import ru.abledo.ui.main.detectDefaultBrowser
import ru.abledo.ui.main.BrowserType

class ToolCreateNewBrowserTab(private val bash: ToolRunBashCommand) : ToolSetup<ToolCreateNewBrowserTab.Input> {
    data class Input(
        @InputParamDescription("The url to open, e.g., 'https://www.sberbank.ru'")
        val url: String
    )

    override val name: String = "CreateNewBrowserTab"
    // Обновили описание, так как теперь это работает не только с Safari
    override val description: String = "Opens the given url in a new tab in the user's default browser (Safari, Chrome, etc.)"

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Открой google в новой вкладке",
            params = mapOf("url" to "https://www.google.com")
        ),
        FewShotExample(
            request = "Какая погода в Москве?",
            params = mapOf("url" to "https://yandex.ru/pogoda/ru/moscow")
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status, e.g., 'Done'")
        )
    )

    override fun invoke(input: Input): String {
        if (input.url.isBlank()) throw BadInputException("The url is empty. Can't open it")

        // 1. Определяем браузер по умолчанию
        val browserType = detectDefaultBrowser(bash)

        // 2. Формируем команду в зависимости от браузера
        val command = when (browserType) {
            BrowserType.SAFARI -> getSafariScript(input.url)
            BrowserType.CHROME -> getChromeScript(input.url)
            // Для остальных (Firefox, Arc, Edge и др.) используем системную команду open.
            // Она надежна и открывает ссылку в дефолтном браузере (обычно в новой вкладке).
            else -> "open \"${input.url}\""
        }

        // 3. Выполняем команду
        bash.invoke(ToolRunBashCommand.Input(command))

        return "Done"
    }

    private fun getSafariScript(url: String): String = """
        osascript <<EOF
            tell application "Safari"
                activate
                if (count of windows) > 0 then
                    tell front window
                        set newTab to make new tab with properties {URL:"$url"}
                        set current tab to newTab
                    end tell
                else
                    make new document with properties {URL:"$url"}
                end if
            end tell
        EOF
    """.trimIndent()

    private fun getChromeScript(url: String): String = """
        osascript <<EOF
            tell application "Google Chrome"
                activate
                if (count of windows) = 0 then
                    make new window
                end if
                tell front window
                    make new tab at end of tabs with properties {URL:"$url"}
                end tell
            end tell
        EOF
    """.trimIndent()
}