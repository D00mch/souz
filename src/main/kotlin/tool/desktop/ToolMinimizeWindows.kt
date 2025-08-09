package com.dumch.tool.desktop

import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.ToolSetup

class ToolMinimizeWindows(private val bash: ToolRunBashCommand) : ToolSetup<ToolMinimizeWindows.Input> {

    override val name: String = "MinimizeWindows"
    override val description: String = "Collapses desktop windows according to the given parameter: all windows or just the active window"
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

    class Input(
        @InputParamDescription("Option: minimize(collapse) all windows or just the current window")
        val minimizeOption: String
    )
}
