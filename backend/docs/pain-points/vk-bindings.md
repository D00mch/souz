# VK bindings

## Invariant

Backend VK bindings are feature-gated and owned by a Souz user and chat. Binding validates the group access token with VK (`groups.getById`), encrypts the token at rest, stores only a hash of the one-time link secret, and returns safe metadata plus the raw one-time secret text. Raw tokens and token hashes never appear in API responses. Unlike Telegram, there is no webhook/polling exclusivity to force off at bind time — VK's Long Poll and Callback API are independent, and this integration only ever uses Long Poll.

A binding remains pending until the exact secret text arrives as a plain message from a 1:1 VK conversation (`peer_id == from_id` — VK has no `/start`-style command grammar, so the first message from an unlinked binding is checked verbatim against the stored secret). The first valid sender/peer pair becomes the permanent account binding; stale, non-matching, or later traffic from another account is rejected.

Each poller holds a renewable per-binding lease, identical in shape to Telegram's (45s TTL, 1/3 renewal). Accepted text is submitted through `AgentExecutionService` with a client message ID derived from VK's own per-message `id` (`"vk:<bindingId>:<messageId>"`), not from the Long Poll `ts` cursor — `ts` is an opaque string batch cursor VK advances per poll call, and a single batch can contain several messages, so it cannot serve as a per-message idempotency key. `last_ts` is persisted as `text`, not `bigint`, and is written unconditionally under lease ownership: there is no numeric monotonicity guard (`greatest()`) the way Telegram's `last_update_id` has, because `ts` values aren't meaningfully comparable as numbers. Reply and checkpoint writes are fenced by current lease ownership, same as Telegram. Long assistant replies are split to a conservative message-length limit, with a short fallback response when delivery content is unavailable.

## Why it is fragile

Tokens, one-time secrets, VK identity, and poller ownership are separate security boundaries, exactly as with Telegram. Advancing a checkpoint early, replying after lease loss, or accepting a public/foreign sender can lose updates, duplicate agent turns, leak a bot credential, or bind the wrong account.

VK Long Poll adds failure modes Telegram has no equivalent for: a poll response's `failed` field (`1` = resume with the server-returned `ts`; `2` = the `key` expired, refetch it via `groups.getLongPollServer`; `3` = `ts` is too old / history lost, refetch both `key` and `ts`) must be handled explicitly per poll attempt. `failed: 3` in particular accepts a best-effort update-loss window — there is no way to recover updates VK has already discarded server-side.

Because `last_ts` has no monotonicity guard, a poll tick's checkpoint write always lands as long as the lease is current — including a `ts` that looks "smaller" than what was previously stored. This is correct for VK's actual cursor semantics, but means a checkpoint invariant like "the stored value only increases" (true for Telegram) does **not** hold here; any future change to this table's write path must not assume it does.

## Safe-change guidance

- Preserve encrypted token custody, unique token hashes, hashed link secrets, and redacted API DTOs — mirror `TelegramBotTokenCrypto`'s at-rest format via the shared `ru.souz.backend.crypto.EncryptedPayloadCipher`.
- Require the exact private-conversation link handshake before activating a binding; never infer ownership from a display name or untrusted message field. `peer_id == from_id` is the only signal that a message is a 1:1 conversation with the bound account, not a group chat.
- Renew leases during in-flight work and verify ownership before every externally visible reply or checkpoint side effect, identical to Telegram's fencing pattern.
- Keep VK's own per-message `id` (not `ts`) in the client-message identity so retried polls remain idempotent.
- Handle all three `failed` codes explicitly on every Long Poll attempt; do not assume a poll response is always a successful batch.
- `last_ts` is a string cursor with no ordering guarantee — do not add a numeric `greatest()`-style guard to `updateLastTs` without first confirming VK's cursor semantics still make one meaningless (they do, as of writing).
- `VkBotTokenCrypto` has no legacy plaintext data to stay backward-compatible with, unlike `TelegramBotTokenCrypto` — a malformed or unprefixed payload should fail loudly, not silently pass through.

## Verification

Run `./gradlew :backend:test`. Cover feature gating, token redaction/encryption, one-time linking, foreign-account rejection, lease takeover and renewal, reply fencing, non-monotonic checkpoint advancement under the current lease owner, idempotent retries via VK message id, message chunking, `failed`-code handling, and persistence constraints.
