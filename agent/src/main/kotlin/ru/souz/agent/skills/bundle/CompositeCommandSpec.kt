package ru.souz.agent.skills.bundle

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.TextNode
import ru.souz.llms.restJsonMapper

data class CompositeCommandSpec(
    val inputs: List<String> = emptyList(),
    val steps: List<CompositeStepSpec>,
    val returns: String,
)

data class CompositeStepSpec(
    val id: String,
    val tool: String? = null,
    val script: String? = null,
    val waitMs: Long? = null,
    val runtime: String? = null,
    val arguments: Map<String, Any?> = emptyMap(),
    val args: List<String> = emptyList(),
)

/** Shared traversal and reference syntax for manifest validation and execution. */
object CompositeTemplate {
    private val reference = Regex("""\$\{([^}]*)}""")
    private val path = Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*|\[\d+])*""")

    fun resolve(value: Any?, lookup: (List<String>) -> JsonNode): JsonNode = when (value) {
        is String -> {
            if (reference.replace(value, "").contains("\${")) throw SkillBundleException("Unclosed composite reference: $value")
            fun resolveMatch(match: MatchResult): JsonNode {
                val expression = match.groupValues[1]
                if (!path.matches(expression)) throw SkillBundleException("Invalid composite reference: ${match.value}")
                return lookup(expression.replace("[", ".").replace("]", "").split('.'))
            }
            reference.matchEntire(value)?.let(::resolveMatch)
                ?: TextNode(reference.replace(value) { text(resolveMatch(it)) })
        }
        is Map<*, *> -> restJsonMapper.createObjectNode().apply {
            value.forEach { (key, nested) ->
                val resolved = resolve(nested, lookup)
                if (!resolved.isNull && !(resolved.isTextual && resolved.textValue().isEmpty())) {
                    set<JsonNode>(key.toString(), resolved)
                }
            }
        }
        is List<*> -> restJsonMapper.createArrayNode().apply { value.forEach { add(resolve(it, lookup)) } }
        else -> restJsonMapper.valueToTree(value)
    }

    fun text(value: JsonNode): String = if (value.isTextual) value.textValue() else value.toString()
}

internal fun CompositeCommandSpec.validate(name: String) {
    fun check(valid: Boolean, message: String) {
        if (!valid) throw SkillBundleException("Composite command '$name': $message")
    }
    val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
    check(name.isNotBlank(), "name must not be blank.")
    check(inputs.all(identifier::matches) && inputs.distinct().size == inputs.size, "inputs must be unique identifiers.")
    check(steps.isNotEmpty(), "must contain at least one step.")
    val seen = mutableSetOf<String>()
    fun validateReferences(value: Any?) {
        CompositeTemplate.resolve(value) { tokens ->
            check(
                if (tokens.first() == "inputs") tokens.getOrNull(1) in inputs else tokens.first() in seen,
                "reference '${tokens.joinToString(".")}' must name a declared input or an earlier step.",
            )
            NullNode.instance
        }
    }
    steps.forEach { step ->
        check(identifier.matches(step.id) && step.id != "inputs" && step.id !in seen, "invalid or duplicate step id '${step.id}'.")
        check(listOfNotNull(step.tool, step.script, step.waitMs).size == 1, "step '${step.id}' must set exactly one of tool, script, waitMs.")
        check(step.tool == null || (step.tool.isNotBlank() && !step.tool.contains("\${")), "tool names must be static and nonblank.")
        check(step.waitMs == null || step.waitMs >= 0, "waitMs must not be negative.")
        if (step.script != null) {
            SkillPathNormalizer.normalize(step.script)
            check(!step.script.contains("\${"), "scripts need a static path.")
            check(step.runtime?.uppercase() in listOf("BASH", "PYTHON", "NODE"), "scripts require BASH, PYTHON, or NODE runtime.")
        }
        validateReferences(step.arguments)
        validateReferences(step.args)
        seen += step.id
    }
    validateReferences(returns)
}
