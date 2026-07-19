## Project Structure
```text
ui/settings/
├── SettingsScreen.kt                  # Desktop entry screen, screen switching, and ViewModel wiring
├── SettingsContent.kt                 # Section composables (models/general/keys/functions/security/support)
├── SupportLogSender.kt                # Support log archive + mail handoff
├── TelegramLoginContent.kt            # Telegram login/authorization UI blocks
└── AGENTS.md                          # This file
```

Notes:
- `SettingsViewModel`, settings DTOs, model availability helpers, settings sidebar, and folder management DTO/ViewModel now live in `sharedUI/src/commonJvmMain/kotlin/ru/souz/ui/settings`; desktop-only settings screens stay here in `jvmMain`.
- `SettingsScreen` owns top-level navigation between settings sub-screens (`MAIN`, `SESSIONS`, `VISUALIZATION`, `FOLDERS`, `TELEGRAM`).
- The reusable `ui/common/LanguageToggle.kt` backs separate EN/RU controls for the regional profile and desktop interface language.
- `SettingsViewModel` is the source of truth for persisted values (`SettingsProvider`) and delegates desktop-only actions such as interface-language persistence/application, support logs, privacy-policy opening, Telegram bot control, voice speed, and local model UI work through common host ports. Compose Resources on desktop follows the JVM default locale, so the desktop host controller applies the selected language with the startup display locale's metadata and restores the original format locale; desktop DI keeps the agent runtime on the original locale.
