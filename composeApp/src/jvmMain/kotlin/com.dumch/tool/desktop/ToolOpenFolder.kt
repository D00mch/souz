package com.dumch.tool.desktop

import com.dumch.tool.*
import com.dumch.tool.files.ToolListFiles
import org.slf4j.LoggerFactory

class ToolOpenFolder(private val bash: ToolRunBashCommand) : ToolSetup<ToolOpenFolder.Input> {
    private val l = LoggerFactory.getLogger(ToolOpenFolder::class.java)

    override val name: String = "OpenFolder"
    override val description: String = "Opens Folder by its name, returns the path and the list of files inside"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Открой загрузки",
            params = mapOf("name" to "Downloads")
        ),
        FewShotExample(
            request = "Открой папку 'Семья'",
            params = mapOf("name" to "Семья")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "JSON string with path and files")
        )
    )

    data class Input(
        @InputParamDescription("Folder name")
        val name: String
    )

    override fun invoke(input: Input): String {
        l.info("Opening folder '${input.name}'")
        val script = buildScript(input)

        val path = bash.apple(script)
        if (path.isBlank()) return "Can't find path of such folder: ${input.name}"

        val files = ToolListFiles(ToolListFiles.Input(path))
        val result = """{"path":"$path","files":$files}"""
        l.info("Result is $result")
        return result
    }

    private fun buildScript(input: Input): String = """
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
    try
        if folderName is "" then error "Empty name"
    
        set q1 to "kMDItemFSName == " & quoted form of folderName & " && kMDItemContentType == 'public.folder'"
        set r to do shell script ("mdfind " & quoted form of q1)
    
        if r is "" then
            set q2 to "kMDItemDisplayName == " & quoted form of folderName & " && kMDItemContentType == 'public.folder'"
            set r to do shell script ("mdfind " & quoted form of q2)
        end if
        if r is "" then error "Not found"
    
        set paths to paragraphs of r
        if (count of paths) = 1 then
            return item 1 of paths
        else
            set choice to choose from list paths with title "Folders found" with prompt "Choose:" OK button name "OK" cancel button name "Cancel"
            if choice is false then error "Canceled"
            return item 1 of choice
        end if
    on error err
        do shell script "echo " & quoted form of ("ERROR: " & err) & " 1>&2"
        return ""
    end try
                """.trimIndent()
}

fun main() {
    val v = ToolOpenFolder(ToolRunBashCommand)(ToolOpenFolder.Input("семья"))
    println(v)
}