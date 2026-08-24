package ru.souz.build.quality

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DuplicationRatchetTest {
    @Test
    fun `unchanged duplicated tokens pass while growth and stale debt fail`(@TempDir root: Path) {
        val baselineFile = root.resolve("quality/duplication-baseline.json").toFile()
        val measurements = measurements(production = 100, tests = 200)
        DuplicationRatchet.writeBaseline(
            file = baselineFile,
            jscpdVersion = "5.0.16",
            configurationSha256 = "sha256:config",
            measurements = measurements,
        )

        val unchanged = evaluate(baselineFile, measurements)
        val growth = evaluate(baselineFile, measurements(production = 101, tests = 200))
        val stale = evaluate(baselineFile, measurements(production = 100, tests = 199))

        assertFalse(unchanged.failed)
        assertTrue(growth.failed)
        assertTrue(growth.diagnostics.any { it.message.contains("exceeds baseline 100") })
        assertTrue(stale.failed)
        assertTrue(stale.diagnostics.any { it.message.contains("below the stale") })
    }

    @Test
    fun `tool and configuration changes require an explicit baseline update`(@TempDir root: Path) {
        val baselineFile = root.resolve("quality/duplication-baseline.json").toFile()
        val measurements = measurements(production = 100, tests = 200)
        DuplicationRatchet.writeBaseline(
            file = baselineFile,
            jscpdVersion = "5.0.15",
            configurationSha256 = "sha256:old",
            measurements = measurements,
        )

        val evaluation = evaluate(baselineFile, measurements)

        assertTrue(evaluation.failed)
        assertTrue(evaluation.diagnostics.any { it.message.contains("does not match pinned version") })
        assertTrue(evaluation.diagnostics.any { it.message.contains("configuration changed") })
    }

    private fun evaluate(
        baselineFile: java.io.File,
        measurements: Map<DuplicationScope, DuplicationMeasurement>,
    ): DuplicationEvaluation = DuplicationRatchet.evaluate(
        baselineFile = baselineFile,
        baselinePath = "quality/duplication-baseline.json",
        expectedJscpdVersion = "5.0.16",
        expectedConfigurationSha256 = "sha256:config",
        measurements = measurements,
    )

    private fun measurements(
        production: Long,
        tests: Long,
    ): Map<DuplicationScope, DuplicationMeasurement> = mapOf(
        DuplicationScope.PRODUCTION to measurement(production),
        DuplicationScope.TESTS to measurement(tests),
    )

    private fun measurement(duplicatedTokens: Long) = DuplicationMeasurement(
        clones = 1,
        sources = 2,
        tokens = 1_000,
        duplicatedTokens = duplicatedTokens,
        percentageTokens = duplicatedTokens / 10.0,
    )
}
