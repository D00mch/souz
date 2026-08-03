package ru.souz.backend.llm

import ru.souz.db.SettingsProvider

internal fun SettingsProvider.hasCompleteCodexOAuthCredentials(): Boolean =
    !codexAccessToken.isNullOrBlank() &&
        !codexRefreshToken.isNullOrBlank() &&
        !codexAccountId.isNullOrBlank() &&
        codexExpiresAt != null
