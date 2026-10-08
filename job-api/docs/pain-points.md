# Pain points

Read the topics relevant to the code you plan to change in `:job-api`.

## Topics

- Execution is at least once. The persisted job ID and scheduled occurrence timestamp identify one occurrence across retries; handlers use that identity when deduplicating side effects.
- Owner identity is host-supplied and independent of backend user tables. Listing and cancellation must retain owner scoping in every implementation.
