package ru.souz.skills.registry

import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.test.runTest
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleException
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.bundle.SkillManifest
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.validation.SkillValidationFinding
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.agent.skills.validation.SkillValidationSeverity
import ru.souz.agent.skills.validation.SkillValidationStatus
import ru.souz.db.SettingsProvider
import ru.souz.paths.SandboxSouzPaths
import ru.souz.paths.SouzPaths
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.docker.DockerRuntimeSandbox
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import ru.souz.paths.DefaultSouzPaths
import ru.souz.tool.files.FilesToolUtil
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
    fun `saves and loads skill bundle by user id and skill id`() = runTest {
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
    fun `user-scoped storage isolates skills by user id`() = runTest {
        val stateRoot = createTempDirectory("skill-registry-user-scoped-")
        val paths = DefaultSouzPaths(stateRoot = stateRoot)
        val repository = FileSystemSkillRegistryRepository(
            sandbox = createLocalSandbox(paths),
            config = FileSystemSkillRegistryConfig(scope = SkillStorageScope.USER_SCOPED),
        )
        val bundle = sampleBundle(skillId = SkillId("paper-summarize-academic"))

        repository.saveSkillBundle(userId = "user-1", bundle = bundle)

        assertEquals(bundle, repository.loadSkillBundle(userId = "user-1", skillId = bundle.skillId))
        assertNull(repository.loadSkillBundle(userId = "user-2", skillId = bundle.skillId))
        assertTrue(metadataPath(paths, "user-1", bundle.skillId, SkillStorageScope.USER_SCOPED).exists())
        assertTrue(!metadataPath(paths, "user-1", bundle.skillId, SkillStorageScope.SINGLE_USER).exists())
    }

    @Test
    fun `lists stored skill metadata without validation storage`() = runTest {
        val stateRoot = createTempDirectory("skill-registry-list-")
        val repository = createRepository(DefaultSouzPaths(stateRoot = stateRoot))
        val bundle = sampleBundle(skillId = SkillId("listable-skill"))

        repository.saveSkillBundle(userId = "user-1", bundle = bundle)

        val listed = repository.listSkills("user-1")
        val byId = repository.getSkill("user-1", bundle.skillId)
        val byName = repository.getSkillByName("user-1", bundle.manifest.name)

        assertEquals(1, listed.size)
        assertEquals(bundle.skillId, listed.single().skillId)
        assertEquals(bundle.manifest, byId?.manifest)
        assertEquals(bundle.skillId, byName?.skillId)
        assertEquals(bundle.skillId, repository.getSkill("user-2", bundle.skillId)?.skillId)
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
        assertEquals(listed, repository.getSkill("user-1", skillId))
        assertTrue(!skillRoot.resolve("stored-skill.json").exists())
        assertTrue(loaded.files.any { it.normalizedPath == "README.md" })
    }

    @Test
    fun `saves and reads validation record`() = runTest {
        val stateRoot = createTempDirectory("skill-registry-validation-")
        val repository = createRepository(DefaultSouzPaths(stateRoot = stateRoot))
        val record = SkillValidationRecord(
            userId = "user-1",
            skillId = SkillId("paper-summarize-academic"),
            bundleHash = VALIDATION_HASH_A,
            status = SkillValidationStatus.APPROVED,
            policyVersion = "skills-policy/v1",
            validatorVersion = "skills-validator/v1",
            model = "gpt-test",
            reasons = listOf("passed"),
            findings = listOf(
                SkillValidationFinding(
                    code = "ok",
                    message = "Looks safe",
                    severity = SkillValidationSeverity.INFO,
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
    fun `user-scoped validation storage isolates records by user id`() = runTest {
        val stateRoot = createTempDirectory("skill-registry-validation-user-scoped-")
        val paths = DefaultSouzPaths(stateRoot = stateRoot)
        val repository = createRepository(
            paths,
            config = FileSystemSkillRegistryConfig(scope = SkillStorageScope.USER_SCOPED),
        )
        val record = sampleValidationRecord(bundleHash = VALIDATION_HASH_A)

        repository.saveValidation(record)

        assertEquals(
            record,
            repository.getValidation(record.userId, record.skillId, record.bundleHash, record.policyVersion),
        )
        assertNull(repository.getValidation("user-2", record.skillId, record.bundleHash, record.policyVersion))
        assertTrue(
            validationRecordPath(
                paths = paths,
                userId = record.userId,
                skillId = record.skillId,
                policyVersion = record.policyVersion,
                bundleHash = record.bundleHash,
                scope = SkillStorageScope.USER_SCOPED,
            ).exists()
        )
        assertTrue(
            !validationRecordPath(
                paths = paths,
                userId = record.userId,
                skillId = record.skillId,
                policyVersion = record.policyVersion,
                bundleHash = record.bundleHash,
                scope = SkillStorageScope.SINGLE_USER,
            ).exists()
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
    fun `invalidates older approved validations without touching active bundle`() = runTest {
        val stateRoot = createTempDirectory("skill-registry-validation-invalidate-")
        val repository = createRepository(DefaultSouzPaths(stateRoot = stateRoot))
        val active = sampleValidationRecord(bundleHash = VALIDATION_HASH_A)
        val old = sampleValidationRecord(bundleHash = VALIDATION_HASH_B)
        repository.saveValidation(active)
        repository.saveValidation(old)

        repository.invalidateOtherValidations(
            userId = active.userId,
            skillId = active.skillId,
            activeBundleHash = active.bundleHash,
            policyVersion = active.policyVersion,
            reason = "new bundle",
        )

        val reloadedActive = repository.getValidation(active.userId, active.skillId, active.bundleHash, active.policyVersion)
        val reloadedOld = repository.getValidation(old.userId, old.skillId, old.bundleHash, old.policyVersion)

        assertEquals(SkillValidationStatus.APPROVED, reloadedActive?.status)
        assertEquals(SkillValidationStatus.STALE, reloadedOld?.status)
        assertEquals(listOf("passed", "new bundle"), reloadedOld?.reasons)
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

        val skillRoot = skillRoot(paths, "user-1", skillId, SkillStorageScope.SINGLE_USER)
        val metadataPath = metadataPath(paths, "user-1", skillId, SkillStorageScope.SINGLE_USER)
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
        val escapedPath = bundleRoot(paths, "user-1", skillId, bundleHash, SkillStorageScope.SINGLE_USER)
            .resolve("../../escape.txt")
            .normalize()
        assertTrue(!escapedPath.exists(), "Unexpected write outside bundle root: $escapedPath")
        assertNull(repository.getSkill("user-1", skillId))
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
            "user-1",
            bundle.skillId,
            SkillStorageScope.SINGLE_USER,
        )
        val bundleRoot = bundleRoot(
            paths = SandboxSouzPaths(sandbox.runtimePaths),
            userId = "user-1",
            skillId = bundle.skillId,
            bundleHash = stored.bundleHash,
            scope = SkillStorageScope.SINGLE_USER,
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
            userId = record.userId,
            skillId = record.skillId,
            policyVersion = record.policyVersion,
            bundleHash = record.bundleHash,
            scope = SkillStorageScope.SINGLE_USER,
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
        status = SkillValidationStatus.APPROVED,
        policyVersion = "skills-policy/v1",
        validatorVersion = "skills-validator/v1",
        model = "gpt-test",
        reasons = listOf("passed"),
        findings = listOf(
            SkillValidationFinding(
                code = "ok",
                message = "Looks safe",
                severity = SkillValidationSeverity.INFO,
                filePath = "SKILL.md",
            )
        ),
        createdAt = Instant.parse("2026-05-02T12:00:00Z"),
    )

    private fun createRepository(
        paths: SouzPaths,
        runtimeSandbox: RuntimeSandbox? = null,
        config: FileSystemSkillRegistryConfig = FileSystemSkillRegistryConfig(),
    ): FileSystemSkillRegistryRepository {
        val effectiveSandbox = runtimeSandbox
            ?: createLocalSandbox(paths)
        return FileSystemSkillRegistryRepository(
            sandbox = effectiveSandbox,
            config = config,
        )
    }

    private fun metadataPath(
        paths: SouzPaths,
        userId: String,
        skillId: SkillId,
        scope: SkillStorageScope,
    ): Path = skillRoot(paths, userId, skillId, scope).resolve("stored-skill.json")

    private fun bundleRoot(
        paths: SouzPaths,
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        scope: SkillStorageScope,
    ): Path = skillRoot(paths, userId, skillId, scope).resolve("bundles").resolve(bundleHash)

    private fun skillRoot(
        paths: SouzPaths,
        userId: String,
        skillId: SkillId,
        scope: SkillStorageScope,
    ): Path = when (scope) {
        SkillStorageScope.SINGLE_USER -> paths.skillsDir.resolve(skillId.value)
        SkillStorageScope.USER_SCOPED -> paths.skillsDir
            .resolve("users")
            .resolve(encodeSegment(userId))
            .resolve("skills")
            .resolve(skillId.value)
    }

    private fun validationRecordPath(
        paths: SouzPaths,
        userId: String,
        skillId: SkillId,
        policyVersion: String,
        bundleHash: String,
        scope: SkillStorageScope,
    ): Path = validationPolicyRoot(paths, userId, skillId, policyVersion, scope)
        .resolve("$bundleHash.json")

    private fun validationPolicyRoot(
        paths: SouzPaths,
        userId: String,
        skillId: SkillId,
        policyVersion: String,
        scope: SkillStorageScope,
    ): Path {
        val skillValidationRoot = when (scope) {
            SkillStorageScope.SINGLE_USER -> paths.skillValidationsDir.resolve(skillId.value)
            SkillStorageScope.USER_SCOPED -> paths.skillValidationsDir
                .resolve("users")
                .resolve(encodeSegment(userId))
                .resolve("skills")
                .resolve(skillId.value)
        }
        return skillValidationRoot.resolve("policies").resolve(policyVersion)
    }

    private fun encodeSegment(raw: String): String =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw.toByteArray(Charsets.UTF_8))

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
