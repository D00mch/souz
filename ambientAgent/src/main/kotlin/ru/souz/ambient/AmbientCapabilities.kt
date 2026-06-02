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
        val sortedCategories = capabilities
            .groupBy { it.category }
            .toList()
            .sortedWith(compareBy<Pair<String, List<AmbientCapability>>> { it.first.priority() }.thenBy { it.first })
        return buildString {
            sortedCategories
                .map { (category, categoryCapabilities) -> category.capabilityLine(categoryCapabilities) }
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

    private fun String.capabilityLine(capabilities: List<AmbientCapability>): String {
        val example = categoryExample(capabilities)
        return "capability|${sanitize()}|heard=${example.heard.sanitize()}|task=${example.task.sanitize()}"
    }

    private fun String.categoryExample(capabilities: List<AmbientCapability>): CategoryExample =
        CATEGORY_EXAMPLES[this]
            ?: capabilities.asSequence()
                .flatMap { it.examples.asSequence() }
                .firstOrNull()
                ?.let { example -> CategoryExample(heard = example.compact(MAX_EXAMPLE_CHARS), task = example.compact(MAX_EXAMPLE_CHARS)) }
            ?: CategoryExample(
                heard = "нужна помощь с ${lowercase()}",
                task = "Помочь с ${lowercase()}",
            )

    private fun String.compact(limit: Int): String =
        replace(Regex("\\s+"), " ").trim().let { normalized ->
            if (normalized.length <= limit) normalized else normalized.take(limit)
        }

    private fun String.sanitize(): String =
        replace(Regex("\\s+"), " ")
            .replace('|', '/')
            .trim()

    private companion object {
        const val DEFAULT_MAX_CHARS = 1_800
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
            "FILES",
            "DESKTOP",
            "IMAGE",
            "IMAGE_GENERATION",
            "DATA_ANALYTICS",
            "TEXT_REPLACE",
            "PRESENTATION",
            "CONFIG",
            "CHAT",
            "HELP",
        )
        val CATEGORY_EXAMPLES = mapOf(
            "FILES" to CategoryExample("что в загрузках", "Показать файлы в папке Загрузки"),
            "IMAGE" to CategoryExample("что на этой картинке", "Описать изображение"),
            "IMAGE_GENERATION" to CategoryExample("сгенерируй картинку кота в очках", "Сгенерировать картинку кота в очках"),
            "BROWSER" to CategoryExample("открой сайт Kotlin", "Открыть сайт Kotlin в браузере"),
            "WEB_SEARCH" to CategoryExample("интересно, какая погода в Москве", "Найти текущую погоду в Москве"),
            "CONFIG" to CategoryExample("запомни что тишина значит уменьшить громкость", "Сохранить настройку для команды тишина"),
            "NOTES" to CategoryExample("надо записать идею для проекта", "Создать заметку с идеей для проекта"),
            "APPLICATIONS" to CategoryExample("нужно открыть телеграм", "Открыть приложение Telegram"),
            "DATA_ANALYTICS" to CategoryExample("построй график продаж за неделю", "Построить график продаж за неделю"),
            "CALENDAR" to CategoryExample("нужно проверить календарь на завтра", "Проверить календарь на завтра"),
            "MAIL" to CategoryExample("надо посмотреть письмо от Анны", "Найти письмо от Анны"),
            "TEXT_REPLACE" to CategoryExample("исправь выделенный текст", "Исправить выделенный текст"),
            "CALCULATOR" to CategoryExample("сколько будет 18 процентов от 2400", "Посчитать 18 процентов от 2400"),
            "CHAT" to CategoryExample("ответь на последний вопрос", "Ответить на последний вопрос"),
            "TELEGRAM" to CategoryExample("надо написать Пете что я опоздаю", "Подготовить сообщение Пете"),
            "DESKTOP" to CategoryExample("сделай скриншот", "Сделать скриншот экрана"),
            "PRESENTATION" to CategoryExample("создай презентацию про ИИ", "Создать презентацию про ИИ"),
            "HELP" to CategoryExample("покажи что ты умеешь", "Показать доступные возможности"),
        )
    }
}

private data class CategoryExample(
    val heard: String,
    val task: String,
)
