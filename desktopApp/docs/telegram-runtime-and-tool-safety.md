# Telegram runtime and tool safety

## Invariant

`TelegramService` owns TDLib authorization, lookups, chat/history caches, and Telegram operations. Bot polling and resumable BotFather workflows run only when authorization is `READY`; workflow progress is persisted so an interrupted create/delete operation can continue after restart.

A forced history refresh replaces that chat's cached history, while a regular fetch merges and de-duplicates results. Incoming messages update the cache and history deletion invalidates it. Telegram tools delegate service access, route ambiguous matches through selection brokers, and leave localized approval UI to `:sharedUI`.

Send and Saved Messages tools resolve attachments through `TelegramAttachmentPathResolver`. Forwarding and state-changing tools must honor SafeMode and reject execution until the caller repeats the operation with explicit confirmation.

## Why it is fragile

Authorization, TDLib updates, cache fetches, bot polling, and UI selections are asynchronous. Bypassing their owners can create stale histories, duplicate polling, unresolved selection continuations, or destructive actions without a visible approval step.

## Safe-change guidance

- Keep API/cache work in `TelegramService`; tools should adapt inputs, outputs, permissions, and selection results.
- Preserve replace-versus-merge behavior and cache invalidation when changing history retrieval.
- Keep bot workflow checkpoints durable and gate bot activity on `READY` authorization.
- Use the existing brokers for ambiguity and the shared UI approval sources for prompts.
- Normalize attachment paths through the resolver and retain the SafeMode `confirmed` handshake for destructive operations.

## Verification

Run `./gradlew :desktopApp:test`. Cover bot readiness and workflow resumption, cache refresh propagation, ambiguous selection, attachment extraction, and SafeMode rejection/confirmation.
