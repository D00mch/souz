package ru.souz.build.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DetektBaselinesTest {
    @Test
    fun `identical finding ids remain scoped to their modules`(@TempDir root: Path) {
        val sharedId = "SuspendFunSwallowedCancellation:Runtime.kt:Runtime${'$'}runCatching"
        val agentOnlyId = "SuspendFunInFinallySection:Agent.kt:Agent${'$'}close"
        val agentMain = partialBaseline(root, "agent", sharedId, agentOnlyId)
        val agentTest = partialBaseline(root, "agent", sharedId, fileName = "detektBaselineTest.xml")
        val backend = partialBaseline(root, "backend", sharedId)

        val rendered = DetektBaselines.renderByModule(
            repository = root.toFile(),
            baselines = setOf(agentMain, agentTest, backend),
        )

        assertEquals(setOf("agent.xml", "backend.xml"), rendered.keys)
        assertTrue(rendered.values.all { sharedId in it })
        assertTrue(agentOnlyId in rendered.getValue("agent.xml"))
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
