package ru.souz.ui.main

import kotlin.test.Test
import kotlin.test.assertEquals

class AmbientSuggestionCountdownTest {
    @Test
    fun `remaining fraction decreases from full to zero`() {
        assertEquals(1f, ambientSuggestionRemainingFraction(nowMs = 1_000L, createdAtMs = 1_000L, expiresAtMs = 11_000L))
        assertEquals(0.5f, ambientSuggestionRemainingFraction(nowMs = 6_000L, createdAtMs = 1_000L, expiresAtMs = 11_000L))
        assertEquals(0f, ambientSuggestionRemainingFraction(nowMs = 11_000L, createdAtMs = 1_000L, expiresAtMs = 11_000L))
    }

    @Test
    fun `remaining fraction clamps invalid ranges`() {
        assertEquals(0f, ambientSuggestionRemainingFraction(nowMs = 2_000L, createdAtMs = 1_000L, expiresAtMs = 1_000L))
        assertEquals(1f, ambientSuggestionRemainingFraction(nowMs = 0L, createdAtMs = 1_000L, expiresAtMs = 11_000L))
        assertEquals(0f, ambientSuggestionRemainingFraction(nowMs = 12_000L, createdAtMs = 1_000L, expiresAtMs = 11_000L))
    }
}
