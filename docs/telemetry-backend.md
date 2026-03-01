# Telemetry Backend Contract

This document describes the backend contract required by the desktop telemetry client implemented in `composeApp`.

## Client behavior

- The desktop app writes telemetry events into a local SQLite outbox at `~/.local/state/souz/telemetry.db`.
- Telemetry is always enabled. There is no user-facing toggle or server URL field in Settings.
- The client auto-generates and persists:
  - `userId`
  - `deviceId`
  - Ed25519 installation keypair
  - `installationId` returned by the backend
- The client batches up to `50` events per request.
- Retry strategy: exponential backoff starting at `5s`, capped at `160s`.
- Local outbox retention: oldest events are dropped when the queue exceeds `10_000` rows.

Important limitation:

- Desktop telemetry cannot prove with cryptographic certainty that a request came from an unmodified official binary.
- The implemented scheme proves that the request came from the same registered installation that owns the private key.

## Authentication model

The backend must use installation registration plus signed requests.

### Registration

`POST /v1/installations/register`

The client sends:

- generated `userId`
- generated `deviceId`
- installation `publicKey`
- `keyAlgorithm = Ed25519`
- client metadata

The request is signed by the installation private key. The backend verifies the signature against the `publicKey` provided in the same request body and returns `installationId`.

### Signed batch requests

`POST /v1/metrics/batch`

The client includes:

- `X-Telemetry-Installation-Id`
- `X-Telemetry-Timestamp`
- `X-Telemetry-Nonce`
- `X-Telemetry-Key-Algorithm`
- `X-Telemetry-Signature`

The backend resolves the stored `publicKey` by `installationId` and verifies the signature before accepting the batch.

Recommended backend checks:

- reject requests with stale timestamps, for example older than `5 minutes`
- reject reused nonces within the accepted timestamp window
- deduplicate events by `eventId`
- rate limit by `installationId`, `userId`, and IP
- on invalid or unknown installation, respond with `401`, `403`, `404`, or `409`

The desktop client will clear the stored `installationId` and try registration again after such batch responses.

## Registration request

`POST /v1/installations/register`

Headers:

- `Content-Type: application/json`
- `X-Telemetry-Timestamp: <unix epoch millis>`
- `X-Telemetry-Nonce: <uuid>`
- `X-Telemetry-Key-Algorithm: Ed25519`
- `X-Telemetry-Signature: <base64 signature>`

Canonical signature payload:

```text
POST
/v1/installations/register
<timestampMs>
<nonce>
<base64(sha256(bodyJsonUtf8))>
```

Body:

```json
{
  "schemaVersion": 1,
  "userId": "user-uuid",
  "deviceId": "device-uuid",
  "publicKey": "base64-x509-ed25519-public-key",
  "keyAlgorithm": "Ed25519",
  "client": {
    "appName": "souz-desktop",
    "appVersion": "dev",
    "edition": "ru",
    "userId": "user-uuid",
    "deviceId": "device-uuid",
    "appSessionId": "app-session-uuid",
    "osName": "Mac OS X",
    "osVersion": "15.3.1",
    "osArch": "aarch64",
    "sentAtMs": 1772312312312
  }
}
```

Success response:

```json
{
  "installationId": "installation-uuid"
}
```

Backend requirements:

- `installationId` must be stable for the registered installation
- registration should be idempotent if the same public key is sent again
- the backend should persist:
  - `installationId`
  - `userId`
  - `deviceId`
  - `publicKey`
  - `keyAlgorithm`
  - registration timestamps and metadata

## Metrics batch request

`POST /v1/metrics/batch`

Headers:

- `Content-Type: application/json`
- `X-Telemetry-Installation-Id: <installationId>`
- `X-Telemetry-Timestamp: <unix epoch millis>`
- `X-Telemetry-Nonce: <uuid>`
- `X-Telemetry-Key-Algorithm: Ed25519`
- `X-Telemetry-Signature: <base64 signature>`

Canonical signature payload:

```text
POST
/v1/metrics/batch
<installationId>
<timestampMs>
<nonce>
<base64(sha256(bodyJsonUtf8))>
```

Body:

```json
{
  "schemaVersion": 1,
  "client": {
    "appName": "souz-desktop",
    "appVersion": "dev",
    "edition": "ru",
    "userId": "user-uuid",
    "deviceId": "device-uuid",
    "installationId": "installation-uuid",
    "appSessionId": "app-session-uuid",
    "osName": "Mac OS X",
    "osVersion": "15.3.1",
    "osArch": "aarch64",
    "sentAtMs": 1772312312312
  },
  "events": [
    {
      "eventId": "uuid",
      "type": "request_finished",
      "occurredAtMs": 1772312312000,
      "userId": "user-uuid",
      "deviceId": "device-uuid",
      "appSessionId": "app-session-uuid",
      "conversationId": "conversation-uuid",
      "requestId": "request-uuid",
      "payload": {
        "status": "success",
        "source": "chat_ui",
        "model": "gpt-5.2",
        "provider": "OPENAI",
        "durationMs": 842,
        "inputLengthChars": 91,
        "responseLengthChars": 418,
        "attachedFilesCount": 1,
        "toolCallCount": 2,
        "requestTokenUsage": {
          "promptTokens": 1200,
          "completionTokens": 340,
          "totalTokens": 1540,
          "precachedTokens": 0
        },
        "sessionTokenUsage": {
          "promptTokens": 1200,
          "completionTokens": 340,
          "totalTokens": 1540,
          "precachedTokens": 0
        }
      }
    }
  ]
}
```

Success response:

```json
{
  "acceptedEvents": 50
}
```

Recommended failure responses:

- `400` for malformed signature headers or body
- `401` for missing/invalid signature or stale timestamp
- `403` for signature mismatch or revoked installation
- `404` for unknown installation
- `409` for installation conflicts or replayed nonces
- `413` if payload is too large
- `429` for rate limiting
- `5xx` for transient server failures

## Event types

The client currently emits:

- `app_opened`
- `app_closed`
- `conversation_started`
- `conversation_finished`
- `request_finished`
- `tool_executed`

## Important event payloads

`conversation_finished`

- `reason`: `new_conversation | clear_context | view_model_cleared`
- `startReason`: `chat_ui | voice_input | telegram_bot`
- `durationMs`
- `requestCount`
- `toolCallCount`
- `tokenUsage`

`request_finished`

- `status`: `success | error | cancelled`
- `source`: `chat_ui | voice_input | telegram_bot`
- `model`
- `provider`
- `durationMs`
- `inputLengthChars`
- `responseLengthChars`
- `attachedFilesCount`
- `toolCallCount`
- `requestTokenUsage`
- `sessionTokenUsage`
- `errorMessage`

`tool_executed`

- `toolName`
- `toolCategory`
- `durationMs`
- `success`
- `errorMessage`
- `argumentKeys`
- `argumentCount`

## Backend storage requirements

Minimum dimensions that should be indexed or queryable:

- `installationId`
- `userId`
- `deviceId`
- `appSessionId`
- `conversationId`
- `requestId`
- `type`
- `occurredAtMs`
- `payload.toolName`
- `payload.model`
- `payload.provider`

Recommended additional dimensions:

- request IP
- app version
- edition
- OS name/version/arch

## Privacy and data handling

The current client implementation sends:

- event type and timestamps
- generated user/device/install identifiers
- tool name/category and argument keys only
- token usage and request/session totals
- model/provider names
- request/response character counts
- short error messages when present

The current client does not send full tool arguments or raw prompt text as telemetry payload fields.
