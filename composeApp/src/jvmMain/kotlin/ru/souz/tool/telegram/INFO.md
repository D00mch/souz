## Project Structure
```text
tool/telegram/
├── ToolTelegramGetHistory.kt          # Read/analyze chat history and warm message cache
├── ToolTelegramReadInbox.kt           # Return unread chats summary from the Telegram cache
├── ToolTelegramSearch.kt              # Search Telegram messages globally or inside one chat
├── ToolTelegramSend.kt                # Send text/files to Telegram contacts
├── ToolTelegramForward.kt             # Forward a Telegram message between chats
├── ToolTelegramSavedMessages.kt       # Send text/files to Saved Messages
├── ToolTelegramSetState.kt            # Archive, mute, mark-read, or delete Telegram chats
├── TelegramToolResolvers.kt           # Shared chat/contact lookup + ambiguity handling helpers
├── TelegramAttachmentPathResolver.kt  # Resolve file attachments embedded in tool input text
├── SelectionBroker.kt                 # Generic async selection broker for ambiguous Telegram targets
├── TelegramChatSelectionBroker.kt     # Chat selection broker specialization
├── TelegramContactSelectionBroker.kt  # Contact selection broker specialization
├── TelegramSelectionApprovalSources.kt# UI approval adapters for Telegram selections
└── INFO.md                            # This file
```

Notes:
- `ToolTelegramGetHistory` is the entrypoint for explicit “inspect/read/analyze Telegram chat history” requests. If the user does not specify a message count, keep the default `limit = 100` and `forceRefresh = true` so the service warms fresh history instead of relying on whatever is already cached.
- `ToolTelegramReadInbox` is summary-only. If the user wants real chat content analysis, the agent should follow up with `ToolTelegramGetHistory` or `ToolTelegramSearch`.
- Chat/contact tools that operate on a specific target should resolve ambiguity through the selection brokers instead of silently taking the top fuzzy match.
