# Job Implementation

Before changing this module, read the relevant pain-point topics below.

## Purpose and boundaries

- `:job-impl` owns PostgreSQL persistence, migrations, cron scheduling, leased claims, retries, cancellation, and sequential coroutine workers.
- It implements `:job-api`; the host supplies a suspend handler and owns its application scope and credentials.
- Keep SQL helpers internal. Do not add repository interfaces or executor registries without another concrete implementation.

## Pain points

- [Job ownership and recovery](docs/job-ownership.md) — claim fencing, at-least-once execution, recurrence, and shutdown.

Add a focused topic under `docs/` only for a lasting, non-obvious constraint and link it here. Keep each topic current-state and operational.

## Verification

Run with Docker available; database tests must fail rather than skip when Docker is unavailable.

```bash
./gradlew :job-impl:test
```
