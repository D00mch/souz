package com.dumch.tool.desktop

import com.dumch.audio.playText
import com.dumch.giga.objectMapper
import com.dumch.giga.toGiga
import com.dumch.image.ImageUtils
import com.dumch.tool.*
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory


class ToolCollectButtons(
    private val bash: ToolRunBashCommand,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : ToolSetup<ToolCollectButtons.Input> {
    private val l = LoggerFactory.getLogger(ToolCollectButtons::class.java)

    data class Input(
        @InputParamDescription("Default buttons count is 7. If you want to return more, send a number, e.g., 15")
        val buttonsCount: String = "7"
    )

    override val name: String = "CollectButtons"
    override val description: String = "Collects buttons from the frontmost application window " +
            "and returns JSON with buttons description and coordinates," +
            "e.g., [{\"x\": 100, \"y\": 200, \"name\": \"Button 1\"}, {\"x\": 300, \"y\": 400, \"name\": \"Button 2\"}]"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Какие кнопки на экране, перечисли несколько?",
            params = mapOf("buttonsCount" to "5")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "JSON array of buttons")
        )
    )

    override fun invoke(input: Input): String = runBlocking { suspendInvoke(input) }

    override suspend fun suspendInvoke(input: Input): String {
        scope.launch {
            playText("Щаа-щаа", 190)
        }
        l.info("Collecting buttons from the frontmost application window")
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
                    	
                        set text item delimiters to ", "
                        return "[" & (jsonButtons as text) & "]"
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

        val outButtons = objectMapper.readValue<List<OsxButton>>(result)
            .take(input.buttonsCount.toInt())
            .map { osxBtn ->
                val width = osxBtn.size[0]
                val height = osxBtn.size[1]
                val x = (osxBtn.position[0] + width / 2) * ImageUtils.DESKTOP_SCREENSHOT_QUALITY
                val y = (osxBtn.position[1] + height / 2) * ImageUtils.DESKTOP_SCREENSHOT_QUALITY
                OutButton(x.toInt(), y.toInt(), osxBtn.name)
            }
        return objectMapper.writeValueAsString(outButtons)
    }

    data class OsxButton(
        val buttonName: String,
        val role: String,
        val name: String,
        val description: String,
        val help: String?,
        val position: List<Int>,
        val size: List<Int>
    )

    data class OutButton(val x: Int, val y: Int, val name: String)
}

fun main() {
    val tool = ToolCollectButtons(ToolRunBashCommand)
    println(tool.invoke(ToolCollectButtons.Input("3")))
    println(tool.toGiga().fn)
}
