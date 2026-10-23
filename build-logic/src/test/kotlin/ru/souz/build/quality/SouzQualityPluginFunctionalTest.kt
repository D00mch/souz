package ru.souz.build.quality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

class SouzQualityPluginFunctionalTest {
    private val mapper = ObjectMapper()

    @Test
    fun `passing gate writes a report and reuses configuration cache`(@TempDir root: Path) {
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
        val report = report(fixture)

        assertEquals("pass", report.path("status").asText())
        assertEquals(setOf("pass"), report.path("checks").map { it.path("status").asText() }.toSet())

        val second = fixture.build(*arguments)

        assertTrue(first.output.contains("Configuration cache entry stored."))
        assertTrue(second.output.contains("Configuration cache entry reused."))
    }

    @Test
    fun `broken links and orphaned topics fail after writing both reports`(@TempDir root: Path) {
        val fixture = FixtureProject(root).apply {
            create()
            append("sharedUI/docs/runtime.md", "[Broken](missing.md)\n")
            write("sharedUI/docs/orphan.md", "# Unindexed constraint\n")
            commit()
        }

        fixture.buildAndFail("souzGateFast")
        val report = report(fixture)
        val result = check(report, "repository-contracts")
        val diagnostics = result.path("diagnostics")
        val orphan = diagnostics.single { it.path("path").asText() == "sharedUI/AGENTS.md" }

        assertEquals("fail", report.path("status").asText())
        assertEquals("fail", result.path("status").asText())
        assertEquals("pass", check(report, "module-boundaries").path("status").asText())
        assertEquals(2, diagnostics.size())
        assertEquals(3, orphan.path("line").asInt())
        assertTrue(orphan.path("message").asText().contains("sharedUI/docs/orphan.md"))
        assertTrue(diagnostics.any {
            it.path("path").asText() == "sharedUI/docs/runtime.md" &&
                it.path("message").asText().contains("does not resolve")
        })
        assertTrue(Files.isRegularFile(root.resolve("build/reports/souz-quality/fast/gate-summary.md")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["implementation", "compileOnlyApi"])
    fun `forbidden production edge fails with an actionable diagnostic`(configuration: String, @TempDir root: Path) {
        val fixture = FixtureProject(root).apply {
            create()
            write(
                "graph-engine/build.gradle.kts",
                """
                plugins { `java-library` }
                dependencies { $configuration(project(":llms")) }
                """.trimIndent() + "\n",
            )
            commit()
        }

        fixture.buildAndFail("souzGateFast")
        val boundaryResult = check(report(fixture), "module-boundaries")

        assertEquals("fail", boundaryResult.path("status").asText())
        assertTrue(boundaryResult.toString().contains("graph-engine/build.gradle.kts"))
        assertTrue(boundaryResult.toString().contains(":graph-engine main must not depend on :llms"))
        assertTrue(boundaryResult.toString().contains(configuration))
    }

    @ParameterizedTest
    @CsvSource(
        "internalDependencies, unclassified configuration internalDependencies",
        "sharedTestImplementation, :graph-engine main must not depend on :llms",
    )
    fun `configuration inherited by production cannot bypass the boundary gate`(
        configuration: String,
        expectedMessage: String,
        @TempDir root: Path,
    ) {
        val fixture = FixtureProject(root).apply {
            create()
            write(
                "graph-engine/build.gradle.kts",
                """
                plugins { `java-library` }
                val $configuration by configurations.creating
                configurations.named("implementation") {
                    extendsFrom($configuration)
                }
                dependencies {
                    add($configuration.name, project(":llms"))
                }
                """.trimIndent() + "\n",
            )
            commit()
        }

        fixture.buildAndFail("souzGateFast")
        val boundaryResult = check(report(fixture), "module-boundaries")

        assertEquals("fail", boundaryResult.path("status").asText())
        assertTrue(boundaryResult.toString().contains(expectedMessage))
        assertTrue(boundaryResult.toString().contains(configuration))
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
        assertEquals("error", check(report, "cancellation-propagation").path("status").asText())
        assertEquals("error", check(report, "coroutine-thread-local").path("status").asText())
        assertEquals("error", check(report, "coroutine-monitor-use").path("status").asText())
        assertEquals("error", check(report, "temporary-collection-membership").path("status").asText())
        assertEquals("error", check(report, "repeated-getter-computation").path("status").asText())
        assertEquals("error", check(report, "layered-mutability").path("status").asText())
        assertTrue(Files.isRegularFile(root.resolve("build/reports/souz-quality/fast/gate-summary.md")))
    }

    @ParameterizedTest
    @CsvSource(
        "TemporaryCollectionForMembership, temporary-collection-membership, blocking, fail",
        "RepeatedComputationInGetter, repeated-getter-computation, advisory, warning",
        "LayeredMutability, layered-mutability, advisory, warning",
    )
    fun `review findings reach their check with the selected enforcement`(
        rule: String,
        checkId: String,
        enforcement: String,
        status: String,
        @TempDir root: Path,
    ) {
        val fixture = FixtureProject(root).apply {
            create()
            addDetektReport(rule)
            commit()
        }
        if (status == "fail") fixture.buildAndFail("souzGateFast") else fixture.build("souzGateFast")
        val report = report(fixture)
        val result = check(report, checkId)

        assertEquals(status, report.path("status").asText())
        assertEquals(status, result.path("status").asText())
        assertEquals(enforcement, result.path("enforcement").asText())
        assertEquals(1, result.path("implementationVersion").asInt())
        assertEquals("agent/Example.kt", result.path("diagnostics").single().path("path").asText())
        assertEquals(7, result.path("diagnostics").single().path("line").asInt())
        assertFalse(Files.readString(fixture.reportPath()).contains(root.toAbsolutePath().toString()))
        assertTrue(Files.readString(root.resolve("build/reports/souz-quality/fast/gate-summary.md")).contains(checkId))
    }

    @Test
    fun `malformed analysis is an error even for advisory checks`(@TempDir root: Path) {
        val fixture = FixtureProject(root).apply {
            create()
            addDetektReport()
            write("analysis.xml", "<checkstyle>")
            commit()
        }
        fixture.buildAndFail("souzGateFast")
        val report = report(fixture)

        assertEquals("error", report.path("status").asText())
        assertEquals("error", check(report, "repeated-getter-computation").path("status").asText())
        assertEquals("error", check(report, "layered-mutability").path("status").asText())
    }

    private fun report(fixture: FixtureProject): JsonNode {
        assertTrue(Files.isRegularFile(fixture.reportPath()))
        return mapper.readTree(fixture.reportPath().toFile())
    }

    private fun check(report: JsonNode, id: String): JsonNode =
        report.path("checks").first { it.path("id").asText() == id }
}
