# Distributed backend boundary

## Invariant

Backend storage is PostgreSQL-backed, but active runtime ownership is distributed-safe only for the Client-Souz public WebSocket thread path (`docs/public-souz-contract`). That path stores `runtime_owner` and a renewable `runtime_lease_until`, requires sticky routing for live active-thread frames, and has recovery that fails expired public thread leases and emits the required terminal `thread.failed` event.

Ordinary trusted-proxy HTTP executions and Telegram-triggered executions run as process-local background jobs without a renewable runtime lease. Their durable `agent_executions` rows can remain active if the owning process exits while the job is running. `waiting_option` is durable user-wait state and must not be treated as a crashed runtime by lease recovery.

Server-managed Codex OAuth is safe for a single backend process through process-local refresh coordination. A terminal refresh rejection suppresses the matching deployment bundle until all four deployment credentials are replaced, including a different refresh token, and the backend restarts. Multi-replica deployments must not enable it unless refresh is database-coordinated and replaces the access token, refresh token, account ID, and expiry as one credential set.

## Why it is fragile

PostgreSQL enforces one active execution per chat. If a process-local ordinary execution is stranded in `queued`, `running`, or `cancelling`, later chat turns can be rejected as active-execution conflicts even though no runtime job exists. Treating all active statuses as crashed work is also unsafe because `waiting_option` intentionally outlives the runtime job while waiting for user input.

The public WebSocket contract solves a narrower problem: live frames must reach the process that owns the active runtime registry. Its lease and recovery rules should not be read as proof that every backend entry point is horizontally distributable.

Codex refresh tokens can rotate. Without database coordination, two replicas can refresh the same expired credential concurrently or interleave separate credential-field writes, leaving a mismatched token set.

## Safe-change guidance

- Document deployments as distributed-ready only for the Client-Souz public WebSocket active-thread path unless ordinary executions also gain runtime ownership, lease refresh, and recovery.
- Keep `waiting_option` separate from owned runtime work. Clear any runtime lease when entering `waiting_option`, and acquire a fresh lease when an option continuation resumes.
- Prefer a shared execution-ownership model over endpoint-specific recovery logic: `queued`, `running`, and `cancelling` should have an owner and renewable lease when a process is executing them.
- Keep live Client-Souz frames owner-sticky while the live registry remains process-local.
- Do not fail active ordinary executions on backend startup in a multi-replica deployment unless ownership proves the starting process is recovering only abandoned work.
- Do not enable server-managed Codex OAuth on multiple replicas without a database-backed lock or compare-and-set path that re-reads credentials before refresh and stores the refreshed credential set atomically.

## Verification

Run `./gradlew :backend:test` for changes to execution ownership or recovery. Cover ordinary HTTP execution crash recovery, Telegram-triggered execution crash recovery, Client-Souz expired lease recovery, sticky active-thread routing, cancellation races, option resume from `waiting_option`, and concurrent Codex OAuth refresh when Codex is enabled on multiple replicas.
