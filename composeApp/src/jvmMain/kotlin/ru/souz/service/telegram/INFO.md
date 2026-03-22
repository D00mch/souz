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
- `TelegramService` is the entry point for Telegram auth/session lifecycle, cache refresh, lookup, and chat operations.
- Chat cache behavior in `TelegramService`:
  - Keeps up to `500` chats (`TELEGRAM_MAX_CHATS_CACHE`).
  - Uses warmup fetch limit `100` by default (`TELEGRAM_CHAT_CACHE_WARMUP_LIMIT`).
  - Uses concurrent chat fetches with semaphore limit `12` (`TELEGRAM_CHAT_FETCH_CONCURRENCY`).
  - Performs TDLib server search fallback (`SearchChatsOnServer`) with limit `50` (`TELEGRAM_SERVER_CHAT_SEARCH_LIMIT`) when local lookup is weak or empty.
- History behavior in `TelegramService`:
  - `getHistoryByChatId(chatId, limit, forceRefresh)` supports cached and forced reload flows.
  - Limit is clamped to `1..500` (`TELEGRAM_MAX_HISTORY_LIMIT`).
  - Fetch uses paging (`GetChatHistory`) with page size up to `100` (`TELEGRAM_HISTORY_PAGE_LIMIT`) and de-duplicates by `messageId`.
  - History cache stores up to `200` chats (`TELEGRAM_MAX_HISTORY_CHATS_CACHE`) and up to `500` messages per chat, with LRU-style eviction across chats.
  - `forceRefresh=true` replaces per-chat history cache; `forceRefresh=false` merges fetched data with cached messages.
  - Incoming messages (`updateChatFromMessage`) append into history cache; `DeleteChatHistory` removes the chat history cache entry.
- Lookup behavior in `TelegramService`:
  - Numeric `chatName` is treated as chat id and resolved directly from cache or `GetChat`.
  - Text `chatName` uses fuzzy lookup first, then cache warmup and server priming fallback.
- `TelegramBotController` depends on `TelegramService.authState` and runs polling only while auth state is `READY`.
- Bot creation/deletion progress is persisted in `ConfigStore` keys prefixed with `TG_BOT_`, enabling workflow continuation after app restart.
- Main regression coverage for this package is in `composeApp/src/jvmTest/kotlin/ru/souz/service/telegram/TelegramBotControllerTest.kt` and `composeApp/src/jvmTest/kotlin/ru/souz/service/telegram/BotFatherReplyParserTest.kt`.
