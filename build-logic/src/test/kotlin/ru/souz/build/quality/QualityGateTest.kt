package ru.souz.build.quality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

class QualityGateTest {
    @TempDir lateinit var root: Path

    @BeforeEach
    fun fixture() {
        write("settings.gradle", "rootProject.name = 'fixture'; include 'graph-engine', 'llms'")
        write("build.gradle", """
            plugins { id 'souz.quality' }
            tasks.named('souzGateFastReport') { detektReports.from(project.file('analysis.xml')) }
        """.trimIndent())
        write("AGENTS.md", "## Module Map\n\n- `:graph-engine`\n- `:llms`\n")
        write("docs/quality-gates.md", "# Quality gates\n")
        for (module in listOf("graph-engine", "llms")) {
            write("$module/AGENTS.md", "# Module\n\n## Pain points\n")
            write("$module/build.gradle", "plugins { id 'java-library' }")
        }
        write("analysis.xml", "<checkstyle/>")
        for (args in listOf(listOf("init", "-q"), listOf("add", "."), listOf(
            "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "fixture",
        ))) {
            val git = ProcessBuilder(listOf("git", "-C", root.toString()) + args).redirectErrorStream(true).start()
            val output = git.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, git.waitFor(), output)
        }
    }

    @ParameterizedTest
    @CsvSource(
        "TemporaryCollectionForMembership, temporary-collection-membership, fail",
        "RepeatedComputationInGetter, repeated-getter-computation, warning",
        "LayeredMutability, layered-mutability, warning",
    )
    fun `findings reach the gate with the correct enforcement`(rule: String, id: String, status: String) {
        write("analysis.xml", """
            <checkstyle><file name="Example.kt">
              <error line="7" source="detekt.souz-review.$rule" message="Review $rule."/>
            </file></checkstyle>
        """.trimIndent())
        val report = gate(fails = status == "fail")
        val check = check(report, id)
        assertEquals(status, report.path("status").asText())
        assertEquals(status, check.path("status").asText())
        assertEquals("Example.kt", check.path("diagnostics").single().path("path").asText())
        assertEquals(7, check.path("diagnostics").single().path("line").asInt())
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "malformed"])
    fun `analysis failures produce error reports`(failure: String) {
        if (failure == "missing") Files.delete(root.resolve("analysis.xml")) else write("analysis.xml", "<checkstyle>")
        val report = gate(fails = true)
        assertEquals("error", report.path("status").asText())
        assertEquals("error", check(report, "temporary-collection-membership").path("status").asText())
        assertEquals("error", check(report, "repeated-getter-computation").path("status").asText())
        assertEquals("error", check(report, "layered-mutability").path("status").asText())
    }

    @Test
    fun `test dependencies cannot bypass production boundaries through inheritance`() {
        write("graph-engine/build.gradle", """
            plugins { id 'java-library' }
            configurations { sharedTestImplementation }
            dependencies { sharedTestImplementation project(':llms') }
        """.trimIndent())
        assertEquals("pass", gate().path("status").asText())
        Files.writeString(root.resolve("graph-engine/build.gradle"),
            "\nconfigurations.implementation.extendsFrom(configurations.sharedTestImplementation)",
            java.nio.file.StandardOpenOption.APPEND)
        val result = check(gate(fails = true), "module-boundaries")
        assertEquals("fail", result.path("status").asText())
        assertTrue(result.path("diagnostics").single().path("message").asText()
            .contains(":graph-engine main must not depend on :llms"))
    }

    @Test
    fun `runtime main dependencies follow the backend JVM module graph`() {
        val modules = listOf("graph-engine", "llms", "agent", "sharedLogic", "skill-oauth-api", "skill-oauth-impl",
            "job-api", "job-impl", "skill-plugin-api", "backend")
        val projects = modules.map { ProjectDescriptor(":$it", it, "$it/build.gradle.kts") }
        fun edge(source: String, target: String) = DependencyEdge(
            ":$source", "implementation", "main", ":$target", "$source/build.gradle.kts",
        )
        val allowed = listOf(
            edge("agent", "graph-engine"), edge("agent", "llms"),
            edge("sharedLogic", "agent"), edge("sharedLogic", "llms"), edge("sharedLogic", "skill-oauth-api"),
            edge("skill-oauth-impl", "skill-oauth-api"), edge("job-impl", "job-api"),
            edge("skill-plugin-api", "llms"),
        ) + modules.filter { it != "graph-engine" && it != "backend" }.map { edge("backend", it) }
        assertEquals(emptyList<QualityDiagnostic>(), ModuleBoundaries.check(projects, allowed))
        val forbidden = ModuleBoundaries.check(projects, listOf(edge("sharedLogic", "backend")))
        assertEquals(1, forbidden.size)
        assertTrue(forbidden.single().message.contains(":sharedLogic main must not depend on :backend"))
        assertEquals(1, ModuleBoundaries.check(projects, listOf(edge("skill-plugin-api", "agent"))).size)
    }

    @Test
    fun `JVM configurations identify production and explicitly named tests`() {
        for (configuration in listOf("api", "implementation", "compileOnly", "compileOnlyApi", "runtimeOnly", "annotationProcessor")) {
            assertEquals("main", dependencySourceSet(configuration))
        }
        for (configuration in listOf("testImplementation", "testFixturesApi", "integrationTestRuntimeOnly")) {
            assertNull(dependencySourceSet(configuration))
        }
        assertEquals(UNCLASSIFIED_SOURCE_SET, dependencySourceSet("unclassifiedImplementation"))
    }

    @Test
    fun `coroutine baselines preserve analysis scope and exclude review findings`() {
        val coroutine = "MonitorInsideSuspendContext:Example.kt:monitor"
        fun baseline(module: String, analysis: String, vararg ids: String) = write(
            "$module/build/reports/detekt/baselines/detektBaseline$analysis.xml",
            "<SmellBaseline><CurrentIssues>${ids.joinToString("") { "<ID>$it</ID>" }}</CurrentIssues></SmellBaseline>",
        ).toFile()
        val files = setOf(
            baseline("graph-engine", "Main", coroutine, "TemporaryCollectionForMembership:Example.kt:contains"),
            baseline("graph-engine", "Test", coroutine, "RepeatedComputationInGetter:Example.kt:getter"),
            baseline("llms", "Main", "LayeredMutability:Example.kt:registry"),
        )
        val rendered = DetektBaselines.renderByAnalysis(root.toFile(), files)
        assertEquals(setOf("graph-engine/main.xml", "graph-engine/test.xml"), rendered.keys)
        for (xml in rendered.values) {
            assertTrue(xml.contains("<ID>$coroutine</ID>"))
            assertEquals(1, Regex("<ID>").findAll(xml).count())
        }
    }

    private fun gate(fails: Boolean = false): JsonNode {
        val runner = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
            .withArguments("souzGateFast", "--console=plain")
        if (fails) runner.buildAndFail() else runner.build()
        val reports = root.resolve("build/reports/souz-quality/fast")
        assertTrue(Files.isRegularFile(reports.resolve("gate-summary.md")))
        return ObjectMapper().readTree(reports.resolve("gate-summary-v1.json").toFile())
    }

    private fun check(report: JsonNode, id: String) = report.path("checks").single { it.path("id").asText() == id }

    private fun write(path: String, content: String): Path = root.resolve(path).also {
        Files.createDirectories(it.parent)
        Files.writeString(it, content)
    }
}
