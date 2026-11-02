# Desktop Telegram history cache validation

Issue: [#515](https://github.com/D00mch/souz/issues/515). Validated base: `dc86d4704dc5d8f8119836614e9981e7327af5ac`.

## Finding and implementation

At the validated base, `TelegramService.getHistoryByChatId` returns a sufficiently full cache without a TDLib request. Client initialization registers chat metadata handlers but no new-message, content-edit or deletion handlers, and history reads retain no `OpenChat` subscription. A cached conversation can therefore remain stale while Souz is the only client in use.

[TelegramService](../../desktopApp/src/main/kotlin/ru/souz/service/telegram/TelegramService.kt) delegates requested histories to [TelegramHistoryCache](../../desktopApp/src/main/kotlin/ru/souz/service/telegram/TelegramHistoryCache.kt). The cache retains opened chats until LRU eviction, history deletion or client reset, within the existing limits of 200 chats and 500 messages per chat. Lookup entries and unrelated events do not create subscriptions; background updates do not promote idle LRU entries. Each subscription closes through its originating client.

TDLib callbacks maintain cached views for incoming/outgoing messages, content edits, deletions and send completion/failure. Per-chat fetch coordination serializes overlapping reads while allowing callback updates. Events received during a fetch are replayed over its result, including forced replacement. Ordinary reads merge; forced refresh replaces. Failed/cancelled refreshes retain the previous cache and received events; failed/cancelled cold reads release their subscriptions. Late send responses cannot reinsert completed temporary message IDs. Reads do not send messages or mark them read.

TDLib documents the update dependency for opened supergroups/channels in [openChat](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1open_chat.html). Cold-start and reconnection synchronization remain asynchronous. The implementation makes no per-read server freshness guarantee and uses no delay or quiet-window heuristic as proof of freshness.

## Deterministic checks

All **20 added regressions** passed: 16 [cache cases](../../desktopApp/src/test/kotlin/ru/souz/service/telegram/TelegramHistoryCacheTest.kt) and 4 [service cases](../../desktopApp/src/test/kotlin/ru/souz/service/telegram/TelegramHistoryServiceTest.kt).

The idle-arrival case returns the incoming message on the next ordinary cached read, with a fetch count of **1 across both reads**, one retained `OpenChat`, zero `CloseChat`, and zero mark-read calls. This establishes reduced work without claiming measured Telegram synchronization latency.

The cases cover edits/deletions; events racing cold and forced fetches; merge versus replacement; paging, overlap and limits; LRU eviction and unrelated events; failed/cancelled opens and reads; failed-close ownership; reset and stale-client callbacks; rendered edits; and send-completion ordering. External services are simulated and test preferences/homes are isolated.

## Commands and results

Commands ran in the current managed checkout with Temurin `21.0.13+9-LTS`, Gradle `9.4.1`, and opt-in live agent evaluations disabled. `build/issue-515/isolation.init.gradle` supplies in-memory preferences, per-task test homes and local log directories; it is an ignored test fixture and changes no checked-in build configuration.

| Command | Result |
| --- | --- |
| `./gradlew :desktopApp:compileKotlin --rerun test :sharedLogic:allTests :sharedUI:allTests :koverXmlReport :koverHtmlReport koverLog -Psouz.coverage -I build/issue-515/isolation.init.gradle --no-parallel --no-daemon --console=plain` | Passed. Final production source was explicitly recompiled; full JVM tests and XML/HTML/log coverage completed. Desktop: 357 cases, 150 opt-in scenarios skipped, zero failures/errors; all 20 added cases passed. |
| `./gradlew :desktopApp:createReleaseDistributable :desktopApp:verifyMacReleaseShrinking -I build/issue-515/isolation.init.gradle --no-daemon --console=plain` | Passed, including all 6 macOS artifact cases. |
| `npm ci --prefix quality` | Passed. |
| `./gradlew souzGateFast souzDuplicationCheck -I build/issue-515/isolation.init.gradle --no-daemon --console=plain` | Passed with existing advisories outside the changed code. Duplicate-code ratchet: production 252 → 252 duplicated tokens, tests 0 → 0. Exact-checkout CI authority remains pending in GitHub Actions. |
| `git diff --check` and local validation-report links | Passed. |

The coverage graph and policy/duplication gates ran separately because the coverage-only self-project configurations are rejected by the policy gate when combined in one invocation. No quality policy was changed. Exact command logs remain in the ignored `build/issue-515/` directory and the private managed attempt archive.

## Packaged GUI and native provider checks

The release bundle was copied into a process-isolated GUI fixture. All **248 production JARs** were preserved byte-for-byte. The extra fixture JAR uses in-memory preferences, an isolated process home and a loopback model endpoint; provider credentials are not inherited. The authorized stable validation wrapper prepared `Souz Workflow Validation.app` with its existing certificate-bound identity. Strict/deep signature verification passed. No identity, trust setting or OS permission was changed.

The main window, settings, anonymous Telegram login form, synthetic typing, select-all/deletion and paste were verified through CUA. A completed local chat round trip visibly returned `SOUZ_515_FIXTURE_READY`. The owner then authenticated Telegram directly in the isolated candidate.

A separate read-only probe used this exact candidate's unchanged production JARs and its current isolated session after the GUI was closed. No session/database was copied. The probe's normal shutdown and a new JVM restored authorization without another login. It issued no message sends or mark-read operations. Only owner-selected private-chat and supergroup histories were inspected; account identifiers, message bodies, IDs and runtime evidence are excluded from this repository.

| Scenario | Result |
| --- | --- |
| Incoming private message while idle, after process restart | Passed. The synthetic marker was already cached before the first subsequent ordinary read, which returned it without forced refresh. The owner confirmed official receiving clients were closed. Unread stayed 1 → 1 and read-inbox identity was unchanged during the read. |
| Incoming supergroup messages while idle | Passed. New incoming messages were already in the cache before the next ordinary read, including after process restart. Follow-up reads with official receiving clients closed preserved unread and read-inbox identity. The tested supergroup was not a broadcast channel. |
| Edit of the same private message | Passed. The native observer recorded `UpdateMessageContent` and `UpdateMessageEdited`; the same message ID changed in the application cache before another history request. The old synthetic text disappeared and the new text was returned by the first ordinary cached read. |
| Delete the same private message for both participants | Passed. TDLib reported a permanent deletion from the provider, and the ID was absent from the application cache before another history request. The next ordinary read excluded it, refilled to the requested limit and preserved unread/read-inbox state. |

The edit check preserved read state during its history read. Read-inbox identity had advanced between the earlier arrival check and the edit check, so the results make no claim about every other session remaining closed throughout that intervening interval. Live checks establish the observed deliveries; they do not replace the cold-start/reconnection limitation above. Native callback observations contain metadata and synthetic-marker booleans, retained privately with the exact candidate/JAR hashes.

Production source SHA-256:

- `TelegramHistoryCache.kt`: `4e014c7a8108f73af604bd4f43be891645e5d94e021dedc16b3131a16f21f369`
- `TelegramService.kt`: `51d78dce7a1cd39b93d91f63c97df4f8be5db7f976c13eed84302c0b03608800`
