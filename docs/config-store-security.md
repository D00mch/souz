# ConfigStore Security

This document summarizes how `ConfigStore` stores and encrypts sensitive settings.

## Storage

- `ConfigStore` persists values in `Preferences.userNodeForPackage(ConfigStore::class.java)`.
- `String` values are stored directly, primitive-like values are stringified, and other values are serialized as JSON.
- Reads reverse that process after optional decryption.

## Sensitive Values

- Non-sensitive keys are stored as plaintext in Preferences.
- Sensitive keys are encrypted before write. The allowlist currently includes:
  - Telegram bot token
  - LLM/API keys
  - telemetry private key storage
  - any key with the `MCP_OAUTH_STATE_` prefix
- `MCP_SERVERS_JSON` is not currently in that allowlist, so it is not encrypted by the current implementation.

## Encrypted Payload Format

```text
enc:v1:<base64 salt>:<base64 iv>:<base64 aes-gcm-output>
```

- Encryption uses `AES/GCM/NoPadding`.
- Keys are derived from the master secret with `PBKDF2WithHmacSHA256`, `120000` iterations, and a `256-bit` output.
- Each value gets a fresh `16-byte` salt and `12-byte` IV.

## Master Secret

- The master secret is resolved in this order:
  - `SOUZ_MASTER_KEY` environment variable
  - `SOUZ_MASTER_KEY` JVM system property
  - local generated key file
- Env var and JVM property override the local key file.
- If encrypted data was written with a different master secret, decryption fails and reads return `null`.
- The local secret is cached in-process after first load.

## Local Master Key File

- If no `SOUZ_MASTER_KEY` override is present, Souz generates a local master secret automatically.
- The generated secret is `32` random bytes, stored Base64-encoded in `master.key`.
- Storage path is OS-specific:
  - macOS: `~/Library/Application Support/Souz/master.key`
  - Windows: `%APPDATA%/Souz/master.key`, falling back to `~/AppData/Roaming/Souz/master.key`
  - Linux and other Unix-like systems: `~/.config/souz/master.key`
- After writing the file, Souz makes a best-effort attempt to restrict permissions to owner read/write.

## Migration Semantics

- Legacy sensitive values that are still plaintext are handled transparently at read time.
- When a sensitive value is read and does not start with `enc:v1:`:
  - the plaintext value is returned to the caller
  - if a master secret is available, the value is immediately rewritten in encrypted form
  - if a master secret cannot be initialized, the plaintext value remains in place and a warning is logged
- Malformed encrypted payloads log a warning and return `null`.
