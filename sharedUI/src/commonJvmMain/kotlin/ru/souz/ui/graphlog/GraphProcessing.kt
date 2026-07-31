package ru.souz.ui.graphlog

import ru.souz.agent.session.GraphSession
import ru.souz.agent.session.GraphStepRecord

data class LayoutNode(
    val id: String,
    var x: Float,
    var y: Float,
    var layer: Int,
    var indexInLayer: Int,
)

data class ResolvedPos(val x: Float, val y: Float, val layer: Int = 0)

data class DisplayNode(
    val id: String,
    val label: String,
    var resolvedPos: ResolvedPos,
    val steps: List<GraphStepRecord>,
    val visitCount: Int,
)

data class GraphEdge(
    val fromId: String,
    val toId: String,
    val fromPos: ResolvedPos,
    val toPos: ResolvedPos,
    val stepIndex: Int,
    val isHighlighted: Boolean,
)

data class GraphProcessResult(
    val nodes: Map<String, DisplayNode>,
    val edges: List<GraphEdge>,
)

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

    fun getGroupName(name: String): String? =
        if (name.contains("::")) name.substringBefore("::") else null

    fun resolveNodeName(originalName: String): String {
        val group = getGroupName(originalName)
        return if (group != null && group in collapsedSubgraphs) group else originalName
    }

    val nodeIds = mutableListOf<String>()
    session.steps.forEach { step ->
        val finalName = resolveNodeName(step.nodeName)
        if (finalName !in nodeIds) {
            nodeIds.add(finalName)
        }
    }

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

    val layout = calculateGraphLayout(nodeIds, layoutEdges)

    session.steps.forEach { step ->
        val rawName = step.nodeName
        val finalName = resolveNodeName(rawName)

        if (finalName !in nodes) {
            val isGroup = finalName != rawName
            val layoutNode = layout[finalName]

            nodes[finalName] = DisplayNode(
                id = finalName,
                label = if (isGroup) "[$finalName]" else formatLabel(finalName),
                resolvedPos = ResolvedPos(
                    x = layoutNode?.x ?: 0.5f,
                    y = layoutNode?.y ?: 0.5f,
                    layer = layoutNode?.layer ?: 0,
                ),
                steps = emptyList(),
                visitCount = 0,
            )
        }
    }

    session.steps.forEach { step ->
        val finalName = resolveNodeName(step.nodeName)
        val node = nodes.getValue(finalName)
        val newSteps = node.steps + step

        nodes[finalName] = node.copy(
            steps = newSteps,
            visitCount = newSteps.size,
        )
    }

    if (session.steps.size > 1) {
        for (i in 0 until session.steps.size - 1) {
            val current = session.steps[i]
            val next = session.steps[i + 1]

            val fromId = resolveNodeName(current.nodeName)
            val toId = resolveNodeName(next.nodeName)

            if (fromId != toId) {
                val fromNode = nodes.getValue(fromId)
                val toNode = nodes.getValue(toId)

                edges.add(
                    GraphEdge(
                        fromId = fromNode.id,
                        toId = toNode.id,
                        fromPos = fromNode.resolvedPos,
                        toPos = toNode.resolvedPos,
                        stepIndex = i + 1,
                        isHighlighted = true,
                    )
                )
            }
        }
    }

    return GraphProcessResult(nodes, edges)
}

private fun calculateGraphLayout(
    nodeIds: List<String>,
    edges: List<Pair<String, String>>,
): Map<String, LayoutNode> {
    if (nodeIds.isEmpty()) return emptyMap()

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

    val layers = assignLayers(nodeIds, successors, predecessors)
    val layoutNodes = mutableMapOf<String, LayoutNode>()
    val layerGroups = nodeIds.groupBy { layers[it] ?: 0 }
    val maxLayer = layerGroups.keys.maxOrNull() ?: 0

    val layerSpacing = if (maxLayer == 0) 0f else 0.75f / maxLayer
    val leftX = 0.25f
    val rightX = 0.75f
    val centerX = 0.50f

    layerGroups.forEach { (layer, nodesInLayer) ->
        val yPos = if (maxLayer == 0) 0.5f else 0.10f + (layer.toFloat() * layerSpacing)

        if (nodesInLayer.size == 1) {
            val xPos = when {
                layer == 0 -> centerX
                layer == maxLayer -> centerX
                layer % 2 == 1 -> rightX
                else -> leftX
            }
            layoutNodes[nodesInLayer[0]] = LayoutNode(nodesInLayer[0], xPos, yPos, layer, 0)
        } else {
            val zigzagY = 0.03f
            nodesInLayer.forEachIndexed { index, nodeId ->
                val xPos = 0.15f + (index.toFloat() / (nodesInLayer.size - 1)) * 0.70f
                val yOffset = if (index % 2 == 0) 0f else zigzagY
                layoutNodes[nodeId] = LayoutNode(nodeId, xPos, yPos + yOffset, layer, index)
            }
        }
    }

    repeat(3) {
        barycenterOrdering(layoutNodes, layerGroups.keys.sorted(), successors, predecessors)
    }
    applyForceSimulation(layoutNodes, layerGroups)

    return layoutNodes
}

private fun assignLayers(
    nodeIds: List<String>,
    successors: Map<String, List<String>>,
    predecessors: Map<String, List<String>>,
): Map<String, Int> {
    val layers = mutableMapOf<String, Int>()
    val entryNodes = nodeIds.filter { predecessors[it]?.isEmpty() == true }
        .ifEmpty { listOf(nodeIds.first()) }

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

    nodeIds.forEach { if (it !in layers) layers[it] = 0 }
    return layers
}

private fun barycenterOrdering(
    layoutNodes: MutableMap<String, LayoutNode>,
    sortedLayers: List<Int>,
    successors: Map<String, List<String>>,
    predecessors: Map<String, List<String>>,
) {
    val zigzagOffset = 0.04f

    sortedLayers.forEach { layer ->
        val nodesInLayer = layoutNodes.values.filter { it.layer == layer }
        if (nodesInLayer.size <= 1) return@forEach

        val barycenters = nodesInLayer.associateWith { node ->
            val preds = predecessors[node.id].orEmpty()
            if (preds.isEmpty()) node.x else preds.mapNotNull { layoutNodes[it]?.x }.average().toFloat()
        }

        val sorted = nodesInLayer.sortedBy { barycenters[it] }
        val baseY = sorted.first().y - if (sorted.first().indexInLayer % 2 == 0) 0f else zigzagOffset

        sorted.forEachIndexed { index, node ->
            node.indexInLayer = index
            node.x = if (sorted.size == 1) 0.5f else 0.10f + (index.toFloat() / (sorted.size - 1)) * 0.80f
            node.y = baseY + if (index % 2 == 0) 0f else zigzagOffset
        }
    }

    sortedLayers.reversed().forEach { layer ->
        val nodesInLayer = layoutNodes.values.filter { it.layer == layer }
        if (nodesInLayer.size <= 1) return@forEach

        val barycenters = nodesInLayer.associateWith { node ->
            val succs = successors[node.id].orEmpty()
            if (succs.isEmpty()) node.x else succs.mapNotNull { layoutNodes[it]?.x }.average().toFloat()
        }

        val sorted = nodesInLayer.sortedBy { barycenters[it] }
        val baseY = sorted.first().y - if (sorted.first().indexInLayer % 2 == 0) 0f else zigzagOffset

        sorted.forEachIndexed { index, node ->
            node.indexInLayer = index
            node.x = if (sorted.size == 1) 0.5f else 0.10f + (index.toFloat() / (sorted.size - 1)) * 0.80f
            node.y = baseY + if (index % 2 == 0) 0f else zigzagOffset
        }
    }
}

private fun applyForceSimulation(
    layoutNodes: MutableMap<String, LayoutNode>,
    layerGroups: Map<Int, List<String>>,
) {
    val iterations = 80
    val repulsionStrength = 0.05f
    val minDistance = 0.18f

    repeat(iterations) { iteration ->
        val damping = 1f - (iteration.toFloat() / iterations) * 0.5f

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
