package ru.gigadesk.ui.graphlog

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import ru.gigadesk.ui.common.DraggableWindowArea
import kotlin.math.roundToInt
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import androidx.compose.material.icons.rounded.Check

private val jsonMapper = ObjectMapper()

// Layout node for force-directed algorithm
data class LayoutNode(
    val id: String,
    var x: Float,      // 0..1 normalized
    var y: Float,      // 0..1 normalized  
    var layer: Int,
    var indexInLayer: Int
)

data class ResolvedPos(val x: Float, val y: Float, val layer: Int = 0)

data class DisplayNode(
    val id: String,
    val label: String,
    var resolvedPos: ResolvedPos,
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

private data class ActiveToolsDiff(
    val before: List<String>,
    val after: List<String>,
    val added: List<String>,
    val removed: List<String>,
)

private fun extractActiveToolsDiff(data: String): ActiveToolsDiff? {
    return try {
        val root = jsonMapper.readTree(data)
        val beforeTools = parseActiveTools(root.get("in")?.get("activeTools")).orEmpty()
        val afterTools = parseActiveTools(root.get("out")?.get("activeTools")).orEmpty()

        if (beforeTools.isEmpty() && afterTools.isEmpty()) {
            return null
        }

        val beforeSet = beforeTools.toSet()
        val afterSet = afterTools.toSet()
        val added = afterTools.filterNot { it in beforeSet }
        val removed = beforeTools.filterNot { it in afterSet }

        if (added.isEmpty() && removed.isEmpty()) {
            null
        } else {
            ActiveToolsDiff(before = beforeTools, after = afterTools, added = added, removed = removed)
        }
    } catch (_: Exception) {
        null
    }
}

private fun parseActiveTools(node: JsonNode?): List<String>? {
    if (node == null || !node.isArray) return null
    return node.mapNotNull { tool ->
        tool.asText(null)?.trim()?.takeIf { it.isNotEmpty() }
    }
}

// ============= Force-Directed Layout Algorithm =============

/**
 * Calculate graph layout using force-directed algorithm with topological layering
 */
private fun calculateGraphLayout(
    nodeIds: List<String>,
    edges: List<Pair<String, String>>
): Map<String, LayoutNode> {
    if (nodeIds.isEmpty()) return emptyMap()
    
    // Build adjacency lists
    val successors = mutableMapOf<String, MutableList<String>>()
    val predecessors = mutableMapOf<String, MutableList<String>>()
    nodeIds.forEach { 
        successors[it] = mutableListOf()
        predecessors[it] = mutableListOf()
    }
    edges.forEach { (from, to) ->
        successors[from]?.add(to)
        predecessors[to]?.add(from)
    }
    
    // Step 1: Assign layers via BFS (topological layering)
    val layers = assignLayers(nodeIds, successors, predecessors)
    
    // Step 2: Create layout nodes with zigzag positioning (alternate left/right per layer)
    val layoutNodes = mutableMapOf<String, LayoutNode>()
    val layerGroups = nodeIds.groupBy { layers[it] ?: 0 }
    val maxLayer = layerGroups.keys.maxOrNull() ?: 0
    
    // Layout parameters
    val layerSpacing = if (maxLayer == 0) 0f else 0.75f / maxLayer
    val leftX = 0.25f    // Left side position
    val rightX = 0.75f   // Right side position
    val centerX = 0.50f  // Center position (for first/last nodes)
    
    layerGroups.forEach { (layer, nodesInLayer) ->
        val yPos = if (maxLayer == 0) 0.5f else 0.10f + (layer.toFloat() * layerSpacing)
        
        if (nodesInLayer.size == 1) {
            // Single node per layer: zigzag between left and right
            // First layer (entry) and last layer (exit) are centered
            val xPos = when {
                layer == 0 -> centerX           // Entry node centered
                layer == maxLayer -> centerX     // Exit node centered
                layer % 2 == 1 -> rightX         // Odd layers: right
                else -> leftX                    // Even layers: left
            }
            layoutNodes[nodesInLayer[0]] = LayoutNode(nodesInLayer[0], xPos, yPos, layer, 0)
        } else {
            // Multiple nodes in layer: spread horizontally with zigzag Y-offset
            val zigzagY = 0.03f
            nodesInLayer.forEachIndexed { index, nodeId ->
                val xPos = 0.15f + (index.toFloat() / (nodesInLayer.size - 1)) * 0.70f
                val yOffset = if (index % 2 == 0) 0f else zigzagY
                layoutNodes[nodeId] = LayoutNode(nodeId, xPos, yPos + yOffset, layer, index)
            }
        }
    }
    
    // Step 3: Barycenter ordering to minimize edge crossings
    repeat(3) {
        barycenterOrdering(layoutNodes, layerGroups.keys.sorted(), successors, predecessors)
    }
    
    // Step 4: Apply force simulation for fine-tuning
    applyForceSimulation(layoutNodes, edges, layerGroups)
    
    return layoutNodes
}

/**
 * Assign layers to nodes using BFS from entry nodes
 */
private fun assignLayers(
    nodeIds: List<String>,
    successors: Map<String, List<String>>,
    predecessors: Map<String, List<String>>
): Map<String, Int> {
    val layers = mutableMapOf<String, Int>()
    
    // Find entry nodes (nodes with no predecessors, or first node if all have predecessors)
    val entryNodes = nodeIds.filter { predecessors[it]?.isEmpty() == true }
        .ifEmpty { listOf(nodeIds.first()) }
    
    // BFS to assign layers
    val queue = ArrayDeque<String>()
    entryNodes.forEach { 
        layers[it] = 0
        queue.add(it)
    }
    
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        val currentLayer = layers[current] ?: 0
        
        successors[current]?.forEach { next ->
            val existingLayer = layers[next]
            if (existingLayer == null || existingLayer < currentLayer + 1) {
                layers[next] = currentLayer + 1
                if (existingLayer == null) {
                    queue.add(next)
                }
            }
        }
    }
    
    // Assign remaining nodes to layer 0 if not visited
    nodeIds.forEach { if (!layers.containsKey(it)) layers[it] = 0 }
    
    return layers
}

/**
 * Barycenter ordering - reorder nodes within layers to minimize edge crossings
 */
private fun barycenterOrdering(
    layoutNodes: MutableMap<String, LayoutNode>,
    sortedLayers: List<Int>,
    successors: Map<String, List<String>>,
    predecessors: Map<String, List<String>>
) {
    val zigzagOffset = 0.04f
    
    // Forward pass: order by barycenter of predecessors
    sortedLayers.forEach { layer ->
        val nodesInLayer = layoutNodes.values.filter { it.layer == layer }
        if (nodesInLayer.size <= 1) return@forEach
        
        val barycenters = nodesInLayer.associateWith { node ->
            val preds = predecessors[node.id] ?: emptyList()
            if (preds.isEmpty()) node.x
            else preds.mapNotNull { layoutNodes[it]?.x }.average().toFloat()
        }
        
        val sorted = nodesInLayer.sortedBy { barycenters[it] }
        val baseY = sorted.first().y - if (sorted.first().indexInLayer % 2 == 0) 0f else zigzagOffset
        
        sorted.forEachIndexed { index, node ->
            node.indexInLayer = index
            node.x = if (sorted.size == 1) 0.5f 
                     else 0.10f + (index.toFloat() / (sorted.size - 1)) * 0.80f
            // Maintain zigzag pattern
            node.y = baseY + if (index % 2 == 0) 0f else zigzagOffset
        }
    }
    
    // Backward pass: order by barycenter of successors
    sortedLayers.reversed().forEach { layer ->
        val nodesInLayer = layoutNodes.values.filter { it.layer == layer }
        if (nodesInLayer.size <= 1) return@forEach
        
        val barycenters = nodesInLayer.associateWith { node ->
            val succs = successors[node.id] ?: emptyList()
            if (succs.isEmpty()) node.x
            else succs.mapNotNull { layoutNodes[it]?.x }.average().toFloat()
        }
        
        val sorted = nodesInLayer.sortedBy { barycenters[it] }
        val baseY = sorted.first().y - if (sorted.first().indexInLayer % 2 == 0) 0f else zigzagOffset
        
        sorted.forEachIndexed { index, node ->
            node.indexInLayer = index
            node.x = if (sorted.size == 1) 0.5f 
                     else 0.10f + (index.toFloat() / (sorted.size - 1)) * 0.80f
            // Maintain zigzag pattern
            node.y = baseY + if (index % 2 == 0) 0f else zigzagOffset
        }
    }
}

/**
 * Apply force simulation for fine-tuning positions
 */
private fun applyForceSimulation(
    layoutNodes: MutableMap<String, LayoutNode>,
    edges: List<Pair<String, String>>,
    layerGroups: Map<Int, List<String>>
) {
    val iterations = 80
    val repulsionStrength = 0.05f
    val minDistance = 0.18f  // Minimum distance between nodes (increased)
    
    repeat(iterations) { iteration ->
        val damping = 1f - (iteration.toFloat() / iterations) * 0.5f
        
        // Apply repulsion forces (only within same layer to maintain layering)
        layerGroups.values.forEach { nodesInLayer ->
            if (nodesInLayer.size < 2) return@forEach
            
            for (i in nodesInLayer.indices) {
                for (j in i + 1 until nodesInLayer.size) {
                    val node1 = layoutNodes[nodesInLayer[i]] ?: continue
                    val node2 = layoutNodes[nodesInLayer[j]] ?: continue
                    
                    val dx = node2.x - node1.x
                    val distance = kotlin.math.abs(dx).coerceAtLeast(0.01f)
                    
                    if (distance < minDistance) {
                        val force = repulsionStrength * (minDistance - distance) / distance * damping
                        val moveAmount = force / 2
                        
                        node1.x = (node1.x - moveAmount * kotlin.math.sign(dx)).coerceIn(0.05f, 0.95f)
                        node2.x = (node2.x + moveAmount * kotlin.math.sign(dx)).coerceIn(0.05f, 0.95f)
                    }
                }
            }
        }
    }
}

fun processSessionData(session: GraphSession, collapsedSubgraphs: Set<String>): GraphProcessResult {
    val nodes = linkedMapOf<String, DisplayNode>()
    val edges = mutableListOf<GraphEdge>()

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

    fun getGroupName(name: String): String? {
        if (name.contains("::")) {
            return name.substringBefore("::")
        }
        return null
    }

    fun resolveNodeName(originalName: String): String {
        val group = getGroupName(originalName)
        if (group != null && collapsedSubgraphs.contains(group)) {
            return group
        }
        return originalName
    }

    // Collect unique node IDs in order of appearance
    val nodeIds = mutableListOf<String>()
    session.steps.forEach { step ->
        val finalName = resolveNodeName(step.nodeName)
        if (!nodeIds.contains(finalName)) {
            nodeIds.add(finalName)
        }
    }
    
    // Collect edges for layout algorithm
    val layoutEdges = mutableListOf<Pair<String, String>>()
    if (session.steps.size > 1) {
        for (i in 0 until session.steps.size - 1) {
            val fromId = resolveNodeName(session.steps[i].nodeName)
            val toId = resolveNodeName(session.steps[i + 1].nodeName)
            if (fromId != toId && !layoutEdges.contains(fromId to toId)) {
                layoutEdges.add(fromId to toId)
            }
        }
    }
    
    // Calculate layout using force-directed algorithm
    val layout = calculateGraphLayout(nodeIds, layoutEdges)
    
    // Create DisplayNodes with calculated positions
    session.steps.forEach { step ->
        val rawName = step.nodeName
        val finalName = resolveNodeName(rawName)
        
        if (!nodes.containsKey(finalName)) {
            val isGroup = finalName != rawName
            val layoutNode = layout[finalName]
            
            nodes[finalName] = DisplayNode(
                id = finalName,
                label = if (isGroup) "[$finalName]" else formatLabel(finalName),
                resolvedPos = ResolvedPos(
                    x = layoutNode?.x ?: 0.5f,
                    y = layoutNode?.y ?: 0.5f,
                    layer = layoutNode?.layer ?: 0
                ),
                steps = mutableListOf(),
                visitCount = 0
            )
        }
    }

    // Populate steps for each node
    session.steps.forEach { step ->
        val finalName = resolveNodeName(step.nodeName)
        val node = nodes[finalName]!!

        val newSteps = node.steps.toMutableList()
        newSteps.add(step)
        
        nodes[finalName] = node.copy(
            steps = newSteps,
            visitCount = newSteps.size
        )
    }

    // Create edges with calculated positions
    if (session.steps.size > 1) {
        for (i in 0 until session.steps.size - 1) {
            val current = session.steps[i]
            val next = session.steps[i + 1]
            
            val fromId = resolveNodeName(current.nodeName)
            val toId = resolveNodeName(next.nodeName)
            
            if (fromId != toId) {
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

    // Derive available groups from raw session steps (scanning for "Group::Node" pattern)
    val allSessionGroups = remember(session.steps) {
        session.steps.mapNotNull { step ->
            if (step.nodeName.contains("::")) {
                step.nodeName.substringBefore("::")
            } else null
        }.distinct().sorted()
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
    val navigateStep = remember(session, graphData) {
        { delta: Int ->
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
                // Header - Draggable area for window
                DraggableWindowArea {
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
                            availableGroups = allSessionGroups,
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
                        // Find node in graphData that contains this step (resolves to group ID if collapsed)
                        val foundId = graphData.nodes.entries.find { (_, node) ->
                            node.steps.contains(step) 
                        }?.key ?: step.nodeName
                        selectedNodeId = foundId
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

        data.nodes.values.forEach { node ->
             val isSelected = selectedNodeId == node.id

             val sizeDp = 90.dp
             val sizePx = with(density) { sizeDp.toPx() }

             val currentPos = getPos(node.id, node.resolvedPos)

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
    
    val dx = end.x - start.x
    val dy = end.y - start.y
    
    // If nodes are on the same layer (horizontal edge)
    if (fromPos.layer == toPos.layer) {
        // Curve upward for same-layer edges to avoid overlap with nodes
        val curvature = kotlin.math.abs(dx) * 0.3f
        return Offset(midX, minOf(start.y, end.y) - curvature.coerceIn(40f, 150f))
    }
    
    // If edge goes backward (from higher layer to lower layer)
    if (fromPos.layer > toPos.layer) {
        // Curve to the side to make backward edges visible
        val sideOffset = if (start.x < end.x) -100f else 100f
        return Offset(midX + sideOffset, midY)
    }
    
    // Normal forward edges (from lower layer to higher layer)
    // Use slight curve based on horizontal distance to avoid edge overlaps
    val horizontalOffset = dx * 0.2f
    return Offset(midX + horizontalOffset, midY)
}

fun DrawScope.drawCurvedEdge(start: Offset, end: Offset, fromPos: ResolvedPos, toPos: ResolvedPos, highlighted: Boolean) {
    val path = Path()
    path.moveTo(start.x, start.y)
    
    val control = calculateControlPoint(start, end, fromPos, toPos)

    path.quadraticTo(control.x, control.y, end.x, end.y)

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

@Composable
fun SideDetailsPanel(
    selectedNode: DisplayNode?,
    selectedStep: GraphStepRecord?,
    onStepSelect: (GraphStepRecord) -> Unit,
    availableGroups: List<String>,
    collapsedSubgraphs: Set<String>,
    onToggleSubgraph: (String) -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* Consume clicks to prevent window drag on tap */ }
            .padding(16.dp)
    ) {
        val groups = remember(availableGroups, collapsedSubgraphs) {
             (availableGroups + collapsedSubgraphs).distinct().sorted()
        }

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
    var isCopied by remember { mutableStateOf(false) }
    val activeToolsDiff = remember(step.data) { extractActiveToolsDiff(step.data) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            kotlinx.coroutines.delay(2000)
            isCopied = false
        }
    }

    val copyContent = remember(step, activeToolsDiff) {
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
            activeToolsDiff?.let {
                appendLine()
                appendLine("ACTIVE TOOLS CHANGED:")
                if (it.added.isNotEmpty()) {
                    appendLine("+ ${it.added.joinToString(", ")}")
                }
                if (it.removed.isNotEmpty()) {
                    appendLine("- ${it.removed.joinToString(", ")}")
                }
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
                onClick = { 
                    clipboardManager.setText(AnnotatedString(copyContent))
                    isCopied = true
                },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = if (isCopied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = if (isCopied) "Copied" else "Copy content",
                    tint = if (isCopied) Color(0xFF66BB6A) else Color.White.copy(alpha = 0.5f),
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
                    val isClassifyStep = step.nodeName.lowercase().contains("classify") || 
                                         step.nodeName.lowercase().contains("классифик")
                    if (isClassifyStep) {
                        val selectedCategories = remember(step.data) {
                            try {
                                val jsonNode = jsonMapper.readTree(step.data)
                                val categoriesNode = jsonNode.get("selectedCategories")
                                if (categoriesNode != null && categoriesNode.isArray) {
                                    categoriesNode.map { it.asText() }
                                } else null
                            } catch (e: Exception) {
                                null
                            }
                        }
                        
                        if (selectedCategories != null && selectedCategories.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("CATEGORIES", style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Gray))
                                Text(
                                    text = selectedCategories.joinToString(", "),
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = Color(0xFFA5D6A7)
                                    )
                                )
                            }
                        }
                    }
                    
                    if (activeToolsDiff != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "ACTIVE TOOLS CHANGED",
                                style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            )
                            if (activeToolsDiff.added.isNotEmpty()) {
                                Text(
                                    text = "+ ${activeToolsDiff.added.joinToString(", ")}",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = Color(0xFFB9F6CA)
                                    )
                                )
                            }
                            if (activeToolsDiff.removed.isNotEmpty()) {
                                Text(
                                    text = "- ${activeToolsDiff.removed.joinToString(", ")}",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = Color(0xFFFF8A80)
                                    )
                                )
                            }
                        }
                    }

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
            .ignoreWhiteSpaces(true)
            .oldTag { _ -> "" }
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
                 Text(
                     text = "  $oldLine",
                     style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.Gray.copy(0.5f))
                 )
             } else {
                 if (oldLine.isNotBlank()) {
                     Text(
                        text = "- $oldLine",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFFF8A80))
                     )
                 }
                 if (newLine.isNotBlank()) {
                     Text(
                        text = "+ $newLine",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFB9F6CA))
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
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Previous Step",
                tint = if (steps.indexOf(selectedStep) > 0) MaterialTheme.glassColors.textPrimary else MaterialTheme.glassColors.textPrimary.copy(alpha = 0.3f)
            )
        }

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
