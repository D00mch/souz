# Desktop locale

## Invariant

Desktop interface language and regional formatting are separate concerns. `DesktopSettingsHostPreferences` captures the startup default, display, and format locales before applying the persisted English/Russian interface choice. It changes the language while retaining display-locale metadata, then restores the original `Locale.Category.FORMAT` locale.

The desktop agent runtime receives the untouched startup locale before the interface locale is applied. Interface changes may update Compose resources immediately, but they must not change the locale already held by the agent runtime.

## Why it is fragile

Compose resources follow the JVM default locale, while date, number, and agent prompt behavior may also consult locale state. Treating the UI toggle as a wholesale regional change can silently alter formatting or the language context supplied to the agent.

## Safe-change guidance

- Preserve startup ordering: capture preferences, construct the agent runtime with the original locale, then apply the interface language before composing the UI.
- Retain the display locale's script, region, and extensions when switching its language.
- Restore the captured format locale after changing the JVM default.
- Persist and apply interface language through `SettingsViewModel` and its host port; composables only emit the choice.

## Verification

Run `./gradlew :sharedUI:jvmTest :desktopApp:test`. Cover language switching with non-default display metadata, preservation of the format locale, persisted fallback behavior, and the agent runtime's immutable startup locale.
