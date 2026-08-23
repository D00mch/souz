package ru.souz.build.quality

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class SouzGateFastTask : DefaultTask() {
    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @get:Input
    abstract val projectDescriptors: ListProperty<String>

    @get:Input
    abstract val dependencyEdges: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val policyFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val detektReports: ConfigurableFileCollection

    @get:OutputFile
    abstract val jsonReport: RegularFileProperty

    @get:OutputFile
    abstract val markdownReport: RegularFileProperty

    @TaskAction
    fun verify() {
        val repository = repositoryDirectory.get().asFile
        var metadataError: Exception? = null
        val identity = try {
            GitMetadata.read(repository)
        } catch (exception: Exception) {
            metadataError = exception
            GitIdentity(null, null, null, null)
        }
        val projects = projectDescriptors.get().map(ProjectDescriptor::decode)
        val edges = dependencyEdges.get().map(DependencyEdge::decode)
        var detektError: Exception? = null
        val detektFindings = try {
            DetektReports.read(repository, detektReports.files)
        } catch (exception: Exception) {
            detektError = exception
            emptyList()
        }

        fun runCheck(
            definition: QualityCheckDefinition,
            check: () -> List<QualityDiagnostic>,
        ): QualityCheckResult = QualityCheckRunner.run(definition, repository, identity, check)

        fun detektDiagnostics(predicate: (String) -> Boolean): List<QualityDiagnostic> {
            detektError?.let { throw it }
            return detektFindings.filter { predicate(it.ruleId) }.map(DetektFinding::diagnostic)
        }

        val results = listOf(
            runCheck(SouzQualityChecks.gitMetadata) {
                metadataError?.let { throw it }
                emptyList()
            },
            runCheck(SouzQualityChecks.repositoryContracts) {
                RepositoryContracts.check(
                    repositoryDirectory = repository,
                    projects = projects,
                    policyFiles = policyFiles.files,
                    registeredChecks = SouzQualityChecks.fast,
                )
            },
            runCheck(SouzQualityChecks.moduleBoundaries) {
                ModuleBoundaries.check(
                    projects = projects,
                    edges = edges,
                )
            },
            runCheck(SouzQualityChecks.cancellationPropagation) {
                detektDiagnostics { it in CANCELLATION_RULES }
            },
            runCheck(SouzQualityChecks.coroutineThreadLocal) {
                detektDiagnostics { it in THREAD_LOCAL_RULES }
            },
            runCheck(SouzQualityChecks.coroutineMonitorUse) {
                detektDiagnostics { it in MONITOR_RULES }
            },
        )

        val status = QualityReport.write(
            results = results,
            repositoryDirectory = repository,
            jsonFile = jsonReport.get().asFile,
            markdownFile = markdownReport.get().asFile,
        )
        logger.lifecycle(
            "Souz fast quality gate: {} ({})",
            status.wireName,
            jsonReport.get().asFile.relativeInvariantPath(repository),
        )

        if (status == QualityStatus.FAIL || status == QualityStatus.ERROR) {
            throw GradleException(
                "Souz fast quality gate ${status.wireName}. See " +
                    jsonReport.get().asFile.relativeInvariantPath(repository)
            )
        }
    }
}
