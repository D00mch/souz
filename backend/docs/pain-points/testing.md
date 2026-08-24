# Testing

## Invariant

The primary backend suite runs through Ktor `testApplication`, `backendDiModule`, Flyway migrations, the real agent kernel, and PostgreSQL 16 Testcontainers. Tests fake only external or nondeterministic boundaries: provider/local LLM calls, Telegram API calls, and clocks where a scenario requires deterministic time.

Focused unit tests remain for configuration validation, runtime shutdown and launcher races, LLM routing and accounting, quota limits, bounded event-bus behavior, compatibility codecs, datasource initialization failure handling, sandbox scoping, Telegram text chunking, and repository lease fencing.

## Why it is fragile

Partial route contexts and in-memory repositories can pass while the production DI graph, migrations, request identity plugin, background launcher, event persistence, or Postgres constraints are broken. They also make route tests repeat service behavior instead of checking user-visible contracts.

## Safe-change guidance

- Add backend workflow coverage under `ru.souz.backend.e2e`.
- Use the shared E2E harness to allocate a unique Postgres schema, install production HTTP routes, override only external boundaries, and close runtime resources.
- Use HTTP or WebSocket helpers for assertions. Direct SQL is reserved for encryption-at-rest, legacy compatibility, lease/crash recovery, and restart persistence checks.
- Keep ordinary route validation table-driven inside workflow tests instead of adding one route class per branch.
- Do not add general-purpose in-memory repository implementations.

## Verification

Run Docker-backed backend tests with `./gradlew :backend:test`. Focus the production-wired suite with `./gradlew :backend:test --tests 'ru.souz.backend.e2e.*'`.
