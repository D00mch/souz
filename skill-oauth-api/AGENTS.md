# Skill OAuth API

Before changing this module, read its [pain-point index](docs/pain-points.md) and any relevant topics.

## Purpose and boundaries

- `:skill-oauth-api` owns provider-neutral Skill OAuth contracts.
- Keep host, HTTP transport, provider implementation, and persistence dependencies outside this module.
- Implementations depend on this API; the API must not depend on `:skill-oauth-impl`, `:backend`, desktop, or UI modules.

## Verification

```bash
./gradlew :skill-oauth-api:test
```
