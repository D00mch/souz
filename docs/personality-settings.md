# Backend personality

`personalityPrompt` is an optional user preference independent of the global/user `systemPrompt`.
Settings GET and bootstrap expose it with `personalityRevision`. Ordinary settings PATCH preserves
both; conversational changes use the enabled tool-backed Skill `personality.update`:

- `update` + `request`: replace the whole personality, even for a request about one trait.
- `append` + `request`: preserve unrelated preferences only for explicit additions or preserving
  the rest ("а еще", "все ок, только"). The validator rejects append without that explicit intent.
- `reset`: clear it; omit `request`.

Validation is a separate call using the current execution's model, credentials and accounting.
Replacement receives only the new wish; append also receives the saved personality. The result must
be a reusable style for both speech and text. Any unsuitable part rejects the entire request.
The check is model-based. JSON may have one Markdown fence, but extra fields/text are rejected.
Failed validation never writes settings. Only a successful tool result confirms persistence.

The next conversational LLM call reads the saved style, including inside the current turn. The
request copy changes; base prompts, stored history, classification, summaries and subagents do not.
Saved style supersedes older personality instructions. Explicit `enabledTools` lists must include
`personality.update`; disabled tools are neither advertised nor executable.

PostgreSQL stores separate prompt/version columns. Empty state starts at revision 1; updates and
resets increment it. The limit is 16,384 UTF-16 units and revision 9,007,199,254,740,991. Validation
runs outside transactions; revision conflicts revalidate the original intent, at most three times.
Ordinary settings writes cannot overwrite personality. Commit and application-owned delivery are
handed off without a cancellation gap; cancelling a turn does not discard a committed change.

Each backend-channel subscription sends a fresh snapshot after registering its live stream, in a
separate coroutine so `tool.result` can be received. Changes prefer the initiating owned Orion
channel; Telegram/VK can use any open owned channel. Offline state syncs on reconnect. One channel
suffices because Orion stores by user; channel lookup is local to the backend process.

Delivery uses live `tool.call.started` with `seq:null`, name `prompt.personality.set`, and exactly
`{"prompt":"...","revision":2}`. Empty prompt clears Orion. The thread ID is the originating
execution's UUID or a technical UUID on connection. Arguments/results never enter durable replay.
`applied`/`unchanged` must echo the revision. `stale` is accepted only if the remote revision matches
a newer local snapshot. Unknown outcomes retry the same snapshot up to three times, ten seconds
each, with fresh call IDs; permanent protocol errors stop retries. Delivery never rolls back storage.
Results expose `saved`, `revision`, and `sync` (`synced`, `pending`, `superseded`, `conflict`, `failed`).
Only `synced` confirms delivery; do not repeat a saved mutation to retry transport.

For legacy prompts, back up the DB and explicitly move only identified style text from the base
prompt while incrementing revision atomically. Direct SQL does not trigger sync: reconnect channels
afterward and verify Orion's stored version.

Verification: `./gradlew :backend:test --tests '*BackendPersonalityE2eTest'`, then `:backend:test :agent:test`.
Cover current-turn style, replacement/addition/reset/rejection, ordinary writes, restart/owner isolation,
concurrent requests, channel selection/retry, and subscription synchronization.
