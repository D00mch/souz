package ru.souz.build.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RepositoryContractsTest {
    @Test
    fun `reports a broken policy link with its source line`(@TempDir repository: Path) {
        write(
            repository.resolve("AGENTS.md"),
            """
            # Policy

            ## Module Map

            - `:agent` — agent module.
            """.trimIndent() + "\n",
        )
        write(
            repository.resolve("agent/AGENTS.md"),
            """
            # Agent

            ## Pain points

            [Missing](docs/missing.md)
            """.trimIndent() + "\n",
        )

        val diagnostics = RepositoryContracts.check(
            repositoryDirectory = repository.toFile(),
            projects = listOf(ProjectDescriptor(":agent", "agent", "agent/build.gradle.kts")),
            policyFiles = Files.walk(repository).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }
                    .map(Path::toFile)
                    .toList()
                    .toSet()
            },
            registeredChecks = SouzQualityChecks.fast,
        )

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
        val diagnostics = RepositoryContracts.check(
            repositoryDirectory = repository.toFile(),
            projects = listOf(ProjectDescriptor(":agent", "agent", "agent/build.gradle.kts")),
            policyFiles = setOf(
                repository.resolve("AGENTS.md").toFile(),
            ),
            registeredChecks = SouzQualityChecks.fast,
        )

        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun `a linked topic without a level-two pain points heading fails`(@TempDir repository: Path) {
        write(
            repository.resolve("AGENTS.md"),
            """
            # Policy

            ## Module Map

            - `:agent` — agent module.
            """.trimIndent() + "\n",
        )
        write(repository.resolve("agent/AGENTS.md"), "# Agent\n\n### Pain points\n\n[Runtime](docs/runtime.md)\n")
        write(repository.resolve("agent/docs/runtime.md"), "# Runtime\n")

        val diagnostics = RepositoryContracts.check(
            repositoryDirectory = repository.toFile(),
            projects = listOf(ProjectDescriptor(":agent", "agent", "agent/build.gradle.kts")),
            policyFiles = Files.walk(repository).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }
                    .map(Path::toFile)
                    .toList()
                    .toSet()
            },
            registeredChecks = SouzQualityChecks.fast,
        )

        assertEquals(1, diagnostics.size)
        assertEquals("agent/AGENTS.md", diagnostics.single().path)
        assertTrue(diagnostics.single().message.contains("'Pain points' level-two heading"))
    }

    private fun write(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }
}
