package ru.souz.skills.registry

import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.test.runTest
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleException
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.bundle.SkillManifest
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.validation.SkillValidationFinding
import ru.souz.agent.skills.validation.SkillValidationLevel
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.db.SettingsProvider
import ru.souz.runtime.paths.SandboxSouzPaths
import ru.souz.paths.SouzPaths
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.docker.DockerRuntimeSandbox
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import ru.souz.paths.DefaultSouzPaths
import ru.souz.runtime.files.FilesToolUtil
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSystemSkillRegistryRepositoryTest {
    private val createdPaths = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        createdPaths.asReversed().forEach { path ->
            runCatching { path.toFile().deleteRecursively() }
        }
        createdPaths.clear()
    }

    @Test
    fun `saves and loads skill bundle from single-user storage`() = runTest {
        val stateRoot = createTempDirectory("skill-registry-save-load-")
        val repository = FileSystemSkillRegistryRepository(
            sandbox = createLocalSandbox(DefaultSouzPaths(stateRoot = stateRoot)),
        )
        val bundle = sampleBundle(skillId = SkillId("paper-summarize-academic"))

        val stored = repository.saveSkillBundle(userId = "user-1", bundle = bundle)
        val loaded = repository.loadSkillBundle(userId = "user-1", skillId = bundle.skillId)

        assertEquals("user-1", stored.userId)
        assertEquals(SkillBundleHasher.hash(bundle), stored.bundleHash)
        assertEquals(bundle, loaded)
        assertEquals(bundle, repository.loadSkillBundle(userId = "user-2", skillId = bundle.skillId))
    }

    @Test
    fun `lists stored skill metadata without validation storage`() = runTest {
        val stateRoot = createTempDirectory("skill-registry-list-")
        val repository = createRepository(DefaultSouzPaths(stateRoot = stateRoot))
        val bundle = sampleBundle(skillId = SkillId("listable-skill"))

        repository.saveSkillBundle(userId = "user-1", bundle = bundle)

        val listed = repository.listSkills("user-1")
        assertEquals(1, listed.size)
        assertEquals(bundle.skillId, listed.single().skillId)
    }

    @Test
    fun `loads loose skill directory without stored metadata`() = runTest {
        val stateRoot = createTempDirectory("skill-registry-loose-")
        val paths = DefaultSouzPaths(stateRoot = stateRoot)
        val repository = createRepository(paths)
        val skillId = SkillId("loose-skill")
        val skillRoot = paths.skillsDir.resolve(skillId.value)
        Files.createDirectories(skillRoot)
        Files.writeString(
            skillRoot.resolve("SKILL.md"),
            """
                ---
                name: loose_skill
                description: Loose fixture skill
                ---
                Loose instructions.
            """.trimIndent(),
        )
        Files.writeString(skillRoot.resolve("README.md"), "Loose readme")

        val listed = repository.listSkills("user-1").single()
        val loaded = assertNotNull(repository.loadSkillBundle("user-1", skillId))

        assertEquals(skillId, listed.skillId)
        assertEquals("loose_skill", listed.manifest.name)
        assertEquals(SkillBundleHasher.hash(loaded), listed.bundleHash)
        assertTrue(!skillRoot.resolve("stored-skill.json").exists())
        assertTrue(loaded.files.any { it.normalizedPath == "README.md" })
    }

    @Test
    fun `lists loose skill inventory ids without reading skill markdown content`() = runTest {
        val stateRoot = createTempDirectory("skill-registry-loose-inventory-id-")
        val paths = DefaultSouzPaths(stateRoot = stateRoot)
        val repository = createRepository(paths)
        val skillId = SkillId("loose-inventory-id-skill")
        val skillRoot = paths.skillsDir.resolve(skillId.value)
        Files.createDirectories(skillRoot)
        Files.write(skillRoot.resolve("SKILL.md"), ByteArray(2 * 1024 * 1024) { 'x'.code.toByte() })

        assertEquals(listOf(skillId), repository.listSkillInventoryIds("user-1"))
    }

    @Test
    fun `saves and reads validation record`() = runTest {
        val stateRoot = createTempDirectory("skill-registry-validation-")
        val repository = createRepository(DefaultSouzPaths(stateRoot = stateRoot))
        val record = SkillValidationRecord(
            userId = "user-1",
            skillId = SkillId("paper-summarize-academic"),
            bundleHash = VALIDATION_HASH_A,
            policyVersion = "skills-policy/v1",
            approved = true,
            findings = listOf(
                SkillValidationFinding(
                    code = "ok",
                    message = "Looks safe",
                    level = SkillValidationLevel.INFO,
                    filePath = "SKILL.md",
                )
            ),
            createdAt = Instant.parse("2026-05-02T12:00:00Z"),
        )

        repository.saveValidation(record)
        val loaded = repository.getValidation(
            userId = "user-1",
            skillId = record.skillId,
            bundleHash = record.bundleHash,
            policyVersion = record.policyVersion,
        )

        assertNotNull(loaded)
        assertEquals(record, loaded)
        assertNull(
            repository.getValidation(
                userId = "user-1",
                skillId = record.skillId,
                bundleHash = VALIDATION_HASH_B,
                policyVersion = record.policyVersion,
            )
        )
    }

    @Test
    fun `rejects unsafe bundle hashes for validation storage paths`() = runTest {
        val stateRoot = createTempDirectory("skill-registry-validation-unsafe-hash-")
        val repository = createRepository(DefaultSouzPaths(stateRoot = stateRoot))

        listOf("", "   ", "../escape", "abc/def", "not-a-sha256").forEach { bundleHash ->
            val error = assertFailsWith<IllegalArgumentException> {
                repository.saveValidation(sampleValidationRecord(bundleHash = bundleHash))
            }
            assertTrue(error.message.orEmpty().contains("bundle hash", ignoreCase = true))
        }
    }

    @Test
    fun `stores bundles under hash-addressed bundle directories and keeps prior bundles`() = runTest {
        val stateRoot = createTempDirectory("skill-registry-bundle-layout-")
        val paths = DefaultSouzPaths(stateRoot = stateRoot)
        val repository = createRepository(paths)
        val skillId = SkillId("paper-summarize-academic")
        val initialBundle = sampleBundle(skillId = skillId)
        val updatedBundle = sampleBundle(
            skillId = skillId,
            readmeContent = "Updated read me",
        )

        val initialStored = repository.saveSkillBundle(userId = "user-1", bundle = initialBundle)
        val updatedStored = repository.saveSkillBundle(userId = "user-1", bundle = updatedBundle)

        val skillRoot = skillRoot(paths, skillId)
        val metadataPath = metadataPath(paths, skillId)
        val initialBundleRoot = skillRoot.resolve("bundles").resolve(initialStored.bundleHash)
        val updatedBundleRoot = skillRoot.resolve("bundles").resolve(updatedStored.bundleHash)

        assertTrue(metadataPath.exists())
        assertTrue(initialBundleRoot.resolve("SKILL.md").exists())
        assertTrue(updatedBundleRoot.resolve("README.md").exists())
        assertTrue(initialBundleRoot.resolve("README.md").exists())
        assertTrue(metadataPath.readText().contains(updatedStored.bundleHash))
        assertEquals(updatedBundle, repository.loadSkillBundle("user-1", skillId))
    }

    @Test
    fun `saveSkillBundle revalidates paths before writing files`() = runTest {
        val stateRoot = createTempDirectory("skill-registry-untrusted-paths-")
        val paths = DefaultSouzPaths(stateRoot = stateRoot)
        val repository = createRepository(paths)
        val skillId = SkillId("paper-summarize-academic")
        val bundle = SkillBundle(
            skillId = skillId,
            manifest = SkillManifest(
                name = "Paper Summarizer",
                description = "Summarize papers",
                rawFrontmatter = """
                    name: Paper Summarizer
                    description: Summarize papers
                """.trimIndent(),
            ),
            files = listOf(
                SkillFile(
                    normalizedPath = "SKILL.md",
                    content = """
                        ---
                        name: Paper Summarizer
                        description: Summarize papers
                        ---
                        Use this skill.
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                ),
                SkillFile(
                    normalizedPath = "../../escape.txt",
                    content = "escape".toByteArray(Charsets.UTF_8),
                ),
            ),
            skillMarkdownBody = "Use this skill.",
        )

        assertFailsWith<SkillBundleException> {
            repository.saveSkillBundle(userId = "user-1", bundle = bundle)
        }

        val bundleHash = SkillBundleHasher.hash(bundle)
        val escapedPath = bundleRoot(paths, skillId, bundleHash)
            .resolve("../../escape.txt")
            .normalize()
        assertTrue(!escapedPath.exists(), "Unexpected write outside bundle root: $escapedPath")
        assertNull(repository.loadSkillBundle("user-1", skillId))
    }

    @Test
    fun `sandbox registry can be constructed with local runtime sandbox`() = runTest {
        val stateRoot = createTempDirectory("skill-registry-sandbox-local-")
        val sandbox = createLocalSandbox(DefaultSouzPaths(stateRoot = stateRoot))
        val repository = FileSystemSkillRegistryRepository(sandbox = sandbox)
        val bundle = sampleBundle(skillId = SkillId("local-sandbox-skill"))

        val stored = repository.saveSkillBundle(userId = "user-1", bundle = bundle)

        assertEquals(bundle, repository.loadSkillBundle("user-1", bundle.skillId))
        assertEquals("user-1", stored.userId)
    }

    @Test
    fun `docker sandbox registry persists and loads skills through container visible paths`() = runTest {
        val sandbox = createDockerSandbox("skill-registry-docker-")
        val repository = FileSystemSkillRegistryRepository(sandbox = sandbox)
        val bundle = sampleBundle(skillId = SkillId("docker-skill"))

        val stored = repository.saveSkillBundle(userId = "user-1", bundle = bundle)
        val loaded = repository.loadSkillBundle(userId = "user-1", skillId = bundle.skillId)

        val metadataPath = metadataPath(
            SandboxSouzPaths(sandbox.runtimePaths),
            bundle.skillId,
        )
        val bundleRoot = bundleRoot(
            paths = SandboxSouzPaths(sandbox.runtimePaths),
            skillId = bundle.skillId,
            bundleHash = stored.bundleHash,
        )
        val metadataInfo = sandbox.fileSystem.resolveExistingFile(metadataPath.toString())
        val bundleInfo = sandbox.fileSystem.resolveExistingDirectory(bundleRoot.toString())

        assertEquals(bundle, loaded)
        assertTrue(metadataInfo.path.startsWith("/souz/state/skills"))
        assertTrue(bundleInfo.path.startsWith("/souz/state/skills"))
        assertNotNull(sandbox.fileSystem.localPathOrNull(metadataInfo))
        assertNotNull(sandbox.fileSystem.localPathOrNull(bundleInfo))
    }

    @Test
    fun `docker sandbox validation storage stays under container skill validation root`() = runTest {
        val sandbox = createDockerSandbox("skill-registry-docker-validation-")
        val repository = FileSystemSkillRegistryRepository(sandbox = sandbox)
        val record = sampleValidationRecord(bundleHash = VALIDATION_HASH_A)

        repository.saveValidation(record)
        val loaded = repository.getValidation(record.userId, record.skillId, record.bundleHash, record.policyVersion)

        val path = validationRecordPath(
            paths = SandboxSouzPaths(sandbox.runtimePaths),
            skillId = record.skillId,
            policyVersion = record.policyVersion,
            bundleHash = record.bundleHash,
        )
        val pathInfo = sandbox.fileSystem.resolveExistingFile(path.toString())

        assertEquals(record, loaded)
        assertTrue(pathInfo.path.startsWith("/souz/state/skill-validations"))
        assertNotNull(sandbox.fileSystem.localPathOrNull(pathInfo))
    }

    @Test
    fun `docker sandbox filesystem lists descendants using container paths`() = runTest {
        val sandbox = createDockerSandbox("skill-registry-docker-listing-")
        val bundle = sampleBundle(skillId = SkillId("docker-listing-skill"))
        val repository = createRepository(
            paths = SandboxSouzPaths(sandbox.runtimePaths),
            runtimeSandbox = sandbox,
        )

        repository.saveSkillBundle(userId = "user-1", bundle = bundle)

        val skillsRoot = sandbox.fileSystem.resolveExistingDirectory(sandbox.runtimePaths.skillsDirPath)
        val descendants = sandbox.fileSystem.listDescendants(skillsRoot, includeHidden = true)

        assertTrue(descendants.isNotEmpty())
        assertTrue(descendants.all { it.path.startsWith("/souz/state/skills") })
        assertTrue(descendants.any { it.name == "stored-skill.json" })
        assertTrue(descendants.any { it.name == "SKILL.md" })
    }

    private fun createTempDirectory(prefix: String): Path =
        Files.createTempDirectory(Path.of(FilesToolUtil.homeStr), prefix).also(createdPaths::add)

    private fun sampleBundle(
        skillId: SkillId,
        readmeContent: String = "Read me",
    ): SkillBundle = SkillBundle.fromFiles(
        skillId = skillId,
        files = listOf(
            SkillFile(
                normalizedPath = "SKILL.md",
                content = """
                    ---
                    name: ${skillId.value}
                    description: Description for ${skillId.value}
                    ---
                    Skill instructions.
                """.trimIndent().toByteArray(Charsets.UTF_8),
            ),
            SkillFile(
                normalizedPath = "README.md",
                content = readmeContent.toByteArray(Charsets.UTF_8),
            ),
        ),
    )

    private fun sampleValidationRecord(bundleHash: String): SkillValidationRecord = SkillValidationRecord(
        userId = "user-1",
        skillId = SkillId("paper-summarize-academic"),
        bundleHash = bundleHash,
        policyVersion = "skills-policy/v1",
        approved = true,
        findings = listOf(
            SkillValidationFinding(
                code = "ok",
                message = "Looks safe",
                level = SkillValidationLevel.INFO,
                filePath = "SKILL.md",
            )
        ),
        createdAt = Instant.parse("2026-05-02T12:00:00Z"),
    )

    private fun createRepository(
        paths: SouzPaths,
        runtimeSandbox: RuntimeSandbox? = null,
    ): FileSystemSkillRegistryRepository {
        val effectiveSandbox = runtimeSandbox
            ?: createLocalSandbox(paths)
        return FileSystemSkillRegistryRepository(sandbox = effectiveSandbox)
    }

    private fun metadataPath(
        paths: SouzPaths,
        skillId: SkillId,
    ): Path = skillRoot(paths, skillId).resolve("stored-skill.json")

    private fun bundleRoot(
        paths: SouzPaths,
        skillId: SkillId,
        bundleHash: String,
    ): Path = skillRoot(paths, skillId).resolve("bundles").resolve(bundleHash)

    private fun skillRoot(
        paths: SouzPaths,
        skillId: SkillId,
    ): Path = paths.skillsDir.resolve(skillId.value)

    private fun validationRecordPath(
        paths: SouzPaths,
        skillId: SkillId,
        policyVersion: String,
        bundleHash: String,
    ): Path = validationPolicyRoot(paths, skillId, policyVersion)
        .resolve("$bundleHash.json")

    private fun validationPolicyRoot(
        paths: SouzPaths,
        skillId: SkillId,
        policyVersion: String,
    ): Path = paths.skillValidationsDir
        .resolve(skillId.value)
        .resolve("policies")
        .resolve(policyVersion)

    private fun createLocalSandbox(paths: SouzPaths): LocalRuntimeSandbox {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.forbiddenFolders } returns emptyList()
        return LocalRuntimeSandbox(
            scope = SandboxScope.localDefault(),
            settingsProvider = settingsProvider,
            stateRoot = paths.stateRoot,
        )
    }

    private fun createDockerSandbox(prefix: String): DockerRuntimeSandbox {
        val hostRoot = createTempDirectory(prefix)
        return DockerRuntimeSandbox(
            scope = SandboxScope(userId = "user-1"),
            hostRoot = hostRoot,
            autoStart = false,
        )
    }

    private companion object {
        private const val VALIDATION_HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val VALIDATION_HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
