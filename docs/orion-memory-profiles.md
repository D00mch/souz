# Orion startup memory handoff

Orion supplies query-based Hindsight results through ordinary `history.append` assistant tool calls.
Souz stores and processes these results as conversation context. The memory service owns fact lifetime;
newly captured facts become available through later retrieval.

## Delivery and representation

Send household and personal results as separate messages, with explicit scope and the personal
`embeddingId`. Await each accepted ACK before `message.submit`, under the client's per-chat submission
lock. Retry an append with the same request ID and frame. Failed delivery prevents submission; an
accepted first append remains committed if another append fails. Appends are passive history and do
not wake an active thread. Submission establishes the history boundary for processing.

Example content inside `payload: {role: "assistant", content: ...}`:

```json
{
  "type": "tool_call",
  "name": "memory.recall",
  "arguments": {
    "scope": "personal",
    "bankId": "person:<userId>:<embeddingId>",
    "embeddingId": "<embeddingId>",
    "query": "Current request: ..."
  },
  "result": {
    "fetchedAt": "2026-10-25T12:00:00Z",
    "status": "ok",
    "facts": [{"id": "<memory ID>", "text": "A relevant fact", "type": "world", "score": 0.8}]
  }
}
```

Household arguments omit `embeddingId`. A failed recall has `status: error`, empty facts and an
`error` code; a successful empty recall has `status: ok` and empty facts. Scope, bank and speaker IDs
are provenance supplied by Orion, not new routing or authorization fields interpreted by Souz.
The tool name records historical provenance and does not register an executable tool. Imported tool
results are excluded from memory capture. Old results remain in conversation history; timestamps and
speaker IDs guide interpretation without deterministic conflict resolution.

## Souz execution

Automatic startup recall is off by default for every channel. The optional node is gated by
`SOUZ_FEATURE_WS_AUTOMATIC_MEMORY_RECALL`; it still clears old automatic injections. Explicit
`SearchMemory` and completed-turn capture retain their existing bank and capture behavior.

The complexity classifier receives the current request separately from reference history, plus
already-collected capability names and short descriptions. It admits a simple answer only when text
from available context suffices, at confidence >= 0.9. Supplied memory results can satisfy this
requirement. Missing facts, tools, external actions and uncertainty select the complex route. Earlier
actions are not pending tasks unless the current request needs or continues them.

The simple model receives full conversation context without tool schemas or executable tools;
complex and fallback calls retain their capabilities. Inventory preparation precedes selection and
its metadata is reused without additional discovery or classifier calls. A non-simple parent turn
emits one initial waiting message; a simple turn suppresses it. Real-model semantic routing requires
live evaluation; deterministic tests verify the request structure and routing mechanics.
