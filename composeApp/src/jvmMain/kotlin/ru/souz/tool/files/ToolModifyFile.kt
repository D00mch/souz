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
        val result = permissionBroker?.requestPermission(
            getString(Res.string.permission_modify_file),
            linkedMapOf("path" to fixedPath)
        )
        if (result is ToolPermissionResult.No) return result.msg
        return invoke(input)
    }

    override fun invoke(input: Input): String {
        val fixedPath = filesToolUtil.applyDefaultEnvs(input.path)
        val file = File(fixedPath)

        if (!filesToolUtil.isPathSafe(file)) throw ForbiddenFolder(fixedPath)
        if (input.path.isBlank() || input.patch.isBlank()) throw BadInputException("Invalid input parameters")
        if (!file.exists()) throw BadInputException("File does not exist")
        if (input.strip !in 0..10) throw BadInputException("Invalid strip value")

        // Guardrail: patch must only target the given file (by filename)
        filesToolUtil.validateUnifiedDiffTargetsSingleFile(
            patch = input.patch,
            expectedFileName = file.name,
            strip = input.strip
        )

        val workDir = file.parentFile ?: throw BadInputException("File has no parent directory")
        val patchFile = kotlin.io.path.createTempFile(prefix = "llm_patch_", suffix = ".patch").toFile()
        patchFile.writeText(input.patch)

        try {
            // Dry run first
            val dry = runPatch(
                workDir = workDir,
                strip = input.strip,
                patchPath = patchFile.absolutePath,
                dryRun = true
            )
            if (dry.exitCode != 0) {
                throw BadInputException("Patch dry-run failed:\n${dry.output}")
            }

            // Apply for real
            val applied = runPatch(
                workDir = workDir,
                strip = input.strip,
                patchPath = patchFile.absolutePath,
                dryRun = false
            )
            if (applied.exitCode != 0) {
                throw BadInputException("Patch apply failed:\n${applied.output}")
            }

            return "OK"
        } finally {
            patchFile.delete()
        }
    }

    private data class CmdResult(val exitCode: Int, val output: String)

    private fun runPatch(workDir: File, strip: Int, patchPath: String, dryRun: Boolean): CmdResult {
        // --batch: never prompt
        // --forward: ignore already-applied hunks instead of reversing
        val args = buildList {
            add("patch")
            add("--batch")
            add("--forward")
            if (dryRun) add("--dry-run")
            add("-p$strip")
            add("-i")
            add(patchPath)
        }

        val pb = ProcessBuilder(args)
            .directory(workDir)
            .redirectErrorStream(true)

        val p = pb.start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val code = p.waitFor()
        return CmdResult(code, out.trim())
    }

}
