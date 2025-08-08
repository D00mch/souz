package com.dumch.tool.desktop

import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.ToolSetup

class ToolOpenFolder(private val bash: ToolRunBashCommand) : ToolSetup<ToolOpenFolder.Input> {

    override val name: String = "OpenFolder"
    override val description: String = "Opens Folder by its name"
    override fun invoke(input: Input): String {
        bash.invoke(
            ToolRunBashCommand.Input(
                """
                osascript <<EOF
                    set folderName to "${input.name}"

                    try
                        set sanitizedName to quoted form of folderName
                        set searchCmd to "mdfind \"kMDItemKind == 'Folder' && kMDItemDisplayName == " & sanitizedName & "\""
                        
                        set searchResults to do shell script searchCmd
                        if searchResults is "" then
                            set searchCmd to "mdfind \"kind:folder " & sanitizedName & "\""
                            set searchResults to do shell script searchCmd
                        end if
                        
                        if searchResults is "" then
                            set searchCmd to "find ~ /Volumes -type d -name " & sanitizedName & " -maxdepth 5 2>/dev/null | head -n 20"
                            set searchResults to do shell script searchCmd
                        end if
                        
                        if searchResults is "" then
                            error "Папка не найдена"
                        end if
                        
                        set foundPaths to paragraphs of searchResults
                        
                        if (count of foundPaths) is 1 then
                            openInFinder(first item of foundPaths)
                        else
                            set selectedPath to choose from list foundPaths with title "Найдено несколько папок" with prompt "Выберите папку '" & folderName & "':" OK button name "Открыть"
                            if selectedPath is not false then
                                openInFinder(first item of selectedPath)
                            end if
                        end if
                        
                    on error errMsg
                        display alert "Ошибка" message errMsg as critical buttons {"OK"}
                    end try
                    
                    on openInFinder(posixPath)
                        tell application "Finder"
                            activate
                            reveal (POSIX file posixPath as alias)
                            open (POSIX file posixPath as alias)
                        end tell
                    end openInFinder
                EOF
            """.trimIndent()
            )
        )
        return "Done"
    }

    class Input(
        @InputParamDescription("Folder name")
        val name: String
    )
}
