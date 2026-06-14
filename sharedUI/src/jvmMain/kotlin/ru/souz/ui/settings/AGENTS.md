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
- EN/RU profile selection is shared with setup through `ui/common/RegionProfileToggle.kt`; settings uses it in the General section.
- `SettingsViewModel` is the source of truth for persisted values (`SettingsProvider`) and delegates desktop-only actions such as support logs, privacy-policy opening, Telegram bot control, voice speed, and local model UI work through common host ports.
