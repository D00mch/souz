package com.dumch.giga

class LocalRegexClassifier : GigaClassifier {
    override suspend fun classify(body: GigaRequest.Chat): GigaAgent.ToolCategory? {
        val lastUser = body.messages.lastOrNull { it.role == GigaMessageRole.user }
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

    companion object {
        private data class WeightedRegex(val regex: Regex, val weight: Double)

        private val CATEGORY_PATTERNS: Map<GigaAgent.ToolCategory, List<WeightedRegex>> = mapOf(
            GigaAgent.ToolCategory.IO to listOf(
                WeightedRegex(Regex("файл|file|readme"), 2.0),
                WeightedRegex(Regex("поиск|find|создай|list|ls|cat|grep"), 1.0),
            ),
            GigaAgent.ToolCategory.BROWSER to listOf(
                WeightedRegex(Regex("http[s]?://|браузер|browser"), 2.0),
                WeightedRegex(Regex("вкладк|tab|сайт|страниц"), 1.0),
            ),
            GigaAgent.ToolCategory.CONFIG to listOf(
                WeightedRegex(Regex("настрой|config"), 2.0),
                WeightedRegex(Regex("громк|volume|скорост|speed|instruction"), 1.0),
            ),
            GigaAgent.ToolCategory.DESKTOP to listOf(
                WeightedRegex(Regex("окн|window|desktop"), 1.5),
                WeightedRegex(Regex("прилож|app|mouse|мыш|screenshot"), 1.0),
            ),
        )
    }
}
