package ru.souz.build.quality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SouzQualityPluginFunctionalTest {
    private val mapper = ObjectMapper()

    @Test
    fun `passing fixture writes a passing report`(@TempDir root: Path) {
        val fixture = FixtureProject(root).apply {
            create()
            commit()
        }

        fixture.build("souzGateFast")
        val report = report(fixture)

        assertEquals("pass", report.path("status").asText())
        assertEquals(setOf("pass"), report.path("checks").map { it.path("status").asText() }.toSet())
    }

    @Test
    fun `configuration cache is stored and reused`(@TempDir root: Path) {
        val fixture = FixtureProject(root).apply {
            create()
            commit()
        }
        val arguments = arrayOf(
            "souzGateFast",
            "--configuration-cache",
            "--configuration-cache-problems=fail",
        )

        val first = fixture.build(*arguments)
        val second = fixture.build(*arguments)

        assertTrue(first.output.contains("Configuration cache entry stored."))
        assertTrue(second.output.contains("Configuration cache entry reused."))
    }

    @Test
    fun `broken documentation link fails after writing both reports and check results`(@TempDir root: Path) {
        val fixture = FixtureProject(root).apply {
            create()
            append("agent/AGENTS.md", "[Broken](docs/missing.md)\n")
            commit()
        }

        fixture.buildAndFail("souzGateFast")
        val report = report(fixture)

        assertEquals("fail", report.path("status").asText())
        assertEquals("fail", check(report, "repository-contracts").path("status").asText())
        assertEquals("pass", check(report, "module-boundaries").path("status").asText())
        assertTrue(check(report, "repository-contracts").toString().contains("agent/AGENTS.md"))
        assertTrue(Files.isRegularFile(root.resolve("build/reports/souz-quality/fast/gate-summary.md")))
    }

    @Test
    fun `forbidden production edge fails with an actionable diagnostic`(@TempDir root: Path) {
        val fixture = FixtureProject(root).apply {
            create()
            write(
                "graph-engine/build.gradle.kts",
                """
                plugins { `java-library` }
                dependencies { implementation(project(":llms")) }
                """.trimIndent() + "\n",
            )
            commit()
        }

        fixture.buildAndFail("souzGateFast")
        val report = report(fixture)
        val boundaryResult = check(report, "module-boundaries")

        assertEquals("fail", boundaryResult.path("status").asText())
        assertTrue(boundaryResult.toString().contains("graph-engine/build.gradle.kts"))
        assertTrue(boundaryResult.toString().contains(":graph-engine main must not depend on :llms"))
    }

    @Test
    fun `compileOnlyApi project edge cannot bypass the boundary gate`(@TempDir root: Path) {
        val fixture = FixtureProject(root).apply {
            create()
            write(
                "graph-engine/build.gradle.kts",
                """
                plugins { `java-library` }
                dependencies { compileOnlyApi(project(":llms")) }
                """.trimIndent() + "\n",
            )
            commit()
        }

        fixture.buildAndFail("souzGateFast")
        val boundaryResult = check(report(fixture), "module-boundaries")

        assertEquals("fail", boundaryResult.path("status").asText())
        assertTrue(boundaryResult.toString().contains(":graph-engine main must not depend on :llms"))
        assertTrue(boundaryResult.toString().contains("compileOnlyApi"))
    }

    @Test
    fun `custom configuration feeding production fails closed`(@TempDir root: Path) {
        val fixture = FixtureProject(root).apply {
            create()
            write(
                "graph-engine/build.gradle.kts",
                """
                plugins { `java-library` }
                val internalDependencies by configurations.creating
                configurations.named("implementation") {
                    extendsFrom(internalDependencies)
                }
                dependencies {
                    add(internalDependencies.name, project(":llms"))
                }
                """.trimIndent() + "\n",
            )
            commit()
        }

        fixture.buildAndFail("souzGateFast")
        val boundaryResult = check(report(fixture), "module-boundaries")

        assertEquals("fail", boundaryResult.path("status").asText())
        assertTrue(boundaryResult.toString().contains("unclassified configuration internalDependencies"))
    }

    @Test
    fun `test named configuration inherited by production is not excluded`(@TempDir root: Path) {
        val fixture = FixtureProject(root).apply {
            create()
            write(
                "graph-engine/build.gradle.kts",
                """
                plugins { `java-library` }
                val sharedTestImplementation by configurations.creating
                configurations.named("implementation") {
                    extendsFrom(sharedTestImplementation)
                }
                dependencies {
                    add(sharedTestImplementation.name, project(":llms"))
                }
                """.trimIndent() + "\n",
            )
            commit()
        }

        fixture.buildAndFail("souzGateFast")
        val boundaryResult = check(report(fixture), "module-boundaries")

        assertEquals("fail", boundaryResult.path("status").asText())
        assertTrue(boundaryResult.toString().contains(":graph-engine main must not depend on :llms"))
        assertTrue(boundaryResult.toString().contains("sharedTestImplementation"))
    }

    @Test
    fun `KMP named configurations preserve production scope and exclude tests`(@TempDir root: Path) {
        val fixture = FixtureProject(root).apply {
            create()
            write(
                "sharedUI/build.gradle.kts",
                """
                plugins { `java-library` }
                configurations.create("commonJvmMainImplementation")
                configurations.create("jvmTestImplementation")
                dependencies {
                    add("commonJvmMainImplementation", project(":graph-engine"))
                    add("jvmTestImplementation", project(":llms"))
                }
                """.trimIndent() + "\n",
            )
            commit()
        }

        fixture.buildAndFail("souzGateFast")
        val diagnostics = check(report(fixture), "module-boundaries").path("diagnostics")

        assertEquals(1, diagnostics.size())
        assertTrue(diagnostics.single().path("message").asText().contains(":sharedUI commonJvmMain"))
        assertFalse(diagnostics.toString().contains("jvmTestImplementation"))
    }

    @Test
    fun `KMP common JVM source is analyzed once with path and line diagnostics`(@TempDir root: Path) {
        val fixture = FixtureProject(root).apply {
            create()
            addSharedUiKmpBoundaryViolation()
            commit()
        }

        val result = fixture.buildAndFail("souzGateFast")
        assertTrue(Files.isRegularFile(fixture.reportPath()), result.output)
        val boundaryResult = check(report(fixture), "source-set-boundaries")
        val diagnostics = boundaryResult.path("diagnostics")

        assertTrue(result.output.contains(":sharedUI:detektMainJvm"))
        assertFalse(result.output.contains("detektCommonJvmMainSourceSet"))
        assertEquals("fail", boundaryResult.path("status").asText())
        assertEquals(1, diagnostics.size())
        assertEquals(
            "sharedUI/src/commonJvmMain/kotlin/fixture/SharedUiState.kt",
            diagnostics.single().path("path").asText(),
        )
        assertEquals(3, diagnostics.single().path("line").asInt())
    }

    @Test
    fun `custom KMP production source set and root preserve classification and configuration cache`(
        @TempDir root: Path,
    ) {
        val fixture = FixtureProject(root).apply {
            create()
            addSharedUiKmpBoundaryViolation("portable-ui", "portable")
            commit()
        }
        val arguments = arrayOf(
            "souzGateFast",
            "--configuration-cache",
            "--configuration-cache-problems=fail",
        )

        val first = fixture.buildAndFail(*arguments)
        val second = fixture.buildAndFail(*arguments)
        val diagnostics = check(report(fixture), "source-set-boundaries").path("diagnostics")

        assertTrue(first.output.contains("Configuration cache entry stored."), first.output)
        assertTrue(second.output.contains("Configuration cache entry reused."), second.output)
        assertEquals(1, diagnostics.size())
        assertEquals(
            "sharedUI/portable-ui/fixture/SharedUiState.kt",
            diagnostics.single().path("path").asText(),
        )
    }

    @Test
    fun `overlapping source roots owned by different source sets are rejected`(@TempDir root: Path) {
        val fixture = FixtureProject(root).apply {
            create()
            addSharedUiKmpBoundaryViolation("portable-ui")
            append(
                "sharedUI/build.gradle.kts",
                """

                kotlin.sourceSets.named("jvmTest") {
                    kotlin.srcDir("portable-ui/tests")
                }
                """.trimIndent() + "\n",
            )
            commit()
        }

        val result = fixture.buildAndFail("generateSourceSetBoundaryConfig")

        assertTrue(result.output.contains("Overlapping source roots belong to different Kotlin source sets"), result.output)
        assertTrue(result.output.contains("sharedUI:commonJvmMain"), result.output)
        assertTrue(result.output.contains("sharedUI:jvmTest"), result.output)
    }

    @Test
    fun `invalid policy encoding is an internal error without hiding the other check`(@TempDir root: Path) {
        val fixture = FixtureProject(root).apply {
            create()
            writeBytes("AGENTS.md", byteArrayOf(0xC3.toByte(), 0x28))
            commit()
        }

        fixture.buildAndFail("souzGateFast")
        val reportText = Files.readString(fixture.reportPath())
        val report = mapper.readTree(reportText)

        assertEquals("error", report.path("status").asText())
        assertEquals("error", check(report, "repository-contracts").path("status").asText())
        assertEquals("pass", check(report, "module-boundaries").path("status").asText())
        assertTrue(check(report, "repository-contracts").toString().contains("Internal repository-contracts error"))
        assertFalse(reportText.contains(root.toAbsolutePath().toString()))
    }

    @Test
    fun `failed Detekt analysis writes reports with an internal error`(@TempDir root: Path) {
        val fixture = FixtureProject(root).apply {
            create()
            addFailingDetektAnalysis()
            commit()
        }

        val result = fixture.buildAndFail("souzGateFast")
        assertTrue(Files.isRegularFile(fixture.reportPath()), result.output)
        val report = report(fixture)

        assertEquals("error", report.path("status").asText())
        assertEquals("error", check(report, "source-set-boundaries").path("status").asText())
        assertEquals("error", check(report, "cancellation-propagation").path("status").asText())
        assertEquals("error", check(report, "coroutine-thread-local").path("status").asText())
        assertEquals("error", check(report, "coroutine-monitor-use").path("status").asText())
        assertTrue(Files.isRegularFile(root.resolve("build/reports/souz-quality/fast/gate-summary.md")))
    }

    private fun report(fixture: FixtureProject): JsonNode {
        assertTrue(Files.isRegularFile(fixture.reportPath()))
        return mapper.readTree(fixture.reportPath().toFile())
    }

    private fun check(report: JsonNode, id: String): JsonNode =
        report.path("checks").first { it.path("id").asText() == id }
}
