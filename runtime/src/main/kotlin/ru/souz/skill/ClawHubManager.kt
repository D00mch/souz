package ru.souz.skill

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Comparator
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import ru.souz.agent.skill.AgentSkillMarkdownParser
import ru.souz.agent.skill.AgentSkillSource

data class ClawHubRemoteSkill(
    val sourceId: String,
    val version: String,
    val archiveUrl: String,
)

data class InstalledClawHubSkill(
    val skillName: String,
    val folderName: String,
    val sourceId: String,
    val version: String,
    val installedAt: String,
)

interface ClawHubClient {
    suspend fun resolveLatest(sourceId: String, currentVersion: String? = null): ClawHubRemoteSkill

    suspend fun downloadArchive(skill: ClawHubRemoteSkill): ByteArray
}

class HttpClawHubClient(
    private val baseUri: URI = URI.create("https://clawhub.ai"),
    private val httpClient: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
) : ClawHubClient {
    override suspend fun resolveLatest(sourceId: String, currentVersion: String?): ClawHubRemoteSkill =
        withContext(Dispatchers.IO) {
            val request = HttpRequest.newBuilder(baseUri.resolve("/api/skills/$sourceId")).GET().build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            require(response.statusCode() in 200..299) { "ClawHub resolve failed: HTTP ${response.statusCode()}" }
            val node = objectMapper.readTree(response.body())
            val version = sequenceOf("version", "latestVersion")
                .map { field -> node.path(field).asText("").trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: error("ClawHub response did not include a version for $sourceId")
            val archiveUrl = sequenceOf("archiveUrl", "downloadUrl", "zipUrl")
                .map { field -> node.path(field).asText("").trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: error("ClawHub response did not include an archive URL for $sourceId")
            ClawHubRemoteSkill(sourceId = sourceId, version = version, archiveUrl = archiveUrl)
        }

    override suspend fun downloadArchive(skill: ClawHubRemoteSkill): ByteArray = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI.create(skill.archiveUrl)).GET().build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        require(response.statusCode() in 200..299) { "ClawHub download failed: HTTP ${response.statusCode()}" }
        response.body()
    }
}

class ClawHubManager(
    private val directories: SkillDirectories = SkillDirectories.default(),
    private val client: ClawHubClient,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
) {
    suspend fun install(sourceId: String): InstalledClawHubSkill {
        val remote = client.resolveLatest(sourceId)
        return install(remote, client.downloadArchive(remote))
    }

    suspend fun update(sourceId: String? = null): List<InstalledClawHubSkill> {
        val installed = list()
        val targets = if (sourceId == null) {
            installed
        } else {
            installed.filter { it.sourceId == sourceId }
        }
        return targets.map { current ->
            val remote = client.resolveLatest(current.sourceId, current.version)
            if (remote.version == current.version) {
                current
            } else {
                install(remote, client.downloadArchive(remote))
            }
        }
    }

    fun list(): List<InstalledClawHubSkill> = readLockfile().installed.sortedBy { it.skillName.lowercase() }

    private suspend fun install(
        remote: ClawHubRemoteSkill,
        archiveBytes: ByteArray,
    ): InstalledClawHubSkill = withContext(Dispatchers.IO) {
        Files.createDirectories(directories.managedSkillsDir)
        Files.createDirectories(directories.clawHubDirectory)

        val extractionRoot = directories.managedSkillsDir.resolve(".tmp-${UUID.randomUUID()}")
        Files.createDirectories(extractionRoot)
        try {
            extractZipSafely(archiveBytes, extractionRoot)
            val skillDirectory = extractedSkillDirectory(extractionRoot)
            val skillFile = skillDirectory.resolve("SKILL.md")
            val parsed = AgentSkillMarkdownParser.parseSkill(
                markdown = Files.readString(skillFile),
                fallbackName = skillDirectory.fileName.toString(),
                source = AgentSkillSource.MANAGED,
            )

            val targetDirectory = directories.managedSkillsDir.resolve(skillDirectory.fileName.toString()).normalize()
            require(targetDirectory.startsWith(directories.managedSkillsDir.normalize())) {
                "Resolved managed skill path escapes the skills directory"
            }
            deleteDirectoryIfExists(targetDirectory)
            Files.move(skillDirectory, targetDirectory, StandardCopyOption.REPLACE_EXISTING)

            val record = InstalledClawHubSkill(
                skillName = parsed.summary.name,
                folderName = targetDirectory.fileName.toString(),
                sourceId = remote.sourceId,
                version = remote.version,
                installedAt = Instant.now().toString(),
            )
            val current = readLockfile().installed.filterNot { it.sourceId == remote.sourceId }
            writeLockfile(ClawHubLockfile(current + record))
            record
        } finally {
            deleteDirectoryIfExists(extractionRoot)
        }
    }

    private fun extractZipSafely(
        archiveBytes: ByteArray,
        extractionRoot: Path,
    ) {
        ZipInputStream(archiveBytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val entryName = entry.name.replace('\\', '/')
                require(!entryName.startsWith("/")) { "Absolute ZIP entry paths are not allowed" }
                val targetPath = extractionRoot.resolve(entryName).normalize()
                require(targetPath.startsWith(extractionRoot.normalize())) { "ZIP entry escapes target directory: $entryName" }
                if (entry.isDirectory) {
                    Files.createDirectories(targetPath)
                } else {
                    Files.createDirectories(targetPath.parent)
                    Files.copy(zip, targetPath, StandardCopyOption.REPLACE_EXISTING)
                }
                zip.closeEntry()
            }
        }
    }

    private fun extractedSkillDirectory(extractionRoot: Path): Path {
        Files.walk(extractionRoot).use { paths ->
            val skillDirs = paths
                .filter { path -> Files.isRegularFile(path) && path.fileName.toString() == "SKILL.md" }
                .map { it.parent }
                .distinct()
                .toList()
            require(skillDirs.size == 1) { "Expected exactly one extracted skill directory, got ${skillDirs.size}" }
            return skillDirs.single()
        }
    }

    private fun readLockfile(): ClawHubLockfile {
        val lockfile = directories.clawHubLockfile
        if (!lockfile.exists()) return ClawHubLockfile()
        return objectMapper.readValue(lockfile.toFile())
    }

    private fun writeLockfile(lockfile: ClawHubLockfile) {
        Files.createDirectories(directories.clawHubDirectory)
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(directories.clawHubLockfile.toFile(), lockfile)
    }

    private fun deleteDirectoryIfExists(path: Path) {
        if (!path.exists()) return
        Files.walk(path)
            .sorted(Comparator.reverseOrder())
            .forEach { current -> current.deleteIfExists() }
    }

    private data class ClawHubLockfile(
        val installed: List<InstalledClawHubSkill> = emptyList(),
    )
}
