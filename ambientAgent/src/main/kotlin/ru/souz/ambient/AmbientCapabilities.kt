package ru.souz.ambient

data class AmbientCapability(
    val id: String,
    val kind: AmbientCapabilityKind,
    val category: String,
    val name: String,
    val description: String,
    val examples: List<String> = emptyList(),
    val risk: AmbientCapabilityRisk = AmbientCapabilityRisk.UNKNOWN,
    val requiresConfirmation: Boolean = true,
)

enum class AmbientCapabilityKind {
    TOOL,
    SKILL,
}

enum class AmbientCapabilityRisk {
    LOW,
    MEDIUM,
    HIGH,
    UNKNOWN,
}

interface AmbientCapabilityProvider {
    suspend fun capabilities(): List<AmbientCapability>
}

object EmptyAmbientSkillCapabilityProvider : AmbientCapabilityProvider {
    override suspend fun capabilities(): List<AmbientCapability> = emptyList()
}

class CompositeAmbientCapabilityProvider(
    private val providers: List<AmbientCapabilityProvider>,
) : AmbientCapabilityProvider {
    override suspend fun capabilities(): List<AmbientCapability> =
        providers.flatMap { it.capabilities() }
}

class AmbientCapabilityManifestRenderer(
    private val maxChars: Int = DEFAULT_MAX_CHARS,
) {
    fun render(capabilities: List<AmbientCapability>): String {
        val full = renderLines(capabilities, descriptionLimit = 240, includeExamples = true)
        if (full.length <= maxChars) return full

        val compact = renderLines(capabilities, descriptionLimit = 40, includeExamples = false)
        if (compact.length <= maxChars) return compact

        return capabilities.joinToString("\n") { capability ->
            "${capability.id}|${capability.category}|${capability.name}"
        }.take(maxChars)
    }

    private fun renderLines(
        capabilities: List<AmbientCapability>,
        descriptionLimit: Int,
        includeExamples: Boolean,
    ): String = capabilities.joinToString("\n") { capability ->
        buildString {
            append(capability.id)
            append(" | ")
            append(capability.kind)
            append(" | ")
            append(capability.category)
            append(" | ")
            append(capability.name)
            append(" | ")
            append(capability.description.compact(descriptionLimit))
            if (includeExamples && capability.examples.isNotEmpty()) {
                append(" | examples: ")
                append(capability.examples.joinToString("; ") { it.compact(160) })
            }
        }
    }

    private fun String.compact(limit: Int): String =
        replace(Regex("\\s+"), " ").trim().let { normalized ->
            if (normalized.length <= limit) normalized else normalized.take(limit)
        }

    private companion object {
        const val DEFAULT_MAX_CHARS = 12_000
    }
}
