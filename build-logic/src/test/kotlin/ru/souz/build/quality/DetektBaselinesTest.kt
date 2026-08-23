package ru.souz.build.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DetektBaselinesTest {
    @Test
    fun `identical finding ids remain scoped to their analysis targets`(@TempDir root: Path) {
        val sharedId = "SuspendFunSwallowedCancellation:Runtime.kt:Runtime${'$'}runCatching"
        val agentOnlyId = "SuspendFunInFinallySection:Agent.kt:Agent${'$'}close"
        val agentMain = partialBaseline(root, "agent", sharedId, agentOnlyId)
        val agentTest = partialBaseline(root, "agent", sharedId, fileName = "detektBaselineTest.xml")
        val backend = partialBaseline(root, "backend", sharedId)

        val rendered = DetektBaselines.renderByAnalysis(
            repository = root.toFile(),
            baselines = setOf(agentMain, agentTest, backend),
        )

        assertEquals(setOf("agent/main.xml", "agent/test.xml", "backend/main.xml"), rendered.keys)
        assertTrue(rendered.values.all { sharedId in it })
        assertTrue(agentOnlyId in rendered.getValue("agent/main.xml"))
    }

    @Test
    fun `empty baselines are omitted`(@TempDir root: Path) {
        val empty = partialBaseline(root, "agent")

        val rendered = DetektBaselines.renderByAnalysis(
            repository = root.toFile(),
            baselines = setOf(empty),
        )

        assertTrue(rendered.isEmpty())
    }

    @Test
    fun `baseline hygiene reports stale entries`(@TempDir root: Path) {
        val currentId =
            "SuspendFunSwallowedCancellation:Runtime.kt:Runtime${'$'}catch { error(\"boom\") -> Unit }"
        val staleId = "SuspendFunInFinallySection:Removed.kt:Removed${'$'}close"
        val generated = partialBaseline(root, "agent", currentId)
        val reviewed = root.resolve("quality/detekt-baselines/agent/main.xml")
        writeBaseline(reviewed, listOf(staleId))

        val diagnostics = DetektBaselines.hygieneDiagnostics(
            repository = root.toFile(),
            generatedBaselines = setOf(generated),
            reviewedBaselines = setOf(reviewed.toFile()),
        )

        assertEquals(
            listOf("Remove stale Detekt baseline entry: $staleId"),
            diagnostics.map(QualityDiagnostic::message),
        )
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
