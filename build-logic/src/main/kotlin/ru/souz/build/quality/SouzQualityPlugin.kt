package ru.souz.build.quality

import dev.detekt.gradle.Detekt
import dev.detekt.gradle.DetektCreateBaselineTask
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.TaskProvider

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

        val policyFiles = project.fileTree(repository).apply {
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

        val report = project.tasks.register(
            "souzGateFastReport",
            SouzGateFastTask::class.java,
            Action { task ->
                task.repositoryDirectory.set(project.layout.projectDirectory)
                task.projectDescriptors.set(descriptors.map(ProjectDescriptor::encode))
                task.dependencyEdges.set(edgeInputs)
                task.policyFiles.from(policyFiles)
                task.detektReports.from(detektReportFiles)
                task.jsonReport.set(project.layout.buildDirectory.file("reports/souz-quality/fast/gate-summary-v1.json"))
                task.markdownReport.set(project.layout.buildDirectory.file("reports/souz-quality/fast/gate-summary.md"))
                task.doNotTrackState("The report records current Git identity and worktree state.")
            },
        )
        val gate = project.tasks.register(
            "souzGateFast",
            Action { task ->
                task.group = "verification"
                task.description = "Runs exact, local-safe Souz repository, module, and coroutine checks."
                task.finalizedBy(report)
            },
        )

        configureDetekt(project, gate, report, detektReportFiles)

        project.tasks.register(
            "souzDuplicationCheck",
            SouzDuplicationTask::class.java,
            Action { task ->
                task.group = "verification"
                task.description = "Checks production and test Kotlin duplication against the jscpd baseline."
                task.repositoryDirectory.set(project.layout.projectDirectory)
                task.projectDescriptors.set(descriptors.map(ProjectDescriptor::encode))
                task.configFile.set(project.layout.projectDirectory.file("quality/jscpd.json"))
                task.packageFile.set(project.layout.projectDirectory.file("quality/package.json"))
                task.baselineFile.set(project.layout.projectDirectory.file("quality/duplication-baseline.json"))
                task.jsonReport.set(project.layout.buildDirectory.file("reports/souz-quality/expensive/gate-summary-v1.json"))
                task.markdownReport.set(project.layout.buildDirectory.file("reports/souz-quality/expensive/gate-summary.md"))
                task.updateBaseline.set(false)
                task.doNotTrackState("The check records current Git identity and invokes the pinned jscpd binary.")
            },
        )
        project.tasks.register(
            "updateSouzDuplicationBaseline",
            SouzDuplicationTask::class.java,
            Action { task ->
                task.group = "verification"
                task.description = "Updates the reviewed production and test jscpd duplication baseline."
                task.repositoryDirectory.set(project.layout.projectDirectory)
                task.projectDescriptors.set(descriptors.map(ProjectDescriptor::encode))
                task.configFile.set(project.layout.projectDirectory.file("quality/jscpd.json"))
                task.packageFile.set(project.layout.projectDirectory.file("quality/package.json"))
                task.baselineFile.set(project.layout.projectDirectory.file("quality/duplication-baseline.json"))
                task.updateBaseline.set(true)
                task.doNotTrackState("Baseline updates are explicit reviewed mutations.")
            },
        )
    }

    private fun configureDetekt(
        project: Project,
        gate: TaskProvider<Task>,
        report: TaskProvider<SouzGateFastTask>,
        reportFiles: ConfigurableFileCollection,
    ) {
        val configFile = project.layout.projectDirectory.file("quality/detekt.yml")
        val baselineDirectory = project.layout.projectDirectory.dir("quality/detekt-baselines")
        val rulesJar = project.layout.projectDirectory.file(
            "build-logic/detekt-rules/build/libs/souz-detekt-rules.jar"
        )
        val updateBaseline = project.tasks.register(
            "updateSouzCoroutineBaseline",
            UpdateSouzCoroutineBaselineTask::class.java,
            Action { task ->
                task.group = "verification"
                task.description = "Updates the reviewed module- and analysis-scoped coroutine baselines."
                task.repositoryDirectory.set(project.layout.projectDirectory)
                task.baselines.set(baselineDirectory)
            },
        )

        project.subprojects.forEach { subproject ->
            var configured = false
            val configure = {
                if (!configured) {
                    configured = true
                    val rulesJarTask = project.gradle.includedBuild("build-logic").task(":detekt-rules:jar")
                    subproject.pluginManager.apply("dev.detekt")
                    subproject.dependencies.add("detektPlugins", subproject.files(rulesJar))
                    subproject.extensions.configure(
                        DetektExtension::class.java,
                        Action { extension ->
                            extension.toolVersion.set("2.0.0-alpha.6")
                            extension.config.setFrom(configFile)
                            extension.basePath.set(project.layout.projectDirectory)
                            extension.buildUponDefaultConfig.set(false)
                            extension.allRules.set(false)
                            extension.disableDefaultRuleSets.set(false)
                            extension.ignoreFailures.set(true)
                            extension.parallel.set(true)
                        },
                    )

                    val analysisTasks = subproject.tasks.withType(Detekt::class.java)
                        .matching { task -> task.name != "detekt" && !task.name.endsWith("SourceSet") }
                    analysisTasks.configureEach { task ->
                        task.dependsOn(rulesJarTask)
                        task.exclude("**/generated/resources/**")
                        val projectPath = subproject.projectDir.relativeInvariantPath(
                            project.layout.projectDirectory.asFile
                        )
                        val analysis = task.name.removePrefix("detekt").replaceFirstChar(Char::lowercase)
                        task.baseline.set(baselineDirectory.file("$projectPath/$analysis.xml"))
                        task.reports.checkstyle.required.set(true)
                        task.reports.html.required.set(false)
                        task.reports.markdown.required.set(false)
                        task.reports.sarif.required.set(false)
                        val checkstyleReport = task.reports.checkstyle.outputLocation
                        reportFiles.from(
                            project.provider {
                                if (task.source.isEmpty) emptyList() else listOf(checkstyleReport.get().asFile)
                            }
                        )
                        task.finalizedBy(report)
                    }
                    gate.configure { task -> task.dependsOn(analysisTasks) }

                    val baselineTasks = subproject.tasks.withType(DetektCreateBaselineTask::class.java)
                        .matching { task -> task.name != "detektBaseline" && !task.name.endsWith("SourceSet") }
                    baselineTasks.configureEach { task ->
                        task.dependsOn(rulesJarTask)
                        task.baseline.set(
                            subproject.layout.buildDirectory.file("reports/detekt/baselines/${task.name}.xml")
                        )
                        task.exclude("**/generated/resources/**")
                        val partialBaseline = task.baseline
                        task.doFirst { partialBaseline.get().asFile.delete() }
                        updateBaseline.configure { baselineTask -> baselineTask.partialBaselines.from(partialBaseline) }
                    }
                    updateBaseline.configure { task -> task.dependsOn(baselineTasks) }
                }
            }
            subproject.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { configure() }
            subproject.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") { configure() }
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
