package com.dumch.tool.desktop

import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.ToolSetup

class ToolOpenPhoto(private val bash: ToolRunBashCommand) : ToolSetup<ToolOpenPhoto.Input> {

    override val name: String = "OpenPhoto"
    override val description: String = "Opens Picture with specified name in selected folder"
    override fun invoke(input: Input): String {
        bash.invoke(
            ToolRunBashCommand.Input(
                """
                osascript <<EOF
                    tell application "Finder"
                        if (count of Finder windows) is 0 then
                            display dialog "Нет открытых окон Finder!" with title "Ошибка" buttons {"OK"} default button 1
                            return
                        end if
                        
                        set currentFolder to (target of front window) as alias
                        set folderPath to POSIX path of currentFolder
                        
                        set imageName to "${input.name}"
                        
                        set imageExtensions to {"jpg", "jpeg", "png", "gif", "heic", "webp", "tiff", "bmp"}
                        
                        set foundImage to missing value
                        
                        repeat with itemFile in (files of currentFolder) as list
                            set fileName to name of itemFile
                            set fileExtension to name extension of itemFile
                            
                            if (fileName contains imageName) and (fileExtension is in imageExtensions) then
                                set foundImage to itemFile
                                exit repeat
                            end if
                        end repeat
                        
                        if foundImage is not missing value then
                            tell application "Preview"
                                activate
                                open foundImage
                            end tell
                        else
                            display dialog "Изображение '" & imageName & "' не найдено в папке:" & return & folderPath with title "Ошибка" buttons {"OK"} default button 1
                        end if
                    end tell
                EOF
            """.trimIndent()
            )
        )
        return "Done"
    }

    class Input(
        @InputParamDescription("Picture name")
        val name: String
    )
}
