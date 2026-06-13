package ru.souz.ui.main

import kotlin.test.Test
import kotlin.test.assertEquals

class AmbientSuggestionCountdownTest {
    @Test
    fun `remaining fraction decreases from full to zero`() {
        val suggestion = suggestion(createdAtMs = 1_000L, expiresAtMs = 11_000L)

        assertEquals(1f, suggestion.remainingFraction(nowMs = 1_000L))
        assertEquals(0.5f, suggestion.remainingFraction(nowMs = 6_000L))
        assertEquals(0f, suggestion.remainingFraction(nowMs = 11_000L))
    }

    @Test
    fun `remaining fraction clamps invalid ranges`() {
        assertEquals(0f, suggestion(createdAtMs = 1_000L, expiresAtMs = 1_000L).remainingFraction(nowMs = 2_000L))
        assertEquals(1f, suggestion(createdAtMs = 1_000L, expiresAtMs = 11_000L).remainingFraction(nowMs = 0L))
        assertEquals(0f, suggestion(createdAtMs = 1_000L, expiresAtMs = 11_000L).remainingFraction(nowMs = 12_000L))
    }

    private fun suggestion(
        createdAtMs: Long,
        expiresAtMs: Long,
    ): AmbientSuggestionUiModel = AmbientSuggestionUiModel(
        id = "s1",
        suggestionText = "Помочь: «задача»?",
        taskText = "задача",
        createdAtMs = createdAtMs,
        expiresAtMs = expiresAtMs,
    )
}
