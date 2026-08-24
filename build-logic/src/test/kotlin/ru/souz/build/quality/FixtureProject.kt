package ru.souz.build.quality

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

internal class FixtureProject(private val root: Path) {
    private val modules = listOf(":graph-engine", ":llms", ":agent", ":sharedUI")
    private var needsKotlinPluginClasspath = false

    fun create() {
        write(
            "settings.gradle.kts",
            """
            rootProject.name = "quality-fixture"
            include(":graph-engine", ":llms", ":agent", ":sharedUI")
            """.trimIndent() + "\n",
        )
        write("build.gradle.kts", "plugins { id(\"souz.quality\") }\n")
        write(".gitignore", ".gradle/\nbuild/\n**/build/\n")
        write(
            "AGENTS.md",
            """
            # Fixture policy

            ## Module Map

            - `:graph-engine` — graph module.
            - `:llms` — contracts module.
            - `:agent` — agent module.
            - `:sharedUI` — shared UI module.
            """.trimIndent() + "\n",
        )
        write(
            "docs/pain-points.md",
            """
            # Pain points

            - [Graph](../graph-engine/docs/pain-points.md)
            - [LLMs](../llms/docs/pain-points.md)
            - [Agent](../agent/docs/pain-points.md)
            - [Shared UI](../sharedUI/docs/pain-points.md)
            """.trimIndent() + "\n",
        )

        modules.forEach { module ->
            val directory = module.removePrefix(":")
            write("$directory/AGENTS.md", "# $directory\n\n[Pain points](docs/pain-points.md)\n")
            write("$directory/docs/pain-points.md", "# Pain points\n")
            write("$directory/build.gradle.kts", "plugins { `java-library` }\n")
        }
        write(
            "agent/build.gradle.kts",
            """
            plugins { `java-library` }
            dependencies {
                implementation(project(":graph-engine"))
                implementation(project(":llms"))
            }
            """.trimIndent() + "\n",
        )
    }

    fun addSharedUiKmpBoundaryViolation(
        sourceDirectory: String = "src/commonJvmMain/kotlin",
        sourceSetName: String = "commonJvmMain",
    ) {
        needsKotlinPluginClasspath = true
        write(
            "build.gradle.kts",
            """
            plugins {
                id("souz.quality")
                id("org.jetbrains.kotlin.multiplatform") apply false
            }
            """.trimIndent() + "\n",
        )
        write(
            "sharedUI/build.gradle.kts",
            """
            plugins { id("org.jetbrains.kotlin.multiplatform") }

            repositories { mavenCentral() }

            kotlin {
                jvm()
                sourceSets {
                    val commonMain by getting
                    val boundarySource = maybeCreate("$sourceSetName").apply {
                        dependsOn(commonMain)
                        kotlin.srcDir("$sourceDirectory")
                    }
                    val jvmMain by getting {
                        dependsOn(boundarySource)
                    }
                }
            }
            """.trimIndent() + "\n",
        )
        write(
            "quality/detekt.yml",
            """
            config:
              validation: false

            souz-coroutines:
              active: false

            souz-architecture:
              active: true
              SourceSetBoundaries:
                active: true
            """.trimIndent() + "\n",
        )
        write(
            "sharedUI/$sourceDirectory/fixture/SharedUiState.kt",
            """
            package fixture

            import java.awt.Desktop

            class SharedUiState
            """.trimIndent() + "\n",
        )
    }

    fun append(path: String, content: String) {
        Files.writeString(root.resolve(path), content, java.nio.file.StandardOpenOption.APPEND)
    }

    fun writeBytes(path: String, content: ByteArray) {
        Files.write(root.resolve(path), content)
    }

    fun write(path: String, content: String) {
        val target = root.resolve(path)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
    }

    fun addFailingDetektAnalysis() {
        append("build.gradle.kts", "apply(from = \"failing-detekt-analysis.gradle\")\n")
        write(
            "failing-detekt-analysis.gradle",
            """
            def gate = tasks.named("souzGateFast")
            def report = tasks.named("souzGateFastReport")
            def analysis = tasks.register("failedDetektAnalysis") {
                finalizedBy(report)
                doLast { throw new GradleException("Detekt analysis crashed") }
            }
            gate.configure {
                dependsOn(analysis)
            }
            report.configure {
                detektReports.from(layout.buildDirectory.file("reports/detekt/missing.xml"))
            }
            """.trimIndent() + "\n",
        )
    }

    fun commit() {
        git("init", "-q")
        git("add", "--all")
        git("-c", "user.name=Souz Test", "-c", "user.email=souz-test@example.invalid", "commit", "-qm", "fixture")
    }

    fun build(vararg arguments: String): BuildResult = runner(arguments.toList()).build()

    fun buildAndFail(vararg arguments: String): BuildResult = runner(arguments.toList()).buildAndFail()

    fun reportPath(): Path = root.resolve("build/reports/souz-quality/fast/gate-summary-v1.json")

    private fun runner(arguments: List<String>): GradleRunner {
        val runner = GradleRunner.create()
            .withProjectDir(root.toFile())
            .withArguments(arguments + listOf("--console=plain", "--stacktrace"))
        return if (needsKotlinPluginClasspath) {
            runner.withPluginClasspath(pluginUnderTestClasspath() + kotlinPluginClasspath())
        } else {
            runner.withPluginClasspath()
        }
    }

    private fun pluginUnderTestClasspath(): List<File> {
        val properties = Properties().apply {
            val metadata = checkNotNull(
                FixtureProject::class.java.classLoader.getResourceAsStream(PLUGIN_METADATA)
            ) {
                "Missing $PLUGIN_METADATA on the test runtime classpath."
            }
            metadata.use { load(it) }
        }
        return properties.getProperty("implementation-classpath")
            .split(File.pathSeparator)
            .map(::File)
    }

    private fun kotlinPluginClasspath(): List<File> =
        checkNotNull(System.getProperty("souz.test.kotlin-plugin-classpath")) {
            "Missing Kotlin plugin classpath for the KMP functional fixture."
        }.split(File.pathSeparator).map(::File)

    private fun git(vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
    }
}

private const val PLUGIN_METADATA = "plugin-under-test-metadata.properties"
