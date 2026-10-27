# Telegram runtime and tool safety

## Invariant

`TelegramService` owns TDLib authorization, lookups, chat/history caches, and Telegram operations. Bot polling and resumable BotFather workflows run only when authorization is `READY`; workflow progress is persisted so an interrupted create/delete operation can continue after restart.

A forced history refresh replaces that chat's cached history, while a regular fetch merges and de-duplicates results. Incoming messages update the cache and history deletion invalidates it. Telegram tools delegate service access, route ambiguous matches through selection brokers, and leave localized approval UI to `:sharedUI`.

Send and Saved Messages tools resolve attachments through `TelegramAttachmentPathResolver`. Forwarding and state-changing tools must honor SafeMode and reject execution until the caller repeats the operation with explicit confirmation.

`TelegramService` rejects sends and forwards to the configured control bot, using the bot token’s numeric user ID and the resolved destination rather than cached names or message text. This restriction is independent of SafeMode and keeps agent tools from issuing owner approval commands through the owner’s account. The identity is read for each operation; an unidentifiable configured bot blocks these writes until its configuration is corrected. Other recipients and Saved Messages remain available.

Mail tools open drafts by default. With `send=true`, they prepare a temporary hidden message, capture Mail’s sender, subject, full body and resolved To/Cc/Bcc addresses, and close it without saving. The shared `MailSendApproval` retains one immutable snapshot for ten minutes; a new request replaces the old one. The owner reviews it with `/mail_preview <token>` in the Telegram control bot, then explicitly sends with `/mail_send <token>` or cancels with `/mail_cancel <token>`. These raw-text commands are handled after owner/private-chat authorization and outside the agent, including when the shared UI subscribes to bot messages. Every preview chunk must be delivered before approval is enabled. Failed updates are isolated so polling advances without replaying earlier tool side effects; an incomplete mail preview remains unapproved. Model-generated `confirmed` flags cannot approve sending; synchronous send calls fail closed. Approval is consumed before shell work, and `/clean` clears pending mail. Sending recreates the message and compares every snapshot field before transmission, including Mail’s Reply-To resolution and any Cc/Bcc recipients. Changes require a new preview. Confirmation is independent of SafeMode. Approval and send scripts use process arguments and neither activate Mail nor open desktop dialogs.

Mail preview chunks and the approval prompt are paced one second apart, following [Telegram's per-chat guidance](https://core.telegram.org/bots/faq#my-bot-is-hitting-limits-how-do-i-avoid-this). A failed or rate-limited `/mail_preview` retains successfully delivered chunks; repeating the command resumes at the first undelivered chunk or the approval prompt. Sending stays disabled until the complete preview and prompt have been delivered.

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
