package ru.gigadesk.ui.graphlog

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
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
import androidx.compose.foundation.window.WindowDraggableArea
import ru.gigadesk.LocalWindowScope

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

fun processSessionData(session: GraphSession, collapsedSubgraphs: Set<String>): GraphProcessResult {
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

    // Helper to get group name (if any)
    fun getGroupName(name: String): String? {
        // Example: "Planner::Step1" -> "Planner"
        if (name.contains("::")) {
            return name.substringBefore("::")
        }
        return null
    }
    
    // Helper: Resolve effective node name (original or group name if collapsed)
    fun resolveNodeName(originalName: String): String {
        val group = getGroupName(originalName)
        if (group != null && collapsedSubgraphs.contains(group)) {
            return group // Return the group name as the node ID
        }
        return originalName
    }

    // 1. Identify all unique nodes (mapped)
    session.steps.forEach { step ->
        val rawName = step.nodeName
        val finalName = resolveNodeName(rawName)
        
        if (!nodes.containsKey(finalName)) {
             // If it's a group, we might want a special label or pos
             val isGroup = finalName != rawName
             
             nodes[finalName] = DisplayNode(
                 id = finalName,
                 label = if (isGroup) "[$finalName]" else formatLabel(finalName),
                 initialPos = mapNodeToPos(rawName), // Use rawName to guess pos
                 resolvedPos = ResolvedPos(0f, 0f, GraphNodePos.MID_CENTER),
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
            val count = groupNodes.size
            val spreadWidth = 0.15f // 15% of screen width per node
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
    session.steps.forEach { step ->
        val finalName = resolveNodeName(step.nodeName)
        val node = nodes[finalName]!!
        
        // Accumulate steps for this node (or group node)
        // We reuse the immutable DisplayNode by updating the map
        val newSteps = node.steps.toMutableList()
        newSteps.add(step)
        
        nodes[finalName] = node.copy(
            steps = newSteps,
            visitCount = newSteps.size
        )
    }

    // 4. Build edges
    if (session.steps.size > 1) {
        for (i in 0 until session.steps.size - 1) {
            val current = session.steps[i]
            val next = session.steps[i + 1]
            
            val fromId = resolveNodeName(current.nodeName)
            val toId = resolveNodeName(next.nodeName)
            
            if (fromId != toId) { // No self-loops for cleanliness, or maybe yes? Let's allow distinct nodes
                val fromNode = nodes[fromId]!!
                val toNode = nodes[toId]!!
    
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
    }

    return GraphProcessResult(nodes, edges)
}

// --- Main Screen ---

@Composable
fun GraphVisualizationScreen(
    session: GraphSession,
    onBack: () -> Unit,
) {
    var collapsedSubgraphs by remember { mutableStateOf(setOf<String>()) }
    val graphData = remember(session, collapsedSubgraphs) { 
        processSessionData(session, collapsedSubgraphs) 
    }
    
    var selectedNodeId by remember { mutableStateOf<String?>(null) }
    var selectedStep by remember { mutableStateOf<GraphStepRecord?>(null) }
    
    // Focus requester for keyboard handling
    val focusRequester = remember { FocusRequester() }

    // Auto-select first node
    LaunchedEffect(graphData) {
        // Only if nothing selected
        if (selectedNodeId == null && graphData.nodes.isNotEmpty()) {
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
    
    // Request focus for keyboard events
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    
    // Navigation helper
    fun navigateStep(delta: Int) {
        val currentIndex = session.steps.indexOf(selectedStep)
        if (currentIndex >= 0) {
            val newIndex = (currentIndex + delta).coerceIn(0, session.steps.size - 1)
            if (newIndex != currentIndex) {
                val newStep = session.steps[newIndex]
                selectedStep = newStep
                // Also update selected node if step belongs to different node
                val resolvedNodeName = graphData.nodes.keys.find { nodeId ->
                    graphData.nodes[nodeId]?.steps?.contains(newStep) == true
                }
                if (resolvedNodeName != null && resolvedNodeName != selectedNodeId) {
                    selectedNodeId = resolvedNodeName
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionUp, Key.DirectionLeft -> {
                            navigateStep(-1)
                            true
                        }
                        Key.DirectionDown, Key.DirectionRight -> {
                            navigateStep(1)
                            true
                        }
                        else -> false
                    }
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        // Main Background
        RealLiquidGlassCard(
            modifier = Modifier.fillMaxSize(),
            isWindowFocused = true
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Get WindowScope for draggable header
                val windowScope = LocalWindowScope.current
                
                // Header - Draggable area for window
                if (windowScope != null) {
                    with(windowScope) {
                        WindowDraggableArea {
                            HeaderRow(session = session, onBack = onBack)
                        }
                    }
                } else {
                    // Fallback if no WindowScope (shouldn't happen in normal use)
                    HeaderRow(session = session, onBack = onBack)
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
                            },
                            allNodes = graphData.nodes.values,
                            collapsedSubgraphs = collapsedSubgraphs,
                            onToggleSubgraph = { group ->
                                collapsedSubgraphs = if (collapsedSubgraphs.contains(group)) {
                                    collapsedSubgraphs - group
                                } else {
                                    collapsedSubgraphs + group
                                }
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
fun HeaderRow(
    session: GraphSession,
    onBack: () -> Unit
) {
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
}

@Composable
fun GraphCanvas(
    data: GraphProcessResult,
    selectedNodeId: String?,
    onNodeClick: (String) -> Unit
) {
    val density = LocalDensity.current
    
    // State for node positions (delta from initial)
    // We use a key to reset if data changes completely, but persist for same session
    val nodeOffsets = remember(data) { mutableStateMapOf<String, Offset>() }
    
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        
        // Helper to get current pos
        fun getPos(nodeId: String, initial: ResolvedPos): Offset {
             val initialX = initial.x * width
             val initialY = initial.y * height
             val offset = nodeOffsets[nodeId] ?: Offset.Zero
             return Offset(initialX + offset.x, initialY + offset.y)
        }

        // Draw Edges first
        Canvas(modifier = Modifier.fillMaxSize()) {
            data.edges.forEach { edge ->
                val start = getPos(edge.fromId, edge.fromPos)
                val end = getPos(edge.toId, edge.toPos)

                drawCurvedEdge(
                    start = start,
                    end = end,
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
             
             // Calculate final position
             val currentPos = getPos(node.id, node.resolvedPos)
             
             // Top-Left corner for Box
             val xPx = (currentPos.x - sizePx / 2).roundToInt()
             val yPx = (currentPos.y - sizePx / 2).roundToInt()

            Box(
                modifier = Modifier
                    .offset { IntOffset(xPx, yPx) }
                    .size(sizeDp)
                    // Draggable logic
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val current = nodeOffsets[node.id] ?: Offset.Zero
                            nodeOffsets[node.id] = current + dragAmount
                        }
                    }
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
    val midY = (start.y + end.y) / 2
    
    // If nodes are moved manually, standard logic might look weird.
    // Let's add simple distance check.
    val dx = end.x - start.x
    val dy = end.y - start.y
    
    // Standard routing based on semantic meaning
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
        
        // Default curve
        else -> Offset(midX, midY)
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
    onStepSelect: (GraphStepRecord) -> Unit,
    allNodes: Collection<DisplayNode>,
    collapsedSubgraphs: Set<String>,
    onToggleSubgraph: (String) -> Unit
) {
    val scrollState = rememberScrollState()

    // Scroll to selected step
    LaunchedEffect(selectedStep) {
        if (selectedStep != null && selectedNode != null) {
            val list = selectedNode.steps
            val index = list.indexOf(selectedStep)
            // Rough estimation or manual scroll? 
            // With Column, animateScrollTo is pixels, tricky to map index -> pixels without layout info.
            // For now, let's skip auto-scroll or implement it simply if possible. 
            // Actually, we can't easily auto-scroll to item index in Column. 
            // Let's rely on user scrolling or minimal auto-scroll if really needed.
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
            // Remove the aggressive pointerInput blocker as it kills scrolling
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* Consume clicks to prevent window drag on tap */ }
            .padding(16.dp)
    ) {
        // Subgraphs Control
        // Find all potential groups
        val groups = remember(allNodes) {
             allNodes.mapNotNull { 
                 if (it.label.startsWith("[") && it.label.endsWith("]")) {
                     it.label.removeSurrounding("[", "]") 
                 } else null
             }.distinct() + collapsedSubgraphs // Also include those currently collapsed
        }.distinct().sorted()

        if (groups.isNotEmpty()) {
            Text(
                text = "SUBGRAPHS",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups) { group ->
                    val isCollapsed = collapsedSubgraphs.contains(group)
                    FilterChip(
                        selected = isCollapsed,
                        onClick = { onToggleSubgraph(group) },
                        label = { Text(group) },
                        leadingIcon = {
                             if (isCollapsed) Icon(Icons.Rounded.KeyboardArrowRight, null, Modifier.size(16.dp))
                             else Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(16.dp))
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.White.copy(alpha = 0.05f),
                            labelColor = Color.White,
                            selectedContainerColor = Color(0xFF00E5FF).copy(alpha = 0.2f),
                            selectedLabelColor = Color(0xFF00E5FF)
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            Spacer(modifier = Modifier.height(16.dp))
        }

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
            // Global SelectionContainer ensures text selection works across items and persists better than inside items
            // History List
            // Global SelectionContainer ensures text selection works across items and persists better than inside items
            // Reverting to Per-Item selection for stability
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                selectedNode.steps.forEach { step ->
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
    val clipboardManager = LocalClipboardManager.current
    
    // Build copyable content
    val copyContent = remember(step) {
        buildString {
            appendLine("=== Step #${step.stepIndex}: ${step.nodeName} ===")
            appendLine()
            appendLine("INPUT:")
            appendLine(step.inputSummary.trim().ifEmpty { "-" })
            step.outputSummary?.let {
                appendLine()
                appendLine("OUTPUT:")
                appendLine(it.trim())
            }
            step.addedHistory?.let {
                appendLine()
                appendLine("SAVED TO HISTORY:")
                appendLine(it.trim())
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isExpanded) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .border(1.dp, if (isExpanded) Color(0xFF00E5FF).copy(0.3f) else Color.White.copy(0.05f), RoundedCornerShape(8.dp))
    ) {
        // Header (Clickable)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Execution #${step.stepIndex}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.weight(1f)
            )
            
            // Copy button
            IconButton(
                onClick = { clipboardManager.setText(AnnotatedString(copyContent)) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = "Copy content",
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(14.dp)
                )
            }
            
            Icon(
                imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
        }

        if (isExpanded) {
            // Details
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Classification Result - shown for classify nodes
                    val isClassifyStep = step.nodeName.lowercase().contains("classify") || 
                                         step.nodeName.lowercase().contains("классифик")
                    if (isClassifyStep && !step.outputSummary.isNullOrEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("CLASSIFICATION RESULT", style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Gray))
                            Text(
                                text = step.outputSummary.trim(),
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color(0xFFA5D6A7) // Same green as OUTPUT
                                )
                            )
                        }
                    }
                    
                    // Diff View (Input vs Output) - only for non-classify or when no classification shown
                    if (!isClassifyStep && !step.outputSummary.isNullOrEmpty() && step.inputSummary != step.outputSummary) {
                        Text("IO DIFF", style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Gray))
                        DiffContent(original = step.inputSummary, revised = step.outputSummary)
                    } else if (!isClassifyStep || step.outputSummary.isNullOrEmpty()) {
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
                        
                        // Output (only for non-classify, since classify shows it as classification result)
                        if (!step.outputSummary.isNullOrEmpty() && !isClassifyStep) {
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
                    }

                    // History Delta
                    if (!step.addedHistory.isNullOrEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("SAVED TO HISTORY", style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Gray))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF101010), RoundedCornerShape(4.dp))
                                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = step.addedHistory,
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = Color(0xFFFFCC80)
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
fun DiffContent(original: String, revised: String) {
    val diff = remember(original, revised) {
        val generator = com.github.difflib.text.DiffRowGenerator.create()
            .showInlineDiffs(true)
            .mergeOriginalRevised(true)
            .inlineDiffByWord(true)
            .ignoreWhiteSpaces(true) // Ignore whitespace
            .oldTag { _ -> "" } // No markdown tags, we handle colors
            .newTag { _ -> "" } 
            .build()
        generator.generateDiffRows(
            original.lines(),
            revised.lines()
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            .padding(8.dp)
    ) {
        diff.forEach { row ->
             val oldLine = row.oldLine
             val newLine = row.newLine
             
             if (oldLine == newLine) {
                 // Unchanged - Show context if needed, or skip for brevity? 
                 // For now show generic grey
                 Text(
                     text = "  $oldLine",
                     style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.Gray.copy(0.5f))
                 )
             } else {
                 // Semantic comparison
                 if (oldLine.isNotBlank()) {
                     Text(
                        text = "- $oldLine",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFFF8A80)) // Softer Red
                     )
                 }
                 if (newLine.isNotBlank()) {
                     Text(
                        text = "+ $newLine",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFB9F6CA)) // Softer Green
                     )
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
