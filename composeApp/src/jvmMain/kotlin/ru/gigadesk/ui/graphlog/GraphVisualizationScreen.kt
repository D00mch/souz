package ru.gigadesk.ui.graphlog

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gigadesk.agent.session.GraphSession
import ru.gigadesk.agent.session.GraphStepRecord
import ru.gigadesk.ui.glassColors
import ru.gigadesk.ui.main.RealLiquidGlassCard
import java.util.*
import kotlin.math.roundToInt
import kotlin.math.cos
import kotlin.math.sin

// --- Data Models for Visualization ---

// Initial semantic positions
enum class GraphNodePos(val xPercent: Float, val yPercent: Float) {
    TOP_CENTER(0.5f, 0.12f),     
    MID_CENTER(0.5f, 0.40f),    
    LEFT_CENTER(0.2f, 0.65f),    
    RIGHT_CENTER(0.8f, 0.65f),   
    BOTTOM_CENTER(0.5f, 0.88f)
}

// Actual calculated position (can be offset)
data class ResolvedPos(val x: Float, val y: Float, val basePos: GraphNodePos)

data class DisplayNode(
    val id: String,
    val label: String,
    val initialPos: GraphNodePos,
    var resolvedPos: ResolvedPos, // Changed to var for layout pass
    val steps: List<GraphStepRecord>,
    val visitCount: Int
)

data class GraphEdge(
    val fromId: String,
    val toId: String,
    val fromPos: ResolvedPos,
    val toPos: ResolvedPos,
    val stepIndex: Int,
    val isHighlighted: Boolean
)

data class GraphProcessResult(
    val nodes: Map<String, DisplayNode>,
    val edges: List<GraphEdge>
)

// --- Helper Logic ---

fun processSessionData(session: GraphSession): GraphProcessResult {
    val nodes = linkedMapOf<String, DisplayNode>()
    val edges = mutableListOf<GraphEdge>()

    // Improved mapping logic
    fun mapNodeToPos(name: String): GraphNodePos {
        val normalized = name.lowercase()
        return when {
            normalized.contains("agent::enter") -> GraphNodePos.TOP_CENTER
            normalized.contains("classify") -> GraphNodePos.MID_CENTER
            normalized.contains("llm") || normalized.contains("call") -> GraphNodePos.LEFT_CENTER
            normalized.contains("tool") -> GraphNodePos.RIGHT_CENTER
            // "Go to user" is definitely exit/bottom
            normalized.contains("exit") || normalized.contains("user") -> GraphNodePos.BOTTOM_CENTER
            else -> GraphNodePos.MID_CENTER // Stack here to be resolved later
        }
    }
    
    // Aggressive Name Cleanup
    fun formatLabel(rawName: String): String {
        var cleaner = rawName
            .replace("Agent::", "")
            .replace("Go to user::", "User ") 
            .replace("Node ", "")
        
        cleaner = cleaner.replace("->", " → ")
        cleaner = cleaner.substringBefore(";")
        cleaner = cleaner.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        
        return cleaner.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    // 1. Identify all unique nodes
    session.steps.forEach { step ->
        val name = step.nodeName
        if (!nodes.containsKey(name)) {
             nodes[name] = DisplayNode(
                 id = name,
                 label = formatLabel(name),
                 initialPos = mapNodeToPos(name),
                 resolvedPos = ResolvedPos(0f, 0f, GraphNodePos.MID_CENTER), // Placeholder
                 steps = mutableListOf(),
                 visitCount = 0
             )
        }
    }

    // 2. Collision Resolution / Spreading
    // Group nodes by their semantic position
    val grouped = nodes.values.groupBy { it.initialPos }
    
    grouped.forEach { (basePos, groupNodes) ->
        if (groupNodes.size == 1) {
            // No collision, just use base pos
            val node = groupNodes.first()
            node.resolvedPos = ResolvedPos(basePos.xPercent, basePos.yPercent, basePos)
        } else {
            // Collision! Spread them out.
            // Technique: Lay them out in a horizontal row or small arc around the center
            val count = groupNodes.size
            val spreadWidth = 0.15f // 15% of screen width per node? maybe less
            val startX = basePos.xPercent - ((count - 1) * spreadWidth / 2)
            
            groupNodes.forEachIndexed { index, node ->
                val offsetX = startX + (index * spreadWidth)
                // Add slight vertical stagger to prevent perfect line overlap if edges connect neighbors
                val offsetY = basePos.yPercent + if(index % 2 == 0) 0.0f else 0.05f 
                
                node.resolvedPos = ResolvedPos(offsetX, offsetY, basePos)
            }
        }
    }

    // 3. Populate steps and counts
    session.steps.groupBy { it.nodeName }.forEach { (name, steps) ->
        val node = nodes[name]!!
        // We need to construct a new object or use a mutable one. 
        // DisplayNode uses val for properties, but we can fake update or rely on object identity if we were using classes.
        // Since using data classes, better to update the map entry.
        nodes[name] = node.copy(
            steps = steps,
            visitCount = steps.size
        )
    }

    // 4. Build edges
    if (session.steps.size > 1) {
        for (i in 0 until session.steps.size - 1) {
            val current = session.steps[i]
            val next = session.steps[i + 1]
            val fromNode = nodes[current.nodeName]!!
            val toNode = nodes[next.nodeName]!!

            edges.add(
                GraphEdge(
                    fromId = fromNode.id,
                    toId = toNode.id,
                    fromPos = fromNode.resolvedPos,
                    toPos = toNode.resolvedPos,
                    stepIndex = i + 1,
                    isHighlighted = true
                )
            )
        }
    }

    return GraphProcessResult(nodes, edges)
}

// --- Main Screen ---

@Composable
fun GraphVisualizationScreen(
    session: GraphSession,
    onBack: () -> Unit,
) {
    val graphData = remember(session) { processSessionData(session) }
    var selectedNodeId by remember { mutableStateOf<String?>(null) }
    var selectedStep by remember { mutableStateOf<GraphStepRecord?>(null) }

    // Auto-select first node
    LaunchedEffect(Unit) {
        if (graphData.nodes.isNotEmpty()) {
            selectedNodeId = graphData.nodes.keys.firstOrNull()
        }
    }
    
    // Update selected step when node changes
    LaunchedEffect(selectedNodeId) {
        selectedNodeId?.let { id ->
            val node = graphData.nodes[id]
            if (node != null && node.steps.isNotEmpty()) {
                 if (selectedStep?.nodeName != id) {
                    selectedStep = node.steps.last()
                 }
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Main Background
        RealLiquidGlassCard(
            modifier = Modifier.fillMaxSize(),
            isWindowFocused = true
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.glassColors.textPrimary
                        )
                    }
                    Column {
                        Text(
                            text = "Session Visualization",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.glassColors.textPrimary
                        )
                        Text(
                            text = "${session.id.take(8)}... • ${session.steps.size} steps",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.glassColors.textPrimary.copy(alpha = 0.6f)
                        )
                    }
                }

                // Main Content Split
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                ) {
                    // LEFT: Graph Canvas
                    Box(
                        modifier = Modifier
                            .weight(0.7f)
                            .fillMaxHeight()
                    ) {
                        GraphCanvas(
                            data = graphData,
                            selectedNodeId = selectedNodeId,
                            onNodeClick = { selectedNodeId = it }
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // RIGHT: Details Panel
                    Box(
                        modifier = Modifier
                            .weight(0.3f)
                            .fillMaxHeight()
                    ) {
                        SideDetailsPanel(
                            selectedNode = selectedNodeId?.let { graphData.nodes[it] },
                            selectedStep = selectedStep,
                            onStepSelect = { step ->
                                selectedStep = if (selectedStep == step) null else step
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // BOTTOM: Timeline Strip
                TimelineStrip(
                    steps = session.steps,
                    selectedStep = selectedStep,
                    onStepClick = { step ->
                        selectedStep = step
                        selectedNodeId = step.nodeName
                    }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

// --- Components ---

@Composable
fun GraphCanvas(
    data: GraphProcessResult,
    selectedNodeId: String?,
    onNodeClick: (String) -> Unit
) {
    val density = LocalDensity.current
    
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        
        // Draw Edges first
        Canvas(modifier = Modifier.fillMaxSize()) {
            data.edges.forEach { edge ->
                val startX = edge.fromPos.x * width
                val startY = edge.fromPos.y * height
                val endX = edge.toPos.x * width
                val endY = edge.toPos.y * height

                drawCurvedEdge(
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    fromPos = edge.fromPos,
                    toPos = edge.toPos,
                    highlighted = edge.isHighlighted
                )
            }
        }

        // Draw Nodes
        data.nodes.values.forEach { node ->
             val isSelected = selectedNodeId == node.id
             
             // Circle Size
             val sizeDp = 90.dp
             val sizePx = with(density) { sizeDp.toPx() }
             
             val xPx = (node.resolvedPos.x * width).roundToInt() - (sizePx / 2).roundToInt()
             val yPx = (node.resolvedPos.y * height).roundToInt() - (sizePx / 2).roundToInt()

            Box(
                modifier = Modifier
                    .offset { IntOffset(xPx, yPx) }
                    .size(sizeDp)
            ) {
                 CircularNodeItem(
                     label = node.label,
                     count = node.visitCount,
                     isSelected = isSelected,
                     onClick = { onNodeClick(node.id) }
                 )
            }
        }
    }
}

fun calculateControlPoint(start: Offset, end: Offset, fromPos: ResolvedPos, toPos: ResolvedPos): Offset {
    val midX = (start.x + end.x) / 2
    
    // Use basePos for generic logic to keep routing sane even for spread nodes
    return when {
        // Loop: Left -> Right (LLM -> Tool)
        (fromPos.basePos == GraphNodePos.LEFT_CENTER && toPos.basePos == GraphNodePos.RIGHT_CENTER) -> {
             Offset(midX, start.y - 180f) 
        }
        // Loop: Right -> Left (Tool -> LLM)
        (fromPos.basePos == GraphNodePos.RIGHT_CENTER && toPos.basePos == GraphNodePos.LEFT_CENTER) -> {
             Offset(midX, start.y + 180f)
        }
        
        // Same row logic (if we spread horizontally in mid center and connect them)
        (fromPos.basePos == GraphNodePos.MID_CENTER && toPos.basePos == GraphNodePos.MID_CENTER) -> {
             Offset(midX, start.y - 80f) // Small arc between neighbors
        }

        else -> Offset(midX, (start.y + end.y)/2)
    }
}

fun DrawScope.drawCurvedEdge(start: Offset, end: Offset, fromPos: ResolvedPos, toPos: ResolvedPos, highlighted: Boolean) {
    val path = Path()
    path.moveTo(start.x, start.y)
    
    val control = calculateControlPoint(start, end, fromPos, toPos)

    path.quadraticBezierTo(control.x, control.y, end.x, end.y)

    val color = if (highlighted) Color(0xFF00E5FF) else Color.Gray.copy(alpha = 0.3f)
    val alpha = if (highlighted) 0.5f else 0.2f
    val strokeWidth = if (highlighted) 2.dp.toPx() else 1.dp.toPx()

    drawPath(
        path = path,
        color = color,
        alpha = alpha,
        style = Stroke(width = strokeWidth)
    )
}


@Composable
fun CircularNodeItem(
    label: String,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val glowColor = Color(0xFF00E5FF)
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Stack effect
        if (count > 1) {
             Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(x = 3.dp, y = 3.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.1f), CircleShape)
            )
        }

        // Main Circle
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(
                    if (isSelected) glowColor.copy(alpha = 0.1f) 
                    else Color(0xFF1E1E1E).copy(alpha = 0.95f) 
                )
                .border(
                    if (isSelected) 2.dp else 1.dp,
                    if (isSelected) glowColor else Color.White.copy(alpha = 0.2f),
                    CircleShape
                )
                .shadow(if (isSelected) 12.dp else 0.dp, CircleShape, spotColor = glowColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) glowColor else Color.White.copy(alpha = 0.9f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 12.sp,
                modifier = Modifier.padding(horizontal = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                fontSize = 11.sp
            )
        }
        
        // Badge
        if (count > 0) {
             Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(Color(0xFF2C2C2C))
                    .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            }
        }
    }
}

// --- Side Panel ---

@Composable
fun SideDetailsPanel(
    selectedNode: DisplayNode?,
    selectedStep: GraphStepRecord?,
    onStepSelect: (GraphStepRecord) -> Unit
) {
    val listState = rememberLazyListState()

    // Scroll to selected step
    LaunchedEffect(selectedStep) {
        if (selectedStep != null && selectedNode != null) {
            val reversedList = selectedNode.steps.asReversed()
            val index = reversedList.indexOf(selectedStep)
            if (index >= 0) {
                listState.animateScrollToItem(index)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        if (selectedNode == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Select a node",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        } else {
            // Title
            Text(
                text = selectedNode.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "${selectedNode.visitCount} executions",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            Spacer(modifier = Modifier.height(16.dp))

            // History List
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(selectedNode.steps.asReversed()) { step ->
                    ExpandableStepItem(
                        step = step,
                        isExpanded = step == selectedStep,
                        onToggle = { onStepSelect(step) }
                    )
                }
            }
        }
    }
}

@Composable
fun ExpandableStepItem(
    step: GraphStepRecord,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isExpanded) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .border(1.dp, if (isExpanded) Color(0xFF00E5FF).copy(0.3f) else Color.White.copy(0.05f), RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Execution #${step.stepIndex}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.9f)
            )
            Icon(
                imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
        }
        
        if (isExpanded) {
            Spacer(modifier = Modifier.height(12.dp))
            
            // Details
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Input
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                         Text("INPUT", style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Gray))
                         Text(
                             text = step.inputSummary.trim().ifEmpty { "-" }, 
                             style = TextStyle(
                                 fontFamily = FontFamily.Monospace, 
                                 fontSize = 11.sp, 
                                 color = Color(0xFF81D4FA)
                             )
                         )
                    }
                    
                    // Output
                    if (!step.outputSummary.isNullOrEmpty()) {
                         Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                             Text("OUTPUT", style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Gray))
                             Text(
                                 text = step.outputSummary.trim(), 
                                 style = TextStyle(
                                     fontFamily = FontFamily.Monospace, 
                                     fontSize = 11.sp, 
                                     color = Color(0xFFA5D6A7)
                                 )
                             )
                         }
                    }
                    
                    // JSON Dump / Payload
                    if (step.data.isNotEmpty()) {
                         Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("PAYLOAD / DATA", style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Gray))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF101010), RoundedCornerShape(4.dp))
                                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            ) {
                                 Text(
                                    text = step.data.take(500) + if(step.data.length > 500) "..." else "",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace, 
                                        fontSize = 10.sp, 
                                        color = Color.LightGray.copy(alpha = 0.7f)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TimelineStrip(
    steps: List<GraphStepRecord>,
    selectedStep: GraphStepRecord?,
    onStepClick: (GraphStepRecord) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Prev Button
        IconButton(
            onClick = {
                val currentIndex = steps.indexOf(selectedStep)
                if (currentIndex > 0) {
                    onStepClick(steps[currentIndex - 1])
                }
            },
            enabled = (steps.indexOf(selectedStep) > 0)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack, // Or appropriate Prev icon
                contentDescription = "Previous Step",
                tint = if (steps.indexOf(selectedStep) > 0) MaterialTheme.glassColors.textPrimary else MaterialTheme.glassColors.textPrimary.copy(alpha = 0.3f)
            )
        }

        // Timeline Scroll
        Row(
            modifier = Modifier
                .weight(1f)
                .height(24.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            steps.forEach { step ->
                val isSelected = step == selectedStep
                Box(
                    modifier = Modifier
                        .width(16.dp)
                        .fillMaxHeight()
                        .padding(horizontal = 2.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (isSelected) Color(0xFF00E5FF) 
                            else Color.White.copy(alpha = 0.2f)
                        )
                        .clickable { onStepClick(step) }
                )
            }
        }
        
        // Next Button
        IconButton(
            onClick = {
                val currentIndex = steps.indexOf(selectedStep)
                if (currentIndex >= 0 && currentIndex < steps.size - 1) {
                    onStepClick(steps[currentIndex + 1])
                }
            },
            enabled = (steps.indexOf(selectedStep) < steps.size - 1)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward, // Need ArrowForward
                contentDescription = "Next Step",
                tint = if (steps.indexOf(selectedStep) < steps.size - 1) MaterialTheme.glassColors.textPrimary else MaterialTheme.glassColors.textPrimary.copy(alpha = 0.3f)
            )
        }
    }

}
