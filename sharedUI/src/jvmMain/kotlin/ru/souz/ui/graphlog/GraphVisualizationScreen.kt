package ru.souz.ui.graphlog

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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
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
import ru.souz.agent.session.GraphSession
import ru.souz.agent.session.GraphStepRecord
import ru.souz.ui.glassColors
import ru.souz.ui.souzColors
import ru.souz.ui.common.RealLiquidGlassCard
import ru.souz.ui.common.DraggableWindowArea
import kotlin.math.roundToInt
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import androidx.compose.material.icons.rounded.Check
import java.awt.Cursor

private val horizontalResizePointerIcon = PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR))

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
    var detailsPanelFraction by remember { mutableStateOf(0.38f) }
    val minDetailsPanelFraction = 0.24f
    val maxDetailsPanelFraction = 0.60f
    
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
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header - Draggable area for window
                DraggableWindowArea {
                    HeaderRow(session = session, onBack = onBack)
                }

                // Main Content Split
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                ) {
                    val containerWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)

                    Row(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // LEFT: Graph Canvas
                        Box(
                            modifier = Modifier
                                .weight(1f - detailsPanelFraction)
                                .fillMaxHeight()
                        ) {
                            GraphCanvas(
                                data = graphData,
                                selectedNodeId = selectedNodeId,
                                onNodeClick = { selectedNodeId = it }
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(12.dp)
                                .padding(horizontal = 2.dp, vertical = 12.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .border(
                                    1.dp,
                                    Color.White.copy(alpha = 0.12f),
                                    RoundedCornerShape(999.dp)
                                )
                                .pointerHoverIcon(horizontalResizePointerIcon)
                                .pointerInput(containerWidthPx) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        val deltaFraction = dragAmount.x / containerWidthPx
                                        detailsPanelFraction =
                                            (detailsPanelFraction - deltaFraction).coerceIn(
                                                minDetailsPanelFraction,
                                                maxDetailsPanelFraction
                                            )
                                    }
                                }
                        )

                        // RIGHT: Details Panel (resizable)
                        Box(
                            modifier = Modifier
                                .weight(detailsPanelFraction)
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
fun SideDetailsPanel(
    selectedNode: DisplayNode?,
    selectedStep: GraphStepRecord?,
    onStepSelect: (GraphStepRecord) -> Unit,
    availableGroups: List<String>,
    collapsedSubgraphs: Set<String>,
    onToggleSubgraph: (String) -> Unit
) {
    val scrollState = rememberScrollState()
    val colors = MaterialTheme.souzColors.graph

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.panelBackground)
            .border(1.dp, colors.panelBorder, RoundedCornerShape(16.dp))
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
                color = colors.secondaryText,
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
                            containerColor = colors.itemBackground,
                            labelColor = colors.primaryText,
                            selectedContainerColor = colors.selectedItemBackground,
                            selectedLabelColor = colors.selectedNodeContent,
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = colors.divider)
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (selectedNode == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Select a node",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.secondaryText,
                )
            }
        } else {
            Text(
                text = selectedNode.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.primaryText,
            )
            Text(
                text = "${selectedNode.visitCount} executions",
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondaryText,
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = colors.divider)
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
    val colors = MaterialTheme.souzColors.graph
    val clipboardManager = LocalClipboardManager.current
    var isCopied by remember { mutableStateOf(false) }
    val activeToolsDiff = remember(step.data) { extractActiveToolsDiff(step.data) }
    val classifyStep = remember(step.nodeName) { isClassifyStep(step.nodeName) }
    val selectedCategories = remember(step.data) { extractSelectedCategories(step.data) }

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
            if (classifyStep && selectedCategories.isNotEmpty()) {
                appendLine()
                appendLine("CATEGORIES:")
                appendLine(selectedCategories.joinToString(", "))
            }
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
            .background(if (isExpanded) colors.selectedItemBackground else Color.Transparent)
            .border(
                1.dp,
                if (isExpanded) colors.selectedNodeBorder.copy(alpha = 0.3f) else colors.panelBorder,
                RoundedCornerShape(8.dp),
            )
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
                color = colors.primaryText,
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
                    tint = if (isCopied) colors.positiveText else colors.secondaryText,
                    modifier = Modifier.size(14.dp)
                )
            }
            
            Icon(
                imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.secondaryText,
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
                    if (classifyStep) {
                        if (selectedCategories.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("CATEGORIES", style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = colors.secondaryText))
                                Text(
                                    text = selectedCategories.joinToString(", "),
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = colors.outputText,
                                    )
                                )
                            }
                        }
                    }
                    
                    if (activeToolsDiff != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "ACTIVE TOOLS CHANGED",
                                style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = colors.secondaryText),
                            )
                            if (activeToolsDiff.added.isNotEmpty()) {
                                Text(
                                    text = "+ ${activeToolsDiff.added.joinToString(", ")}",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = colors.positiveText,
                                    )
                                )
                            }
                            if (activeToolsDiff.removed.isNotEmpty()) {
                                Text(
                                    text = "- ${activeToolsDiff.removed.joinToString(", ")}",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = colors.negativeText,
                                    )
                                )
                            }
                        }
                    }

                    val outputSummary = step.outputSummary
                    val addedHistory = step.addedHistory

                    if (!classifyStep && !outputSummary.isNullOrEmpty() && step.inputSummary != outputSummary) {
                        Text("IO DIFF", style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = colors.secondaryText))
                        DiffContent(original = step.inputSummary, revised = outputSummary)
                    } else if (!classifyStep || outputSummary.isNullOrEmpty()) {
                        // Input
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                             Text("INPUT", style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = colors.secondaryText))
                             Text(
                                 text = step.inputSummary.trim().ifEmpty { "-" }, 
                                 style = TextStyle(
                                     fontFamily = FontFamily.Monospace, 
                                     fontSize = 11.sp, 
                                     color = colors.inputText,
                                 )
                             )
                        }

                        if (!outputSummary.isNullOrEmpty() && !classifyStep) {
                             Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                 Text("OUTPUT", style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = colors.secondaryText))
                                 Text(
                                     text = outputSummary.trim(), 
                                     style = TextStyle(
                                         fontFamily = FontFamily.Monospace, 
                                         fontSize = 11.sp, 
                                         color = colors.outputText,
                                     )
                                 )
                             }
                        }
                    }

                    if (!addedHistory.isNullOrEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("SAVED TO HISTORY", style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = colors.secondaryText))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(colors.codeBackground, RoundedCornerShape(4.dp))
                                    .border(1.dp, colors.panelBorder, RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = addedHistory,
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = colors.historyText,
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
    val colors = MaterialTheme.souzColors.graph
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
            .background(colors.codeBackground, RoundedCornerShape(4.dp))
            .padding(8.dp)
    ) {
        diff.forEach { row ->
             val oldLine = row.oldLine
             val newLine = row.newLine
             
             if (oldLine == newLine) {
                 Text(
                     text = "  $oldLine",
                     style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.secondaryText),
                 )
             } else {
                 if (oldLine.isNotBlank()) {
                     Text(
                        text = "- $oldLine",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.negativeText),
                     )
                 }
                 if (newLine.isNotBlank()) {
                     Text(
                        text = "+ $newLine",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.positiveText),
                     )
                 }
             }
        }
    }
}
