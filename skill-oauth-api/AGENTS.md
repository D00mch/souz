# Skill OAuth API

Before changing this module, read the relevant pain-point topics below.

## Purpose and boundaries

- `:skill-oauth-api` owns provider-neutral Skill OAuth contracts.
- Keep host, HTTP transport, provider implementation, and persistence dependencies outside this module.
- Implementations depend on this API; the API must not depend on `:skill-oauth-impl`, `:backend`, desktop, or UI modules.

## Pain points

No module-specific pain-point topics are currently recorded.

Add a focused topic under `docs/` only for a lasting, non-obvious constraint and link it here. Keep each topic current-state and operational.

## Verification

```bash
./gradlew :skill-oauth-api:test
```
