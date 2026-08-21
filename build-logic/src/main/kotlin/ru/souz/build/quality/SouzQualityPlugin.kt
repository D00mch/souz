package ru.souz.build.quality

import dev.detekt.gradle.Detekt
import dev.detekt.gradle.DetektCreateBaselineTask
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.ConfigurableFileCollection

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

        val gate = project.tasks.register("souzGateFast", SouzGateFastTask::class.java) {
            group = "verification"
            description = "Runs exact, local-safe Souz repository, module, and coroutine checks."
            repositoryDirectory.set(project.layout.projectDirectory)
            projectDescriptors.set(descriptors.map(ProjectDescriptor::encode))
            dependencyEdges.set(edgeInputs)
            this.policyFiles.from(policyFiles)
            detektReports.from(detektReportFiles)
            jsonReport.set(project.layout.buildDirectory.file("reports/souz-quality/fast/gate-summary-v1.json"))
            markdownReport.set(project.layout.buildDirectory.file("reports/souz-quality/fast/gate-summary.md"))
            doNotTrackState("The report records current Git identity and worktree state.")
        }

        configureDetekt(project, gate, detektReportFiles)
    }

    private fun configureDetekt(
        project: Project,
        gate: org.gradle.api.tasks.TaskProvider<SouzGateFastTask>,
        reportFiles: ConfigurableFileCollection,
    ) {
        val configFile = project.layout.projectDirectory.file("config/quality/detekt.yml")
        val baselineFile = project.layout.projectDirectory.file("config/quality/detekt-baseline.xml")
        val rulesJar = project.layout.projectDirectory.file(
            "build-logic/detekt-rules/build/libs/souz-detekt-rules.jar"
        )
        val updateBaseline = project.tasks.register(
            "updateSouzCoroutineBaseline",
            UpdateSouzCoroutineBaselineTask::class.java,
        ) {
            group = "verification"
            description = "Replaces the reviewed coroutine baseline with findings from the current checkout."
            baseline.set(baselineFile)
        }

        project.subprojects.forEach { subproject ->
            var configured = false
            val configure = {
                if (!configured) {
                    configured = true
                    subproject.pluginManager.apply("dev.detekt")
                    subproject.dependencies.add("detektPlugins", subproject.files(rulesJar))
                    subproject.extensions.configure(DetektExtension::class.java) {
                        toolVersion.set("2.0.0-alpha.6")
                        config.setFrom(configFile)
                        baseline.set(baselineFile)
                        basePath.set(project.layout.projectDirectory)
                        buildUponDefaultConfig.set(false)
                        allRules.set(false)
                        disableDefaultRuleSets.set(false)
                        ignoreFailures.set(true)
                        parallel.set(true)
                    }
                }
            }
            subproject.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { configure() }
            subproject.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") { configure() }
        }

        project.gradle.projectsEvaluated {
            val analysisTasks = project.subprojects.flatMap { subproject ->
                subproject.tasks.withType(Detekt::class.java)
                    .matching { task -> task.name != "detekt" && !task.name.endsWith("SourceSet") }
                    .toList()
            }
            val baselineTasks = project.subprojects.flatMap { subproject ->
                subproject.tasks.withType(DetektCreateBaselineTask::class.java)
                    .matching { task -> task.name != "detektBaseline" && !task.name.endsWith("SourceSet") }
                    .toList()
                    .onEach { task ->
                        task.baseline.set(
                            subproject.layout.buildDirectory.file("reports/detekt/baselines/${task.name}.xml")
                        )
                        task.exclude("**/generated/resources/**")
                        task.doFirst {
                            task.baseline.get().asFile.delete()
                        }
                    }
            }
            val rulesJarTask = if (analysisTasks.isEmpty() && baselineTasks.isEmpty()) {
                null
            } else {
                project.gradle.includedBuild("build-logic").task(":detekt-rules:jar")
            }
            analysisTasks.forEach { task ->
                if (rulesJarTask != null) task.dependsOn(rulesJarTask)
                task.exclude("**/generated/resources/**")
                task.baseline.set(baselineFile)
                task.reports.checkstyle.required.set(true)
                task.reports.html.required.set(false)
                task.reports.markdown.required.set(false)
                task.reports.sarif.required.set(false)
                reportFiles.from(task.reports.checkstyle.outputLocation)
            }
            baselineTasks.forEach { task ->
                if (rulesJarTask != null) task.dependsOn(rulesJarTask)
            }
            updateBaseline.configure {
                partialBaselines.from(baselineTasks.map { it.baseline })
                dependsOn(baselineTasks)
            }
            gate.configure { dependsOn(analysisTasks) }
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
