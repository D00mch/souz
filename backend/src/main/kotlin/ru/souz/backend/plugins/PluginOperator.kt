package ru.souz.backend.plugins

import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory

/** Invoked only by the backend distribution's operator command, never by client routes. */
internal class PluginOperator(private val repository: PostgresPluginRepository) {
    suspend fun execute(args: List<String>): String {
        val result = when (args.firstOrNull()) {
            "install" -> {
                require(args.size == 3) { USAGE }
                val expectedRevision = revision(args[2])
                val bytes = Files.newInputStream(Path.of(args[1])).use { it.readNBytes(PluginArtifact.MAX_BYTES + 1) }
                val artifact = repository.install(bytes)
                repository.select(artifact.id, artifact.version, expectedRevision)
            }
            "select" -> {
                require(args.size == 4) { USAGE }
                repository.select(args[1], args[2], revision(args[3]))
            }
            "disable" -> {
                require(args.size == 3) { USAGE }
                repository.disable(args[1], revision(args[2]))
            }
            "status" -> {
                require(args.size == 1) { USAGE }
                return repository.desired().joinToString("\n") { describe(it) }
            }
            else -> throw IllegalArgumentException(USAGE)
        }
        val description = describe(result)
        log.info("Plugin operator: {}", description)
        return description
    }

    private fun revision(value: String): Long =
        requireNotNull(value.toLongOrNull()?.takeIf { it >= 0 }) { "Expected a non-negative desired revision." }

    private fun describe(row: DesiredPlugin): String =
        "${row.id} digest=${row.digest} enabled=${row.enabled} revision=${row.revision}"

    companion object {
        const val USAGE = "plugins install <jar> <expected-revision> | select <id> <version> <expected-revision> | disable <id> <expected-revision> | status"
        private val log = LoggerFactory.getLogger(PluginOperator::class.java)
    }
}
