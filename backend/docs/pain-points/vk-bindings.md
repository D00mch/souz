# VK bindings

## Invariants

VK binding routes use trusted-proxy identity and require an owned chat. Tokens are encrypted with AES-GCM; only token and one-time link-secret hashes are searchable. API responses contain safe metadata and expose the secret only on binding. The community and token are each unique across bindings.

Only an incoming private message from a positive user ID can link an account, using the exact trimmed secret. Display names do not establish ownership. Rebinding replaces the internal binding ID and invalidates old leases, secrets, and cached poll sessions. The linking message ID prevents replayed linking batches from submitting the secret or earlier messages to the agent.

Polling uses a renewable database lease. Claims, cursor writes, and error writes require an enabled binding and an unexpired matching owner; replies check the same ownership immediately before sending. Renewal cannot resurrect an expired lease. External sends cannot be atomic with a database lease check, so an already-started send can finish after a concurrent rebind or lease loss.

The Long Poll `ts` is an opaque batch cursor. Persist the initial cursor before processing and advance it only after the batch completes. Handle `failed=1` by replacing the cursor, `2` by refreshing the key/server, and `3` by refreshing the whole session. VK can discard old events in the last case. Turn IDs use the binding and VK message IDs so replay does not execute the agent again. Failed delivery leaves the cursor for retry; previously delivered replies in that batch may repeat.

CommonMark renders Markdown before chunking. VK receives plain text plus `format_data` version 1 for bold, italic, headings, and HTTP(S) links; code stays literal and lists/quotes retain text markers. Ranges use Unicode code points for API 5.199, following the [published compatibility probe](https://pypi.org/project/hermes-vk-community/). VK's public schema omits `format_data`; verify wire-format changes in a real client. Reserve an extra unit per `@` when splitting.

Complete cross-channel delivery persists the original Markdown. Partial delivery persists only accepted rendered text. Polling checks lease ownership before every formatted chunk. Plain service replies omit `format_data`.

## Safe changes

- Keep token contents and VK error bodies out of responses and logs. VK has no plaintext token migration path.
- API and Long Poll requests share the web tools' `SOUZ_WEB_USER_AGENT` setting and default.
- Preserve lease fencing and message-based execution identity when changing polling or persistence.
- Each enabled binding owns its poll loop and cached session. Limit update processing, never idle long polls, and recheck lease ownership after waiting for a processing permit.
- Split rendered text and clip/rebase formatting ranges per chunk; keep shared delivery bookkeeping. Only successfully sent chunks belong in cross-channel chat history.
- VK execution has the same process-local crash-recovery limitation as ordinary HTTP and Telegram execution; the binding lease does not own the agent runtime.

## Verification

Run `./gradlew :backend:test`. The VK workflow suite covers binding, private linking, replay, ownership, polling recovery, lease loss, and channel tools. Focused tests cover HTTP encoding/error handling and database lease fencing.

Use the shared [manual polling checks](testing.md#verification) to verify cross-binding independence and cancellation.
