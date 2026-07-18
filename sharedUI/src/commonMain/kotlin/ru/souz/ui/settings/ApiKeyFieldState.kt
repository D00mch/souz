package ru.souz.ui.settings

const val HIDDEN_API_KEY_MASK = "••••••••••••"

sealed interface ApiKeyFieldState {
    data object StoredHidden : ApiKeyFieldState
    data object Revealing : ApiKeyFieldState
    data class Editable(val value: String, val revealed: Boolean) : ApiKeyFieldState
}
