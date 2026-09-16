package ru.souz.agent.skills.bundle

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

object SkillBundleParser {

    private val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class RawManifest(
        val name: String? = null,
        val description: String? = null,
        val author: String? = null,
        val version: String? = null,
        val oauthProvider: String? = null,
        val oauthScopes: List<String>? = null,
        val metadata: Map<String, String>? = null,
        val commands: Map<String, CompositeCommandSpec>? = null,
    )

    fun parse(markdown: String): ParsedSkillMarkdown {
        val normalized = markdown.replace("\r\n", "\n")
        val secondDelimiterIndex = closingDelimiterIndex(normalized)
        val frontmatter = normalized.substring(4, secondDelimiterIndex).trim()
        val body = normalized.substring(secondDelimiterIndex + "\n---\n".length).trim()
        return ParsedSkillMarkdown(
            manifest = parseManifestFrontmatter(frontmatter),
            body = body,
        )
    }

    fun parseManifest(markdown: String): SkillManifest {
        val normalized = markdown.replace("\r\n", "\n")
        val secondDelimiterIndex = closingDelimiterIndex(normalized)
        val frontmatter = normalized.substring(4, secondDelimiterIndex).trim()
        return parseManifestFrontmatter(frontmatter)
    }

    private fun closingDelimiterIndex(normalized: String): Int {
        if (!normalized.startsWith("---\n")) {
            throw SkillBundleException("SKILL.md must start with YAML frontmatter.")
        }

        val secondDelimiterIndex = normalized.indexOf("\n---\n", startIndex = 4)
        if (secondDelimiterIndex < 0) {
            throw SkillBundleException("SKILL.md is missing a closing YAML frontmatter delimiter.")
        }
        return secondDelimiterIndex
    }

    private fun parseManifestFrontmatter(frontmatter: String): SkillManifest {
        val raw = try {
            yamlMapper.readValue(frontmatter, RawManifest::class.java) ?: RawManifest()
        } catch (e: MismatchedInputException) {
            throw SkillBundleException("SKILL.md frontmatter field '${e.path.lastOrNull()?.fieldName}' has the wrong shape: ${e.originalMessage}", e)
        } catch (e: Exception) {
            throw SkillBundleException("SKILL.md frontmatter is not valid YAML: ${e.message}", e)
        }

        val name = raw.name?.takeIf { it.isNotBlank() }
            ?: throw SkillBundleException("SKILL.md frontmatter is missing required field: name")
        val description = raw.description?.takeIf { it.isNotBlank() }
            ?: throw SkillBundleException("SKILL.md frontmatter is missing required field: description")

        val commands = raw.commands.orEmpty()
        commands.forEach { (commandName, spec) -> validateComposite(commandName, spec) }

        return SkillManifest(
            name = name,
            description = description,
            author = raw.author?.takeIf { it.isNotBlank() },
            version = raw.version?.takeIf { it.isNotBlank() },
            oauthProvider = raw.oauthProvider?.takeIf { it.isNotBlank() },
            oauthScopes = raw.oauthScopes.orEmpty(),
            metadata = raw.metadata.orEmpty(),
            commands = commands,
            rawFrontmatter = frontmatter,
        )
    }

    private val referenceExpression = Regex("""\$\{([^}]+)}""")

    /**
     * Keeps composite commands linear and statically checkable at parse time, per the v1 scope
     * (no loops, no conditionals, no expression language): every step is exactly one of
     * tool/script/waitMs, script steps declare their runtime explicitly (the same BASH-default
     * gotcha `RunSkillCommand` already has), step ids are unique, and every `${...}` reference in
     * `arguments`/`args`/`returns` names either a declared input or an earlier step's id.
     */
    private fun validateComposite(commandName: String, spec: CompositeCommandSpec) {
        fun fail(message: String): Nothing =
            throw SkillBundleException("SKILL.md composite command '$commandName' $message")

        if (spec.steps.isEmpty()) fail("must declare at least one step.")

        val seenIds = mutableSetOf<String>()
        spec.steps.forEach { step ->
            val kindCount = listOfNotNull(step.tool, step.script, step.waitMs).size
            if (kindCount != 1) {
                fail("step '${step.id}' must set exactly one of tool, script, or waitMs.")
            }
            if (step.script != null && step.runtime.isNullOrBlank()) {
                fail("step '${step.id}' has a script but no explicit runtime.")
            }
            if (!seenIds.add(step.id)) {
                fail("has a duplicate step id: '${step.id}'.")
            }
            referencesIn(step.arguments.values.asSequence() + step.args.asSequence()).forEach { reference ->
                validateReference(commandName, spec, step.id, seenIds, reference, ::fail)
            }
        }
        referencesIn(sequenceOf(spec.returns)).forEach { reference ->
            validateReference(commandName, spec, stepId = null, declaredSoFar = seenIds, reference, ::fail)
        }
    }

    private fun referencesIn(values: Sequence<Any?>): List<String> =
        values.flatMap { value ->
            when (value) {
                is String -> referenceExpression.findAll(value).map { it.groupValues[1] }.toList()
                is Map<*, *> -> referencesIn(value.values.asSequence())
                is List<*> -> referencesIn(value.asSequence())
                else -> emptyList()
            }
        }.toList()

    private val pathToken = Regex("""[A-Za-z0-9_]+|\[(\d+)]""")

    /** Splits `inputs.device` / `screenshot.content[0].uri` into `["inputs","device"]` /
     * `["screenshot","content","0","uri"]` — shared shape with the runtime resolver in
     * `CompositeCommandExecutor` (`:sharedLogic`), duplicated rather than shared across modules
     * for a helper this small. */
    private fun referencePathTokens(reference: String): List<String> =
        pathToken.findAll(reference).map { it.groupValues[1].ifEmpty { it.value } }.toList()

    private fun validateReference(
        commandName: String,
        spec: CompositeCommandSpec,
        stepId: String?,
        declaredSoFar: Set<String>,
        reference: String,
        fail: (String) -> Nothing,
    ) {
        val tokens = referencePathTokens(reference)
        val root = tokens.firstOrNull() ?: fail("has an empty '\${}' reference.")
        if (root == "inputs") {
            val inputName = tokens.getOrNull(1)
            if (inputName !in spec.inputs) {
                fail("references undeclared input 'inputs.$inputName'.")
            }
            return
        }
        if (root !in declaredSoFar) {
            val where = stepId?.let { "step '$it'" } ?: "'returns'"
            fail("$where references '$root', which is not an earlier step id or a declared input.")
        }
    }

    data class ParsedSkillMarkdown(
        val manifest: SkillManifest,
        val body: String,
    )
}
