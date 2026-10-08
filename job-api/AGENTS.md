# Job API

Before changing this module, read its [pain-point index](docs/pain-points.md).

## Purpose and boundaries

- `:job-api` owns owner-scoped job creation, listing, cancellation, and execution contracts.
- Keep persistence, scheduling implementation, worker lifecycle, agent behavior, and host dependencies outside this module.
- Payloads contain job data; credentials belong to the execution host.

## Verification

```bash
./gradlew :job-api:test
```
