# Conversation cleanup

## Invariant

Closing a local conversation clears both conversation-scoped memory and temporary Knowledge for the same user and conversation. Cleanup runs for a new conversation, explicit context clearing, and `MainViewModel` teardown.

`ChatUseCase` retains the exact `ToolInvocationMeta` passed to the latest request in the active conversation. The metadata must be captured before `AgentFacade.clearContext()` replaces the agent context, then passed unchanged to `ConversationKnowledgeStore.clearConversation`.

## Lifetime

Knowledge survives between requests in one open conversation. It is temporary and its cleanup is best effort: non-cancellation storage failures are logged and must not block closing the chat, while coroutine cancellation still propagates.

`CLEAR_CONTEXT` is reversible only for message history. It clears Knowledge immediately, so restoring the saved agent context can restore messages whose Knowledge references have expired. Backend archival is outside this local ViewModel lifecycle and is non-destructive.

## Safe changes

- Keep conversation cleanup outside Composables.
- Preserve invocation metadata rather than recreating it from defaults or only a conversation ID.
- Keep memory and Knowledge cleanup isolated so one non-cancellation failure does not prevent the other.
- Use the ViewModel scope for user-triggered closes and the application scope for teardown cleanup.

## Verification

Run `./gradlew :sharedUI:jvmTest`. Cover new-conversation, clear-context, and teardown close reasons, exact metadata forwarding, storage failure, and cancellation.
