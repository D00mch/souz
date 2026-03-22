## Project Structure
```text
service/telegram/
├── TelegramService.kt                 # Main Telegram auth/session service, chat lookup, history fetch, send/forward/search flows
├── TelegramLookupEngine.kt            # Fuzzy matching and ranking for chats/contacts from cached Telegram data
├── TelegramModels.kt                  # Auth, cache, lookup, inbox, and message DTOs
├── TelegramBotController.kt           # Telegram control-bot polling and agent handoff
├── TelegramBotWorkflow.kt             # BotFather automation flow for creating/deleting the control bot
├── TelegramInteractiveAuthBridge.kt   # TDLib interactive auth bridge for phone/code/password prompts
├── TelegramTdlightExtensions.kt       # TDLight async helpers
├── TelegramPlatformSupport.kt         # Runtime gating and platform support checks
├── BotFatherReplyParser.kt            # BotFather response parsing helpers
└── INFO.md                            # This file
```

Notes:
- `TelegramService` owns the in-memory chat/contact caches plus an LRU-like history cache capped at 200 chats; per-chat history windows are capped at 500 messages.
- `refreshTopChatsCache()` intentionally warms only a bounded top-chat window (default 100 chats with limited parallel `GetChat` fetches), but `readUnreadInbox(limit)` warms `max(limit, 100)` chats so small unread limits do not hide unread chats deeper in the list.
- Chat resolution is two-stage: local fuzzy cache first, then TDLib `SearchChatsOnServer` fallback for already-known chats that are missing from the warm cache.
- `getHistoryByChatId()` paginates `GetChatHistory` because TDLib can return fewer messages than requested in one page; explicit history reads can force-refresh and repopulate the local history cache.
- `forwardMessageByChatIds(..., messageId = "last")` must bypass stale history cache and refresh the latest message from Telegram before resolving the forwarded message ID.
