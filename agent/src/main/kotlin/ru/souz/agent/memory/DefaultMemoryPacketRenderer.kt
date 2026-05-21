package ru.souz.agent.memory

class DefaultMemoryPacketRenderer {
    fun render(input: MemoryPacketRenderInput): MemoryInjectionResult {
        val maxChars = input.maxChars.coerceAtLeast(0)
        val maxItems = input.maxItems.coerceAtLeast(0)
        if (maxChars == 0 || maxItems == 0) {
            return MemoryInjectionResult()
        }

        val renderItems = buildList {
            selectFacts(input.facts).forEachIndexed { index, fact ->
                add(
                    RenderItem(
                        packetId = fact.id ?: "fact-$index",
                        recordId = fact.id,
                        text = "- ${fact.subjectDisplayName} ${fact.predicate} = ${fact.renderValue()}",
                        sortEpochMillis = fact.createdAt.toEpochMilli(),
                    )
                )
            }
            input.episodes
                .filter { it.status.equals("ACTIVE", ignoreCase = true) }
                .sortedByDescending { it.lastTouchedAt }
                .forEachIndexed { index, episode ->
                    add(
                        RenderItem(
                            packetId = episode.id ?: "episode-$index",
                            recordId = episode.id,
                            text = buildString {
                                append("- Episode: ")
                                append(episode.title.trim())
                                append(". ")
                                append(episode.summary.trim())
                                if (!episode.nextAction.isNullOrBlank()) {
                                    append(" Next: ")
                                    append(episode.nextAction.trim())
                                }
                            },
                            sortEpochMillis = episode.lastTouchedAt.toEpochMilli(),
                        )
                    )
                }
        }.sortedByDescending { it.sortEpochMillis }

        val packets = mutableListOf<MemoryPacket>()
        val selectedRecordIds = mutableListOf<String>()
        var renderedLength = 0
        for (item in renderItems) {
            if (packets.size >= maxItems) {
                break
            }
            val separatorLength = if (packets.isEmpty()) 0 else 1
            val remaining = maxChars - renderedLength - separatorLength
            if (remaining <= 0) {
                break
            }
            val packetText = truncateToBudget(item.text, remaining)
            if (packetText.isEmpty()) {
                break
            }
            packets += MemoryPacket(recordId = item.packetId, text = packetText)
            item.recordId?.let(selectedRecordIds::add)
            renderedLength += separatorLength + packetText.length
            if (packetText.length < item.text.length) {
                break
            }
        }

        val renderedBlock = packets.joinToString(separator = "\n") { it.text }
        return MemoryInjectionResult(
            packets = packets,
            renderedBlock = renderedBlock,
            selectedRecordIds = selectedRecordIds,
            estimatedTokens = estimateTokenCount(renderedBlock),
            debugSummary = "items=${packets.size}",
        )
    }

    private fun selectFacts(facts: List<MemoryFactSnapshot>): List<MemoryFactSnapshot> {
        val seenKeys = mutableSetOf<String>()
        return facts
            .filter { it.status == MemoryFactStatus.ACTIVE }
            .sortedWith(
                compareByDescending<MemoryFactSnapshot> { it.createdAt }
                    .thenBy { normalizeKey(it.subjectNormalizedKey).orEmpty() }
                    .thenBy { normalizeKey(it.predicate).orEmpty() }
                    .thenBy { normalizeKey(it.slotKey).orEmpty() }
                    .thenBy { it.renderValue() }
            )
            .filter { fact ->
                val key = fact.slotGroupingKey() ?: fact.duplicateFingerprint()
                seenKeys.add(key)
            }
    }

    private fun truncateToBudget(text: String, maxLength: Int): String {
        if (maxLength <= 0) {
            return ""
        }
        if (text.length <= maxLength) {
            return text
        }
        if (maxLength == 1) {
            return "…"
        }
        return text.take(maxLength - 1).trimEnd() + "…"
    }

    private data class RenderItem(
        val packetId: String,
        val recordId: String?,
        val text: String,
        val sortEpochMillis: Long,
    )
}
