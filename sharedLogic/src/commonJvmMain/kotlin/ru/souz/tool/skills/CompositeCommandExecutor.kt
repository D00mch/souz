package ru.souz.tool.skills

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.convertValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import ru.souz.agent.skills.bundle.CompositeTemplate
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import kotlin.time.TimeSource

/** Tool lookup is the caller's already-filtered snapshot; scripts use the ordinary bundle executor. */
internal suspend fun SkillCommandExecutor.executeComposite(
    bundle: SkillBundle,
    bundleHash: String,
    arguments: SkillCommandExecutor.Args,
    meta: ToolInvocationMeta,
    tools: Map<String, LLMToolSetup>,
): SandboxCommandResult {
    var location = "composite '${arguments.composite}'"
    return try {
        val spec = bundle.manifest.commands[arguments.composite]
            ?: error("Command is unavailable.")
        require(arguments.inputs.keys.containsAll(spec.inputs)) { "Missing inputs: ${spec.inputs - arguments.inputs.keys}" }
        // Reject unavailable capabilities before any earlier step can produce side effects.
        spec.steps.mapNotNull { it.tool }.forEach { name ->
            require(name in tools && name !in NON_DELEGABLE_SKILL_TOOLS && tools.getValue(name).fn.name !in NON_DELEGABLE_SKILL_TOOLS) {
                "Tool '$name' is unavailable."
            }
        }
        val context = mutableMapOf<String, JsonNode>(
            "inputs" to restJsonMapper.valueToTree(arguments.inputs.mapValues { jsonOrText(it.value) }),
        )
        fun resolve(value: Any?): JsonNode = CompositeTemplate.resolve(value) { tokens ->
            tokens.drop(1).fold(context.getValue(tokens.first())) { node, token ->
                (if (node.isArray) token.toIntOrNull()?.let(node::get) else node.get(token))
                    ?: error("Unresolved reference: ${tokens.joinToString(".")}")
            }
        }
        val timeoutMillis = arguments.timeoutMillis.coerceIn(1, SkillCommandExecutor.MAX_TIMEOUT_MILLIS)
        val started = TimeSource.Monotonic.markNow()
        withTimeoutOrNull(timeoutMillis) {
            for (step in spec.steps) {
                location = "step '${step.id}'"
                val remainingMillis = timeoutMillis - started.elapsedNow().inWholeMilliseconds
                if (remainingMillis <= 0) return@withTimeoutOrNull null
                val tool = step.tool
                context[step.id] = when {
                    tool != null -> {
                        val target = tools.getValue(tool)
                        val call = LLMResponse.FunctionCall(target.fn.name, restJsonMapper.convertValue(resolve(step.arguments)))
                        jsonOrText(target.invoke(call, meta).content)
                    }
                    step.script != null -> {
                        val result = execute(bundle, bundleHash, SkillCommandExecutor.Args(
                            runtime = SandboxCommandRuntime.valueOf(step.runtime!!.uppercase()),
                            scriptPath = step.script,
                            args = step.args.map { CompositeTemplate.text(resolve(it)) },
                            timeoutMillis = remainingMillis,
                        ), meta)
                        if (result.exitCode != 0 || result.timedOut) {
                            return@withTimeoutOrNull result.copy(stderr = "$location: ${result.stderr}")
                        }
                        TextNode(result.stdout)
                    }
                    else -> {
                        delay(step.waitMs!!)
                        NullNode.instance
                    }
                }
            }
            location = "returns"
            SandboxCommandResult(0, CompositeTemplate.text(resolve(spec.returns)), "")
        } ?: SandboxCommandResult(-1, "", "$location timed out.", timedOut = true)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        SandboxCommandResult(1, "", "$location: ${error.message}")
    }
}

private val compositeJsonReader = restJsonMapper.reader()
    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

private fun jsonOrText(value: String): JsonNode =
    runCatching { compositeJsonReader.readTree(value) }.getOrNull()?.takeUnless { it.isMissingNode } ?: TextNode(value)
