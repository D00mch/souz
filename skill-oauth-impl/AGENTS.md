# Skill OAuth Implementation

Before changing this module, read the relevant pain-point topics below.

## Purpose and boundaries

- `:skill-oauth-impl` owns provider discovery, authorization-code exchange, encrypted token persistence, PostgreSQL repositories, migrations, and the OAuth callback route.
- It implements `:skill-oauth-api` and is composed only by `:backend`.
- Keep agent behavior, UI, desktop integration, and provider-neutral contracts outside this module.

## Pain points

- [OAuth lifecycle and concurrency](docs/oauth-lifecycle.md) — shared scope grants, single-use links, refresh failure handling, and database lock ordering.

Add a focused topic under `docs/` only for a lasting, non-obvious constraint and link it here. Keep each topic current-state and operational.

## Verification

```bash
./gradlew :skill-oauth-impl:test
```
