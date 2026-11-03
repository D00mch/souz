# Desktop SQLite memory deletion — issue 659

Base: `03b31d7cae5c7494f962dd0aa15f917432ff5fe1`. The [issue](https://github.com/D00mch/souz/issues/659) description and empty discussion were read through the GitHub connector.

## Finding and change

Conversation deletion calls `MemoryServiceConversationCleanup.cleanupConversation`, then `MemoryService.deleteFactsByScope`, then the desktop [SQLite override](../../desktopApp/src/main/kotlin/ru/souz/memory/SqliteMemoryRepository.kt). The shared repository's default per-fact fallback is bypassed on desktop. The SQLite override already uses the IO dispatcher, one connection and one deletion transaction; the reported cost is repeated prepared SQL execution inside that transaction.

The repository deletes selected IDs in groups of 400. Each group loads distinct source IDs, deletes dependent rows and both supersession endpoints, deletes the facts, then removes only candidate sources without remaining evidence references. Source IDs are independently chunked. The largest statement has 800 parameters, below SQLite's legacy 999-variable limit. Single-fact deletion uses the same helper. Owner/scope selection, compatibility scopes, transaction boundaries and dispatcher ownership are preserved. Unrelated orphan sources, tombstones, maintenance jobs and retained facts' legacy `supersedes_fact_id` values are preserved.

## Correctness and reduced work

[SqliteMemoryDeletionTest](../../desktopApp/src/test/kotlin/ru/souz/memory/SqliteMemoryDeletionTest.kt) uses real SQLite databases under JUnit temporary directories. JDBC wrappers count actual prepared query/update executions without substituting results. Initialization, fixture construction and connection PRAGMAs are excluded from both measurements.

Each measured fact has an exclusive source and a source shared with a retained fact. Both versions use the same fixture and call `deleteFactsByScope`.

| Selected facts | Base executions | Candidate executions |
| --- | ---: | ---: |
| 1 | 10 | 9 |
| 24 | 217 | 9 |
| 1,001 | 9,010 | 27 |

This demonstrates fewer JDBC preparations/executions; it is not a wall-clock benchmark or a claim that SQLite performs no per-row work. The source-reference predicate still checks whether evidence remains.

The eight regression cases cover scalar/scoped equivalence, active/retired facts, chat/thread compatibility and prefixed IDs, owner and scope isolation, all dependent tables, both supersession directions, shared/exclusive/unrelated orphan sources, more than 999 facts and sources, sources shared across deletion chunks, empty/missing/repeated deletion, and complete rollback after a later chunk fails. Existing conversation cleanup and memory tests run in the full JVM graph.

## Review feedback

The [owner review](https://github.com/D00mch/souz/pull/999) requests fixes for layered mutability and repeated getter computation. `WaitingPhrases` stores immutable round snapshots in its mutex-protected map. `DeferredToolModifyPermissionBroker` replaces a virtual-file snapshot after each staged edit. `MemoryMaintenanceUiState` resolves `selectedModel` once per snapshot; `copy()` recomputes it, and conversion from controller status copies the available-model list.

The staged-edit regression covers sequential edits, an intervening rejected edit, applying the resulting edits and clearing a later edit. `MemoryMaintenanceUiStateTest` counts model-list reads and covers copied preferences, empty/unknown/null selections and isolation from subsequent controller-list mutations.

## Verification

The candidate is based on PR head `1db46542018eae062bb791645d11e8a4c4bcf14d` with the review fixes above. All Gradle commands use Temurin Java `21.0.13`, with `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` and `_JAVA_OPTIONS` unset. `-I build/repair-659/isolation.init.gradle` supplies isolated homes, logs and preferences without changing production build configuration. Live-provider evaluations are disabled.

| Exact command | Result |
| --- | --- |
| `./gradlew souzGateFast :desktopApp:test --tests ru.souz.memory.SqliteMemoryDeletionTest :backend:test --tests ru.souz.backend.agent.runtime.WaitingPhrasesTest --tests ru.souz.backend.agent.runtime.InitialWaitingDeliveryTest :sharedLogic:jvmTest --tests tool.files.ToolTest :sharedUI:jvmTest --tests ru.souz.ui.memory.MemoryMaintenanceUiStateTest --tests ru.souz.ui.memory.MemoryViewModelTest -I build/repair-659/isolation.init.gradle --no-daemon --console=plain` | Passed. All three review advisories cleared. SQLite execution counts remain 9 for 24 facts and 27 for 1,001 facts. |
| `npm ci --prefix quality` | Passed. |
| `./gradlew test :sharedLogic:allTests :sharedUI:allTests :koverXmlReport :koverHtmlReport koverLog souzDuplicationCheck -Psouz.coverage --no-parallel -I build/repair-659/isolation.init.gradle --no-daemon --console=plain` | Passed. Backend: 369 cases / 1 skipped; desktop: 368 / 150; shared logic: 397 / 11; shared UI: 134 / 39. Zero failures/errors; coverage reports completed. Duplication check passed; local checkout authority is `not_authoritative`, so remote CI remains required. |
| `./gradlew :desktopApp:createReleaseDistributable :desktopApp:packageReleaseDmg :desktopApp:verifyMacReleaseShrinking -I build/repair-659/isolation.init.gradle --no-daemon --console=plain` | Passed, including all six macOS artifact cases. |
| `git diff --check` and local Markdown link validation | Passed. |

## Packaged GUI validation

The exact repair checkout is `attempts/issue-659/repair-be9mez4_/checkout` under the managed workflow runtime. Its isolated source app is `build/repair-659/Souz Repair 659 Isolated.app`. All 248 production JARs match the release byte-for-byte. The canonical `Souz Workflow Validation.app` retains bundle ID `ru.souz.workflow.validation` and the existing certificate-bound identity.

The fixture uses a test-only delegating classloader, an in-memory PreferencesFactory and the synthetic `build/repair-659/gui-home`. It supplies an offline provider endpoint and forces maintenance Off/Auto. Production JARs are unchanged. Required startup correction, source hashes, command results and test totals are retained in ignored `build/repair-659/validation.json`; the independent GUI completion report is retained beside the checkout as `operator-gui-validation.json`.

| Scenario | Result |
| --- | --- |
| Open Memory and inspect the two synthetic facts | Passed: count 2, `SOUZ_659_DELETE`, `SOUZ_659_KEEP`, and two evidence entries on the deletion fact. |
| Delete `SOUZ_659_DELETE` through its named confirmation | Passed: CUA opened the card with a coordinate click, selected Delete through Accessibility and confirmed the named dialog. Count became 1. |
| Retain `SOUZ_659_KEEP` and shared evidence | Passed: a second coordinate click opened the remaining Active fact. Coordinate scrolling exposed its evidence card. |
| Open maintenance model picker and choose Auto model | Passed for the empty installed-model configuration; Auto model is the only available option and maintenance remains Off. |
| Read-only SQLite assertions | Passed: deleted fact, evidence, embedding, statistics, index job and both supersession endpoints are absent. Exclusive source is removed; shared source, unrelated orphan and retained dependencies remain. `integrity_check=ok`. |
| Clean shutdown and unchanged packaged code | Passed: Quit through the application menu, process exit, strict deep signature verification and all production JAR/source hashes. |

Concrete local-model switching and settings persistence were not GUI-tested because the isolated fixture has no downloaded models and forces maintenance preferences. Cached resolution, recomputation and model-list snapshot isolation are covered by the deterministic regression tests above. No inference was requested.

CUA coordinate input and scrolling passed without manual hover during this completion. Earlier `noWindowsAvailable` / `cgWindowNotFound` errors recovered after reconnect and service-restart attempts; the exact cause and permanent resolution are not established. No TCC or signing-identity changes were made. Voice, hotkeys, live providers and sleep/wake were outside these changes and were not tested. Remote CI, review and owner merge approval remain required.
