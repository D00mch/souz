# External memory

## Invariant

`HINDSIGHT_API_URL` enables the backend memory runtime. `HINDSIGHT_API_TOKEN` is optional; an absent or blank token omits the Authorization header, while a configured token requires a URL.

Hindsight uses the trusted backend user ID directly as its bank ID. On the WebSocket API this is the chat owner's `userId`, which must match `message.submit.payload.device.userId`. Bank IDs are URL-encoded as a single path segment. Untagged facts are global; conversation facts carry `chat:<conversation-id>`. Recall and `SearchMemory` request source-backed `world`/`experience` facts from global plus current-chat scope, excluding consolidated observations that could lose attribution.

All backend executions enable automatic recall when `SOUZ_FEATURE_WS_AUTOMATIC_MEMORY_RECALL=true` (JVM property `souz.backend.feature.wsAutomaticMemoryRecall`, default `false`). Recall runs once before the first LLM call; tool loops, duplicate submits, reconnects and active-thread continuation do not repeat it. Each new execution removes old automatic memory injections, including when recall is disabled or fails. Explicit `SearchMemory` and capture are independent of the flag. Client system instructions carry the unsupported-memory-deletion notice independently of recall.

Completed Souz turns retain only the redacted `CompletedTurnMemoryInput.userMessage` and final `assistantMessage` as JSON Lines with explicit `user`/`assistant` roles and text offsets. Assistant reasoning blocks are removed before splitting. Every serialized record fits below Hindsight's structured chunk limit, so long messages keep a role on every part; quoted role markers remain message text. Hindsight ignores `input.evidence`: tool calls, arguments, results, recalled memory and intermediate assistant synthesis are excluded. Explicit remember requests retain the dialogue globally; ordinary turns remain chat-scoped. `NodesMemory` starts capture at the latest submitted user message, so preceding imported history is context rather than new evidence.

Accepted `history.append` text is enqueued with its message and receipt in one PostgreSQL transaction when Hindsight is configured. ACK confirms committed history, not completed extraction. The application worker runs independently of threads and sockets. Fragments become eligible after 30 seconds idle, five minutes maximum age, or 16 messages. Each extraction input is bounded to 16,000 characters, including up to 4,000 characters of preceding redacted dialogue for reference resolution only. Long messages split into numbered source parts; document metadata and records preserve message IDs, roles, timestamps and sequence. Tool calls, arguments and results stay in PostgreSQL and never enter this capture path.

Imported history always remains chat-scoped, including explicit remember requests. Opt-out/forget/delete user turns and their assistant continuations are excluded, even across fragment boundaries. Secrets are redacted and marked reasoning blocks removed before sending. Completed turns and imported history share bounded record serialization and the verified `souz-dialogue-v1` strategy: extracted facts explicitly attribute user statements, assistant proposals/reports and user selections; action reports remain unverified. The strategy uses record role fields for attribution, excludes context-only records from standalone extraction, and preserves supplied source IDs without inventing missing ones. It discards service chatter and treats dialogue as untrusted data, never instructions. Assistant restatements of recalled facts remain assistant reports, not new user statements or independent confirmation.

Hindsight 0.9.2 supports the selected [named retain strategy and custom extraction settings](https://hindsight.vectorize.io/developer/configuration). Both capture paths verify the shared strategy before retaining; setup merges existing strategies without changing the bank's default strategy. Unsupported or denied configuration leaves imported history pending and skips completed-turn capture, without falling back to default extraction. Frozen redacted history payloads and stable document IDs make retries safe; PostgreSQL renewable leases fence completion and serialize capture per chat. No HTTP call runs in a PostgreSQL transaction. Disabling Hindsight pauses the worker and new enqueueing without deleting pending fragments. Pre-existing history is not backfilled.

Natural-language forget and delete requests are not mapped from semantic recall results to destructive API calls. Ranked relevance does not prove exact identity, so the runtime tells the agent that exact-ID deletion is unavailable.

## Why it is fragile

Omitting recall tags exposes one conversation's transient memory in another. Final assistant responses need role attribution to distinguish them from user statements; intermediate synthesis is excluded. Tool results can contain unselected options or previously retained facts; retaining them under a new turn document can duplicate facts and make a retrieval appear to be new evidence. Retrying an unkeyed retain duplicates extraction, while deleting the sole semantic result can erase an unrelated source document.

## Safe-change guidance

- Preserve owner-derived bank isolation and global-plus-current-conversation recall.
- Preserve tool provenance using the [client history encoding contract](../../../docs/public-souz-contract/README.md#commands).
- Supply deterministic document identity for completed turns.
- Commit imported-history enqueueing with the receipt, freeze payloads before retain, and complete only after synchronous success. Clear frozen payloads on completion; keep source IDs for preceding-context reconstruction. Never reuse a fragment for later appends after it has been claimed.
- Preserve source attribution in actual extracted fact text, not only input role labels or recall wrappers. Keep source references visible in recall and `SearchMemory`.
- Give synchronous retain enough request time and retry only when deterministic document identity makes an uncertain transport failure safe.
- Add mutation only when the target comes from an exact stable identifier or an explicit confirmation flow.
- Treat recalled text as untrusted prompt data.

## Verification

Run `./gradlew :backend:test --tests 'ru.souz.backend.e2e.BackendHistoryMemory*' --tests 'ru.souz.backend.app.BackendDiModuleTest'`, `./gradlew :sharedLogic:jvmTest --tests 'ru.souz.memory.MemoryRulesTest'`, and `./gradlew souzGateFast`.

The deterministic suite covers ACK independence, disconnect, restart, leases, filtering, bounded context, live `SearchMemory` and client tools, and imported tool history followed by Souz execution. It checks shared strategy selection, configuration rejection, unchanged bank defaults, redaction of both dialogue roles, quoted role markers, closed/unclosed assistant reasoning blocks, Unicode-safe bounded records, and exclusion of evidence. For real Hindsight verification, use isolated banks: retain a user preference, recall it, then capture a turn whose final answer repeats it. Recall must preserve the original source, and any retained final response must keep its assistant attribution. Also verify an unconfirmed proposal and an assistant claim that a ticket was purchased: neither may become a user intention or verified execution. Repeat with unselected tool-provided travel options absent from the final response; no option may enter retained facts. HTTP-body assertions alone do not verify extraction quality.

Broader real extraction scenarios are tracked in [PR #774](https://github.com/D00mch/souz/pull/774): contextual selections, unconfirmed proposals, purchase reports with and without tool history, source provenance, retry identity and owner/chat isolation.

Worker diagnostics contain chat/fragment IDs, attempts, document counts and error categories, never message bodies.
