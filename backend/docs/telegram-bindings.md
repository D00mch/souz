# Telegram bindings

## Setup

Set `ENABLE_BACKEND_TG_FEATURE=true` and supply `TELEGRAM_TOKEN_ENCRYPTION_KEY` as 32 bytes encoded in Base64. Generate the key with `openssl rand -base64 32`.

## Invariant

Bindings belong to a Souz user and chat. Validate the bot token, remove any webhook, and discard pending updates before polling. Encrypt tokens, hash link secrets, and keep both tokens and token hashes out of API responses.

A binding remains pending until the exact secret arrives from a private Telegram chat. The first valid private sender/chat pair becomes the permanent account binding; stale, non-matching, or later traffic from another account is rejected.

Each poller holds a renewable per-binding lease. Submit accepted text with an update-derived client message ID for idempotent retries. Check ownership before each reply or checkpoint write and advance `lastUpdateId` only after processing completes under that owner. [Polling and replies](../src/main/kotlin/ru/souz/backend/telegram/TelegramBotPollingService.kt)

Bot replies and cross-channel deliveries use [`sendRichMessage`](https://core.telegram.org/bots/api#sendrichmessage) with JSON `rich_message.markdown`. Chunking conservatively limits source Markdown to 32,768 UTF-16 units; splitting can interrupt Markdown constructs in oversized replies. Preserve the original Markdown in conversation storage.

## Safe-change guidance

- Opt-in progress shares the formatted reply path and lease checks; follow the [live delivery lifecycle](execution-openapi-and-events.md).
- Enforce unique token hashes and derive linked ownership from the private-chat handshake. Renew leases during in-flight work.
- Keep one independent poll loop per enabled binding. Limit update processing, never idle long polls, and recheck lease ownership after waiting for a processing permit.
- Treat plaintext-compatible rows as migration input only; rebinding or an application rewrite must place them on encrypted storage before removing compatibility support.

## Verification

Run `./gradlew :backend:test` with Docker. See the [binding and delivery workflows](../src/test/kotlin/ru/souz/backend/e2e/BackendTelegramE2eTest.kt) and [lease and checkpoint tests](../src/test/kotlin/ru/souz/backend/telegram/PostgresTelegramBotBindingRepositoryTest.kt).

Use the shared [manual polling checks](testing.md#verification) to verify cross-binding independence and cancellation.
