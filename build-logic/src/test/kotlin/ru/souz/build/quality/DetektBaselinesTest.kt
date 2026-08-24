package ru.souz.build.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class DetektBaselinesTest {
    @Test
    fun `identical finding ids remain scoped to their analysis targets`(@TempDir root: Path) {
        val sharedId = "SuspendFunSwallowedCancellation:Runtime.kt:Runtime${'$'}runCatching"
        val agentMain = partialBaseline(root, "agent", sharedId)
        val agentTest = partialBaseline(root, "agent", sharedId, fileName = "detektBaselineTest.xml")
        val backend = partialBaseline(root, "backend", sharedId)

        val rendered = DetektBaselines.renderByAnalysis(
            repository = root.toFile(),
            baselines = setOf(agentMain, agentTest, backend),
        )

        assertEquals(setOf("agent/main.xml", "agent/test.xml", "backend/main.xml"), rendered.keys)
        assertTrue(rendered.values.all { sharedId in it })
    }

    @Test
    fun `empty baselines are omitted`(@TempDir root: Path) {
        val empty = partialBaseline(root, "agent")

        assertTrue(DetektBaselines.renderByAnalysis(root.toFile(), setOf(empty)).isEmpty())
    }

    private fun partialBaseline(
        root: Path,
        module: String,
        vararg issueIds: String,
        fileName: String = "detektBaselineMain.xml",
    ): File {
        val path = root.resolve("$module/build/reports/detekt/baselines/$fileName")
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
        return path.toFile()
    }
}
