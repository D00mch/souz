package ru.souz.skill

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class FilesystemSkillCatalogTest {
    @Test
    fun `workspace skills override managed skills`() {
        val root = Files.createTempDirectory("skill-catalog-test")
        val workspaceDir = root.resolve("workspace").resolve("skills")
        val managedDir = root.resolve("managed")
        workspaceDir.createDirectories()
        managedDir.createDirectories()

        writeSkill(
            managedDir.resolve("weather"),
            """
            ---
            name: weather
            description: Managed weather
            when_to_use: Managed
            ---
            managed body
            """.trimIndent(),
        )
        writeSkill(
            workspaceDir.resolve("weather"),
            """
            ---
            name: weather
            description: Workspace weather
            when_to_use: Workspace
            allowed-tools: [Bash]
            metadata:
              openclaw:
                requires:
                  bins:
                    - curl
            ---
            workspace body
            """.trimIndent(),
        )

        val catalog = FilesystemSkillCatalog(
            directories = SkillDirectories(
                workspaceSkillsDir = workspaceDir,
                managedSkillsDir = managedDir,
            ),
        )

        val summaries = catalog.listSkillSummaries()

        assertEquals(1, summaries.size)
        assertEquals("Workspace weather", summaries.single().description)

        val skill = catalog.loadSkill("weather")
        assertEquals("workspace body", skill?.body)
    }

    @Test
    fun `workspace root can be detected from runtime code location instead of user dir`() {
        val root = Files.createTempDirectory("skill-root-detect")
        val repoRoot = root.resolve("repo")
        val unrelatedCwd = root.resolve("app")
        repoRoot.resolve("skills").createDirectories()
        unrelatedCwd.createDirectories()

        val detected = SkillDirectories.detectWorkspaceRoot(
            userDir = unrelatedCwd,
            workspaceOverride = null,
            codeSourcePath = repoRoot.resolve("runtime").resolve("build").resolve("classes").resolve("kotlin").resolve("main"),
        )

        assertEquals(repoRoot, detected)
    }

    private fun writeSkill(directory: java.nio.file.Path, markdown: String) {
        directory.createDirectories()
        directory.resolve("SKILL.md").writeText(markdown)
    }
}
