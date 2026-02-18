package ru.souz.ui.graphlog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.souz.agent.session.GraphSession
import ru.souz.agent.session.GraphSessionRepository
import ru.souz.ui.glassColors
import ru.souz.ui.main.RealLiquidGlassCard
import ru.souz.ui.common.DraggableWindowArea
import java.text.SimpleDateFormat
import java.util.*
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun GraphSessionsScreen(
    sessionRepository: GraphSessionRepository,
    onClose: () -> Unit,
    onSelectSession: (GraphSession) -> Unit,
) {
    val sessions by remember { mutableStateOf(sessionRepository.loadAll()) }
    val windowInfo = LocalWindowInfo.current
    val isFocused = windowInfo.isWindowFocused

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        RealLiquidGlassCard(
            modifier = Modifier.fillMaxSize(),
            isWindowFocused = isFocused
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
            ) {
                DraggableWindowArea {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(Res.string.graph_sessions_title),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.glassColors.textPrimary
                        )
                        Text(
                            text = stringResource(Res.string.graph_sessions_count_format).format(sessions.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.7f)
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(Res.string.action_close),
                            tint = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.8f)
                        )
                    }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (sessions.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(Res.string.graph_sessions_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f)
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(sessions) { session ->
                            SessionCard(session = session, onClick = { onSelectSession(session) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: GraphSession,
    onClick: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()) }
    val startDate = remember(session.startTime) { dateFormat.format(Date(session.startTime)) }
    
    val suffixS = stringResource(Res.string.duration_suffix_s)
    val statusInProgress = stringResource(Res.string.status_in_progress)
    
    val duration = remember(session.endTime, session.startTime, suffixS, statusInProgress) {
        session.endTime?.let { end ->
            val ms = end - session.startTime
            "${ms / 1000}.${(ms % 1000) / 100}$suffixS"
        } ?: statusInProgress
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = startDate,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.glassColors.textPrimary
                )
                Text(
                    text = "${stringResource(Res.string.graph_steps_count).format(session.steps.size)} • $duration",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f)
                )
            }
            
            Text(
                text = session.initialInput.take(100) + if (session.initialInput.length > 100) "..." else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            
            // Node path preview
            val nodePath = session.steps.joinToString(" → ") {
                it.nodeName.substringAfter("Node ").substringBefore(";").trim()
            }

            Text(
                text = nodePath,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF82B1FF).copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
