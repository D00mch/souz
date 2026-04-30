package ru.souz.tool.desktop

import ru.souz.service.keys.HotKey
import ru.souz.service.keys.Keys
import ru.souz.tool.*
import java.lang.Thread.sleep

class ToolSendTelegramMessage(
    private val bash: ToolRunBashCommand,
    private val keys: Keys,
) : ToolSetup<ToolSendTelegramMessage.Input> {

    data class Input(
        @InputParamDescription("Messenger contact name")
        val name: String,
        @InputParamDescription("Message to send")
        val message: String,
    )

    override val name: String = "SendMessage"
    override val description: String = "Sends a message to a contact"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Напиши сообщение Артуру привет",
            params = mapOf("name" to "Артур", "message" to "привет"),
        ),
        FewShotExample(
            request = "Можешь отправить Шамилю сообщение, чтобы не забыл подготовить презентацию к завтрашнему дню",
            params = mapOf(
                "name" to "Шамиль",
                "message" to "не забудь подготовить презентацию к завтрашнему дню",
            ),
        ),
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status"),
        ),
    )

    override fun invoke(input: Input): String {
        require(System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
            "This implementation supports macOS only."
        }

        // Open Telegram
        ToolRunBashCommand.apple("""tell application "Telegram" to activate""")
        sleep(1000)

        // Open find window
        keys.press(HotKey.escape)
        sleep(100)
        keys.press(HotKey.escape)
        sleep(100)
        keys.press(HotKey.find)
        sleep(300)

        // Paste the name
        setClipboard(input.name)
        keys.press(HotKey.paste)
        sleep(2000)

        // Press enter
        keys.press(HotKey.enter)
        sleep(1000)
        keys.press(HotKey.enter) // to select the first result

        // Step 7: write the message
        setClipboard(input.message)

        sleep(1000)
        keys.press(HotKey.paste)

        // Step 8: wait for 1 second
        sleep(1000)

        // Step 9: press enter
        keys.press(HotKey.enter)
        sleep(1000)

        return "Sent message to ${input.name}"
    }

    private fun setClipboard(text: String) {
        val escaped = text.replace("'", "'\"'\"'")
        bash.sh("printf '%s' '$escaped' | pbcopy")
    }
}

fun main() {
    val tool = ToolSendTelegramMessage(ToolRunBashCommand, Keys())
    println(tool.invoke(ToolSendTelegramMessage.Input("Шамиль", "привет, пишу нашим агентом! Сработало!")))
}
