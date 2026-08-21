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

        val results = listOf(
            QualityCheckRunner.run(
                definition = SouzQualityChecks.repositoryContracts,
                repositoryDirectory = repository,
                gitIdentity = identity,
                metadataError = metadataError,
            ) {
                RepositoryContracts.check(
                    repositoryDirectory = repository,
                    projects = projectDescriptors.get().map(ProjectDescriptor::decode),
                    policyFiles = policyFiles.files,
                    registeredChecks = SouzQualityChecks.fast,
                )
            },
            QualityCheckRunner.run(
                definition = SouzQualityChecks.moduleBoundaries,
                repositoryDirectory = repository,
                gitIdentity = identity,
                metadataError = metadataError,
            ) {
                ModuleBoundaries.check(
                    projects = projectDescriptors.get().map(ProjectDescriptor::decode),
                    edges = dependencyEdges.get().map(DependencyEdge::decode),
                )
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
