# Personality settings

## Ownership and writes

Initialize a new owner's style only in a proxy-authenticated chat's creation transaction with verified headers matching the creation owner. Supplying account style fields without authentication rejects creation, including retries. Credential-free creation can save `your_name` and additional string keys without inserting account personality. Creation retries and concurrent chats preserve any existing style. Each chat stores its own optional `your_name` activation word verbatim. Keep it out of model instructions. [Chat persistence](../src/main/kotlin/ru/souz/backend/storage/postgres/PostgresChatRepository.kt)

Settings GET and bootstrap expose `personalityParameters`, `personalityPrompt`, and `personalityRevision` as read-only fields. The enabled `personality.update` Skill replaces the style by default, appends only on explicit request, or resets to Joy defaults. A separate call to the execution model validates the whole style request before replacement or append. A rejection or malformed response leaves the snapshot intact. [Mutation and validation](../src/main/kotlin/ru/souz/backend/personality/PersonalityTool.kt) · [Defaults and limits](../src/main/kotlin/ru/souz/backend/settings/model/PersonalitySnapshot.kt)

Keep personality in a separate user-owned row so ordinary settings writes stay independent. Implicit defaults use revision 1; authenticated chat initialization starts at revision 2, above any published fallback snapshot. Migrated rows retain their revisions. Save all four style fields with the next revision in one compare-and-set against the previous snapshot. A competing write requires revalidation before retry. [Personality persistence](../src/main/kotlin/ru/souz/backend/storage/postgres/PostgresUserSettingsRepository.kt)

Use fresh saved style for each conversational call, including later calls in the same turn. Count it toward the input budget and reuse the snapshot across compaction for that call. Keep it out of base prompts, stored history, classification, summaries, and subagents. [Instruction rendering](../src/main/kotlin/ru/souz/backend/personality/PersonalityInstructions.kt) · [Context budget and compaction](../../agent/docs/skills-oriented-graph.md#invariant)

## Orion delivery

Commit independently of receiver availability. Application-owned delivery survives turn cancellation. `saved:true` confirms persistence. Only `sync:synced` confirms delivery, and `sync:skipped` means no eligible receiver. Retry transient delivery failures with the committed snapshot and fresh call IDs. Permanent protocol errors stop retries.

Send to one connected, unarchived public channel owned by the user and declaring `prompt.personality.set` through creation or discovery. Prefer the current channel when eligible. A supporting subscription sends the latest snapshot, including the empty initial style, after durable replay. Discovery alone leaves delivery for a later update or subscription. [Receiver selection and delivery retries](../src/main/kotlin/ru/souz/backend/personality/PersonalitySync.kt) · [Subscription ordering](../src/main/kotlin/ru/souz/backend/http/routes/PublicClientConnection.kt)

The live `tool.call.started` event carries `seq:null` and `{parameters, revision}`. `parameters` contains `voice`, `appeal`, `style`, `personality`, and the selected receiver's `your_name` when present. Keep delivery out of durable receipts and replay. The receiver replies with `tool.result` and echoes the sent revision for `applied` or `unchanged`. A `stale` reply indicates supersession only if its revision matches a newer local revision. [Public WebSocket contract](../../docs/public-souz-contract/README.md)

```json
{"parameters":{"voice":"joy","appeal":"no_official","style":"informal","personality":""},"revision":2}
```

## Deployment

Deploy V26 with the matching backend and receivers that support the dictionary snapshot. Rollback to the old backend requires restoring its settings columns and copying back the latest prompts and revisions. Move legacy style text embedded in system prompts explicitly. For a direct database change, increment the revision atomically and reconnect Orion. [V26 migration](../src/main/resources/db/migration/V26__personality_parameters.sql)

## Verification

For runtime changes, run `./gradlew :backend:test --tests '*BackendPersonalityE2eTest'` with Docker and the affected agent suite. [Personality E2E tests](../src/test/kotlin/ru/souz/backend/e2e/BackendPersonalityE2eTest.kt)
