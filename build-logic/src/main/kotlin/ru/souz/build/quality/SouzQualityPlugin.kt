package ru.souz.build.quality

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

class SouzQualityPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        require(project == project.rootProject) { "The souz.quality plugin must be applied to the root project." }

        val repository = project.layout.projectDirectory.asFile
        val descriptors = project.subprojects.map { subproject ->
            ProjectDescriptor(
                path = subproject.path,
                directory = subproject.projectDir.relativeInvariantPath(repository),
                buildFile = subproject.buildFile.relativeInvariantPath(repository),
            )
        }.sortedBy(ProjectDescriptor::path)
        val edgeInputs = project.objects.listProperty(String::class.java).convention(emptyList())

        project.gradle.projectsEvaluated {
            edgeInputs.set(
                project.subprojects
                    .flatMap { dependencyEdges(it, repository) }
                    .map(DependencyEdge::encode)
                    .sorted()
            )
            edgeInputs.finalizeValue()
        }

        val policyFiles = project.fileTree(repository) {
            include("**/AGENTS.md")
            include("docs/pain-points.md")
            include("**/docs/pain-points.md")
            include("**/docs/pain-points/**/*.md")
            exclude(".git/**")
            exclude(".gradle/**")
            exclude(".claude/**")
            exclude("**/build/**")
            exclude("**/.gradle/**")
            exclude("third_party/**")
            exclude("native/third_party/**")
        }

        project.tasks.register("souzGateFast", SouzGateFastTask::class.java) {
            group = "verification"
            description = "Runs exact, local-safe Souz repository and module-boundary checks."
            repositoryDirectory.set(project.layout.projectDirectory)
            projectDescriptors.set(descriptors.map(ProjectDescriptor::encode))
            dependencyEdges.set(edgeInputs)
            this.policyFiles.from(policyFiles)
            jsonReport.set(project.layout.buildDirectory.file("reports/souz-quality/fast/gate-summary-v1.json"))
            markdownReport.set(project.layout.buildDirectory.file("reports/souz-quality/fast/gate-summary.md"))
            doNotTrackState("The report records current Git identity and worktree state.")
        }
    }

    private fun dependencyEdges(sourceProject: Project, repository: java.io.File): List<DependencyEdge> {
        val productionConfigurations = sourceProject.configurations.mapNotNull { configuration ->
            dependencySourceSet(configuration.name)
                ?.takeUnless { it == UNCLASSIFIED_SOURCE_SET }
                ?.let { sourceSet -> configuration to sourceSet }
        }

        return sourceProject.configurations.flatMap { configuration ->
            val declaredSourceSet = dependencySourceSet(configuration.name)
            val effectiveSourceSets = when (declaredSourceSet) {
                null -> productionConfigurations
                    .filter { (productionConfiguration, _) -> configuration in productionConfiguration.hierarchy }
                    .map { (_, sourceSet) -> sourceSet }
                    .distinct()

                else -> listOf(declaredSourceSet)
            }

            configuration.dependencies.withType(ProjectDependency::class.java).flatMap { dependency ->
                effectiveSourceSets.map { sourceSet ->
                    DependencyEdge(
                        source = sourceProject.path,
                        configuration = configuration.name,
                        sourceSet = sourceSet,
                        target = dependency.path,
                        buildFile = sourceProject.buildFile.relativeInvariantPath(repository),
                    )
                }
            }
        }.distinct()
    }
}
