# Personality settings

`personalityPrompt` and `personalityRevision` are user settings exposed in settings GET and bootstrap.
Ordinary settings PATCH cannot change them. The enabled `personality.update` Skill accepts a whole
`request` and an optional `action`: `update` (default) replaces the style, `append` preserves unrelated
preferences only for explicit additions, and `reset` clears it without a request.

A separate call through the execution model validates the entire wish and normalizes a style suitable
for both text and voice. Rejection or malformed output leaves settings unchanged. The call uses the
execution's credentials, context limit and usage accounting. Only conversational requests receive the
latest saved style, including subsequent calls in the same turn; base prompts, persisted history,
classification, summaries and subagents remain independent.

Personality instructions count toward conversational context budgeting before every model call.
Each call reads one snapshot, reused if history needs compaction; the next call reads fresh settings.
If necessary, completed history is summarized without the personality before sending; the current
user/tool exchange stays intact. If that exchange and the style still cannot fit, the call returns a
context-budget error. The style never enters persisted history or auxiliary summary requests.

Separate PostgreSQL columns prevent ordinary settings writes from overwriting personality. Revisions
start at 1 and increase on every update/reset, up to 9,007,199,254,740,991. Prompts are limited to 16,384
UTF-16 units. Validation runs outside transactions; a competing write triggers revalidation, at most
three attempts. Before validation or mutation, if no connected channel declares synchronization support,
`personality.update` returns `saved:false` and `client_tool_discovery_required` for an unknown connected
catalogue, preferring the current channel. The parent invokes `orion.tools.list` there and retries the
same update. `prompt.personality.set` is hidden from model discovery output; retry the update even if
it is absent from the returned tools. No revision is consumed by this discovery request. Successfully
empty catalogues and offline channels do not require discovery. After commit, delivery belongs to the
application scope and survives turn cancellation.

Orion receives `prompt.personality.set` through live `tool.call.started` / `tool.result` transport, with
`seq:null` and exactly `{"prompt":"...","revision":2}` as arguments. Empty prompt clears the style.
Only owned, unarchived public channels declaring the command through creation or discovery are eligible.
Prefer the current connected channel when it supports the command; otherwise select another eligible
connected channel. Deliver to one receiver, without broadcasting. A channel that rejects publication
yields to another eligible channel on retry; a lone busy channel can still recover within the retry limit.
A subscription/reconnection sends the current snapshot only when that channel declares support,
after durable replay completes, including the empty initial state. Replay time does not consume
command deadlines. Discovery is not triggered by connecting, and saved catalogues survive restart.
The thread ID is the source execution or a technical UUID on connection. These calls create no target
execution, durable result receipt or replay event.

`applied`/`unchanged` acknowledgements must echo the revision; `stale` is accepted only when it matches a
newer local revision. Transient failures retry the same snapshot up to three times with fresh call IDs
and ten-second deadlines. Permanent protocol errors stop retries. Results distinguish storage (`saved`,
`revision`) from delivery (`sync`: `synced`, `skipped`, `pending`, `superseded`, `conflict`, `failed`).
`skipped` means there is no eligible connected receiver; the style is still saved. Only `synced` confirms
delivery. Offline changes synchronize on a supporting channel’s reconnect; never repeat a saved mutation
to retry delivery.

Existing style text embedded in system prompts is not automatically migrated. Move identified style
text explicitly, increment the revision atomically, and reconnect Orion after any direct database change.

Verify with `./gradlew :backend:test --tests '*BackendPersonalityE2eTest'` and the backend/agent suites.
