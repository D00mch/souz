# Job Implementation

Before changing this module, read its [pain-point index](docs/pain-points.md).

## Purpose and boundaries

- `:job-impl` owns PostgreSQL persistence, migrations, cron scheduling, leased claims, retries, cancellation, and sequential coroutine workers.
- It implements `:job-api`; the host supplies a suspend handler and owns its application scope and credentials.
- Keep SQL helpers internal. Do not add repository interfaces or executor registries without another concrete implementation.

## Verification

Run with Docker available; database tests must fail rather than skip when Docker is unavailable.

```bash
./gradlew :job-impl:test
```
