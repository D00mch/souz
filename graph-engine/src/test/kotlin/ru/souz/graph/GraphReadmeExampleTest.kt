package ru.souz.graph

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphReadmeExampleTest {
    @Test
    fun `README example graph runs end to end`() = runBlocking {
        val parse = Node<String, Int>("parse") { it.trim().toInt() }
        val double = Node<Int, Int>("double") { it * 2 }
        val render = Node<Int, String>("render") { "result=$it" }
        val reject = Node<Int, String>("reject") { "value is too large: $it" }

        val postProcess: Graph<Int, String> = buildGraph(name = "post-process") {
            nodeInput.edgeTo { value -> if (value < 10) double else reject }
            double.edgeTo(render)
            render.edgeTo(nodeFinish)
            reject.edgeTo(nodeFinish)
        }

        val pipeline = buildGraph<String, String>(
            name = "pipeline",
            retryPolicy = RetryPolicy(maxAttempts = 2) { error, _, node, _ ->
                error is NumberFormatException && node?.name?.contains("parse") == true
            },
        ) {
            nodeInput.edgeTo(parse)
            parse.edgeTo(postProcess)
            postProcess.edgeTo(nodeFinish)
        }

        val steps = mutableListOf<StepInfo>()
        val result = pipeline.start(
            seed = "4",
            onStep = { step, _, _, _ ->
                steps += step
            },
        )

        assertEquals("result=8", result)
        assertTrue(steps.any { it.graphName == "pipeline::graph" && !it.isSubgraph })
        assertTrue(steps.any { it.graphName == "post-process::graph" && it.isSubgraph })
    }
}
