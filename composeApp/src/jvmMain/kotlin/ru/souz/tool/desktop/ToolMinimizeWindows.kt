package ru.souz.tool.desktop

import ru.souz.tool.*

class ToolMinimizeWindows(private val bash: ToolRunBashCommand) : ToolSetup<ToolMinimizeWindows.Input> {

    enum class WindowTarget { all, current }

    data class Input(
        @InputParamDescription("send \"all\" to minimize all windows or \"current\" to minimize the current window")
        val minimizeOption: WindowTarget
    )

    override val name: String = "MinimizeWindows"
    override val description: String = "Collapses desktop windows according to the given parameter: all windows or just the active window"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Сверни все окна",
            params = mapOf("minimizeOption" to WindowTarget.all)
        ),
        FewShotExample(
            request = "Скрой текущее окно",
            params = mapOf("minimizeOption" to WindowTarget.current)
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status")
        )
    )
    override fun invoke(input: Input): String {
        bash.invoke(
            ToolRunBashCommand.Input(
                """
                osascript <<EOF
                    set actionToExecute to "${input.minimizeOption}" 

                    if actionToExecute is "current" then

                    	tell application "System Events"
                    		set frontApp to name of first application process whose frontmost is true
                    		tell application process frontApp
                    			if exists window 1 then
                    				try
                    					try
                    						perform action "AXMinimizeWindow" of window 1
                    					on error
                    						set value of attribute "AXMinimized" of window 1 to true
                    					end try
                    				end try
                    			end if
                    		end tell
                    	end tell
                    	display notification "Текущее окно свёрнуто" with title "Готово"
                    	
                    else if actionToExecute is "all" then
                    	tell application "System Events"
                    		set visibleProcesses to application processes where background only is false
                    		repeat with eachProcess in visibleProcesses
                    			try
                    				tell eachProcess
                    					repeat with eachWindow in windows
                    						try
                    							set value of attribute "AXMinimized" of eachWindow to true
                    						end try
                    					end repeat
                    				end tell
                    			end try
                    		end repeat
                    	end tell
                    	display notification "Все окна свёрнуты" with title "Готово"
                    	
                    else
                    	display dialog ¬
                    		"Некорректный параметр. Допустимые значения: 'current' или 'all'" buttons {"OK"} default button 1 with icon stop
                    end if
                EOF
            """.trimIndent()
            )
        )
        return "Done"
    }
}
