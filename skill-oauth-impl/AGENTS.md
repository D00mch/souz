# Skill OAuth Implementation

## Purpose and boundaries

- `:skill-oauth-impl` owns provider discovery, authorization-code exchange, encrypted token persistence, PostgreSQL repositories, migrations, and the OAuth callback route.
- Only `:backend` composes this implementation of `:skill-oauth-api`. Keep agent behavior, UI, desktop integration and application composition outside this module.

## Pain points

- [OAuth lifecycle and concurrency](docs/oauth-lifecycle.md) — shared scope grants, single-use links, refresh failure handling, and database lock ordering.

## Verification

Run `./gradlew :skill-oauth-impl:test`.
