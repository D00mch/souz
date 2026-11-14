package ru.souz.ui.graphlog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import ru.souz.agent.session.GraphSession
import ru.souz.llms.restJsonMapper
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.graph_session_export
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path

internal class GraphSessionExporter(
    private val chooseDestination: suspend (String) -> Path?,
) {
    suspend fun export(session: GraphSession): Path? {
        val safeId = session.id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        val destination = chooseDestination("souz-session-$safeId.json") ?: return null
        withContext(Dispatchers.IO) {
            Files.newBufferedWriter(destination, Charsets.UTF_8).use { writer ->
                restJsonMapper.writerWithDefaultPrettyPrinter().writeValue(writer, session)
            }
        }
        return destination
    }
}

internal suspend fun chooseGraphSessionExportDestination(owner: Window?, fileName: String): Path? =
    withContext(Dispatchers.Swing) {
        val dialog = FileDialog(owner as? Frame, getString(Res.string.graph_session_export), FileDialog.SAVE)
        try {
            dialog.file = fileName
            dialog.isVisible = true
            dialog.file?.let { Path.of(dialog.directory, it) }
        } finally {
            dialog.dispose()
        }
    }
