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
            repository.resolve("docs/pain-points.md"),
            """
            # Pain points

            - [Agent](../agent/docs/pain-points.md)
            """.trimIndent() + "\n",
        )
        write(
            repository.resolve("agent/AGENTS.md"),
            """
            # Agent
            [Pain points](docs/pain-points.md)
            [Missing](docs/missing.md)
            """.trimIndent() + "\n",
        )
        write(repository.resolve("agent/docs/pain-points.md"), "# Pain points\n")

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
        assertEquals(3, diagnostics.single().line)
        assertTrue(diagnostics.single().message.contains("does not resolve"))
    }

    @Test
    fun `module policy exemption does not exempt pain point contracts`(@TempDir repository: Path) {
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
        write(repository.resolve("docs/pain-points.md"), "# Pain points\n")

        val diagnostics = RepositoryContracts.check(
            repositoryDirectory = repository.toFile(),
            projects = listOf(ProjectDescriptor(":agent", "agent", "agent/build.gradle.kts")),
            policyFiles = setOf(
                repository.resolve("AGENTS.md").toFile(),
                repository.resolve("docs/pain-points.md").toFile(),
            ),
            registeredChecks = SouzQualityChecks.fast,
        )

        assertEquals(2, diagnostics.size)
        assertTrue(diagnostics.any { it.message.contains("needs a module pain-point index") })
        assertTrue(diagnostics.any { it.message.contains("root pain-point index must link") })
        assertTrue(diagnostics.none { it.message.contains("needs an AGENTS.md policy") })
    }

    @Test
    fun `pain point image does not satisfy the policy link contract`(@TempDir repository: Path) {
        write(
            repository.resolve("AGENTS.md"),
            """
            # Policy

            ## Module Map

            - `:agent` — agent module.
            """.trimIndent() + "\n",
        )
        write(
            repository.resolve("docs/pain-points.md"),
            "# Pain points\n\n[Agent](../agent/docs/pain-points.md)\n",
        )
        write(repository.resolve("agent/AGENTS.md"), "# Agent\n\n![Pain points](docs/pain-points.md)\n")
        write(repository.resolve("agent/docs/pain-points.md"), "# Pain points\n")

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
        assertTrue(diagnostics.single().message.contains("AGENTS.md must link"))
    }

    private fun write(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }
}
