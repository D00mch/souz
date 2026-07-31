package ru.souz.ui.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.localDI
import ru.souz.agent.session.GraphSession
import ru.souz.agent.session.GraphStepRecord
import ru.souz.ui.graphlog.DisplayNode
import ru.souz.ui.graphlog.GraphCanvas
import ru.souz.ui.graphlog.GraphProcessResult
import ru.souz.ui.graphlog.GraphSessionSummaryUi
import ru.souz.ui.graphlog.GraphSessionsEvent
import ru.souz.ui.graphlog.GraphSessionsState
import ru.souz.ui.graphlog.GraphSessionsViewModel
import ru.souz.ui.graphlog.TimelineStrip
import ru.souz.ui.graphlog.extractActiveToolsDiff
import ru.souz.ui.graphlog.extractSelectedCategories
import ru.souz.ui.graphlog.isClassifyStep
import ru.souz.ui.graphlog.processSessionData
import souz.sharedui.generated.resources.Res
import souz.sharedui.generated.resources.action_refresh
import souz.sharedui.generated.resources.duration_suffix_s
import souz.sharedui.generated.resources.error_prefix
import souz.sharedui.generated.resources.graph_active_tools_changed
import souz.sharedui.generated.resources.graph_categories
import souz.sharedui.generated.resources.graph_execution_format
import souz.sharedui.generated.resources.graph_executions_format
import souz.sharedui.generated.resources.graph_input
import souz.sharedui.generated.resources.graph_no_categories
import souz.sharedui.generated.resources.graph_output
import souz.sharedui.generated.resources.graph_saved_to_history
import souz.sharedui.generated.resources.graph_select_node
import souz.sharedui.generated.resources.graph_session_opening
import souz.sharedui.generated.resources.graph_sessions_count_format
import souz.sharedui.generated.resources.graph_sessions_empty
import souz.sharedui.generated.resources.graph_sessions_title
import souz.sharedui.generated.resources.graph_steps_count
import souz.sharedui.generated.resources.graph_subgraphs
import souz.sharedui.generated.resources.graph_visualization_title
import souz.sharedui.generated.resources.status_in_progress

@Composable
internal fun AndroidGraphSessionsRoute(
    onBack: () -> Unit,
) {
    val di = localDI()
    val viewModel = viewModel { GraphSessionsViewModel(di) }
    val state by viewModel.uiState.collectAsState()

    AndroidGraphSessionsScreen(
        state = state,
        onBack = onBack,
        onRefresh = { viewModel.send(GraphSessionsEvent.Refresh) },
        onSelectSession = { viewModel.send(GraphSessionsEvent.OpenSession(it)) },
        onBackToList = { viewModel.send(GraphSessionsEvent.BackToList) },
    )
}

@Composable
private fun AndroidGraphSessionsScreen(
    state: GraphSessionsState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectSession: (String) -> Unit,
    onBackToList: () -> Unit,
) {
    val selectedSession = state.selectedSession
    if (selectedSession != null) {
        AndroidGraphSessionViewer(
            session = selectedSession,
            isOpeningSession = state.isOpeningSession,
            onBack = onBackToList,
        )
    } else {
        AndroidGraphSessionsList(
            state = state,
            onBack = onBack,
            onRefresh = onRefresh,
            onSelectSession = onSelectSession,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AndroidGraphSessionsList(
    state: GraphSessionsState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectSession: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(Res.string.graph_sessions_title))
                        Text(
                            text = stringResource(Res.string.graph_sessions_count_format).format(state.sessions.size),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.isLoading) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(Res.string.action_refresh),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.isLoading && state.sessions.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                state.sessions.isEmpty() -> {
                    Text(
                        text = stringResource(Res.string.graph_sessions_empty),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.sessions, key = { it.id }) { session ->
                            AndroidGraphSessionCard(
                                session = session,
                                enabled = !state.isOpeningSession,
                                onClick = { onSelectSession(session.id) },
                            )
                        }
                    }
                }
            }

            state.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(Res.string.error_prefix).format(error),
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onRefresh) {
                            Text(stringResource(Res.string.action_refresh))
                        }
                    }
                }
            }

            if (state.isOpeningSession) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 4.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(Res.string.graph_session_opening))
                    }
                }
            }
        }
    }
}

@Composable
private fun AndroidGraphSessionCard(
    session: GraphSessionSummaryUi,
    enabled: Boolean,
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

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = startDate,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${stringResource(Res.string.graph_steps_count).format(session.stepsCount)} | $duration",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = session.initialInput,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (session.nodePathPreview.isNotBlank()) {
                Text(
                    text = session.nodePathPreview,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AndroidGraphSessionViewer(
    session: GraphSession,
    isOpeningSession: Boolean,
    onBack: () -> Unit,
) {
    var collapsedSubgraphs by remember(session.id) { mutableStateOf(emptySet<String>()) }
    val graphData = remember(session, collapsedSubgraphs) {
        processSessionData(session, collapsedSubgraphs)
    }
    val availableGroups = remember(session.steps) {
        session.steps
            .mapNotNull { step -> step.nodeName.substringBefore("::").takeIf { it != step.nodeName } }
            .distinct()
            .sorted()
    }
    var selectedNodeId by remember(session.id) { mutableStateOf<String?>(null) }
    var selectedStep by remember(session.id) { mutableStateOf<GraphStepRecord?>(null) }

    LaunchedEffect(graphData) {
        if (selectedNodeId == null || selectedNodeId !in graphData.nodes) {
            selectedNodeId = graphData.nodes.keys.firstOrNull()
        }
    }

    LaunchedEffect(selectedNodeId, graphData) {
        val steps = selectedNodeId?.let { graphData.nodes[it]?.steps }.orEmpty()
        if (selectedStep == null || selectedStep !in steps) {
            selectedStep = steps.lastOrNull()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(Res.string.graph_visualization_title))
                        Text(
                            text = "${session.id.take(8)}... | ${stringResource(Res.string.graph_steps_count).format(session.steps.size)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val useSidePanel = maxWidth >= 720.dp
            val selectNode: (String) -> Unit = { nodeId ->
                selectedNodeId = nodeId
                selectedStep = graphData.nodes[nodeId]?.steps?.lastOrNull()
            }
            val selectTimelineStep: (GraphStepRecord) -> Unit = { step ->
                selectedStep = step
                selectedNodeId = graphData.nodes.entries
                    .firstOrNull { (_, node) -> step in node.steps }
                    ?.key ?: step.nodeName
            }
            val toggleSubgraph: (String) -> Unit = { group ->
                collapsedSubgraphs = if (group in collapsedSubgraphs) {
                    collapsedSubgraphs - group
                } else {
                    collapsedSubgraphs + group
                }
            }
            val toggleStep: (GraphStepRecord) -> Unit = { step ->
                selectedStep = if (selectedStep == step) null else step
            }

            if (useSidePanel) {
                val sidePanelWidth = (maxWidth * 0.38f).coerceIn(320.dp, 420.dp)

                Row(modifier = Modifier.fillMaxSize()) {
                    AndroidGraphMainPane(
                        graphData = graphData,
                        steps = session.steps,
                        selectedNodeId = selectedNodeId,
                        selectedStep = selectedStep,
                        isOpeningSession = isOpeningSession,
                        onNodeSelect = selectNode,
                        onStepSelect = selectTimelineStep,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    AndroidGraphDetailsPanel(
                        selectedNode = selectedNodeId?.let { graphData.nodes[it] },
                        selectedStep = selectedStep,
                        availableGroups = availableGroups,
                        collapsedSubgraphs = collapsedSubgraphs,
                        onToggleSubgraph = toggleSubgraph,
                        onStepSelect = toggleStep,
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(sidePanelWidth),
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    AndroidGraphMainPane(
                        graphData = graphData,
                        steps = session.steps,
                        selectedNodeId = selectedNodeId,
                        selectedStep = selectedStep,
                        isOpeningSession = isOpeningSession,
                        onNodeSelect = selectNode,
                        onStepSelect = selectTimelineStep,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                    AndroidGraphDetailsPanel(
                        selectedNode = selectedNodeId?.let { graphData.nodes[it] },
                        selectedStep = selectedStep,
                        availableGroups = availableGroups,
                        collapsedSubgraphs = collapsedSubgraphs,
                        onToggleSubgraph = toggleSubgraph,
                        onStepSelect = toggleStep,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 320.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AndroidGraphMainPane(
    graphData: GraphProcessResult,
    steps: List<GraphStepRecord>,
    selectedNodeId: String?,
    selectedStep: GraphStepRecord?,
    isOpeningSession: Boolean,
    onNodeSelect: (String) -> Unit,
    onStepSelect: (GraphStepRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF101214)),
        ) {
            GraphCanvas(
                data = graphData,
                selectedNodeId = selectedNodeId,
                onNodeClick = onNodeSelect,
                scrollableContent = true,
            )
            if (isOpeningSession) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }

        TimelineStrip(
            steps = steps,
            selectedStep = selectedStep,
            onStepClick = onStepSelect,
            modifier = Modifier
                .height(48.dp)
                .background(Color(0xFF101214)),
        )
    }
}

@Composable
private fun AndroidGraphDetailsPanel(
    selectedNode: DisplayNode?,
    selectedStep: GraphStepRecord?,
    availableGroups: List<String>,
    collapsedSubgraphs: Set<String>,
    onToggleSubgraph: (String) -> Unit,
    onStepSelect: (GraphStepRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val groups = remember(availableGroups, collapsedSubgraphs) {
                (availableGroups + collapsedSubgraphs).distinct().sorted()
            }
            if (groups.isNotEmpty()) {
                Text(
                    text = stringResource(Res.string.graph_subgraphs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(groups) { group ->
                        val isCollapsed = group in collapsedSubgraphs
                        FilterChip(
                            selected = isCollapsed,
                            onClick = { onToggleSubgraph(group) },
                            label = { Text(group) },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isCollapsed) {
                                        Icons.AutoMirrored.Rounded.KeyboardArrowRight
                                    } else {
                                        Icons.Rounded.KeyboardArrowDown
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(),
                        )
                    }
                }
                HorizontalDivider()
            }

            if (selectedNode == null) {
                Text(
                    text = stringResource(Res.string.graph_select_node),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = selectedNode.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.graph_executions_format).format(selectedNode.visitCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                selectedNode.steps.forEach { step ->
                    AndroidGraphStepItem(
                        step = step,
                        isExpanded = step == selectedStep,
                        onClick = { onStepSelect(step) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AndroidGraphStepItem(
    step: GraphStepRecord,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    val activeToolsDiff = remember(step.data) { extractActiveToolsDiff(step.data) }
    val classifyStep = remember(step.nodeName) { isClassifyStep(step.nodeName) }
    val selectedCategories = remember(step.data) { extractSelectedCategories(step.data) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = 1.dp,
                color = if (isExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.graph_execution_format).format(step.stepIndex),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowDown else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
            )
        }
        if (isExpanded) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (classifyStep) {
                        GraphStepTextBlock(
                            title = stringResource(Res.string.graph_categories),
                            text = selectedCategories
                                .joinToString(", ")
                                .ifEmpty { stringResource(Res.string.graph_no_categories) },
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    GraphStepTextBlock(
                        title = stringResource(Res.string.graph_input),
                        text = step.inputSummary.trim().ifEmpty { "-" },
                        color = MaterialTheme.colorScheme.primary,
                    )
                    step.outputSummary?.takeIf { it.isNotBlank() }?.let {
                        GraphStepTextBlock(
                            title = stringResource(Res.string.graph_output),
                            text = it.trim(),
                            color = Color(0xFF2E7D32),
                        )
                    }
                    step.addedHistory?.takeIf { it.isNotBlank() }?.let {
                        GraphStepTextBlock(
                            title = stringResource(Res.string.graph_saved_to_history),
                            text = it.trim(),
                            color = Color(0xFFB26A00),
                        )
                    }
                    activeToolsDiff?.let { diff ->
                        val changes = buildString {
                            if (diff.added.isNotEmpty()) appendLine("+ ${diff.added.joinToString(", ")}")
                            if (diff.removed.isNotEmpty()) appendLine("- ${diff.removed.joinToString(", ")}")
                        }.trim()
                        if (changes.isNotBlank()) {
                            GraphStepTextBlock(
                                title = stringResource(Res.string.graph_active_tools_changed),
                                text = changes,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GraphStepTextBlock(
    title: String,
    text: String,
    color: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = text,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = color,
            ),
        )
    }
}
