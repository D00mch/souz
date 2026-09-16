package ru.souz.tool.skills

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.TextNode
import kotlinx.coroutines.delay
import ru.souz.agent.SubagentTool
import ru.souz.agent.skills.bundle.CompositeStepSpec
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.state.AgentTools
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.tool.BadInputException

/**
 * Interprets a Skill manifest's declarative `commands` (`CompositeCommandSpec`, `:agent`) — a
 * linear, parse-time-validated chain of tool calls, bundled scripts, and deterministic waits —
 * so the model makes one `RunSkillCommand(arguments={command, inputs})` call instead of a
 * separately LLM-decided step for each one. Tool steps resolve through [toolCatalog]/[toolsFilter]
 * the same way [SkillCommandExecutor]'s (bridge) socket does — an in-process `LLMToolSetup.invoke`,
 * no IPC — but a step's `tool:` name must also be in [ToolInvocationMeta.ACTIVE_TOOL_NAMES_ATTRIBUTE]
 * (see [executeToolStep]) before it's looked up there: [toolCatalog] alone is the *host's* full
 * catalog, not scoped to any one invocation, so without that check a composite step could reach a
 * tool the calling agent (parent or a spawned child) was never itself granted — that attribute is
 * `AgentToolExecutor`'s own record of exactly what the calling agent could dispatch, so reusing it
 * here means a composite step can never reach further than the calling agent already could.
 * Script steps run through [runScript], which the owning [SkillCommandExecutor] binds to its own
 * script execution path with the bridge disabled — composite mode never starts it, it doesn't
 * need it.
 */
internal class CompositeCommandExecutor(
    private val toolCatalog: AgentToolCatalog?,
    private val toolsFilter: AgentToolsFilter?,
    private val runScript: suspend (
        bundle: SkillBundle,
        bundleHash: String,
        args: SkillCommandExecutor.Args,
        meta: ToolInvocationMeta,
    ) -> SandboxCommandResult,
    private val delayFn: suspend (Long) -> Unit = ::delay,
) {
    suspend fun execute(
        bundle: SkillBundle,
        bundleHash: String,
        commandName: String,
        inputs: Map<String, String>,
        meta: ToolInvocationMeta,
    ): SandboxCommandResult {
        val spec = bundle.manifest.commands[commandName]
            ?: return failure("Composite command is unavailable: $commandName")

        val context = mutableMapOf<String, JsonNode>("inputs" to inputsToNode(inputs))
        for (step in spec.steps) {
            val result = runCatching { executeStep(bundle, bundleHash, step, context, meta) }
                .getOrElse { error -> return failure("step '${step.id}' failed: ${error.message ?: error::class.simpleName}") }
            context[step.id] = result
        }

        val returned = runCatching { resolveValue(context, spec.returns) }
            .getOrElse { error -> return failure("'returns' failed: ${error.message}") }
        return SandboxCommandResult(exitCode = 0, stdout = returned.asStdout(), stderr = "")
    }

    private suspend fun executeStep(
        bundle: SkillBundle,
        bundleHash: String,
        step: CompositeStepSpec,
        context: MutableMap<String, JsonNode>,
        meta: ToolInvocationMeta,
    ): JsonNode {
        val waitMs = step.waitMs
        val tool = step.tool
        val script = step.script
        return when {
            waitMs != null -> {
                delayFn(waitMs)
                NullNode.instance
            }
            tool != null -> executeToolStep(step, context, meta)
            script != null -> executeScriptStep(bundle, bundleHash, step, context, meta)
            else -> error("composite step '${step.id}' has no kind — SkillBundleParser should have rejected this")
        }
    }

    private suspend fun executeToolStep(
        step: CompositeStepSpec,
        context: Map<String, JsonNode>,
        meta: ToolInvocationMeta,
    ): JsonNode {
        val toolName = step.tool!!
        if (toolName in RESTRICTED_TOOLS) {
            throw BadInputException("tool '$toolName' cannot be called from a composite command step.")
        }
        val allowedTools = meta.attributes[ToolInvocationMeta.ACTIVE_TOOL_NAMES_ATTRIBUTE]
            ?.split(',')
            ?.filter(String::isNotEmpty)
            ?.toSet()
            .orEmpty()
        if (toolName !in allowedTools) {
            throw BadInputException(
                "tool '$toolName' is not among the tools available to the calling agent for this invocation."
            )
        }
        val catalog = toolCatalog ?: throw BadInputException("no tool catalog is available for composite tool steps.")
        val filter = toolsFilter ?: throw BadInputException("no tool filter is available for composite tool steps.")
        val enabled = AgentTools(filter.applyFilter(catalog.toolsByCategory)).byName
        val tool = enabled[toolName] ?: throw BadInputException("tool '$toolName' is not available to this invocation.")

        val argumentsNode = resolveValue(context, step.arguments)
        @Suppress("UNCHECKED_CAST")
        val argumentsMap = restJsonMapper.convertValue(argumentsNode, Map::class.java) as Map<String, Any>
        val message = tool.invoke(LLMResponse.FunctionCall(toolName, argumentsMap), meta)
        return message.content.asJsonOrText()
    }

    private suspend fun executeScriptStep(
        bundle: SkillBundle,
        bundleHash: String,
        step: CompositeStepSpec,
        context: Map<String, JsonNode>,
        meta: ToolInvocationMeta,
    ): JsonNode {
        val argsNode = resolveValue(context, step.args)
        val args = (0 until argsNode.size()).map { argsNode[it].asArgString() }
        val runtime = runCatching { SandboxCommandRuntime.valueOf(step.runtime!!.uppercase()) }
            .getOrElse { throw BadInputException("unknown runtime '${step.runtime}' for step '${step.id}'.") }

        val result = runScript(
            bundle,
            bundleHash,
            SkillCommandExecutor.Args(runtime = runtime, scriptPath = step.script, args = args),
            meta,
        )
        if (result.exitCode != 0 || result.timedOut) {
            val timeoutNote = if (result.timedOut) " (timed out)" else ""
            throw BadInputException("script '${step.script}' exited ${result.exitCode}$timeoutNote: ${result.stderr.take(500)}")
        }
        // Unlike a tool step's result (already structured JSON from resultMessage), a script's
        // stdout is just text the author controls — it may look like JSON without meaning to be
        // reinterpreted as one (e.g. a pre-built request body string meant to flow verbatim into
        // a later tool step's argument). Keep it as text; a step that wants structured field
        // access on its own output can still get it, since ${step} substituted into a tool
        // argument is that same text, and downstream JSON.parse-style consumers (another tool
        // step's arguments) work fine against a JSON-shaped string too.
        return TextNode(result.stdout)
    }

    // --- ${...} resolution -------------------------------------------------------------------

    private val wholeReference = Regex("""^\$\{([^}]+)}$""")
    private val referenceExpression = Regex("""\$\{([^}]+)}""")
    private val pathToken = Regex("""[A-Za-z0-9_]+|\[(\d+)]""")

    /** Resolves a manifest-declared value (string, nested map/list from YAML, or scalar) against
     * [context], substituting `${...}` references. A value that is *exactly* one reference (e.g.
     * `"${inputs.actionArguments}"`) keeps that reference's real JSON type (object, array,
     * number...); a reference embedded in a larger string is stringified. Inside an object
     * (`arguments`), a field that resolves to null or an empty string is **omitted** rather than
     * sent as `""`/`null` — lets a manifest thread an optional value like `target` straight from
     * `inputs.device` without a conditional: the caller passes `""` for "not specified" (the same
     * convention `tv-control`'s SKILL.md already uses for "current device"), and the composite
     * step ends up calling the tool exactly as if that argument had never been set. */
    private fun resolveValue(context: Map<String, JsonNode>, value: Any?): JsonNode = when (value) {
        null -> NullNode.instance
        is String -> resolveString(context, value)
        is Map<*, *> -> restJsonMapper.createObjectNode().apply {
            value.forEach { (key, nested) ->
                val resolved = resolveValue(context, nested)
                if (!resolved.isOmittable()) set<JsonNode>(key.toString(), resolved)
            }
        }
        is List<*> -> restJsonMapper.createArrayNode().apply {
            value.forEach { nested -> add(resolveValue(context, nested)) }
        }
        else -> restJsonMapper.valueToTree(value)
    }

    private fun JsonNode.isOmittable(): Boolean = isNull || (isTextual && asText().isEmpty())

    private fun resolveString(context: Map<String, JsonNode>, value: String): JsonNode {
        wholeReference.matchEntire(value)?.let { match ->
            return resolvePath(context, match.groupValues[1])
        }
        val substituted = referenceExpression.replace(value) { match ->
            resolvePath(context, match.groupValues[1]).asArgString()
        }
        return TextNode(substituted)
    }

    private fun resolvePath(context: Map<String, JsonNode>, reference: String): JsonNode {
        val tokens = pathToken.findAll(reference).map { it.groupValues[1].ifEmpty { it.value } }.toList()
        val root = tokens.firstOrNull()
            ?: throw BadInputException("empty reference '\${$reference}'.")
        var node: JsonNode = context[root]
            ?: throw BadInputException("unresolved reference '\${$reference}': no input or earlier step named '$root'.")
        for (token in tokens.drop(1)) {
            node = token.toIntOrNull()?.let { node.get(it) } ?: node.get(token)
                ?: throw BadInputException("unresolved reference '\${$reference}': no field or index '$token' on '$root'.")
        }
        return node
    }

    // --- small conversions ---------------------------------------------------------------------

    private fun inputsToNode(inputs: Map<String, String>): JsonNode = restJsonMapper.createObjectNode().apply {
        inputs.forEach { (key, value) -> set<JsonNode>(key, value.asJsonOrText()) }
    }

    /** Values arriving as text (tool-call arguments, `inputs`, step outputs) are JSON when the
     * author or model wrote structured data, and plain text otherwise (a URL, a device id) — try
     * JSON first, fall back to a text node. A side effect worth knowing: a bare numeric-looking
     * string (e.g. a package name that happens to be all digits) parses as a JSON number, not
     * text — acceptable for this v1, not perfectly lossless. */
    private fun String.asJsonOrText(): JsonNode =
        runCatching { restJsonMapper.readTree(this) }.getOrNull()
            // Jackson's readTree("") returns a non-null MissingNode rather than throwing or
            // returning Kotlin null — filter it out too, or an empty string round-trips as a
            // node that is neither textual nor null and defeats isOmittable() below.
            ?.takeUnless { it.isMissingNode }
            ?: TextNode(this)

    private fun JsonNode.asArgString(): String = if (isTextual) asText() else toString()

    private fun JsonNode.asStdout(): String = if (isTextual) asText() else toString()

    private fun failure(message: String): SandboxCommandResult =
        SandboxCommandResult(exitCode = 1, stdout = "", stderr = message)

    private companion object {
        /** No re-entrant escape hatch — mirrors `SubagentToolFactory.RESTRICTED_TOOLS`. A
         * composite step's tool name is static (declared in the manifest, parse-time visible),
         * so this is defense in depth rather than the primary guard. */
        val RESTRICTED_TOOLS = setOf(
            SubagentTool.NAME,
            ToolInvokeSkill.NAME,
            ToolGetSkillByName.NAME,
            ToolGetSkillsByCategory.NAME,
            ToolGetSkillsNamesByCategory.NAME,
        )
    }
}
