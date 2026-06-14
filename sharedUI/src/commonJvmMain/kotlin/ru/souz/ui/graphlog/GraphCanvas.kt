package ru.souz.ui.graphlog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import ru.souz.agent.session.GraphStepRecord
import ru.souz.ui.glassColors

@Composable
fun GraphCanvas(
    data: GraphProcessResult,
    selectedNodeId: String?,
    onNodeClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    scrollableContent: Boolean = false,
) {
    val nodeOffsets = remember(data) { mutableStateMapOf<String, Offset>() }

    if (scrollableContent) {
        val horizontalScrollState = rememberScrollState()
        val verticalScrollState = rememberScrollState()

        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val contentWidth = data.scrollableContentWidth(maxWidth)
            val contentHeight = data.scrollableContentHeight(maxHeight)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .horizontalScroll(horizontalScrollState)
                    .verticalScroll(verticalScrollState),
            ) {
                GraphCanvasContent(
                    data = data,
                    selectedNodeId = selectedNodeId,
                    onNodeClick = onNodeClick,
                    nodeOffsets = nodeOffsets,
                    fitContentBounds = true,
                    modifier = Modifier
                        .width(contentWidth)
                        .height(contentHeight),
                )
            }
        }
    } else {
        GraphCanvasContent(
            data = data,
            selectedNodeId = selectedNodeId,
            onNodeClick = onNodeClick,
            nodeOffsets = nodeOffsets,
            fitContentBounds = false,
            modifier = modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun GraphCanvasContent(
    data: GraphProcessResult,
    selectedNodeId: String?,
    onNodeClick: (String) -> Unit,
    nodeOffsets: MutableMap<String, Offset>,
    fitContentBounds: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val nodeSizeDp = 90.dp
    val nodeSizePx = with(density) { nodeSizeDp.toPx() }
    val contentInsetPx = with(density) { 53.dp.toPx() }
    val xBounds = remember(data) {
        val xs = data.nodes.values.map { it.resolvedPos.x }
        (xs.minOrNull() ?: 0f) to (xs.maxOrNull() ?: 1f)
    }
    val yBounds = remember(data) {
        val ys = data.nodes.values.map { it.resolvedPos.y }
        (ys.minOrNull() ?: 0f) to (ys.maxOrNull() ?: 1f)
    }

    BoxWithConstraints(modifier = modifier) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()

        fun getPos(nodeId: String, initial: ResolvedPos): Offset {
            val initialX: Float
            val initialY: Float
            if (fitContentBounds) {
                val xRange = (xBounds.second - xBounds.first).coerceAtLeast(0.001f)
                val yRange = (yBounds.second - yBounds.first).coerceAtLeast(0.001f)
                val availableWidth = (width - contentInsetPx * 2).coerceAtLeast(1f)
                val availableHeight = (height - contentInsetPx * 2).coerceAtLeast(1f)
                initialX = contentInsetPx + ((initial.x - xBounds.first) / xRange) * availableWidth
                initialY = contentInsetPx + ((initial.y - yBounds.first) / yRange) * availableHeight
            } else {
                initialX = initial.x * width
                initialY = initial.y * height
            }
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
                    highlighted = edge.isHighlighted,
                )
            }
        }

        data.nodes.values.forEach { node ->
            val isSelected = selectedNodeId == node.id
            val currentPos = getPos(node.id, node.resolvedPos)
            val xPx = (currentPos.x - nodeSizePx / 2).roundToInt()
            val yPx = (currentPos.y - nodeSizePx / 2).roundToInt()

            Box(
                modifier = Modifier
                    .offset { IntOffset(xPx, yPx) }
                    .size(nodeSizeDp)
                    .pointerInput(node.id) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val current = nodeOffsets[node.id] ?: Offset.Zero
                            nodeOffsets[node.id] = current + dragAmount
                        }
                    },
            ) {
                CircularNodeItem(
                    label = node.label,
                    count = node.visitCount,
                    isSelected = isSelected,
                    onClick = { onNodeClick(node.id) },
                )
            }
        }
    }
}

private fun GraphProcessResult.scrollableContentWidth(viewportWidth: Dp): Dp {
    val widestLayer = nodes.values
        .groupingBy { it.resolvedPos.layer }
        .eachCount()
        .values
        .maxOrNull() ?: 1

    if (widestLayer <= 1) return viewportWidth

    val contentInset = 53.dp
    val desiredNodeGap = 150.dp
    val requiredWidth = contentInset * 2f + desiredNodeGap * (widestLayer - 1).toFloat()
    return maxOf(viewportWidth, requiredWidth)
}

private fun GraphProcessResult.scrollableContentHeight(viewportHeight: Dp): Dp {
    val maxLayer = nodes.values.maxOfOrNull { it.resolvedPos.layer } ?: 0
    if (maxLayer <= 0) return viewportHeight

    val contentInset = 53.dp
    val desiredLayerGap = 150.dp
    val requiredHeight = contentInset * 2f + desiredLayerGap * maxLayer.toFloat()
    return maxOf(viewportHeight, requiredHeight)
}

fun calculateControlPoint(start: Offset, end: Offset, fromPos: ResolvedPos, toPos: ResolvedPos): Offset {
    val midX = (start.x + end.x) / 2
    val midY = (start.y + end.y) / 2
    val dx = end.x - start.x

    if (fromPos.layer == toPos.layer) {
        val curvature = kotlin.math.abs(dx) * 0.3f
        return Offset(midX, minOf(start.y, end.y) - curvature.coerceIn(40f, 150f))
    }

    if (fromPos.layer > toPos.layer) {
        val sideOffset = if (start.x < end.x) -100f else 100f
        return Offset(midX + sideOffset, midY)
    }

    val horizontalOffset = dx * 0.2f
    return Offset(midX + horizontalOffset, midY)
}

fun DrawScope.drawCurvedEdge(
    start: Offset,
    end: Offset,
    fromPos: ResolvedPos,
    toPos: ResolvedPos,
    highlighted: Boolean,
) {
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
        style = Stroke(width = strokeWidth),
    )
}

@Composable
fun CircularNodeItem(
    label: String,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val glowColor = Color(0xFF00E5FF)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (count > 1) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(x = 3.dp, y = 3.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.1f), CircleShape),
            )
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(
                    if (isSelected) glowColor.copy(alpha = 0.1f)
                    else Color(0xFF1E1E1E).copy(alpha = 0.95f),
                )
                .border(
                    if (isSelected) 2.dp else 1.dp,
                    if (isSelected) glowColor else Color.White.copy(alpha = 0.2f),
                    CircleShape,
                )
                .shadow(if (isSelected) 12.dp else 0.dp, CircleShape, spotColor = glowColor),
            contentAlignment = Alignment.Center,
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
                textAlign = TextAlign.Center,
                fontSize = 11.sp,
            )
        }

        if (count > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(Color(0xFF2C2C2C))
                    .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
fun TimelineStrip(
    steps: List<GraphStepRecord>,
    selectedStep: GraphStepRecord?,
    onStepClick: (GraphStepRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val currentIndex = steps.indexOf(selectedStep)
        IconButton(
            onClick = {
                if (currentIndex > 0) {
                    onStepClick(steps[currentIndex - 1])
                }
            },
            enabled = currentIndex > 0,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Previous Step",
                tint = if (currentIndex > 0) {
                    MaterialTheme.glassColors.textPrimary
                } else {
                    MaterialTheme.glassColors.textPrimary.copy(alpha = 0.3f)
                },
            )
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .height(24.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
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
                            else Color.White.copy(alpha = 0.2f),
                        )
                        .clickable { onStepClick(step) },
                )
            }
        }

        IconButton(
            onClick = {
                if (currentIndex >= 0 && currentIndex < steps.size - 1) {
                    onStepClick(steps[currentIndex + 1])
                }
            },
            enabled = currentIndex >= 0 && currentIndex < steps.size - 1,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = "Next Step",
                tint = if (currentIndex >= 0 && currentIndex < steps.size - 1) {
                    MaterialTheme.glassColors.textPrimary
                } else {
                    MaterialTheme.glassColors.textPrimary.copy(alpha = 0.3f)
                },
            )
        }
    }
}
