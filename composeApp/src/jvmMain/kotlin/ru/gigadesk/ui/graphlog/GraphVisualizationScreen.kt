package ru.gigadesk.ui.graphlog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import ru.gigadesk.agent.session.GraphSession
import ru.gigadesk.agent.session.GraphStepRecord
import ru.gigadesk.ui.glassColors
import ru.gigadesk.ui.main.RealLiquidGlassCard
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun GraphVisualizationScreen(
    session: GraphSession,
    onBack: () -> Unit,
) {
    var selectedStep by remember { mutableStateOf<GraphStepRecord?>(null) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        RealLiquidGlassCard(
            modifier = Modifier.fillMaxSize(),
            isWindowFocused = true
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Назад",
                            tint = MaterialTheme.glassColors.textPrimary
                        )
                    }
                    Column {
                        Text(
                            text = "Визуализация сессии",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.glassColors.textPrimary
                        )
                        Text(
                            text = "Сессия ${session.id.take(8)}... • ${session.steps.size} шагов",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Vertical Timeline
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(session.steps) { index, step ->
                        StepItem(
                            step = step,
                            isLast = index == session.steps.lastIndex,
                            onClick = { selectedStep = step }
                        )
                        
                        // Connector line (except for last item)
                        if (index < session.steps.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .padding(start = 20.dp) // Center of circle
                                    .width(2.dp)
                                    .height(12.dp)
                                    .background(MaterialTheme.glassColors.textPrimary.copy(alpha = 0.1f))
                            )
                        }
                    }
                }
            }
        }

        // Details Overlay
        selectedStep?.let { step ->
            StepDetailsDialog(
                step = step,
                onDismiss = { selectedStep = null }
            )
        }
    }
}

@Composable
private fun StepItem(
    step: GraphStepRecord,
    isLast: Boolean,
    onClick: () -> Unit
) {
    val cleanName = remember(step.nodeName) {
        step.nodeName.substringAfter("Node ").substringBefore(";").trim()
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Node Indicator
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(0xFF00E5FF).copy(alpha = 0.1f))
                .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${step.stepIndex}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.glassColors.textPrimary,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Node Name and Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = cleanName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.glassColors.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(step.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.4f),
                    fontSize = 10.sp
                )
            }
            
            Spacer(modifier = Modifier.height(2.dp))
            
            // Micro Summaries
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "IN: " + step.inputSummary.trim().replace("\n", " "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF00E5FF).copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
            }
            step.outputSummary?.let { out ->
                Text(
                    text = "OUT: " + out.trim().replace("\n", " "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Green.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun StepDetailsDialog(
    step: GraphStepRecord,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        RealLiquidGlassCard(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.8f)
                .padding(16.dp),
            isWindowFocused = true
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Шаг #${step.stepIndex}: ${step.nodeName.substringAfter("Node ").substringBefore(";")}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.glassColors.textPrimary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.glassColors.textPrimary
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.1f))
                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    DetailSection("Input Data", step.inputSummary)
                    
                    step.outputSummary?.let { 
                        DetailSection("Output Data", it)
                    }
                    
                    if (!step.data.isNullOrBlank()) {
                        DetailSection("Full Context (JSON)", step.data)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.8f)
        )
        
        SelectionContainer {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                    .border(1.dp, MaterialTheme.glassColors.textPrimary.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                    .padding(8.dp)
            ) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    ),
                    color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.9f)
                )
            }
        }
    }
}
