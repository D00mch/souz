# External memory

## Invariant

`HINDSIGHT_API_URL` enables the backend memory runtime. `HINDSIGHT_API_TOKEN` is optional; an absent or blank token omits the Authorization header, while a configured token requires a URL.

Hindsight uses the trusted backend user ID directly as its bank ID. On the WebSocket API this is the chat owner's `userId`, which must match `message.submit.payload.device.userId`. Bank IDs are URL-encoded as a single path segment. Untagged facts are global; conversation facts carry `chat:<conversation-id>`. Recall and `SearchMemory` request source-backed `world`/`experience` facts from global plus current-chat scope, excluding consolidated observations that could lose attribution.

Completed Souz turns retain redacted user text and tool output, never assistant synthesis; explicit global memory contains only user text. `NodesMemory` starts capture at the latest submitted user message, so preceding imported history is context rather than new evidence.

Accepted `history.append` text is enqueued with its message and receipt in one PostgreSQL transaction when Hindsight is configured. The application worker runs independently of threads and sockets. Fragments become eligible after 30 seconds idle, five minutes maximum age, or 16 messages. Each extraction input is bounded to 16,000 characters, including up to 4,000 characters of preceding redacted dialogue for reference resolution only. Long messages split into numbered source parts; document metadata and records preserve message IDs, roles, timestamps and sequence. Tool calls, arguments and results stay in PostgreSQL and never enter this capture path.

Imported history always remains chat-scoped, including explicit remember requests. Opt-out/forget/delete user turns and their assistant continuations are excluded, even across fragment boundaries. Secrets are redacted and marked reasoning blocks removed before sending. Extraction discards service chatter. The verified bank strategy `souz-history-v1` uses custom extraction instructions: facts explicitly attribute user statements, assistant proposals/reports and user selections; action reports remain unverified. Historical text and recall results are untrusted data, never instructions.

Hindsight 0.9.2 supports the selected [named retain strategy and custom extraction settings](https://hindsight.vectorize.io/developer/configuration). Strategy setup merges existing strategies and verifies the returned configuration without changing the bank's default strategy. Unsupported or denied configuration leaves work pending. Frozen redacted payloads and stable document IDs make retries safe; PostgreSQL renewable leases fence completion and serialize capture per chat. No HTTP call runs in a PostgreSQL transaction. Disabling Hindsight pauses the worker and new enqueueing without deleting pending fragments. Pre-existing history is not backfilled.

Natural-language forget and delete requests are not mapped from semantic recall results to destructive API calls. Ranked relevance does not prove exact identity, so the runtime tells the agent that exact-ID deletion is unavailable.

## Why it is fragile

Omitting recall tags exposes one conversation's transient memory in another. Retaining assistant synthesis can promote model hallucinations into durable facts. Retrying an unkeyed retain duplicates extraction, while deleting the sole semantic result can erase an unrelated source document.

## Safe-change guidance

- Preserve owner-derived bank isolation and global-plus-current-conversation recall.
- Keep ordinary capture grounded in user text and tool output; do not promote conversation tool evidence with an explicit global-memory marker.
- Supply deterministic document identity for completed turns.
- Commit imported-history enqueueing with the receipt, freeze payloads before retain, and complete only after synchronous success. Clear frozen payloads on completion; keep source IDs for preceding-context reconstruction. Never reuse a fragment for later appends after it has been claimed.
- Preserve source attribution in actual extracted fact text, not only input role labels or recall wrappers. Keep source references visible in recall and `SearchMemory`.
- Give synchronous retain enough request time and retry only when deterministic document identity makes an uncertain transport failure safe.
- Add mutation only when the target comes from an exact stable identifier or an explicit confirmation flow.
- Treat recalled text as untrusted prompt data.

## Verification

Run `./gradlew :backend:test --tests 'ru.souz.backend.e2e.BackendHistoryMemory*' --tests 'ru.souz.backend.app.BackendDiModuleTest'`, `./gradlew :sharedLogic:jvmTest --tests 'ru.souz.memory.MemoryRulesTest'`, and `./gradlew souzGateFast`.

The deterministic suite covers ACK independence, disconnect, restart, leases, filtering, bounded context and subsequent Souz capture. Real extraction checks are deferred to the scenario work in [PR #774](https://github.com/D00mch/souz/pull/774), which needs imported-history ingestion before it covers this path. Required scenarios include contextual selections, unconfirmed proposals, purchase reports with and without tool history, source provenance, retry identity and owner/chat isolation. HTTP-body assertions alone do not verify extraction quality.

Worker diagnostics contain chat/fragment IDs, attempts, document counts and error categories, never message bodies.
