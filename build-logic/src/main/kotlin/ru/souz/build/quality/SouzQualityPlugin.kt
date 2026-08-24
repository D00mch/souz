package ru.souz.build.quality

import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.ConfigurableFileCollection
import ru.souz.build.quality.detekt.SouzDetektRulesClasspathMarker
import java.io.File

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
        val detektReportFiles = project.objects.fileCollection()

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

        val report = project.tasks.register("souzGateFastReport", SouzGateFastTask::class.java) {
            repositoryDirectory.set(project.layout.projectDirectory)
            projectDescriptors.set(descriptors.map(ProjectDescriptor::encode))
            dependencyEdges.set(edgeInputs)
            this.policyFiles.from(policyFiles)
            detektReports.from(detektReportFiles)
            jsonReport.set(project.layout.buildDirectory.file("reports/souz-quality/fast/gate-summary-v1.json"))
            markdownReport.set(project.layout.buildDirectory.file("reports/souz-quality/fast/gate-summary.md"))
            doNotTrackState("The report records current Git identity and worktree state.")
        }
        val gate = project.tasks.register("souzGateFast") {
            group = "verification"
            description = "Runs exact, local-safe Souz repository, module, source-set, and coroutine checks."
            finalizedBy(report)
        }

        configureDetekt(project, gate, report, detektReportFiles)
    }

    private fun configureDetekt(
        project: Project,
        gate: org.gradle.api.tasks.TaskProvider<org.gradle.api.Task>,
        report: org.gradle.api.tasks.TaskProvider<SouzGateFastTask>,
        reportFiles: ConfigurableFileCollection,
    ) {
        val configFile = project.layout.projectDirectory.file("quality/detekt.yml")
        val rulesClasspath = detektRulesClasspath(project)

        project.subprojects.forEach { subproject ->
            var configured = false
            val configure = {
                if (!configured) {
                    configured = true
                    subproject.pluginManager.apply("dev.detekt")
                    subproject.dependencies.add("detektPlugins", subproject.files(rulesClasspath))
                    subproject.extensions.configure(DetektExtension::class.java) {
                        toolVersion.set("2.0.0-alpha.6")
                        config.setFrom(configFile)
                        basePath.set(project.layout.projectDirectory)
                        buildUponDefaultConfig.set(false)
                        allRules.set(false)
                        disableDefaultRuleSets.set(false)
                        ignoreFailures.set(true)
                        parallel.set(true)
                    }

                    val analysisTasks = subproject.tasks.withType(Detekt::class.java)
                        .matching { task -> task.name != "detekt" && !task.name.endsWith("SourceSet") }
                    analysisTasks.configureEach {
                        exclude("**/generated/resources/**")
                        reports.checkstyle.required.set(true)
                        reports.html.required.set(false)
                        reports.markdown.required.set(false)
                        reports.sarif.required.set(false)
                        val checkstyleReport = reports.checkstyle.outputLocation
                        reportFiles.from(
                            project.provider {
                                if (source.isEmpty) emptyList() else listOf(checkstyleReport.get().asFile)
                            }
                        )
                        finalizedBy(report)
                    }
                    gate.configure { dependsOn(analysisTasks) }
                }
            }
            subproject.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { configure() }
            subproject.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") { configure() }
        }
    }

    private fun detektRulesClasspath(project: Project): ConfigurableFileCollection {
        val markerClass = SouzDetektRulesClasspathMarker::class.java
        return project.files(File(markerClass.protectionDomain.codeSource.location.toURI()))
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
