package ru.souz.build.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

class RepositoryContractsTest {
    @Test
    fun `reports a broken policy link with its source line`(@TempDir repository: Path) {
        writePolicies(
            repository,
            """
            # Agent

            ## Pain points

            [Missing](docs/missing.md)
            """.trimIndent() + "\n",
        )

        val diagnostics = check(repository)

        assertEquals(1, diagnostics.size)
        assertEquals("agent/AGENTS.md", diagnostics.single().path)
        assertEquals(5, diagnostics.single().line)
        assertTrue(diagnostics.single().message.contains("does not resolve"))
    }

    @Test
    fun `module policy exemption allows root-owned policy without a module index`(@TempDir repository: Path) {
        write(
            repository.resolve("AGENTS.md"),
            """
            # Policy

            ## Module Map

            - `:agent` — agent module.

            ## Module Policy Exemptions

            - `:agent` — policy is owned at the root.
            """.trimIndent() + "\n",
        )
        assertTrue(check(repository).isEmpty())
    }

    @Test
    fun `a linked topic without a level-two pain points heading fails`(@TempDir repository: Path) {
        writePolicies(repository, "# Agent\n\n### Pain points\n\n[Runtime](docs/runtime.md)\n")
        write(repository.resolve("agent/docs/runtime.md"), "# Runtime\n")

        val diagnostics = check(repository)

        assertEquals(1, diagnostics.size)
        assertEquals("agent/AGENTS.md", diagnostics.single().path)
        assertTrue(diagnostics.single().message.contains("'Pain points' level-two heading"))
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "## Pain points\n",
        "[Runtime](docs/runtime.md)\n\n## Pain points\n",
        "## Pain points\n\n## Verification\n\n[Runtime](docs/runtime.md)\n",
        "## Pain points\n\n# Appendix\n\n[Runtime](docs/runtime.md)\n",
        "## Pain points\n\n![Runtime](docs/runtime.md)\n",
        "## Pain points\n\n[Runtime](../other/docs/runtime.md)\n",
        "## Pain points\n\n`[Runtime](docs/runtime.md)`\n",
    ])
    fun `unindexed topics fail even when other references exist`(policy: String, @TempDir repository: Path) {
        writePolicies(repository, "# Agent\n\n$policy")
        write(repository.resolve("agent/docs/runtime.md"), "# Runtime\n")
        write(repository.resolve("other/docs/runtime.md"), "# Other runtime\n")

        val diagnostic = check(repository).single()

        assertEquals("agent/AGENTS.md", diagnostic.path)
        assertTrue(diagnostic.message.contains("agent/docs/runtime.md"))
        assertTrue(diagnostic.message.contains("'Pain points' section"))
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "[Runtime](docs/runtime.md)",
        "[Runtime](docs/./runtime.md#invariants)",
        "[Runtime](docs/runt%69me.md?view=raw)",
        "### Runtime\n\n[Runtime][runtime]\n\n[runtime]: docs/runtime.md",
    ])
    fun `section links index topics using resolved markdown targets`(link: String, @TempDir repository: Path) {
        writePolicies(repository, "# Agent\n\n## Pain points\n\n$link\n")
        write(repository.resolve("agent/docs/runtime.md"), "# Runtime\n")

        assertTrue(check(repository).isEmpty())
    }

    private fun check(repository: Path): List<QualityDiagnostic> = RepositoryContracts.check(
        repositoryDirectory = repository.toFile(),
        projects = listOf(ProjectDescriptor(":agent", "agent", "agent/build.gradle.kts")),
        policyFiles = Files.walk(repository).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }
                .map(Path::toFile).toList().toSet()
        },
        registeredChecks = SouzQualityChecks.fast,
    )

    private fun writePolicies(repository: Path, modulePolicy: String) {
        write(repository.resolve("AGENTS.md"), "# Policy\n\n## Module Map\n\n- `:agent` — agent module.\n")
        write(repository.resolve("agent/AGENTS.md"), modulePolicy)
    }

    private fun write(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }
}
