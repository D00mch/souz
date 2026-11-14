# VK bindings

## Setup

Set `ENABLE_BACKEND_VK_FEATURE=true` and supply `VK_TOKEN_ENCRYPTION_KEY` as 32 bytes encoded in Base64. Generate the key with `openssl rand -base64 32`. Enable Bots Long Poll and `message_new` events in the community settings.

Use trusted-proxy `PUT /v1/chats/{chatId}/vk-bot` with `{"token":"..."}` to bind a community. Send the returned `pendingLinkCommand` to the community in a private message. Use `GET` or `DELETE` on the same route to inspect or remove the binding. [Route metadata](../src/main/kotlin/ru/souz/backend/http/routes/VkRoutes.kt)

## Invariants

VK binding routes use trusted-proxy identity and require an owned chat. Tokens are encrypted with AES-GCM; only token and one-time link-secret hashes are searchable. API responses contain safe metadata and expose the secret only on binding. The community and token are each unique across bindings.

Only an incoming private message from a positive user ID can link an account, using the exact trimmed secret. Display names do not establish ownership. Rebinding replaces the internal binding ID and invalidates old leases, secrets, and cached poll sessions. The linking message ID prevents replayed linking batches from submitting the secret or earlier messages to the agent.

Polling uses a renewable database lease. Claims, cursor writes, and error writes require an enabled binding and an unexpired matching owner; replies check the same ownership immediately before sending. Renewal cannot resurrect an expired lease. External sends cannot be atomic with a database lease check, so an already-started send can finish after a concurrent rebind or lease loss.

The Long Poll `ts` is an opaque batch cursor. Persist the initial cursor before processing and advance it only after the batch completes. A full session reset can discard old events. Turn IDs use binding and VK message IDs to deduplicate agent execution. Failed delivery leaves the cursor for retry, and previously delivered replies in the batch may repeat. [Polling and cursor recovery](../src/main/kotlin/ru/souz/backend/vk/VkBotPollingService.kt)

CommonMark renders Markdown before chunking. VK receives plain text plus `format_data` version 1 for bold, italic, headings, and HTTP(S) links; code stays literal and lists/quotes retain text markers. Ranges use Unicode code points for API 5.199, following the [published compatibility probe](https://pypi.org/project/hermes-vk-community/). VK's public schema omits `format_data`; verify wire-format changes in a real client. Reserve an extra unit per `@` when splitting.

Complete cross-channel delivery persists the original Markdown. Partial delivery persists only accepted rendered text. Polling checks lease ownership before every formatted chunk. Plain service replies omit `format_data`.

## Safe changes

- Opt-in progress shares the formatted reply path and lease checks; follow the [live delivery lifecycle](execution-openapi-and-events.md).
- Keep token contents and VK error bodies out of responses and logs. VK has no plaintext token migration path.
- API and Long Poll requests share the web tools' `SOUZ_WEB_USER_AGENT` setting and default.
- Each enabled binding owns its poll loop and cached session. Limit update processing, never idle long polls, and recheck lease ownership after waiting for a processing permit.
- Clip and rebase formatting ranges per chunk and keep shared delivery bookkeeping. [Markdown rendering and chunking](../src/main/kotlin/ru/souz/backend/vk/VkMarkdown.kt)
- Follow the [distributed execution boundary](distributed-backend-boundary.md) for process-local agent recovery limits.

## Verification

Run `./gradlew :backend:test` with Docker. See the [VK workflows](../src/test/kotlin/ru/souz/backend/e2e/BackendVkE2eTest.kt), [HTTP encoding tests](../src/test/kotlin/ru/souz/backend/vk/HttpVkBotApiTest.kt), and [repository lease tests](../src/test/kotlin/ru/souz/backend/vk/PostgresVkBotBindingRepositoryTest.kt).

Use the shared [manual polling checks](testing.md#verification) to verify cross-binding independence and cancellation.
