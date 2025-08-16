package com.dumch.tool.desktop

import com.dumch.keys.*
import com.dumch.tool.*
import java.lang.Thread.sleep

class ToolSendTelegramMessage(private val bash: ToolRunBashCommand) : ToolSetup<ToolSendTelegramMessage.Input> {
    private val cg = CoreGraphics.INSTANCE
    private val cf = CoreFoundation.INSTANCE

    data class Input(
        @InputParamDescription("Telegram contact name")
        val name: String,
        @InputParamDescription("Message to send")
        val message: String,
    )

    override val name: String = "SendTelegramMessage"
    override val description: String = "Sends a message to a Telegram contact"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Напиши в телеграм Шамилю привет",
            params = mapOf("name" to "Шамиль", "message" to "привет"),
        ),
        FewShotExample(
            request = "Можешь отправить Шамилю сообщение в TG, чтобы не забыл подготовить презентацию к завтрашнему дню",
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

        // Step 1: open Telegram by bundle id
        bash.sh("""open -b org.telegram.desktop""")

        // Step 2: wait until Telegram opens
        var opened = false
        repeat(20) {
            try {
                bash.sh("pgrep -x Telegram")
                opened = true
                return@repeat
            } catch (_: Exception) {
                sleep(500)
            }
        }
        if (!opened) sleep(1000)

        // Step 3: press Esc twice
        press(VK.ESC)
        sleep(50)
        press(VK.ESC)

        // Step 4: wait for 1 second
        sleep(1000)

        // Step 5: type the name (paste from clipboard)
        setClipboard(input.name)
        paste()

        // Step 6: press enter
        press(VK.RETURN)

        // Step 7: write the message
        setClipboard(input.message)
        paste()

        // Step 8: wait for 1 second
        sleep(1000)

        // Step 9: press enter
        press(VK.RETURN)

        return "Sent Telegram message to ${input.name}"
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
        press(VK.V)
        keyUp(VK.CMD)
    }

    private fun setClipboard(text: String) {
        val escaped = text.replace("'", "'\"'\"'")
        bash.sh("printf '%s' '$escaped' | pbcopy")
    }
}

