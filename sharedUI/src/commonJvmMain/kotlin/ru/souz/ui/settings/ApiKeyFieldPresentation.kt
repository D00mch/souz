package ru.souz.ui.settings

import ru.souz.ui.sharedsettings.SharedApiKeyFieldMode
import ru.souz.ui.sharedsettings.SharedApiKeyFieldUi

const val HIDDEN_API_KEY_MASK = "••••••••••••"

fun ApiKeyFieldState.toSharedField(id: String, label: String): SharedApiKeyFieldUi = when (this) {
    ApiKeyFieldState.StoredHidden -> SharedApiKeyFieldUi(
        id = id,
        label = label,
        value = HIDDEN_API_KEY_MASK,
        mode = SharedApiKeyFieldMode.STORED_HIDDEN,
    )

    ApiKeyFieldState.Revealing -> SharedApiKeyFieldUi(
        id = id,
        label = label,
        value = HIDDEN_API_KEY_MASK,
        mode = SharedApiKeyFieldMode.REVEALING,
    )

    is ApiKeyFieldState.Editable -> SharedApiKeyFieldUi(
        id = id,
        label = label,
        value = value,
        mode = if (revealed) {
            SharedApiKeyFieldMode.EDITABLE_REVEALED
        } else {
            SharedApiKeyFieldMode.EDITABLE_HIDDEN
        },
    )

    ApiKeyFieldState.RevealFailed -> SharedApiKeyFieldUi(
        id = id,
        label = label,
        value = HIDDEN_API_KEY_MASK,
        mode = SharedApiKeyFieldMode.REVEAL_FAILED,
    )
}
