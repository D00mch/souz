## Project Structure
```text
service/telegram/
├── TelegramService.kt                 # TDLib client lifecycle, auth state, caches, and chat/contact operations
├── TelegramBotController.kt           # Control bot long-polling, inbound updates, file download, and agent handoff
├── TelegramBotWorkflow.kt             # BotFather-driven create/delete workflow and bot bootstrap steps
├── TelegramLookupEngine.kt            # Contact/chat lookup scoring, transliteration, and ambiguity handling
├── TelegramInteractiveAuthBridge.kt   # Bridges TDLib auth prompts with app-provided phone/code/password input
├── TelegramModels.kt                  # Auth, lookup, inbox, and bot task models/enums
├── TelegramPlatformSupport.kt         # Platform gating and minimum macOS version checks
├── TelegramTdlightExtensions.kt       # Coroutine bridge for TDLight `CompletableFuture` APIs
├── BotFatherReplyParser.kt            # BotFather reply parsing helpers for setup/delete automation
└── INFO.md                            # This file
```

Notes:
- `TelegramService` is the main entry point for Telegram auth/session lifecycle, cache refresh, contact/chat lookup, and inbox/message operations.
- `TelegramBotController` depends on `TelegramService.authState` and only runs bot polling while Telegram auth is `READY`.
- Bot creation/deletion progress is persisted in `ConfigStore` (`TG_BOT_*`) so pending BotFather workflows can resume after restart.
- Main regression coverage for this package is in `composeApp/src/jvmTest/kotlin/ru/souz/service/telegram/TelegramBotControllerTest.kt` and `composeApp/src/jvmTest/kotlin/ru/souz/service/telegram/BotFatherReplyParserTest.kt`.
