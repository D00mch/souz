# Telegram bindings

## Invariant

Backend Telegram bindings are feature-gated and owned by a Souz user and chat. Binding validates the bot token with Telegram, removes any webhook before long polling, encrypts the token at rest, stores only a hash of the one-time link secret, and returns safe metadata plus the one-time `/start` command. Raw tokens and token hashes never appear in API responses.

A binding remains pending until the exact secret arrives from a private Telegram chat. The first valid private sender/chat pair becomes the permanent account binding; stale, non-matching, or later traffic from another account is rejected.

Each poller holds a renewable per-binding lease. Accepted text is submitted through `AgentExecutionService` with an update-derived client message ID and non-streaming message delivery. Reply and checkpoint writes are fenced by current lease ownership, and `lastUpdateId` advances only after processing completes under that owner. Long assistant replies are split to Telegram's message limit, with a short fallback response when delivery content is unavailable.

## Why it is fragile

Tokens, one-time secrets, Telegram identity, and poller ownership are separate security boundaries. Advancing a checkpoint early, replying after lease loss, or accepting a public/foreign sender can lose updates, duplicate agent turns, leak a bot credential, or bind the wrong account.

## Safe-change guidance

- Preserve encrypted token custody, unique token hashes, hashed link secrets, and redacted API DTOs.
- Require the exact private-chat link handshake before activating a binding; never infer ownership from a username or untrusted message field.
- Renew leases during in-flight work and verify ownership before every externally visible reply or checkpoint side effect.
- Keep Telegram update IDs in the client-message identity so retried updates remain idempotent.
- Treat plaintext-compatible rows as migration input only; rebinding or an application rewrite must place them on encrypted storage before removing compatibility support.

## Verification

Run `./gradlew :backend:test`. Cover feature gating, token redaction/encryption, one-time linking, foreign-account rejection, lease takeover and renewal, reply fencing, checkpoint advancement, idempotent retries, message chunking, and persistence constraints.
