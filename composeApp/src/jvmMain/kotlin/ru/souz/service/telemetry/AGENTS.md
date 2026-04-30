## Project Structure
```text
telemetry/
├── TelemetryModels.kt                # Telemetry DTOs, event enums, request/session models
├── TelemetryRuntimeConfig.kt         # Hidden runtime config for telemetry transport endpoints
├── TelemetryStorageKeys.kt           # ConfigStore keys for persistent telemetry identity state
├── TelemetryCrypto.kt                # Crypto service for installation keypair handling and signing
├── TelemetryOutboxRepository.kt      # Local SQLite outbox used before sending batches
├── TelemetryService.kt               # Event capture, batching, registration, signing, and send flow
└── AGENTS.md                         # This file
```

Notes:
- The package persists telemetry to `~/.local/state/souz/telemetry.db` before sending it to the backend.
- `TelemetryOutboxRepository` stores only pending events and retry metadata. Successfully delivered rows are deleted.
- The backend API contract is documented in `docs/telemetry-backend.md` and must stay wire-compatible with production.
