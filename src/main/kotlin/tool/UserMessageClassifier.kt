package com.dumch.tool

import com.dumch.giga.GigaMessageRole
import com.dumch.giga.GigaRequest
import com.dumch.giga.gigaJsonMapper
import com.fasterxml.jackson.module.kotlin.readValue

fun interface UserMessageClassifier {
    suspend fun classify(body: String): ToolCategory?
}

enum class ToolCategory {
    CODER,
    BROWSER,
    CONFIG,
    DESKTOP,
    IO,
    DATAANALYTICS,
}

object LocalRegexClassifier : UserMessageClassifier {
    override suspend fun classify(body: String): ToolCategory? {
        val chat: GigaRequest.Chat = try {
            gigaJsonMapper.readValue(body)
        } catch (_: Exception) {
            return null
        }
        val lastUser = chat.messages.lastOrNull { it.role == GigaMessageRole.user }
            ?: return null
        val text = lastUser.content
            .substringAfter("new message:\n", lastUser.content)
            .lowercase()

        val scores = CATEGORY_PATTERNS.mapValues { (_, patterns) ->
            patterns.sumOf { (regex, weight) ->
                if (regex.containsMatchIn(text)) weight else 0.0
            }
        }

        val sorted = scores.entries.sortedByDescending { it.value }
        val best = sorted.firstOrNull() ?: return null
        if (best.value == 0.0) return null
        val second = sorted.getOrNull(1)?.value ?: 0.0
        return if (best.value > second) best.key else null
    }

    private data class WeightedRegex(val regex: Regex, val weight: Double)

    private val CATEGORY_PATTERNS: Map<ToolCategory, List<WeightedRegex>> = mapOf(
        ToolCategory.CODER to listOf(
            WeightedRegex(Regex("кодер|coder"), 2.0),
            WeightedRegex(
                Regex("readme|ридми|разработ|рефактор|отрефактор|баг|композиц|наслед|абстракт|ооп|полиморф|лисков|чистый код"),
                2.0
            ),
            WeightedRegex(Regex("реализ|ошибк|open closed|абстракц"), 1.0),
            WeightedRegex(Regex("вынес|напис|поправ|измен|додел|чищ|удобн|созда"), 0.5),
        ),
        ToolCategory.BROWSER to listOf(
            WeightedRegex(Regex("http[s]?://|браузер|browser|safari"), 2.0),
            WeightedRegex(Regex("вкладк|tab|сайт|страниц|истори"), 1.0),
        ),
        ToolCategory.CONFIG to listOf(
            WeightedRegex(Regex("настрой|config|запомни инструкцию|сохрани инструкцию"), 2.0),
            WeightedRegex(Regex("громк|volume|скорост|speed|instruction|ускорь речь|замедли речь|скорость речь"), 1.0),
        ),
        ToolCategory.DESKTOP to listOf(
            WeightedRegex(Regex("перенеси окно|перейди на экран|перетащи окно|размести приложения по"), 2.0),
            WeightedRegex(Regex("окн|window|desktop"), 1.5),
            WeightedRegex(Regex("прилож|app|mouse|мыш|screen|скрин|экран"), 1.0),
        ),
        ToolCategory.IO to listOf(
            WeightedRegex(Regex("скриншот|screenshot|сфоткай экран|сфотографируй экран|что на экране|прочти весь текст"), 2.0),
            WeightedRegex(Regex("скач|download|загруз|upload|прочти экран|текст с экрана"), 1.0),
        ),
        ToolCategory.DATAANALYTICS to listOf(
            WeightedRegex(Regex("построй|созда|сделай|проанализ|график|chart|graph|plot|что на графике"), 2.0),
            WeightedRegex(Regex("найд|find|скольк|корреляци|correlation|причин|корреляции"), 1.0),
        ),
    )
}