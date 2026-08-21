package ru.souz.build.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class QualityReportTest {
    @Test
    fun `normalized check hash excludes duration`(@TempDir repository: Path) {
        val identity = GitIdentity("a".repeat(40), null, null, true)
        val result = QualityCheckResult(
            definition = SouzQualityChecks.repositoryContracts,
            status = QualityStatus.FAIL,
            durationMs = 1,
            diagnostics = listOf(QualityDiagnostic("AGENTS.md", 3, "Missing module.")),
            gitIdentity = identity,
        )

        assertEquals(QualityReport.normalizedHash(result), QualityReport.normalizedHash(result.copy(durationMs = 999)))

        val json = repository.resolve("build/json/gate-summary-v1.json")
        val markdown = repository.resolve("build/markdown/gate-summary.md")
        QualityReport.write(listOf(result), repository.toFile(), json.toFile(), markdown.toFile())
        val rendered = Files.readString(json)
        assertTrue(rendered.contains("\"durationMs\" : 1"))
        assertFalse(rendered.contains(repository.toAbsolutePath().toString()))
        assertTrue(Files.isRegularFile(markdown))
    }

    @Test
    fun `advisory diagnostics produce a warning without failing the lane`(@TempDir repository: Path) {
        val result = QualityCheckRunner.run(
            definition = SouzQualityChecks.coroutineMonitorUse,
            repositoryDirectory = repository.toFile(),
            gitIdentity = GitIdentity(null, null, null, true),
        ) {
            listOf(QualityDiagnostic("Example.kt", 4, "Review synchronized use."))
        }

        assertEquals(QualityStatus.WARNING, result.status)
        assertEquals(
            QualityStatus.WARNING,
            QualityReport.write(
                listOf(result),
                repository.toFile(),
                repository.resolve("gate.json").toFile(),
                repository.resolve("gate.md").toFile(),
            ),
        )
    }
}
