package ru.gigadesk.ui.graphlog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gigadesk.agent.session.GraphSession
import ru.gigadesk.agent.session.GraphStepRecord
import ru.gigadesk.ui.glassColors
import ru.gigadesk.ui.main.RealLiquidGlassCard
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun GraphVisualizationScreen(
    session: GraphSession,
    onBack: () -> Unit,
) {
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
                    .padding(24.dp),
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
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.glassColors.textPrimary
                        )
                        Text(
                            text = session.id.take(8) + "...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                GraphCanvas(
                    session = session,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private data class NodeData(
    val id: String,
    val label: String,
    val xRel: Float, // 0.0 to 1.0 relative to width
    val yRel: Float  // 0.0 to 1.0 relative to height
)

private val GRAPH_NODES = listOf(
    NodeData("input", "Input", 0.05f, 0.5f),
    NodeData("classify", "Consider", 0.2f, 0.5f),
    NodeData("appendActualInformation", "Context", 0.35f, 0.5f),
    NodeData("String->Request", "ToReq", 0.5f, 0.5f),
    NodeData("llmCall", "LLM", 0.65f, 0.5f),
    NodeData("toolUse", "Tools", 0.65f, 0.2f),
    NodeData("llmSummarize", "Summary", 0.65f, 0.8f),
    NodeData("Response->String", "ToString", 0.8f, 0.8f), // Changed path for better visual
    NodeData("exit", "Finish", 0.95f, 0.8f)
)

// Simplified edges definition for visualization
private val GRAPH_EDGES = listOf(
    "input" to "classify",
    "classify" to "appendActualInformation",
    "appendActualInformation" to "String->Request",
    "String->Request" to "llmCall",
    "llmCall" to "toolUse",
    "llmCall" to "llmSummarize",
    "toolUse" to "llmCall",
    "llmSummarize" to "Response->String",
    "llmCall" to "Response->String", // Direct path if no summary needed maybe? Graph logic says summarize -> exit or llm -> summarize. Let's stick to what create buildGraph says approx.
    "Response->String" to "exit"
)

@Composable
private fun GraphCanvas(
    session: GraphSession,
    modifier: Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val visitedNodes = remember(session) {
        session.steps.associateBy { it.nodeName.substringAfter("Node ").substringBefore(";").trim() }
    }
    
    // Normalize node names from session to match our static graph
    // Session records might have "Node classify; 3f2a" etc.
    // We try to match by partial name or known IDs.
    
    fun findStepForNode(nodeId: String): GraphStepRecord? {
        // Mapping static IDs to regex/substrings that might appear in logs
        val search = when(nodeId) {
            "input" -> "enter"
            "classify" -> "NodesClassification" // Check actual class name or node name
            "appendActualInformation" -> "appendActualInformation"
            "String->Request" -> "String->Request"
            "llmCall" -> "llmCall"
            "toolUse" -> "toolUse"
            "llmSummarize" -> "llmSummarize"
            "Response->String" -> "Response->String"
            "exit" -> "exit"
            else -> nodeId
        }
        
        return session.steps.lastOrNull { 
            val cleanName = it.nodeName.substringAfter("Node ").substringBefore(";").trim()
            cleanName.contains(search, ignoreCase = true) || 
            (nodeId == "classify" && it.nodeName.contains("Classif", ignoreCase = true)) ||
            (nodeId == "input" && it.nodeName.contains("enter", ignoreCase = true))
        }
    }

    var hoveredNodeId by remember { mutableStateOf<String?>(null) }
    var tooltipPos by remember { mutableStateOf(Offset.Zero) }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    // Simple hit testing could be done here if needed
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val pos = change.position
                        
                        // Check proximity to nodes
                        // We need to recalculate node positions same as in draw
                        // passing size is tricky in Modifier. 
                        // Simplified: we will use onHover in draw loop or external state
                    }
                }
            }
            // For separate hover handling we might need a Layout or BoxWithConstraints,
            // but let's try to do it in draw for simplicity of code structure 
            // OR use a Layout with Composables for Nodes.
            // Using Layout is better for interactivity.
    ) {
        val width = size.width
        val height = size.height
        val nodeRadius = 25.dp.toPx()

        // Draw Edges
        val nodePositions = GRAPH_NODES.associate { node ->
            node.id to Offset(node.xRel * width, node.yRel * height)
        }

        GRAPH_EDGES.forEach { (from, to) ->
            val start = nodePositions[from] ?: return@forEach
            val end = nodePositions[to] ?: return@forEach

            drawLine(
                color = Color.White.copy(alpha = 0.2f),
                start = start,
                end = end,
                strokeWidth = 2.dp.toPx()
            )
            
            // Draw arrow
            val angle = atan2(end.y - start.y, end.x - start.x)
            val arrowSize = 10.dp.toPx()
            val arrowEnd = Offset(
                end.x - cos(angle) * (nodeRadius + 5f),
                end.y - sin(angle) * (nodeRadius + 5f)
            )
            
            val path = Path().apply {
                moveTo(arrowEnd.x, arrowEnd.y)
                lineTo(
                    arrowEnd.x - arrowSize * cos(angle - Math.PI / 6).toFloat(),
                    arrowEnd.y - arrowSize * sin(angle - Math.PI / 6).toFloat()
                )
                lineTo(
                    arrowEnd.x - arrowSize * cos(angle + Math.PI / 6).toFloat(),
                    arrowEnd.y - arrowSize * sin(angle + Math.PI / 6).toFloat()
                )
                close()
            }
            drawPath(path, Color.White.copy(alpha = 0.2f))
        }

        // Draw Nodes
        GRAPH_NODES.forEach { node ->
            val center = nodePositions[node.id]!!
            val stepRecord = findStepForNode(node.id)
            val isVisited = stepRecord != null
            val isHovered = false // Hard to do pure canvas hover without Layout. 
            // We will switch to Layout based implementation below for interactivity.

            drawCircle(
                color = if (isVisited) Color(0xFF00E5FF).copy(alpha = 0.8f) else Color.Gray.copy(alpha = 0.3f),
                radius = nodeRadius,
                center = center
            )
            
            if (isVisited) {
                drawCircle(
                    color = Color(0xFF00E5FF).copy(alpha = 0.4f),
                    radius = nodeRadius + 4.dp.toPx(),
                    center = center,
                    style = Stroke(width = 2f)
                )
            }

            val textLayoutResult = textMeasurer.measure(
                text = node.label,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            
            drawText(
                textLayoutResult,
                topLeft = Offset(
                    center.x - textLayoutResult.size.width / 2,
                    center.y - textLayoutResult.size.height / 2
                )
            )
        }
    }
    
    // Switch to Layout approach for clickable/hoverable nodes
    BoxWithConstraints(modifier = modifier) {
        val w = maxWidth
        val h = maxHeight
        
        GRAPH_NODES.forEach { node ->
            val x = w * node.xRel
            val y = h * node.yRel
            val stepRecord = findStepForNode(node.id)
            
            Box(
                modifier = Modifier
                    .offset(
                        x = (x - 25.dp), // Centering approximation
                        y = (y - 25.dp)
                    )
                    .size(50.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .pointerInput(node.id) {
                         awaitPointerEventScope {
                             while (true) {
                                 val event = awaitPointerEvent()
                                 if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Enter) {
                                     hoveredNodeId = node.id
                                     tooltipPos = event.changes.first().position
                                 } else if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Exit) {
                                     if (hoveredNodeId == node.id) hoveredNodeId = null
                                 }
                             }
                         }
                    }
                    .background(Color.Transparent) // Transparent hit box over canvas
            )
        }
        
        // Tooltip
        hoveredNodeId?.let { nodeId ->
            val step = findStepForNode(nodeId)
            if (step != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter) // Fixed position or follow mouse
                        .padding(bottom = 32.dp)
                        .fillMaxWidth(0.9f)
                        .background(Color.Black.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
                        .border(1.dp, Color.White.copy(0.2f), RoundedCornerShape(8.dp))
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            text = "Node: ${step.nodeName}",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color(0xFF00E5FF)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Input: ${step.inputSummary}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                        Text(
                            text = "Time: ${java.time.Instant.ofEpochMilli(step.timestamp)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                }
            }
        }
    }
}
