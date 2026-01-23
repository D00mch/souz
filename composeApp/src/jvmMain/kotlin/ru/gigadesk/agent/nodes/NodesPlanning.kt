package ru.gigadesk.agent.nodes

import io.ktor.util.logging.*
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import ru.gigadesk.agent.engine.AgentContext
import ru.gigadesk.agent.engine.Node
import ru.gigadesk.agent.planning.*
import ru.gigadesk.giga.GigaChatAPI
import ru.gigadesk.giga.GigaMessageRole
import ru.gigadesk.giga.GigaRequest
import ru.gigadesk.giga.GigaResponse
import ru.gigadesk.giga.gigaJsonMapper
import ru.gigadesk.tool.ToolCategory
import ru.gigadesk.tool.ToolsFactory
import ru.gigadesk.agent.engine.AgentSettings

class NodesPlanning(
    private val giga: GigaChatAPI,
    private val toolsFactory: ToolsFactory,
    // private val agentStateStore: AgentStateStore // Future persistence
) {
    private val l = LoggerFactory.getLogger(NodesPlanning::class.java)

    val contextGathering: Node<String, String> = Node("contextGathering") { ctx ->
        // For now, just pass through. In future, run RAG or environment checks here.
        l.info("Gathering context for complex task...")
        ctx.map { it }
    }

    val planner: Node<String, String> = Node("planner") { ctx ->
        val historyStrs = ctx.history.takeLast(10).joinToString("\n") { "${it.role}: ${it.content}" }
        
        // Check if we are replanning
        val currentPlan = ctx.plan
        val isReplanning = currentPlan != null && currentPlan.steps.any { it.status == StepStatus.FAILED }

        val systemPrompt = if (isReplanning) {
             val failedStep = currentPlan!!.steps.first { it.status == StepStatus.FAILED }
             REPLANNING_PROMPT
                 .replace("{{original_goal}}", currentPlan.goal)
                 .replace("{{current_plan_json}}", gigaJsonMapper.writeValueAsString(currentPlan))
                 .replace("{{failed_step_id}}", failedStep.id)
                 .replace("{{failed_step_tool}}", failedStep.toolName)
                 .replace("{{error_message}}", failedStep.result ?: "Unknown error")

        } else {
            val availableTools = ctx.settings.availableTools()
            val toolsPrompt = availableTools.joinToString("\n") { "- ${it.name}: ${it.description}" }
            
            PLANNING_SYSTEM_PROMPT
                .replace("{{available_tools}}", toolsPrompt)
        }

        val messages = listOf(
             GigaRequest.Message(GigaMessageRole.system, systemPrompt),
             GigaRequest.Message(GigaMessageRole.user, "Goal: ${ctx.input}\n\nContext:\n$historyStrs")
        )

        val request = GigaRequest.Chat(
            model = ctx.settings.model,
            messages = messages,
            temperature = 0.1f // Low temperature for deterministic planning
        )

        val response = giga.message(request)
        if (response is GigaResponse.Chat.Ok) {
            val content = response.choices.firstOrNull()?.message?.content ?: ""
            try {
                 // Try to find JSON block if wrapped in markdown
                 val jsonString = if (content.contains("```json")) {
                     content.substringAfter("```json").substringBefore("```")
                 } else if (content.contains("```")) {
                     content.substringAfter("```").substringBefore("```")
                 } else {
                     content
                 }
                 
                 val plan: ExecutionPlan = gigaJsonMapper.readValue(jsonString, ExecutionPlan::class.java)
                 l.info("Generated plan: $plan")
                 ctx.map(plan = plan) { it }
            } catch (e: Exception) {
                 l.error("Failed to parse plan", e)
                 // Start over or fail? For now, just return context
                 // Ideally we should have an error loop
                 ctx.map { it }
            }
        } else {
            l.error("Planner failed to get response from LLM")
            ctx.map { it }
        }
    }

    val approval: Node<String, String> = Node("approval") { ctx ->
        // When plan is generated, we just pass through.
        // The graph routing logic in GraphBasedAgent will stop execution because isApproved=false
        l.info("Plan waiting for approval: ${ctx.plan}")
        
        // Return a clear message to the user demanding approval
        // The previous node (planner) returned the original input. We overwrite it here.
        val plan = ctx.plan
        val msg = if (plan != null) {
            val stepsStr = plan.steps.joinToString("\n") { step ->
                // Try to make it readable: "Step N: ToolName (arguments...)"
                val argsStr = if (step.arguments.isNotEmpty()) {
                    step.arguments.entries.joinToString(", ") { entry -> "${entry.key}=${entry.value}" }
                } else {
                    "(без параметров)"
                }
                "  ${step.id}: ${step.toolName} $argsStr"
            }
            """
            План действий: ${plan.goal}
            
            Шаги:
            $stepsStr
            
            Всё верно? Ответьте 'Да' для подтверждения.
            """.trimIndent()
        } else {
            "План готов, ожидаю подтверждения."
        }
        
        ctx.map { msg }
    }

    val planReview: Node<String, String> = Node("planReview") { ctx ->
        val input = ctx.input.trim().lowercase().replace(Regex("[.,!?;:]"), "")
        val plan = ctx.plan ?: return@Node ctx.map { it }

        // Simple keyword check for approval
        val positiveKeywords = setOf("yes", "ok", "approve", "confirm", "proceed", "good", "go", "да", "подтверждаю", "верно", "хорошо")
        // If input contains any positive keyword and isn't too long (avoid "yes but...")
        // For MVP, if it starts with positive or is short positive
        val isApproved = positiveKeywords.any { input == it || input.startsWith("$it ") }

        if (isApproved) {
            l.info("User approved plan.")
            ctx.map(plan = plan.copy(isApproved = true)) { it }
        } else {
            l.info("User did not approve explicitly. Assuming Feedback/Replanning.")
            // Ideally we feed this back to Planner as feedback.
            ctx.map { it }
        }
    }

    val executor: Node<String, String> = Node("executor") { ctx ->
        val plan = ctx.plan ?: return@Node ctx.map { it }
        
        // Find next pending step
        val nextStep = plan.steps.find { it.status == StepStatus.PENDING }
        
        if (nextStep == null) {
            // All done?
            l.info("All steps completed.")
            return@Node ctx.map { it }
        }

        l.info("Executing step: ${nextStep.id} - ${nextStep.toolName}")

        // Execute tool
        // We need to look up tool by name. ctx.activeTools only has schemas. We need the implementation from factory.
        val toolSetup = toolsFactory.toolsByCategory.flatMap { it.value.values }.find { it.fn.name == nextStep.toolName }

        if (toolSetup == null) {
             l.error("Tool ${nextStep.toolName} not found")
             val updatedStep = nextStep.copy(status = StepStatus.FAILED, result = "Tool not found")
             val updatedPlan = updateStepInPlan(plan, updatedStep)
             return@Node ctx.map(plan = updatedPlan) { it }
        }

        try {
             l.info("Invoking tool: ${toolSetup.fn.name}")
             val call = GigaResponse.FunctionCall(
                 name = nextStep.toolName,
                 arguments = mapOfArgumentsToMap(nextStep.arguments)
             )
             val resultMsg = toolSetup.invoke(call)
             val result = resultMsg.content
             
             l.info("Tool execution success: $result")
             
             val updatedStep = nextStep.copy(status = StepStatus.SUCCESS, result = result)
             val updatedPlan = updateStepInPlan(plan, updatedStep)
             
             ctx.map(plan = updatedPlan) { it }

        } catch (e: Exception) {
             l.error("Step execution failed", e)
             val updatedStep = nextStep.copy(status = StepStatus.FAILED, result = e.message)
             val updatedPlan = updateStepInPlan(plan, updatedStep)
             ctx.map(plan = updatedPlan) { it }
        }
    }
    
    private fun mapOfArgumentsToMap(args: Map<String, Any>): Map<String, Any> {
        return args
    }

    private fun updateStepInPlan(plan: ExecutionPlan, updatedStep: PlanStep): ExecutionPlan {
        val newSteps = plan.steps.map { if (it.id == updatedStep.id) updatedStep else it }
        return plan.copy(steps = newSteps)
    }

    private fun AgentSettings.availableTools(): List<GigaRequest.Function> {
         return this.toolsByCategory.flatMap { it.value.values }.map { it.fn }
    }
}
