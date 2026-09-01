# Pain points

Read the topics relevant to the code you plan to change in `:backend`.

## Topics

- [Trusted proxy](pain-points/trusted-proxy.md) — identity validation, provisioning, user scoping, and backend-safe tools.
- [Execution, OpenAPI, and events](pain-points/execution-openapi-and-events.md) — runtime ownership, event durability, compatibility, and route documentation.
- [Public client WebSocket](pain-points/public-client-websocket.md) — public idempotency, thread/runtime coordination, client tools, acknowledgement ordering, and replay.
- [Distributed backend boundary](pain-points/distributed-backend-boundary.md) — which backend entry points have distributed runtime ownership and recovery.
- [Telegram bindings](pain-points/telegram-bindings.md) — token custody, private-account linking, polling leases, and checkpoint safety.
- [Testing](pain-points/testing.md) — production-wired E2E coverage, Docker requirement, and allowed test doubles.
- [External memory](pain-points/external-memory.md) — Hindsight owner isolation, scope tags, grounded capture, and safe mutation boundaries.

Add a focused topic only for a lasting, non-obvious constraint. Keep each topic current-state and operational.
