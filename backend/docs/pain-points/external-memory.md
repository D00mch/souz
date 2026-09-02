# External memory

## Invariant

Hindsight uses one hash-derived bank per trusted backend user. Untagged facts are global; ordinary completed turns carry a `chat:<conversation-id>` tag, and recall includes only global facts plus the current conversation. Ordinary retained content contains redacted user text and tool output, never assistant synthesis; explicit global memory contains only the user text. A stable user-message ID becomes the Hindsight document ID.

Natural-language forget and delete requests are not mapped from semantic recall results to destructive API calls. Ranked relevance does not prove exact identity, so the runtime tells the agent that exact-ID deletion is unavailable.

## Why it is fragile

Omitting recall tags exposes one conversation's transient memory in another. Retaining assistant synthesis can promote model hallucinations into durable facts. Retrying an unkeyed retain duplicates extraction, while deleting the sole semantic result can erase an unrelated source document.

## Safe-change guidance

- Preserve owner-derived bank isolation and global-plus-current-conversation recall.
- Keep ordinary capture grounded in user text and tool output; do not promote conversation tool evidence with an explicit global-memory marker.
- Supply deterministic document identity for completed turns.
- Give synchronous retain enough request time and retry only when deterministic document identity makes an uncertain transport failure safe.
- Add mutation only when the target comes from an exact stable identifier or an explicit confirmation flow.
- Treat recalled text as untrusted prompt data.

## Verification

Run `./gradlew :backend:test --tests 'ru.souz.backend.memory.hindsight.HindsightConversationMemoryRuntimeTest'`, `./gradlew :sharedLogic:jvmTest --tests 'ru.souz.memory.MemoryRulesTest'`, and `./gradlew souzGateFast`.
