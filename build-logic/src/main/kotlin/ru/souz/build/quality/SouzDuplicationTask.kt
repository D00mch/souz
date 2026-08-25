package ru.souz.build.quality

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject
import kotlin.time.TimeSource

abstract class SouzDuplicationTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @get:Input
    abstract val projectDescriptors: ListProperty<String>

    @get:Internal
    abstract val configFile: RegularFileProperty

    @get:Internal
    abstract val packageFile: RegularFileProperty

    @get:Internal
    abstract val baselineFile: RegularFileProperty

    @get:Internal
    abstract val jsonReport: RegularFileProperty

    @get:Internal
    abstract val markdownReport: RegularFileProperty

    @get:Input
    abstract val updateBaseline: Property<Boolean>

    @TaskAction
    fun run() {
        val repository = repositoryDirectory.get().asFile
        val duplicationStarted = TimeSource.Monotonic.markNow()
        val duplicationRun = runCatching {
            DuplicationRun(
                measurements = measure(repository),
                jscpdVersion = DuplicationRatchet.jscpdVersion(packageFile.get().asFile),
                configurationSha256 = DuplicationRatchet.sha256(configFile.get().asFile),
            )
        }
        val duplicationDurationMs = duplicationStarted.elapsedNow().inWholeMilliseconds.coerceAtLeast(0)

        if (updateBaseline.get()) {
            val run = duplicationRun.getOrThrow()
            DuplicationRatchet.writeBaseline(
                file = baselineFile.get().asFile,
                jscpdVersion = run.jscpdVersion,
                configurationSha256 = run.configurationSha256,
                measurements = run.measurements,
            )
            logger.lifecycle(
                "Updated Souz duplication baseline: {}",
                baselineFile.get().asFile.relativeInvariantPath(repository),
            )
            return
        }

        val gitIdentity = runCatching { GitMetadata.read(repository) }
        val identity = gitIdentity.getOrElse { GitIdentity(null, null, null, null) }
        val results = listOf(
            exactCheckoutResult(repository, identity, gitIdentity.exceptionOrNull()),
            duplicationResult(repository, identity, duplicationRun, duplicationDurationMs),
        )
        val status = QualityReport.write(
            results = results,
            repositoryDirectory = repository,
            jsonFile = jsonReport.get().asFile,
            markdownFile = markdownReport.get().asFile,
        )
        logger.lifecycle(
            "Souz duplication gate: {} ({})",
            status.wireName,
            jsonReport.get().asFile.relativeInvariantPath(repository),
        )
        if (status == QualityStatus.FAIL || status == QualityStatus.ERROR) {
            throw GradleException(
                "Souz duplication gate ${status.wireName}. See " +
                    jsonReport.get().asFile.relativeInvariantPath(repository)
            )
        }
    }

    private fun measure(repository: File): Map<DuplicationScope, DuplicationMeasurement> {
        val sourceRoots = sourceRoots(repository)
        return DuplicationScope.entries.associateWith { scope ->
            val roots = sourceRoots.getValue(scope)
            require(roots.isNotEmpty()) { "No ${scope.wireName} Kotlin source roots were found." }
            val output = temporaryDir.resolve(scope.wireName)
            output.mkdirs()
            val binary = jscpdBinary(packageFile.get().asFile.parentFile)
            require(binary.isFile) {
                "Pinned jscpd is not installed; run 'npm ci --prefix quality' from the repository root."
            }
            val threshold = DuplicationRatchet.thresholds.getValue(scope)
            execOperations.exec { spec ->
                spec.workingDir(repository)
                spec.executable(binary)
                spec.args(roots.map { it.relativeInvariantPath(repository) })
                spec.args(
                    "--config", configFile.get().asFile.relativeInvariantPath(repository),
                    "--min-lines", threshold.minLines.toString(),
                    "--min-tokens", threshold.minTokens.toString(),
                    "--output", output.absolutePath,
                    "--silent",
                    "--no-colors",
                    "--no-tips",
                )
            }.assertNormalExitValue()
            DuplicationRatchet.readMeasurement(output.resolve("jscpd-report.json"))
        }
    }

    private fun sourceRoots(repository: File): Map<DuplicationScope, List<File>> {
        val roots = projectDescriptors.get().map(ProjectDescriptor::decode).flatMap { descriptor ->
            repository.resolve(descriptor.directory).resolve("src").listFiles()
                .orEmpty()
                .filter(File::isDirectory)
                .mapNotNull { sourceSet -> sourceSet.resolve("kotlin").takeIf(File::isDirectory) }
                .map { sourceRoot -> sourceRoot to sourceSetScope(sourceRoot.parentFile.name) }
        }
        return DuplicationScope.entries.associateWith { scope ->
            roots.filter { (_, rootScope) -> rootScope == scope }.map(Pair<File, DuplicationScope>::first).sorted()
        }
    }

    private fun sourceSetScope(name: String): DuplicationScope =
        if (name.endsWith("Test", ignoreCase = true) || name.equals("test", ignoreCase = true)) {
            DuplicationScope.TESTS
        } else {
            DuplicationScope.PRODUCTION
        }

    private fun exactCheckoutResult(
        repository: File,
        identity: GitIdentity,
        metadataError: Throwable?,
    ): QualityCheckResult {
        val definition = SouzQualityChecks.ciExactCheckout
        if (metadataError != null) {
            return QualityCheckRunner.run(definition, repository, identity) {
                throw metadataError.asException()
            }
        }
        if (System.getenv("GITHUB_ACTIONS") != "true") {
            return QualityCheckResult(
                definition = definition,
                status = QualityStatus.NOT_AUTHORITATIVE,
                durationMs = 0,
                diagnostics = listOf(
                    QualityDiagnostic(null, null, "Exact-checkout authority is available only in GitHub Actions.")
                ),
                gitIdentity = identity,
            )
        }
        return QualityCheckRunner.run(definition, repository, identity) {
            val diagnostics = mutableListOf<QualityDiagnostic>()
            if (identity.dirtyWorktree != false) {
                diagnostics += QualityDiagnostic(null, null, "GitHub Actions checkout must have a clean worktree.")
            }
            val githubSha = System.getenv("GITHUB_SHA")?.trim().orEmpty()
            if (githubSha.isBlank() || githubSha != identity.testedCommitSha) {
                diagnostics += QualityDiagnostic(
                    null,
                    null,
                    "GITHUB_SHA must match the tested checkout HEAD.",
                )
            }
            diagnostics
        }
    }

    private fun duplicationResult(
        repository: File,
        identity: GitIdentity,
        duplicationRun: Result<DuplicationRun>,
        durationMs: Long,
    ): QualityCheckResult = try {
        val run = duplicationRun.getOrThrow()
        val evaluation = DuplicationRatchet.evaluate(
            baselineFile = baselineFile.get().asFile,
            baselinePath = baselineFile.get().asFile.relativeInvariantPath(repository),
            expectedJscpdVersion = run.jscpdVersion,
            expectedConfigurationSha256 = run.configurationSha256,
            measurements = run.measurements,
        )
        QualityCheckResult(
            definition = SouzQualityChecks.duplicateCode,
            status = if (evaluation.failed) QualityStatus.FAIL else QualityStatus.PASS,
            durationMs = durationMs,
            diagnostics = evaluation.diagnostics,
            gitIdentity = identity,
        )
    } catch (exception: Exception) {
        QualityCheckRunner.run(SouzQualityChecks.duplicateCode, repository, identity) { throw exception }
            .copy(durationMs = durationMs)
    }

    private fun jscpdBinary(toolDirectory: File): File = toolDirectory.resolve(
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "node_modules/.bin/jscpd.cmd"
        } else {
            "node_modules/.bin/jscpd"
        }
    )

    private fun Throwable.asException(): Exception = this as? Exception ?: RuntimeException(this)

    private data class DuplicationRun(
        val measurements: Map<DuplicationScope, DuplicationMeasurement>,
        val jscpdVersion: String,
        val configurationSha256: String,
    )
}
