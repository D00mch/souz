package ru.souz.tool.files

import ru.souz.tool.*
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import java.io.File

class ToolModifyFile(
    private val filesToolUtil: FilesToolUtil,
    private val permissionBroker: ToolPermissionBroker? = null,
) : ToolSetup<ToolModifyFile.Input> {

    data class Input(
        @InputParamDescription("The path to the file to be modified")
        val path: String,

        @InputParamDescription(
            "Unified diff patch to apply (like git diff output). " +
                    "Must modify ONLY the target file. Use a/<filename> and b/<filename> or just <filename>."
        )
        val patch: String,

        @InputParamDescription("How many leading path components to strip from patch paths. Use 1 for a/ and b/.")
        val strip: Int = 1,
    )

    override val name = "EditFile"
    override val description =
        "Modify a file by applying a unified diff patch. Runs a dry-run first; applies only if clean. Patch must target only the specified file."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Применить патч к ~/notes.txt",
            params = mapOf(
                "path" to "~/notes.txt",
                "patch" to """
                    --- a/notes.txt
                    +++ b/notes.txt
                    @@ -1,3 +1,4 @@
                     one
                    -two
                    +TWO
                     three
                    +four
                """.trimIndent(),
                "strip" to 1
            )
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status")
        )
    )

    override suspend fun suspendInvoke(input: Input): String {
        val fixedPath = filesToolUtil.applyDefaultEnvs(input.path)
        validateInput(input, fixedPath)
        val result = permissionBroker?.requestPermission(
            getString(Res.string.permission_modify_file),
            linkedMapOf(
                "path" to fixedPath,
                "strip" to input.strip.toString(),
                "patch" to input.patch,
            )
        )
        if (result is ToolPermissionResult.No) return result.msg
        return invoke(input)
    }

    override fun invoke(input: Input): String {
        val fixedPath = filesToolUtil.applyDefaultEnvs(input.path)
        val file = validateInput(input, fixedPath)
        val patchForCommand = normalizePatchForPatchCommand(input.patch, file)

        val workDir = file.parentFile ?: throw BadInputException("File has no parent directory")
        // Dry run first
        val dry = runPatch(
            workDir = workDir,
            strip = input.strip,
            patchText = patchForCommand,
            dryRun = true
        )
        if (dry.exitCode != 0) {
            throw BadInputException(
                "Patch dry-run failed:\n${dry.output}\n" +
                        "Hint: generate an exact unified diff for current file content. " +
                        "Do not delete and recreate the file; use EditFile to update it."
            )
        }

        // Apply for real
        val applied = runPatch(
            workDir = workDir,
            strip = input.strip,
            patchText = patchForCommand,
            dryRun = false
        )
        if (applied.exitCode != 0) {
            throw BadInputException(
                "Patch apply failed:\n${applied.output}\n" +
                        "Do not delete and recreate the file; use EditFile with a valid patch."
            )
        }

        return "OK"
    }

    private data class CmdResult(val exitCode: Int, val output: String)

    private fun runPatch(workDir: File, strip: Int, patchText: String, dryRun: Boolean): CmdResult {
        // --batch: never prompt
        // --forward: ignore already-applied hunks instead of reversing
        val args = buildList {
            add("patch")
            add("--batch")
            add("--forward")
            if (dryRun) add("--dry-run")
            add("-p$strip")
        }

        val pb = ProcessBuilder(args)
            .directory(workDir)
            .redirectErrorStream(true)

        val p = pb.start()
        p.outputStream.bufferedWriter().use { writer ->
            writer.write(patchText)
            if (!patchText.endsWith("\n")) writer.newLine()
        }
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val code = p.waitFor()
        return CmdResult(code, out.trim())
    }

    private fun normalizePatchForPatchCommand(patchText: String, targetFile: File): String {
        val targetCanonical = targetFile.canonicalFile
        val normalized = patchText.lineSequence().joinToString("\n") { line ->
            normalizePatchHeaderPath(line, targetCanonical)
        }
        return if (patchText.endsWith("\n")) "$normalized\n" else normalized
    }

    private fun normalizePatchHeaderPath(line: String, targetCanonical: File): String {
        val prefix = when {
            line.startsWith("--- ") -> "--- "
            line.startsWith("+++ ") -> "+++ "
            else -> return line
        }

        val rest = line.removePrefix(prefix)
        val tabIndex = rest.indexOf('\t')
        val pathPart = if (tabIndex >= 0) rest.substring(0, tabIndex) else rest
        val suffix = if (tabIndex >= 0) rest.substring(tabIndex) else ""
        val trimmedPath = pathPart.trim()
        if (trimmedPath == "/dev/null") return line
        if (!isAbsolutePath(trimmedPath)) return line

        val canonicalMatchesTarget = runCatching {
            File(trimmedPath).canonicalFile == targetCanonical
        }.getOrDefault(false)
        if (!canonicalMatchesTarget) return line

        return "$prefix${targetCanonical.name}$suffix"
    }

    private fun isAbsolutePath(path: String): Boolean =
        path.startsWith("/") || WINDOWS_ABSOLUTE_PATH.matches(path)

    private fun validateInput(input: Input, fixedPath: String): File {
        val file = File(fixedPath)

        if (input.path.isBlank() || input.patch.isBlank()) throw BadInputException("Invalid input parameters")
        if (!filesToolUtil.isPathSafe(file)) throw ForbiddenFolder(fixedPath)
        if (!file.exists()) throw BadInputException("File does not exist")
        if (input.strip !in 0..10) throw BadInputException("Invalid strip value")

        // Guardrail: patch must only target the given file.
        filesToolUtil.validateUnifiedDiffTargetsSingleFile(
            patch = input.patch,
            expectedFilePath = fixedPath,
            strip = input.strip
        )
        return file
    }

    companion object {
        private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[\\\\/].*")
    }
}
