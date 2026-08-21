# Skill OAuth Implementation

Before changing this module, read its [pain-point index](docs/pain-points.md) and any relevant topics.

## Purpose and boundaries

- `:skill-oauth-impl` owns provider discovery, authorization-code exchange, encrypted token persistence, PostgreSQL repositories, migrations, and the OAuth callback route.
- It implements `:skill-oauth-api` and is composed only by `:backend`.
- Keep agent behavior, UI, desktop integration, and provider-neutral contracts outside this module.

## Verification

```bash
./gradlew :skill-oauth-impl:test
```
