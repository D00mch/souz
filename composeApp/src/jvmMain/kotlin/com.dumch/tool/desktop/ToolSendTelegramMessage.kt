package com.dumch.tool.desktop

import com.dumch.keys.*
import com.dumch.tool.*
import java.lang.Thread.sleep

class ToolSendTelegramMessage(private val bash: ToolRunBashCommand) : ToolSetup<ToolSendTelegramMessage.Input> {
    private val cg = CoreGraphics.INSTANCE
    private val cf = CoreFoundation.INSTANCE

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
        press(VK.ESC)
        sleep(100)
        press(VK.ESC)
        sleep(100)
        find()
        sleep(300)

        // Paste the name
        setClipboard(input.name)
        paste()
        sleep(2000)

        // Press enter
        press(VK.RETURN)
        sleep(1000)
        press(VK.RETURN) // to select the first result

        // Step 7: write the message
        setClipboard(input.message)

        sleep(1000)
        paste()

        // Step 8: wait for 1 second
        sleep(1000)

        // Step 9: press enter
        press(VK.RETURN)
        sleep(1000)

        return "Sent message to ${input.name}"
    }

    private fun post(key: Int, down: Boolean) {
        val evt = cg.CGEventCreateKeyboardEvent(null, key, down)
        cg.CGEventPost(CG.kCGHIDEventTap, evt)
        cf.CFRelease(evt)
    }

    private fun keyDown(k: Int) = post(k, true)
    private fun keyUp(k: Int) = post(k, false)
    private fun press(k: Int) {
        keyDown(k)
        keyUp(k)
    }

    private fun paste() {
        keyDown(VK.CMD)
        sleep(50)
        press(VK.V)
        sleep(50)
        keyUp(VK.CMD)
    }

    private fun find() {
        keyDown(VK.CMD)
        sleep(50)
        press(VK.F)
        sleep(50)
        keyUp(VK.CMD)
    }

    private fun setClipboard(text: String) {
        val escaped = text.replace("'", "'\"'\"'")
        bash.sh("printf '%s' '$escaped' | pbcopy")
    }
}

fun main() {
    val tool = ToolSendTelegramMessage(ToolRunBashCommand)
    println(tool.invoke(ToolSendTelegramMessage.Input("Шамиль", "привет, пишу нашим агентом! Сработало!")))
}