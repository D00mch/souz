package ru.gigadesk.ui.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.sqrt
import kotlin.random.Random
import androidx.compose.ui.input.pointer.PointerEventType

// --- Data Structures ---

private data class HexNode(
    val q: Int,
    val r: Int,
    val center: Offset
)

private data class HexEdge(
    val id: String,
    val start: Offset,
    val end: Offset,
    var pulse: Float = 0f, // 0f..1f
    var cooldown: Int = 0
)

private class HexGridSystem {
    var nodes: List<HexNode> = emptyList()
    var edges: MutableList<HexEdge> = mutableListOf()
    var staticGridPath: Path? = null
    val adjacency: MutableMap<String, List<HexEdge>> = mutableMapOf()
}


@Composable
fun HexagonNeuralBackground(
    modifier: Modifier = Modifier,
    hexSize: Float = 90f, // +50% size
    gridColor: Color = Color.White.copy(alpha = 0.15f),
    pulseColor: Color = Color(0xFF00E5FF),
    content: @Composable () -> Unit
) {
    var mousePosition by remember { mutableStateOf<Offset?>(null) }
    var regionSize by remember { mutableStateOf(Size.Zero) }
    
    val system = remember { HexGridSystem() }
    val spotlightRadius = 52f // -30% radius
    var frameTick by remember { mutableStateOf(0L) }
    var updateCounter by remember { mutableStateOf(0) }

    val hasMouse = mousePosition != null && regionSize.width > 0
    val globalAlpha by animateFloatAsState(
        targetValue = if (hasMouse) 1f else 0f,
        animationSpec = tween(50) // "Appear immediately" - very fast fade
    )



    Box(
        modifier = modifier
            .onSizeChanged { regionSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (event.type == PointerEventType.Exit) {
                            mousePosition = null
                        } else {
                            val change = event.changes.firstOrNull()
                            mousePosition = change?.position
                        }
                    }
                }
            }
    ) {
        if (globalAlpha > 0f && regionSize.width > 0) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (system.nodes.isEmpty() && size.width > 0) {
                    val (nodes, edges) = generateHoneycombGrid(size.width, size.height, hexSize)
                    system.nodes = nodes
                    system.edges = edges.toMutableList()
                    system.staticGridPath = createGridPath(system.edges)
                    buildAdjacency(system)
                }
                
                val tick = frameTick
                val currentMouse = mousePosition ?: Offset.Zero
                
                // Vignette factor: darken edges of screen (radius from center)
                // Or simplified: Just ensure no glow at extreme corners relative to screen size.
                // We'll use a distance check from the mouse to the center of the screen, or just let the
                // mouse-based radius handle it? 
                // User said: "On the corners of the application there should be no glow".
                // We'll multiply alpha by a "Safe Zone" mask.
                // Map local coordinate to 0..1, calculate distance from center.
                // Actually, simplest is: if mouse is in corner, reduce spotlight radius?
                // Let's use a standard vignette:
                val center = Offset(size.width / 2, size.height / 2)
                val maxDist = sqrt(center.x * center.x + center.y * center.y)
                // But this affects the whole screen.
                // Let's modify the pulse/grid drawing to respect a "Corner Mask".
                // Mask = 1.0 at center, fades to 0.0 at very corners.
                
                system.staticGridPath?.let { path ->
                    drawPath(
                        path = path,
                        brush = Brush.radialGradient(
                            colors = listOf(
                                gridColor.copy(alpha = gridColor.alpha * globalAlpha),
                                gridColor.copy(alpha = 0f)
                            ),
                            center = currentMouse,
                            radius = spotlightRadius
                        ),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
                
                val strokeWidth = 2.dp.toPx()
                system.edges.forEach { edge ->
                    if (edge.pulse > 0.01f) {
                        val midX = (edge.start.x + edge.end.x) / 2
                        val midY = (edge.start.y + edge.end.y) / 2
                        val edgeCenter = Offset(midX, midY)
                        
                        // 1. Mouse Distance (Spotlight)
                        val dist = (edgeCenter - currentMouse).getDistance()
                        
                        // 2. Corner/Edge Avoidance (Vignette)
                        // If edge is close to the absolute corner of the view, fade it out.
                        // Let's just say padding = 50dp from bounds.
                        val padding = 50.dp.toPx()
                        val isInCornerZone = edgeCenter.x < padding || edgeCenter.x > size.width - padding ||
                                           edgeCenter.y < padding || edgeCenter.y > size.height - padding
                        
                        val vignetteAlpha = if (isInCornerZone) 0f else 1f
                        // Or smooth fade? Smooth is better.
                        val xAlpha = (edgeCenter.x / padding).coerceIn(0f, 1f) * 
                                     ((size.width - edgeCenter.x) / padding).coerceIn(0f, 1f)
                        val yAlpha = (edgeCenter.y / padding).coerceIn(0f, 1f) * 
                                     ((size.height - edgeCenter.y) / padding).coerceIn(0f, 1f)
                        val boundaryAlpha = (xAlpha * yAlpha).coerceIn(0f, 1f)

                        if (boundaryAlpha > 0.01f) {
                             // Allow pulses to go much further than the static spotlight
                             val pulseVisibleRadius = spotlightRadius * 2.5f
                             val radiusAlpha = ((1f - dist / pulseVisibleRadius).coerceIn(0f, 1f))
                             
                             if (radiusAlpha > 0f) {
                                 drawLine(
                                     // Reduced opacity by 30% -> max 0.7f
                                     color = pulseColor.copy(alpha = edge.pulse * globalAlpha * radiusAlpha * boundaryAlpha * 0.7f),
                                     start = edge.start,
                                     end = edge.end,
                                     strokeWidth = strokeWidth,
                                     cap = StrokeCap.Round
                                 )
                             }
                        }
                    }
                }
            }
        }
        
        content()
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameNanos { time ->
                frameTick = time
                if (globalAlpha > 0.01f && system.edges.isNotEmpty()) {
                    // Update only every 2nd frame to slow down movement 50%
                    updateCounter++
                    if (updateCounter % 2 == 0) {
                        updatePulses(system, mousePosition, spotlightRadius)
                    }
                }
            }
        }
    }
}

// --- Logic Update ---

private fun generateHoneycombGrid(width: Float, height: Float, r: Float): Pair<List<HexNode>, List<HexEdge>> {
    val nodes = mutableListOf<HexNode>()
    // Pointy topped hexes
    val wStep = r * sqrt(3f)
    val hStep = r * 1.5f
    
    val cols = (width / wStep).toInt() + 2
    val rows = (height / hStep).toInt() + 2
    
    // 1. Generate Centers
    for (row in -1..rows) {
        for (col in -1..cols) {
            val osRow = row
            val osCol = col - (row / 2)
            val x = r * sqrt(3f) * (osCol + osRow / 2f)
            val y = r * 1.5f * osRow
            nodes.add(HexNode(osCol, osRow, Offset(x, y)))
        }
    }
    
    // 2. Generate Edges (Honeycomb = Vertices connections)
    // We need unique edges. Each hex has 6 edges.
    // Calculate vertices for every hex, store unique segments.
    // Segment Key: sorted(v1, v2)
    
    val uniqueEdges = mutableMapOf<String, HexEdge>()
    
    nodes.forEach { node ->
        // Calculate 6 vertices
        val verts = DoubleArray(12) // x,y pairs
        for (i in 0 until 6) {
            val angle_deg = 60 * i - 30
            val angle_rad = Math.toRadians(angle_deg.toDouble())
            val vx = node.center.x + r * kotlin.math.cos(angle_rad).toFloat()
            val vy = node.center.y + r * kotlin.math.sin(angle_rad).toFloat()
            verts[i*2] = vx.toDouble()
            verts[i*2+1] = vy.toDouble()
        }
        
        // Create edges v0-v1, v1-v2... v5-v0
        for (i in 0 until 6) {
            val x1 = verts[i*2].toFloat()
            val y1 = verts[i*2+1].toFloat()
            val next = (i + 1) % 6
            val x2 = verts[next*2].toFloat()
            val y2 = verts[next*2+1].toFloat()
            
            // Round to avoid float precision issues in key
            val p1 = Offset((x1 * 10).toInt() / 10f, (y1 * 10).toInt() / 10f)
            val p2 = Offset((x2 * 10).toInt() / 10f, (y2 * 10).toInt() / 10f)
            
            // Key based on spatial hash or just string (slow but reliable for init)
            // Sort by x, then y
            val (start, end) = if (p1.x < p2.x || (p1.x == p2.x && p1.y < p2.y)) p1 to p2 else p2 to p1
            val key = "${start.x},${start.y}-${end.x},${end.y}"
            
            if (!uniqueEdges.containsKey(key)) {
                uniqueEdges[key] = HexEdge(key, start, end)
            }
        }
    }
    
    return Pair(nodes, uniqueEdges.values.toList())
}





private fun updatePulses(system: HexGridSystem, mousePos: Offset?, radius: Float) {
    // Slower decay so lines persist and "travel" visibly
    val decay = 0.01f 
    
    // Very high propagation to ensure they "diverge along the faces" effectively
    val propagateChance = 0.4f
    
    val radiusSq = radius * radius
    val activationCooldown = 20 // Frames before an edge can be reused
    
    system.edges.forEach { edge ->
        if (edge.cooldown > 0) edge.cooldown--
        
        if (edge.pulse > 0f) {
            // Aggressive propagation
            if (edge.pulse > 0.5f) { // Spread earlier
                // Chance to fork behavior
                val forkChance = 0.15f // Reduced 50% for fewer lines
                val shouldFork = Random.nextFloat() < forkChance
                val count = if (shouldFork) 2 else 1
                
                if (Random.nextFloat() < propagateChance) {
                     val neighbors = system.adjacency[edge.id]
                     if (!neighbors.isNullOrEmpty()) {
                         // Pick distinct neighbors that are NOT cooling down
                         val targets = neighbors
                             .filter { it.cooldown == 0 }
                             .shuffled()
                             .take(count)
                         
                         targets.forEach { target ->
                             // Ignite neighbor if not already bright
                             if (target.pulse < 0.1f) {
                                 target.pulse = 1f
                                 target.cooldown = activationCooldown
                             }
                         }
                     }
                }
            }
            edge.pulse = (edge.pulse - decay).coerceAtLeast(0f)
        }
    }
    
    // Chaotic ignition (Bursts)
    if (mousePos != null) {
        val strikeProbability = 0.07f // Reduced 50% for fewer lines
        
        if (Random.nextFloat() < strikeProbability) {
             // Try to find a start point near mouse that is ready
             // We'll try a few candidates
             var ignited = false
             for (i in 0..10) {
                 if (system.edges.isNotEmpty()) {
                     val candidate = system.edges.random()
                     if (candidate.cooldown > 0) continue
                     
                     val midX = (candidate.start.x + candidate.end.x) / 2
                     val midY = (candidate.start.y + candidate.end.y) / 2
                     val distSq = (midX - mousePos.x) * (midX - mousePos.x) + (midY - mousePos.y) * (midY - mousePos.y)
                     
                     if (distSq < radiusSq) {
                         candidate.pulse = 1f
                         candidate.cooldown = activationCooldown
                         ignited = true
                         break 
                     }
                 }
             }
        }
    }
}


private fun buildAdjacency(system: HexGridSystem) {
    if (system.adjacency.isNotEmpty()) return

    val nodeToEdges = mutableMapOf<Offset, MutableList<HexEdge>>()
    system.edges.forEach { edge ->
        nodeToEdges.getOrPut(edge.start) { mutableListOf() }.add(edge)
        nodeToEdges.getOrPut(edge.end) { mutableListOf() }.add(edge)
    }
    
    system.edges.forEach { edge ->
        val neighbors = mutableSetOf<HexEdge>()
        nodeToEdges[edge.start]?.let { neighbors.addAll(it) }
        nodeToEdges[edge.end]?.let { neighbors.addAll(it) }
        neighbors.remove(edge)
        system.adjacency[edge.id] = neighbors.toList()
    }
}

private fun Offset.getDistance(): Float = sqrt(x * x + y * y)
private fun Offset.getDistanceSquared(): Float = x * x + y * y

private fun createGridPath(edges: List<HexEdge>): Path {
    val path = Path()
    edges.forEach { edge ->
        path.moveTo(edge.start.x, edge.start.y)
        path.lineTo(edge.end.x, edge.end.y)
    }
    return path
}
