# Orion-Souz Contract

Draft contract package for the Orion channel integration.

## Package

- [openapi.yaml](openapi.yaml) - REST API for opening chats, submitting recognized text, replaying durable events, answering options, and cancellation.
- [asyncapi.yaml](asyncapi.yaml) - WebSocket protocol for Souz events and Orion commands.
- [schemas/common.yaml](schemas/common.yaml) - shared identifiers, device metadata, execution state, and errors.
- [schemas/events.yaml](schemas/events.yaml) - Souz -> Orion event envelopes and payloads.
- [schemas/commands.yaml](schemas/commands.yaml) - Orion -> Souz command envelopes and acknowledgements.
- [semantics.md](semantics.md) - identity, idempotency, replay, command acknowledgement, and terminal-state rules.
- [backend-feasibility.md](backend-feasibility.md) - current backend fit and implementation gaps.
- [examples/](examples/) - JSONL traces for happy path, device tool, and reconnect.

## Integration Boundary

Orion owns device audio ingestion, SberID token validation, speaker identification, ASR, and TTS. Souz Cloud receives trusted proxy identity, recognized text, device/session metadata, and command results that continue an active agent execution.

`X-User-Id` is the authoritative Souz user identity. For Orion traffic it should contain the normalized `SberID.UUID` after Orion or the proxy has validated the SberID token. The SberID token and raw audio stream are not part of this Souz contract.

## Runtime Flow

```text
device audio -> Orion ASR and identity -> Souz REST message
  -> backend chat/execution state in PostgreSQL
  -> Agent Graph
  -> Souz event stream to Orion
  -> Orion TTS/screen/device command handling
  -> Orion command result back to Souz
  -> Agent Graph continuation
  -> durable final event and saved messages
```

The contract is written as `v1.0.0-draft`: it is shareable as the desired external interface and calls out backend deltas separately.
