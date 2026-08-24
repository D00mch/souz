package ru.souz.build.quality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale

internal enum class DuplicationScope(val wireName: String) {
    PRODUCTION("production"),
    TESTS("tests"),
}

internal data class DuplicationThreshold(
    val minLines: Int,
    val minTokens: Int,
)

internal data class DuplicationMeasurement(
    val clones: Long,
    val sources: Long,
    val tokens: Long,
    val duplicatedTokens: Long,
    val percentageTokens: Double,
)

internal data class DuplicationBaselineEntry(
    val minLines: Int,
    val minTokens: Int,
    val duplicatedTokens: Long,
)

internal data class DuplicationBaseline(
    val jscpdVersion: String,
    val configurationSha256: String,
    val entries: Map<DuplicationScope, DuplicationBaselineEntry>,
)

internal data class DuplicationEvaluation(
    val failed: Boolean,
    val diagnostics: List<QualityDiagnostic>,
)

internal object DuplicationRatchet {
    const val SCHEMA_VERSION = 1
    val thresholds = mapOf(
        DuplicationScope.PRODUCTION to DuplicationThreshold(minLines = 15, minTokens = 100),
        DuplicationScope.TESTS to DuplicationThreshold(minLines = 20, minTokens = 120),
    )

    private val mapper = ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)

    fun readMeasurement(report: File): DuplicationMeasurement {
        val total = mapper.readTree(report).requiredObject("statistics").requiredObject("total")
        return DuplicationMeasurement(
            clones = total.requiredLong("clones"),
            sources = total.requiredLong("sources"),
            tokens = total.requiredLong("tokens"),
            duplicatedTokens = total.requiredLong("duplicatedTokens"),
            percentageTokens = total.requiredDouble("percentageTokens"),
        )
    }

    fun readBaseline(file: File): DuplicationBaseline {
        val root = mapper.readTree(file)
        require(root.requiredLong("schemaVersion") == SCHEMA_VERSION.toLong()) {
            "Unsupported duplication baseline schema."
        }
        return DuplicationBaseline(
            jscpdVersion = root.requiredText("jscpdVersion"),
            configurationSha256 = root.requiredText("configurationSha256"),
            entries = DuplicationScope.entries.associateWith { scope ->
                val entry = root.requiredObject(scope.wireName)
                DuplicationBaselineEntry(
                    minLines = entry.requiredLong("minLines").toInt(),
                    minTokens = entry.requiredLong("minTokens").toInt(),
                    duplicatedTokens = entry.requiredLong("duplicatedTokens"),
                )
            },
        )
    }

    fun writeBaseline(
        file: File,
        jscpdVersion: String,
        configurationSha256: String,
        measurements: Map<DuplicationScope, DuplicationMeasurement>,
    ) {
        val root = mapper.createObjectNode().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("jscpdVersion", jscpdVersion)
            put("configurationSha256", configurationSha256)
            DuplicationScope.entries.forEach { scope ->
                val threshold = thresholds.getValue(scope)
                val measurement = measurements.getValue(scope)
                set<ObjectNode>(scope.wireName, mapper.createObjectNode().apply {
                    put("minLines", threshold.minLines)
                    put("minTokens", threshold.minTokens)
                    put("duplicatedTokens", measurement.duplicatedTokens)
                })
            }
        }
        Files.createDirectories(file.toPath().parent)
        Files.writeString(
            file.toPath(),
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
            StandardCharsets.UTF_8,
        )
    }

    fun evaluate(
        baselineFile: File,
        baselinePath: String,
        expectedJscpdVersion: String,
        expectedConfigurationSha256: String,
        measurements: Map<DuplicationScope, DuplicationMeasurement>,
    ): DuplicationEvaluation {
        val baseline = readBaseline(baselineFile)
        var failed = false
        val diagnostics = mutableListOf<QualityDiagnostic>()

        if (baseline.jscpdVersion != expectedJscpdVersion) {
            failed = true
            diagnostics += diagnostic(
                baselinePath,
                "jscpd version ${baseline.jscpdVersion} does not match pinned version $expectedJscpdVersion.",
            )
        }
        if (baseline.configurationSha256 != expectedConfigurationSha256) {
            failed = true
            diagnostics += diagnostic(
                baselinePath,
                "jscpd configuration changed; review it and update the duplication baseline.",
            )
        }

        DuplicationScope.entries.forEach { scope ->
            val threshold = thresholds.getValue(scope)
            val baselineEntry = baseline.entries.getValue(scope)
            val measurement = measurements.getValue(scope)
            if (baselineEntry.minLines != threshold.minLines || baselineEntry.minTokens != threshold.minTokens) {
                failed = true
                diagnostics += diagnostic(
                    baselinePath,
                    "${scope.wireName} detector thresholds changed; review them and update the duplication baseline.",
                )
            }

            val comparison = measurement.duplicatedTokens.compareTo(baselineEntry.duplicatedTokens)
            if (comparison != 0) {
                failed = true
            }
            val ratchet = when {
                comparison > 0 -> "exceeds"
                comparison < 0 -> "is below the stale"
                else -> "matches"
            }
            diagnostics += diagnostic(
                baselinePath,
                "${scope.wireName}: ${measurement.duplicatedTokens} duplicated tokens in " +
                    "${measurement.clones} clones (${formatPercentage(measurement.percentageTokens)}% of " +
                    "${measurement.tokens} tokens across ${measurement.sources} files) $ratchet baseline " +
                    "${baselineEntry.duplicatedTokens}; minimum ${threshold.minLines} lines/${threshold.minTokens} tokens.",
            )
        }

        return DuplicationEvaluation(failed, diagnostics)
    }

    fun jscpdVersion(packageFile: File): String = mapper.readTree(packageFile)
        .requiredObject("devDependencies")
        .requiredText("jscpd")

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()))
        return "sha256:" + digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun diagnostic(path: String, message: String) = QualityDiagnostic(
        path = path,
        line = null,
        message = message,
    )

    private fun formatPercentage(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    private fun JsonNode.requiredObject(name: String): JsonNode = required(name).also {
        require(it.isObject) { "'$name' must be a JSON object." }
    }

    private fun JsonNode.requiredText(name: String): String = required(name).also {
        require(it.isTextual) { "'$name' must be a JSON string." }
    }.asText()

    private fun JsonNode.requiredLong(name: String): Long = required(name).also {
        require(it.isIntegralNumber) { "'$name' must be a JSON integer." }
    }.asLong()

    private fun JsonNode.requiredDouble(name: String): Double = required(name).also {
        require(it.isNumber) { "'$name' must be a JSON number." }
    }.asDouble()
}
