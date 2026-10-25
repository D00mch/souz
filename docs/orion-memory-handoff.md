# Orion startup memory handoff

Orion supplies query-based Hindsight results through ordinary `history.append` assistant tool calls.
Souz stores and processes these results as conversation context. The memory service owns fact lifetime;
newly captured facts become available through later retrieval.

## Delivery and representation

Append each memory result as an assistant tool call and await its accepted ACK before
`message.submit`, under the client's per-chat submission lock. Retry an append with the same request
ID and frame. Failed delivery prevents submission. Appends are passive history and do not wake an
active thread; submission establishes the history boundary for processing.

Example content inside `payload: {role: "assistant", content: ...}`:

```json
{
  "type": "tool_call",
  "name": "SearchMemory",
  "arguments": {"semanticQuery": "The current request"},
  "result": {"facts": [{"text": "A relevant fact"}]}
}
```

Tool names, arguments and results are ordinary history data. Souz does not interpret a separate
memory-import schema or register an executable tool from the imported name. The client supplies the
result shape; empty or failed retrieval must not be presented as established facts. Imported tool
results are excluded from memory capture and remain in conversation history.

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
