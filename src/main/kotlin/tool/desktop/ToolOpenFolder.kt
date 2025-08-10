package com.dumch.tool.desktop

import com.dumch.tool.InputParamDescription
import com.dumch.tool.ToolRunBashCommand
import com.dumch.tool.ToolSetup
import com.dumch.tool.files.ToolListFiles
import org.slf4j.LoggerFactory

class ToolOpenFolder(private val bash: ToolRunBashCommand) : ToolSetup<ToolOpenFolder.Input> {
    private val l = LoggerFactory.getLogger(ToolOpenFolder::class.java)

    override val name: String = "OpenFolder"
    override val description: String = "Opens Folder by its name, returns the path and the list of files inside"

    override fun invoke(input: Input): String {
        l.info("Opening folder '${input.name}'")
        val script =
            """
osascript <<EOF
set folderName to "${input.name}"
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
EOF
            """.trimIndent()

        val path = bash.script(script)
        if (path.isBlank()) return path

        val files = ToolListFiles(ToolListFiles.Input(path))
        return """{"path":"$path","files":$files}"""
    }

    class Input(
        @InputParamDescription("Folder name")
        val name: String
    )
}

fun main() {
    val v = ToolOpenFolder(ToolRunBashCommand)(ToolOpenFolder.Input("Pictures"))
    println(v)
}