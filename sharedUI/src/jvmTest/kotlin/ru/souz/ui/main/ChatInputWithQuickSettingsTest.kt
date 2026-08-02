package ru.souz.ui.main

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatInputWithQuickSettingsTest {
    @Test
    fun `voice toggle is enabled for active run input`() {
        assertTrue(canStartVoiceInput(false, true, null))
        assertFalse(canStartVoiceInput(false, true, "Voice input unavailable"))
    }
}
