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

## Verification

All Gradle commands use the wrapper with process-local `JAVA_HOME=/Users/dumch/work/souz/.git/main-to-kmp/toolchains/temurin-21.jdk/Contents/Home`. Its `bin/java -version` reports Temurin `21.0.13+9-LTS`.

`-I build/issue-659/isolation.init.gradle` supplies an in-memory PreferencesFactory, per-task homes/log directories inside this checkout, removes inherited credential variables, and disables live-provider/Docker opt-ins. It changes no production build or global configuration. Raw logs, the baseline XML, test totals and helper sources remain under ignored `build/issue-659/`.

| Exact command after the JAVA_HOME assignment | Result |
| --- | --- |
| `./gradlew :desktopApp:test --tests ru.souz.memory.SqliteMemoryDeletionTest -I build/issue-659/isolation.init.gradle --no-daemon --console=plain` on the base production code | Expected regression failures: scoped statement budgets for 24 and 1,001 facts and single-fact source fanout; four correctness cases passed. `baseline-counts.log`, `baseline-deletion.xml`. |
| Same focused command after the production patch | Passed all seven initial cases; `candidate-focused.log`. The additional cross-chunk shared-source case is included in the full run below. |
| `./gradlew test :sharedLogic:allTests :sharedUI:allTests :koverXmlReport :koverHtmlReport koverLog -Psouz.coverage --no-parallel -I build/issue-659/isolation.init.gradle --no-daemon --console=plain` | Passed; `coverage-tests.log`. Desktop: 368 cases / 150 skipped; backend: 369 / 1; shared logic: 396 / 11; shared UI: 132 / 39. Zero failures/errors; all eight new cases passed. XML/HTML/log coverage completed. |
| `npm ci --prefix quality` | Passed; `npm-ci.log`, zero vulnerabilities reported. |
| `./gradlew souzGateFast souzDuplicationCheck -I build/issue-659/isolation.init.gradle --no-daemon --console=plain` | Passed; `gates.log`. All fast blocking checks passed. Duplicate tokens match baseline: production 252, tests 0. |
| `./gradlew :desktopApp:createReleaseDistributable :desktopApp:packageReleaseDmg :desktopApp:verifyMacReleaseShrinking -I build/issue-659/isolation.init.gradle --no-daemon --console=plain` | Passed; `packaging.log`. All six macOS artifact cases passed, including signature verification. |
| `git diff --check` and local Markdown link validation | Passed. |

Fast-gate advisories concern unchanged `WaitingPhrases.kt:20`, `DeferredToolModifyPermissionBroker.kt:81` and `MemoryUiState.kt:53`. The local duplication preflight reports `not_authoritative` for CI checkout identity; the duplicate-code check itself passes. Exact-checkout CI remains the wrapper's responsibility.

An initial focused invocation without the init script failed during fixture setup because an embedding INSERT omitted a migrated column; explicit column names corrected the fixture before baseline measurement. An initial full coverage invocation was stopped while compiling to add checkout-local log destinations to its isolation script, then rerun successfully as above (`coverage-interrupted.log`). No failed/incomplete invocation is counted as passing.

## Packaged GUI validation

Exact checkout: `/Users/dumch/work/souz/.git/main-to-kmp/attempts/issue-659/investigate-w9gelobd/checkout`, at the base above with this patch. All 249 production JARs match the freshly packaged release byte-for-byte.

The isolated GUI source is `build/issue-659/operator-gui-u52hlucn/Souz Issue 659 Isolated.app`. Its additional test-only PreferencesFactory uses in-memory preferences, forces the current checkout's synthetic `build/issue-659/gui-home` before application initialization, clears provider credentials and disables memory maintenance. No production JAR was modified. The existing wrapper prepared and signed it from a one-shot user GUI LaunchAgent at `/Users/dumch/work/souz/.git/main-to-kmp/native-validation/Souz Workflow Validation.app`, preserving the original certificate-bound identity. The temporary signing job was unloaded after completion.

| Current-candidate scenario | Result |
| --- | --- |
| Launch exact stable app through CUA/LaunchServices | Passed; main and Memory screens observed. |
| Display the two synthetic facts and deletion fact's exclusive/shared evidence | Passed; count 2, `SOUZ_659_DELETE`, `SOUZ_659_KEEP`, and both evidence entries observed. |
| Delete `SOUZ_659_DELETE` through its named confirmation | Passed; CUA clicked Delete and confirmed the dialog. Count changed to 1 and the fact disappeared. |
| Retain `SOUZ_659_KEEP` and its shared evidence | Passed; remaining fact was Active and `SOUZ_659_SHARED_EVIDENCE` was observed in its detail tree. |
| Read-only SQLite assertions | Passed: fact, evidence, embeddings, statistics, index job and both supersession endpoints removed; exclusive source removed; shared source and unrelated orphan preserved; remaining dependencies intact; `integrity_check=ok`. |
| Clean shutdown and unchanged packaged code | Passed; application exited normally through its menu, strict deep signature verification passed and all production JAR/source hashes matched. |

Metadata, the fixture source and precise database assertion results remain under ignored `build/issue-659/operator-gui-u52hlucn/validation.json`. The original agent report and failed `stable-prepare.log` are retained in this same attempt. Its initial cron-context signing failure was isolated with the same identity and a disposable executable: cron failed, while user LaunchAgent signing passed. Full candidate signing also passed in the user session.

CUA coordinate mouse input returned `noWindowsAvailable`. Accessibility clicks worked. The owner authorized the existing narrow activation helper and supplied hover on the deletion card; CUA then performed both deletion clicks. These observations do not establish that unattended coordinate input works. Voice, hotkeys, external provider evaluation and sleep/wake behavior were outside this SQLite validation. Remote CI, reviews and owner merge approval remain required.
