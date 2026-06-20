package ru.souz.ui.main

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `ambient suggestion prompt formats resource string after lookup`() {
        val source = mainScreenSource()

        assertTrue(
            source.contains("stringResource(Res.string.ambient_suggestion_prompt).format(suggestion.taskText)"),
            "Ambient suggestion prompt should format %s through the project-standard String.format path.",
        )
        assertFalse(
            source.contains("stringResource(Res.string.ambient_suggestion_prompt, suggestion.taskText)"),
            "Compose resource vararg formatting left %s visible in the ambient suggestion prompt.",
        )
    }

    private fun mainScreenSource(): String {
        val candidates = listOf(
            File("sharedUI/src/jvmMain/kotlin/ru/souz/ui/main/MainScreen.kt"),
            File("src/jvmMain/kotlin/ru/souz/ui/main/MainScreen.kt"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("MainScreen.kt source was not found")
    }

    private fun suggestion(
        createdAtMs: Long,
        expiresAtMs: Long,
    ): AmbientSuggestionUiModel = AmbientSuggestionUiModel(
        id = "s1",
        taskText = "задача",
        createdAtMs = createdAtMs,
        expiresAtMs = expiresAtMs,
    )
}
