# Testing

## Invariant

Run workflows through production HTTP and WebSocket wiring, the real agent kernel, Flyway, and PostgreSQL Testcontainers. Fake only external or nondeterministic boundaries such as model and channel APIs, clocks, and socket backpressure. Use the [external-memory verification guide](external-memory.md#verification) for real Hindsight attribution checks.

## Why it is fragile

Partial route contexts and in-memory repositories can hide production wiring, migration, and persistence regressions.

## Safe-change guidance

- Add workflows in the [E2E suite](../src/test/kotlin/ru/souz/backend/e2e). Use the [shared harness](../src/test/kotlin/ru/souz/backend/e2e/BackendE2eHarness.kt) for schema isolation, resource cleanup, worker wiring and timings, and deterministic per-binding polling helpers.
- Use HTTP or WebSocket helpers for assertions. Direct SQL is reserved for encryption-at-rest, legacy compatibility, lease/crash recovery, restart persistence checks, and deterministic database barriers/faults for acceptance and shutdown races.
- Synchronize [stalled hook intake tests](../src/test/kotlin/ru/souz/backend/e2e/BackendHooksE2eTest.kt) on the server body read before sending competing requests so the stalled request holds the permit.
- Keep ordinary route validation table-driven inside workflow tests instead of adding one route class per branch.
- Do not add general-purpose in-memory repository implementations.

## Verification

Run Docker-backed backend tests with `./gradlew :backend:test`. Focus the production-wired suite with `./gradlew :backend:test --tests 'ru.souz.backend.e2e.*'`.

For Telegram and VK, manually keep more bindings idle than the channel's processing limit and confirm another binding handles consecutive messages while their long polls wait. Remove an idle binding and stop the host to check poll cancellation.
