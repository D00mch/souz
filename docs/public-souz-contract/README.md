# Client-Souz Contract

Draft contract package for the Client channel integration.

## Package

- [openapi.yaml](openapi.yaml) - REST API for creating a durable chat.
- [asyncapi.yaml](asyncapi.yaml) - chat WebSocket protocol for message submissions, threads, tool calls, durable events, and acknowledgements.
- [schemas/common.yaml](schemas/common.yaml) - shared identifiers, device metadata, entity states, and errors.
- [schemas/events.yaml](schemas/events.yaml) - Souz -> Client event envelopes and payloads.
- [schemas/client-messages.yaml](schemas/client-messages.yaml) - Client messages, message-submission acknowledgements, tool-result acknowledgements, and thread-cancellation acknowledgements.
- [semantics.md](semantics.md) - chat lifecycle, identity, idempotency, replay, acknowledgement, and terminal-state rules.
- [backend-feasibility.md](backend-feasibility.md) - current backend fit and implementation gaps.
- [diagrams/client-souz-threads.mmd](diagrams/client-souz-threads.mmd) and [diagrams/client-souz-threads.svg](diagrams/client-souz-threads.svg) - Client-to-Souz thread overview.
- [examples/happy-path.json](examples/happy-path.json) - canonical happy path for a thread spanning multiple user submissions and tool calls.
- [examples/happy-path.jsonl](examples/happy-path.jsonl) - compact JSONL version of the canonical happy path.
- [examples/device-tool.jsonl](examples/device-tool.jsonl) - a tool call executed by Client.
- [examples/reconnect.jsonl](examples/reconnect.jsonl) - event replay after reconnect.

## Integration Boundary

Client owns device audio ingestion, external token validation, ASR, and TTS. Souz Cloud receives authenticated identity, recognized text with its input source, device metadata, and tool results that continue an active thread.

The public client profiles are `backend` and `mobile_app`. Backend clients use trusted proxy headers. Mobile applications use a separate mobile auth profile. Raw audio streams are not part of this Souz contract.

## Runtime Flow

```text
POST /v1/chats {requestId, clientType} -> chatId
  -> connect /v1/chats/{chatId}/ws?clientType=...
  -> Client ASR and identity -> message.submit
  -> atomic message + thread acknowledgement
  -> Agent Graph
  -> tool-call events to Client
  -> Client TTS/screen/device tool handling
  -> Client tool result back to Souz
  -> tool-result acknowledgement
  -> Agent Graph continuation
  -> thread terminal event
```

The contract is written as `v1.0.0-draft`: it is shareable as the desired external interface and calls out backend deltas separately.
