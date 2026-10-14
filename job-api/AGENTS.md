# Job API

Before changing this module, read the relevant pain-point topics below.

## Purpose and boundaries

- `:job-api` owns owner-scoped job creation, listing, cancellation, and execution contracts.
- Keep persistence, scheduling implementation, worker lifecycle, agent behavior, and host dependencies outside this module.
- Payloads contain job data; credentials belong to the execution host.

## Pain points

- Execution is at least once. The persisted job ID and scheduled occurrence timestamp identify one occurrence across retries; handlers use that identity when deduplicating side effects.
- `continueAfterFailure` opts a recurring job into advancing after its current occurrence exhausts retries. Exhausted occurrence failures remain owner-scoped and durable; the default still stops the job.
- Job lists use bounded cursor pages; failure reads take a bounded set of job IDs and return only the latest three occurrences per owned job.
- Owner identity is host-supplied and independent of backend user tables. Listing and cancellation must retain owner scoping in every implementation.

Add a focused topic under `docs/` only for a lasting, non-obvious constraint and link it here. Keep each topic current-state and operational.

## Verification

```bash
./gradlew :job-api:test
```
