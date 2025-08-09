package com.dumch.tool.desktop

import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.ToolSetup


class ToolCollectButtons(private val bash: ToolRunBashCommand) : ToolSetup<ToolCollectButtons.Input> {

    override val name: String = "CollectButtons"
    override val description: String = "Collects buttons from frontmost application window and returns JSON with buttons description and coordinates"
    override fun invoke(input: Input): String {
        val result = bash.invoke(
            ToolRunBashCommand.Input(
                """
                osascript <<EOF
                    use AppleScript version "2.7"
                    use scripting additions

                    on collectButtonsFrom(el, depth, btnInfos)
                    	if depth > 4 then return btnInfos
                    	tell application "System Events"
                    		try
                    			tell el to set {r, n, d, h, p, s} to {role, name, description, help, position, size}
                    			if r is "AXButton" then
                    				if n is missing value or n = "" then
                    					if d is not missing value and d ≠ "" then
                    						set n to d
                    					else if h is not missing value and h ≠ "" then
                    						set n to h
                    					else
                    						set n to "[untitled button]"
                    					end if
                    				end if
                    				
                    				-- Собираем данные в плоский список
                    				set buttonData to {n, r, n, d, h, item 1 of p, item 2 of p, item 1 of s, item 2 of s}
                    				set end of btnInfos to buttonData
                    			end if
                    			
                    			repeat with child in (UI elements of el)
                    				set btnInfos to my collectButtonsFrom(child, depth + 1, btnInfos)
                    			end repeat
                    		end try
                    	end tell
                    	return btnInfos
                    end collectButtonsFrom

                    on createJsonFromData(buttonDataList)
                    	set jsonButtons to {}
                    	
                    	repeat with buttonData in buttonDataList
                    		try
                    			-- Извлекаем данные по позициям из списка
                    			set btnName to item 1 of buttonData
                    			set btnRole to item 2 of buttonData
                    			set btnDesc to item 4 of buttonData
                    			set btnHelp to item 5 of buttonData
                    			set posX to item 6 of buttonData
                    			set posY to item 7 of buttonData
                    			set width to item 8 of buttonData
                    			set height to item 9 of buttonData
                    			
                    			-- Формируем JSON-объект для кнопки
                    			set jsonButton to "{"
                    			set jsonButton to jsonButton & "\"buttonName\": \"" & btnName & "\", "
                    			set jsonButton to jsonButton & "\"role\": \"" & btnRole & "\", "
                    			set jsonButton to jsonButton & "\"name\": \"" & btnName & "\", "
                    			
                    			if btnDesc is missing value then
                    				set jsonButton to jsonButton & "\"description\": null, "
                    			else
                    				set jsonButton to jsonButton & "\"description\": \"" & btnDesc & "\", "
                    			end if
                    			
                    			if btnHelp is missing value then
                    				set jsonButton to jsonButton & "\"help\": null, "
                    			else
                    				set jsonButton to jsonButton & "\"help\": \"" & btnHelp & "\", "
                    			end if
                    			
                    			set jsonButton to jsonButton & "\"position\": [" & posX & ", " & posY & "], "
                    			set jsonButton to jsonButton & "\"size\": [" & width & ", " & height & "]"
                    			set jsonButton to jsonButton & "}"
                    			
                    			set end of jsonButtons to jsonButton
                    		on error errMsg
                    			-- Пропускаем проблемные элементы
                    			log "Error processing button: " & errMsg
                    		end try
                    	end repeat
                    	
                    	-- Собираем все кнопки в JSON-массив
                    	if (count of jsonButtons) > ${input.buttonsCount} then
                    		set text item delimiters to ", "
                    		return "[" & (jsonButtons as text) & "]"
                    	else
                    		return "[]"
                    	end if
                    end createJsonFromData

                    tell application "System Events"
                    	set frontProc to first application process whose frontmost is true
                    	set btnData to my collectButtonsFrom(front window of frontProc, 0, {})
                    end tell

                    set jsonResult to my createJsonFromData(btnData)
                    jsonResult
                EOF
                """.trimIndent()
            )
        )

        return result
    }



    class Input(
        @InputParamDescription("Default buttons count")
        val buttonsCount: String = "0"
    )
}

