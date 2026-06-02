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
        val sortedCapabilities = capabilities
            .sortedWith(compareBy<AmbientCapability> { it.category.priority() }.thenBy { it.id })
        return buildString {
            sortedCapabilities
                .map { capability -> "tool|${capability.id}|${capability.name.sanitize()}" }
                .forEach { line -> appendCompleteLine(line) }
            exampleLines(sortedCapabilities).forEach { line -> appendCompleteLine(line) }
            sortedCapabilities
                .map { capability -> capability.detailLine() }
                .forEach { line -> appendCompleteLine(line) }
        }
    }

    private fun String.priority(): Int =
        CATEGORY_PRIORITY.indexOf(this).takeIf { it >= 0 } ?: CATEGORY_PRIORITY.size

    private fun StringBuilder.appendCompleteLine(line: String) {
        if (line.isBlank()) return
        val extraChars = line.length + if (isEmpty()) 0 else 1
        if (length + extraChars > maxChars) return
        if (isNotEmpty()) appendLine()
        append(line)
    }

    private fun AmbientCapability.detailLine(): String {
        val compactDescription = description.compact(MAX_DESCRIPTION_CHARS)
        val example = examples.firstOrNull()
            ?.compact(MAX_EXAMPLE_CHARS)
            ?.takeIf { it.isNotBlank() }
            ?.let { "|ex=${it.sanitize()}" }
            .orEmpty()
        return "detail|$id|${compactDescription.sanitize()}$example"
    }

    private fun exampleLines(capabilities: List<AmbientCapability>): List<String> {
        val byCategory = capabilities.groupBy { it.category }
        return buildList {
            byCategory["CALENDAR"]?.pick("create", "event")?.let {
                add(
                    exampleLine(
                        heard = "надо поставить в шесть встречу с командой",
                        task = "Создать встречу с командой на 18:00",
                    )
                )
            }
            byCategory["CALENDAR"]?.pick("list", "event")?.let {
                add(
                    exampleLine(
                        heard = "нужно проверить календарь на завтра",
                        task = "Проверить события в календаре на завтра",
                    )
                )
            }
            byCategory["WEB_SEARCH"]?.pick("internet", "search")?.let {
                add(
                    exampleLine(
                        heard = "интересно, какая погода в Москве",
                        task = "Найти текущую погоду в Москве",
                    )
                )
            }
            byCategory["NOTES"]?.pick("create", "note")?.let {
                add(
                    exampleLine(
                        heard = "надо записать идею для проекта",
                        task = "Создать заметку с идеей для проекта",
                    )
                )
            }
            byCategory["APPLICATIONS"]?.pick("open")?.let {
                add(
                    exampleLine(
                        heard = "нужно открыть телеграм",
                        task = "Открыть приложение Telegram",
                    )
                )
            }
            byCategory["MAIL"]?.pick("search")?.let {
                add(
                    exampleLine(
                        heard = "надо посмотреть письмо от Анны",
                        task = "Найти письмо от Анны",
                    )
                )
            }
            byCategory["TELEGRAM"]?.pick("send")?.let {
                add(
                    exampleLine(
                        heard = "надо написать Пете что я опоздаю",
                        task = "Подготовить сообщение Пете",
                    )
                )
            }
            byCategory["BROWSER"]?.pick("open")?.let {
                add(
                    exampleLine(
                        heard = "нужно открыть сайт Kotlin",
                        task = "Открыть сайт Kotlin в браузере",
                    )
                )
            }
            byCategory["CALCULATOR"]?.pick("calculate")?.let {
                add(
                    exampleLine(
                        heard = "сколько будет 18 процентов от 2400",
                        task = "Посчитать 18 процентов от 2400",
                    )
                )
            }
        }
    }

    private fun List<AmbientCapability>.pick(vararg tokens: String): AmbientCapability? {
        val normalizedTokens = tokens.map { it.lowercase() }
        return firstOrNull { capability ->
            val haystack = "${capability.name} ${capability.description}".lowercase()
            normalizedTokens.all { token -> token in haystack }
        } ?: firstOrNull()
    }

    private fun exampleLine(
        heard: String,
        task: String,
    ): String = "example|heard=${heard.sanitize()}|task=${task.sanitize()}"

    private fun String.compact(limit: Int): String =
        replace(Regex("\\s+"), " ").trim().let { normalized ->
            if (normalized.length <= limit) normalized else normalized.take(limit)
        }

    private fun String.sanitize(): String =
        replace(Regex("\\s+"), " ")
            .replace('|', '/')
            .trim()

    private companion object {
        const val DEFAULT_MAX_CHARS = 900
        const val MAX_DESCRIPTION_CHARS = 120
        const val MAX_EXAMPLE_CHARS = 90
        val CATEGORY_PRIORITY = listOf(
            "CALENDAR",
            "WEB_SEARCH",
            "NOTES",
            "APPLICATIONS",
            "MAIL",
            "TELEGRAM",
            "BROWSER",
            "CALCULATOR",
        )
    }
}
