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
├── SelectionBroker.kt                  # Generic async broker for user-driven candidate selection
├── TelegramContactSelectionBroker.kt   # Contact selection broker/typealias for ambiguous contact matches
├── TelegramChatSelectionBroker.kt      # Chat selection broker/typealias for ambiguous chat matches
├── TelegramSelectionApprovalSources.kt # Convert broker requests into UI approval prompts
└── INFO.md                             # This file
```

Notes:
- All Telegram tools delegate Telegram API and cache access to `ru.souz.service.telegram.TelegramService`; this package mainly adapts tool inputs/outputs, permissions, and selection UX.
- `ToolTelegramSend` and `ToolTelegramSavedMessages` use `TelegramAttachmentPathResolver` so attachment paths can come either from explicit params or path-like lines embedded in the request text.
- Ambiguous fuzzy matches are routed through `SelectionBroker` instances and surfaced to the UI via `TelegramSelectionApprovalSources` before a tool continues.
- Destructive operations (`ToolTelegramForward`, `ToolTelegramSetState`) respect SafeMode and require `confirmed=true` after explicit user confirmation.
- There is currently no package-specific `jvmTest` coverage under `composeApp/src/jvmTest/kotlin/ru/souz/tool/telegram`.
