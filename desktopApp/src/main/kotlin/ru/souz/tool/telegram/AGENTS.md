## Project Structure
```text
tool/telegram/
├── ToolTelegramSend.kt                 # Send message or attachment to a resolved Telegram contact
├── ToolTelegramSavedMessages.kt        # Save text or attachment to Telegram Saved Messages
├── ToolTelegramSearch.kt               # Search Telegram messages globally or within a chat
├── ToolTelegramGetHistory.kt           # Read recent chat history for summarization workflows
├── ToolTelegramReadInbox.kt            # List unread Telegram chats from the cached inbox snapshot
├── ToolTelegramForward.kt              # Forward a message between chats with SafeMode confirmation support
├── ToolTelegramSetState.kt             # Apply chat actions such as mute, archive, mark-read, or delete
├── TelegramAttachmentPathResolver.kt   # Extract and normalize attachment paths from tool input text
├── TelegramToolResolvers.kt            # Resolve fuzzy contact/chat lookups and ambiguity flows
└── AGENTS.md                           # This file
```

Notes:
- All Telegram tools delegate Telegram API and cache access to `ru.souz.service.telegram.TelegramService`; this package mainly adapts tool inputs/outputs, permissions, and selection UX.
- `ToolTelegramSend` and `ToolTelegramSavedMessages` use `TelegramAttachmentPathResolver` so attachment paths can come either from explicit params or path-like lines embedded in the request text.
- Ambiguous fuzzy matches are routed through `ru.souz.service.telegram` broker instances. `:sharedUI` owns `ru.souz.ui.approval.TelegramSelectionApprovalSources` to turn broker requests into localized UI prompts.
- Destructive operations (`ToolTelegramForward`, `ToolTelegramSetState`) respect SafeMode and require `confirmed=true` after explicit user confirmation.
- `ToolTelegramGetHistory` resolves chat selection through `TelegramChatSelectionBroker` (via `TelegramToolResolvers`) and calls `TelegramService.getHistoryByChatId`.
- `ToolTelegramGetHistory.Input` defaults:
  - `limit = 100`
  - `forceRefresh = true`
- `ToolTelegramGetHistory` response includes:
  - `chatId` and `chatTitle` from fetched messages with fallback to selected candidate
  - `forceRefresh` echo field
  - `summaryReady` list for downstream summarization
- Package test coverage includes `desktopApp/src/test/kotlin/ru/souz/tool/telegram/ToolTelegramGetHistoryTest.kt` for default `forceRefresh=true` and explicit `limit`/`forceRefresh` propagation.
