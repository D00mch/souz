package ru.souz.ui.settings

import ru.souz.ui.sharedsettings.SharedApiKeyFieldMode
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiKeyFieldPresentationTest {
    @Test
    fun `stored hidden field uses fixed mask and cannot edit`() {
        val ui = ApiKeyFieldState.StoredHidden.toSharedField("AI_TUNNEL", "AI Tunnel")

        assertEquals(HIDDEN_API_KEY_MASK, ui.value)
        assertEquals(SharedApiKeyFieldMode.STORED_HIDDEN, ui.mode)
    }

    @Test
    fun `revealed field exposes its real editable value`() {
        val ui = ApiKeyFieldState.Editable("ait-secret", revealed = true)
            .toSharedField("AI_TUNNEL", "AI Tunnel")

        assertEquals("ait-secret", ui.value)
        assertEquals(SharedApiKeyFieldMode.EDITABLE_REVEALED, ui.mode)
    }

    @Test
    fun `hidden editable field keeps its draft behind password transformation`() {
        val ui = ApiKeyFieldState.Editable("new-secret", revealed = false)
            .toSharedField("AI_TUNNEL", "AI Tunnel")

        assertEquals("new-secret", ui.value)
        assertEquals(SharedApiKeyFieldMode.EDITABLE_HIDDEN, ui.mode)
    }
}
