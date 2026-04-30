package ru.souz.tool.desktop

import ru.souz.tool.*
import ru.souz.tool.apple

class ToolOpenTelegramSavedMessages(private val bash: ToolRunBashCommand) : ToolSetup<ToolOpenTelegramSavedMessages.Input> {
    data class Input(
        @InputParamDescription("Delay time for launch the messenger")
        val delayTime: Double = 0.2
    )
    override val name: String = "OpenSavedMessages"
    override val description: String = "Opens Messenger with the saved messages chat"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Открой избранные сообщения",
            params = mapOf("delayTime" to "0.2")
        ),
        FewShotExample(
            request = "Открой сохраненки в сообщениях",
            params = mapOf("delayTime" to "0.2")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status")
        )
    )
    override fun invoke(input: Input): String {
        bash.apple(
                """
                    tell application "Telegram"
                    	if it is not running then
                    		launch
                    		delay ${input.delayTime} 
                    	else
                    		activate
                    	end if
                    end tell

                    tell application "System Events"
                    	tell process "Telegram"
                    		set frontmost to true
                    	end tell
                    end tell
                    delay 0.2

                    tell application "System Events"
                    	try
                    		key code 29 using command down -- Основной способ (физическая клавиша)
                    	on error
                    		keystroke "0" using command down -- Запасной способ
                    	end try
                    end tell
            """.trimIndent()
            )

        return "Done"
    }
}

fun main() {
    val tool = ToolOpenTelegramSavedMessages(ToolRunBashCommand)
    println(tool.invoke(ToolOpenTelegramSavedMessages.Input()))
}
