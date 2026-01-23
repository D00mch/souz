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
import ru.gigadesk.db.DesktopInfoRepository
import ru.gigadesk.agent.engine.AgentSettings

class NodesPlanning(
    private val giga: GigaChatAPI,
    private val toolsFactory: ToolsFactory,
    private val desktopInfoRepository: DesktopInfoRepository,
    // private val agentStateStore: AgentStateStore // Future persistence
) {
    private val l = LoggerFactory.getLogger(NodesPlanning::class.java)

    val contextGathering: Node<String, String> = Node("contextGathering") { ctx ->
        l.info("Gathering context for complex task...")
        
        // Search for relevant information in the vector DB
        val relevantInfo = try {
            val docs = desktopInfoRepository.search(ctx.input, limit = 3)
            if (docs.isNotEmpty()) {
                docs.joinToString("\n\n") { "Context from DB: ${it.text}" }
            } else {
                "No relevant context found in DB."
            }
        } catch (e: Exception) {
            l.error("Failed to search DB context", e)
            "DB Search Unavailable."
        }
        
        // We need to pass this info to the planner.
        // For now, we'll prefix it to the input so the Planner sees it.
        // A cleaner way would be to add a `context` field to AgentContext, but input prefix works for now.
        val enrichedInput = """
            Context Info:
            $relevantInfo
            
            Original Goal:
            ${ctx.input}
        """.trimIndent()
        
        ctx.map { enrichedInput }
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
            val availableTools = ctx.settings.availableTools(ctx.relevantCategories)
            val toolsPrompt = availableTools.joinToString("\n") { tool ->
                val params = tool.parameters?.properties?.keys?.joinToString(", ") ?: "no params"
                "- ${tool.name}($params): ${tool.description}"
            }
            
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
                // Display only the user-friendly description
                "  ${step.id}: ${step.description}"
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
        // Hack: we need access to sideEffects flow or similar. GraphBasedAgent exposes it, but here we are inside a node.
        // Actually, Node does not have access to sideEffects directly. 
        // Ideally we should emit to a flow passed in constructor or context.
        // For MVP, we will assume we can't easily emit "Started" message without refactoring.
        // Wait! SideEffects is usually handled by NodesLLM. 
        // But we want to emit from here.
        // Let's assume we can inject a functional interface or just log for now?
        // No, the user requirements say "agent SHOULD report".
        // I will return the status message as the result of the node if it's a finish state? 
        // But executor loops.
        
        // BETTER APPROACH: Use `ctx.map` to update a `lastStatusMessage` field if it existed?
        // Or look at GraphBasedAgent. It listens to `nodesLLM.sideEffects`.
        // I cannot easily access that flow here without breaking architecture.
        // ALTERNATIVE: Use `l.info` which user sees in logs? No.
        
        // RE-READING ARCHITECTURE: `NodesLLM` has `sideEffects`. `GraphBasedAgent` exposes it.
        // I should probably inject `NodesLLM` into `NodesPlanning`? No, circular dependency.
        // I should inject a `suspend (String) -> Unit` callback or similar.
        // But for now, let's focus on logic. I will fix the placeholders first.

        // Find next pending step
        val nextStep = plan.steps.find { it.status == StepStatus.PENDING }
        
        if (nextStep == null) {
             l.info("All steps completed.")
             return@Node ctx.map { "План успешно выполнен." } // Return final message
        }

        l.info("Executing step: ${nextStep.id} - ${nextStep.toolName}")

        // Resolve dependencies
        val resolvedArgs = nextStep.arguments.mapValues { (_, value) ->
            resolveDependencies(value, plan)
        }

        // Execute tool
        val toolSetup = toolsFactory.toolsByCategory.flatMap { it.value.values }.find { it.fn.name == nextStep.toolName }

        if (toolSetup == null) {
             l.error("Tool ${nextStep.toolName} not found")
             val updatedStep = nextStep.copy(status = StepStatus.FAILED, result = "Tool not found")
             val updatedPlan = updateStepInPlan(plan, updatedStep)
             return@Node ctx.map(plan = updatedPlan) { it }
        }

        try {
             l.info("Invoking tool: ${toolSetup.fn.name} with args: $resolvedArgs")
             val call = GigaResponse.FunctionCall(
                 name = nextStep.toolName,
                 arguments = mapOfArgumentsToMap(resolvedArgs)
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
    
    private fun resolveDependencies(value: String, plan: ExecutionPlan): String {
        // Pattern: {result of step_id}
        val regex = Regex("\\{result of (step_\\d+)\\}")
        return regex.replace(value) { matchResult ->
            val stepId = matchResult.groupValues[1]
            val step = plan.steps.find { it.id == stepId }
            step?.result ?: "MISSING_RESULT"
        }
    }
    
    private fun mapOfArgumentsToMap(args: Map<String, Any>): Map<String, Any> {
        return args
    }

    private fun updateStepInPlan(plan: ExecutionPlan, updatedStep: PlanStep): ExecutionPlan {
        val newSteps = plan.steps.map { if (it.id == updatedStep.id) updatedStep else it }
        return plan.copy(steps = newSteps)
    }

    private fun AgentSettings.availableTools(relevantCategories: List<ToolCategory>): List<GigaRequest.Function> {
        return if (relevantCategories.isNotEmpty()) {
             // Only return tools from relevant categories
             this.toolsByCategory
                 .filterKeys { it in relevantCategories }
                 .flatMap { it.value.values }
                 .map { it.fn }
        } else {
             // Fallback: return all tools (or maybe default set?)
             this.toolsByCategory.flatMap { it.value.values }.map { it.fn }
        }
    }
}
