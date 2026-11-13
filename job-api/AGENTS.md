# Job API

## Purpose and boundaries

- `:job-api` owns owner-scoped job creation, listing, cancellation, and execution contracts.
- Keep persistence, scheduling implementation, worker lifecycle, agent behavior, and host dependencies outside this module.
- The host supplies owner identity and credentials. Scope operations to that identity independently of backend user tables. Keep credentials out of job payloads.

## Pain points

- [Job service contract](src/main/kotlin/ru/souz/jobs/JobService.kt) — cursor paging, owner-scoped failure reads, and at-least-once occurrence identity.
- [Job ownership and recovery](../job-impl/docs/job-ownership.md) — durable occurrence failures and recurrence after retry exhaustion.

## Verification

Run `./gradlew :job-api:test`.
