package agent

import com.fasterxml.jackson.databind.JsonNode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleParser
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxCommandExecutor
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.runtime.sandbox.SandboxRuntimePaths
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.tool.skills.SkillCommandExecutor

/** Pinned, deduplicated bundles; inventory reads metadata, while detail loads verified source files. */
internal object ScenarioSkillFixtures {
    private val entries = restJsonMapper.readTree(resource("index.json")).path("skills").toList().let { skills ->
        skills.associateBy { SkillId(it.path("id").asText()) }.also {
            require(it.size == skills.size) { "Scenario Skill fixture index contains duplicate IDs." }
        }
    }
    val ids: List<SkillId> = entries.keys.toList()

    fun list(userId: String): List<StoredSkill> = entries.map { (id, entry) ->
        val markdown = entry.path("files").first { it.path("path").asText() == "SKILL.md" }
        StoredSkill(
            userId = userId,
            skillId = id,
            manifest = SkillBundleParser.parseManifest(file(id, markdown).contentAsText()),
            bundleHash = hash(entry),
            createdAt = Instant.EPOCH,
        )
    }

    fun load(id: SkillId): SkillBundle? = entries[id]?.let { entry ->
        SkillBundle.fromFiles(id, entry.path("files").map { file(id, it) })
    }

    fun hash(id: SkillId): String? = entries[id]?.let(::hash)

    fun paths(): Set<String> = entries.flatMap { (id, entry) ->
        entry.path("files").map { "$SKILLS_ROOT/${id.value}/${it.path("path").asText()}" }
    }.toSet()

    private fun hash(entry: JsonNode): String = sha256(
        entry.path("files").sortedBy { it.path("path").asText() }
            .joinToString("") { "${it.path("path").asText()}\n${it.path("sha256").asText()}\n" }.toByteArray(),
    )

    private fun file(id: SkillId, entry: JsonNode): SkillFile {
        val path = entry.path("path").asText()
        val bytes = resource("${id.value}/$path")
        require(bytes.size == entry.path("bytes").asInt() && sha256(bytes) == entry.path("sha256").asText()) {
            "Scenario Skill fixture checksum mismatch: ${id.value}/$path"
        }
        return SkillFile(path, bytes)
    }

    private fun resource(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/agent/skills/$path")) { "Missing scenario Skill resource: $path" }
            .use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

internal fun scenarioSkillRegistryRepository(): SkillRegistryRepository = object : SkillRegistryRepository {
    override suspend fun listSkills(userId: String): List<StoredSkill> = ScenarioSkillFixtures.list(userId)
    override suspend fun listSkillInventoryIds(userId: String): List<SkillId> = ScenarioSkillFixtures.ids
    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? = ScenarioSkillFixtures.load(skillId)

    // Exact pinned test bundles need no extra live validation requests. Commands remain simulated below.
    override suspend fun getValidation(
        userId: String, skillId: SkillId, bundleHash: String, policyVersion: String,
    ): SkillValidationRecord? = if (ScenarioSkillFixtures.hash(skillId) != bundleHash) null else SkillValidationRecord(
        userId = userId, skillId = skillId, bundleHash = bundleHash, policyVersion = policyVersion,
        approved = true, createdAt = Instant.EPOCH,
    )

    override suspend fun saveSkillBundle(userId: String, bundle: SkillBundle): StoredSkill =
        error("Scenario Skill fixtures are read-only.")

    override suspend fun saveValidation(record: SkillValidationRecord): Unit =
        error("Scenario Skill validation fixtures are read-only.")
}

private const val SKILLS_ROOT = "/scenario/skills"

/** Allows normal bundle resolution and command validation, but never starts a process or contacts a service. */
internal fun scenarioSkillCommandExecutor(): SkillCommandExecutor {
    val files = ScenarioSkillFixtures.paths()
    fun pathInfo(raw: String): SandboxPathInfo {
        val path = Path.of(raw).normalize().toString().replace('\\', '/')
        val directory = files.any { it.startsWith("$path/") }
        return SandboxPathInfo(
            rawPath = raw, path = path, name = path.substringAfterLast('/'),
            parentPath = path.substringBeforeLast('/'), exists = directory || path in files,
            isDirectory = directory, isRegularFile = path in files, isSymbolicLink = false,
        )
    }
    val sandboxFiles = mockk<SandboxFileSystem> {
        every { resolvePath(any()) } answers { pathInfo(firstArg()) }
        every { resolveExistingDirectory(any()) } answers {
            pathInfo(firstArg()).also { require(it.isDirectory) { "Unknown scenario Skill directory: ${it.path}" } }
        }
        every { resolveExistingFile(any()) } answers {
            pathInfo(firstArg()).also { require(it.isRegularFile) { "Unknown scenario Skill file: ${it.path}" } }
        }
    }
    val paths = mockk<SandboxRuntimePaths> { every { skillsDirPath } returns SKILLS_ROOT }
    val commands = mockk<SandboxCommandExecutor> {
        coEvery { execute(any()) } returns SandboxCommandResult(
            exitCode = 1, stdout = "",
            stderr = "scenario_skill_not_configured: No command fixture is configured. No process was started.",
        )
    }
    val sandbox = mockk<RuntimeSandbox> {
        every { runtimePaths } returns paths
        every { fileSystem } returns sandboxFiles
        every { commandExecutor } returns commands
    }
    return SkillCommandExecutor(ToolInvocationRuntimeSandboxResolver.fixed(sandbox))
}
