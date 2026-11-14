# Job Implementation

## Purpose and boundaries

- `:job-impl` implements `:job-api` with PostgreSQL persistence, scheduling, leased workers, and queue metrics.
- The host supplies a suspend handler and owns its application scope and credentials.
- Keep SQL helpers internal. Do not add repository interfaces or executor registries without another concrete implementation.

## Pain points

- [Job ownership and recovery](docs/job-ownership.md) — claim fencing, at-least-once execution, recurrence, shutdown, and queue metrics.

## Verification

Run `./gradlew :job-impl:test` with Docker available. Database tests must fail if Docker is unavailable.
