## Project Structure
```text
ui/settings/
├── SettingsScreen.kt                  # Entry screen, screen switching, and ViewModel wiring
├── SettingsDTO.kt                     # Settings state/event/effect contracts
├── SettingsViewModel.kt               # Main settings orchestration and persistence
├── SettingsContent.kt                 # Section composables (models/general/keys/functions/security/support)
├── SettingsSidebar.kt                 # Left navigation and section switching UI
├── ModelAvailability.kt               # Provider/key-aware model availability and default pick helpers
├── SupportLogSender.kt                # Support log archive + mail handoff
├── FoldersManagementDTO.kt            # Folders management state/event/effect contracts
├── FoldersManagementViewModel.kt      # Forbidden folders logic
├── FoldersManagementScreen.kt         # Forbidden folders UI
├── TelegramLoginContent.kt            # Telegram login/authorization UI blocks
└── INFO.md                            # This file
```

Notes:
- `SettingsScreen` owns top-level navigation between settings sub-screens (`MAIN`, `SESSIONS`, `VISUALIZATION`, `FOLDERS`, `TELEGRAM`).
- EN/RU profile selection is shared with setup through `ui/common/RegionProfileToggle.kt`; settings uses it in the General section.
- `SettingsViewModel` is the source of truth for persisted values (`SettingsProvider` / `ConfigStore`) and for deferred save flows.
