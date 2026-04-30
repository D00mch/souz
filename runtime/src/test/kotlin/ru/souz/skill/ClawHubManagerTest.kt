package ru.souz.skill

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClawHubManagerTest {
    @Test
    fun `install update and list use lockfile metadata`() = runTest {
        val root = Files.createTempDirectory("clawhub-test")
        val directories = SkillDirectories(
            workspaceSkillsDir = root.resolve("workspace"),
            managedSkillsDir = root.resolve("managed").also { it.createDirectories() },
        )
        val client = FakeClawHubClient(
            latest = mutableMapOf(
                "weather" to ClawHubRemoteSkill("weather", "1.0.0", "mem://weather/1.0.0"),
            ),
            archives = mutableMapOf(
                "mem://weather/1.0.0" to buildSkillArchive("weather", "version one"),
            ),
        )
        val manager = ClawHubManager(directories = directories, client = client)

        val installed = manager.install("weather")
        assertEquals("1.0.0", installed.version)
        assertEquals(listOf(installed), manager.list())
        assertTrue(directories.managedSkillsDir.resolve("weather").resolve("SKILL.md").readText().contains("version one"))

        client.latest["weather"] = ClawHubRemoteSkill("weather", "1.1.0", "mem://weather/1.1.0")
        client.archives["mem://weather/1.1.0"] = buildSkillArchive("weather", "version two")

        val updated = manager.update("weather")
        assertEquals("1.1.0", updated.single().version)
        assertTrue(directories.managedSkillsDir.resolve("weather").resolve("SKILL.md").readText().contains("version two"))
        assertEquals("1.1.0", manager.list().single().version)
    }

    @Test
    fun `zip extraction rejects path traversal`() = runTest {
        val root = Files.createTempDirectory("clawhub-traversal")
        val directories = SkillDirectories(
            workspaceSkillsDir = root.resolve("workspace"),
            managedSkillsDir = root.resolve("managed").also { it.createDirectories() },
        )
        val client = FakeClawHubClient(
            latest = mutableMapOf(
                "weather" to ClawHubRemoteSkill("weather", "1.0.0", "mem://weather/bad"),
            ),
            archives = mutableMapOf(
                "mem://weather/bad" to buildArchive(
                    "../escape.txt" to "boom",
                    "weather/SKILL.md" to minimalSkillMarkdown("weather", "bad"),
                ),
            ),
        )
        val manager = ClawHubManager(directories = directories, client = client)

        assertFailsWith<IllegalArgumentException> {
            manager.install("weather")
        }
        assertTrue(!directories.managedSkillsDir.resolve("weather").exists())
    }

    private class FakeClawHubClient(
        val latest: MutableMap<String, ClawHubRemoteSkill>,
        val archives: MutableMap<String, ByteArray>,
    ) : ClawHubClient {
        override suspend fun resolveLatest(sourceId: String, currentVersion: String?): ClawHubRemoteSkill =
            latest.getValue(sourceId)

        override suspend fun downloadArchive(skill: ClawHubRemoteSkill): ByteArray =
            archives.getValue(skill.archiveUrl)
    }

    private fun buildSkillArchive(folderName: String, body: String): ByteArray =
        buildArchive(folderName + "/SKILL.md" to minimalSkillMarkdown(folderName, body))

    private fun minimalSkillMarkdown(name: String, body: String): String = """
        ---
        name: $name
        description: Demo $name
        when_to_use: Demo
        ---
        $body
    """.trimIndent()

    private fun buildArchive(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
