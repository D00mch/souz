package ru.souz.build.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DetektBaselinesTest {
    @Test
    fun `identical finding ids remain scoped to their analysis tasks`(@TempDir root: Path) {
        val issueId = "SuspendFunSwallowedCancellation:Runtime.kt:Runtime${'$'}runCatching"
        val agent = partialBaseline(root, "agent", issueId)
        val agentTest = partialBaseline(root, "agent", issueId, fileName = "detektBaselineTest.xml")
        val backend = partialBaseline(root, "backend", issueId)

        val rendered = DetektBaselines.renderScoped(
            repository = root.toFile(),
            baselines = setOf(agent, agentTest, backend),
        )

        assertEquals(
            setOf(
                "agent/detektBaselineMain.xml",
                "agent/detektBaselineTest.xml",
                "backend/detektBaselineMain.xml",
            ),
            rendered.keys,
        )
        assertTrue(rendered.values.all { issueId in it })
    }

    @Test
    fun `reports stale findings only in their original scope`(@TempDir root: Path) {
        val currentId = "SuspendFunSwallowedCancellation:Runtime.kt:Runtime${'$'}runCatching"
        val staleId = "SuspendFunInFinallySection:Runtime.kt:Runtime${'$'}close"
        val agentCurrent = partialBaseline(root, "agent", currentId)
        val backendCurrent = partialBaseline(root, "backend", currentId)
        val agentTracked = trackedBaseline(root, "agent", currentId, staleId)
        val backendTracked = trackedBaseline(root, "backend", currentId)

        val diagnostics = DetektBaselines.staleDiagnostics(
            repository = root.toFile(),
            trackedBaselines = setOf(agentTracked, backendTracked),
            currentBaselines = setOf(agentCurrent, backendCurrent),
        )

        assertEquals(1, diagnostics.size)
        assertEquals("quality/detekt-baselines/agent/detektBaselineMain.xml", diagnostics.single().path)
        assertTrue(diagnostics.single().message.contains("SuspendFunInFinallySection"))
        assertTrue(diagnostics.single().message.contains("Runtime.kt"))
    }

    private fun partialBaseline(
        root: Path,
        module: String,
        vararg issueIds: String,
        fileName: String = "detektBaselineMain.xml",
    ): java.io.File {
        val path = root.resolve("$module/build/reports/detekt/baselines/$fileName")
        writeBaseline(path, issueIds.toList())
        return path.toFile()
    }

    private fun trackedBaseline(root: Path, module: String, vararg issueIds: String): java.io.File {
        val path = root.resolve("quality/detekt-baselines/$module/detektBaselineMain.xml")
        writeBaseline(path, issueIds.toList())
        return path.toFile()
    }

    private fun writeBaseline(path: Path, issueIds: List<String>) {
        Files.createDirectories(path.parent)
        Files.writeString(
            path,
            buildString {
                appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                appendLine("<SmellBaseline>")
                appendLine("  <ManuallySuppressedIssues/>")
                appendLine("  <CurrentIssues>")
                issueIds.forEach { appendLine("    <ID>$it</ID>") }
                appendLine("  </CurrentIssues>")
                appendLine("</SmellBaseline>")
            },
        )
    }
}
