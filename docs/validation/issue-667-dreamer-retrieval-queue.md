# Dreamer retrieval queue — issue 667

Candidate base: `b46e0b90d4e820540ce39ff981a58cd279c3d7fb` (`origin/kmp`). The [issue](https://github.com/D00mch/souz/issues/667) and its empty discussion were read through the GitHub connector.

## Finding and implementation

`MemoryService.selectMemoryFacts` calls `SqliteMemoryRepository.recordRetrieval`, which records distinct retrieval statistics before calling [enqueueDreamerRegionsForRetrievedFacts](../../desktopApp/src/main/kotlin/ru/souz/memory/SqliteMemoryMaintenanceQueue.kt). The original retrieval loop reads the same owner/scope's active durable fact count for every eligible retrieved fact and prepares/executes a separate queue upsert. This is actual database work, rather than only a detector hypothesis.

Retrieval collects eligible anchors using the existing normalization and enum fallbacks. A grouped query counts facts for distinct exact owner/scope keys, including unselected and Dreamer-created facts. Groups contain at most 300 regions (902 parameters). Each eligible anchor retains its separate cluster key and original selection order. One prepared JDBC batch performs the queue upserts with the existing SQL conflict rules. Insert/update callers retain their single-fact eligibility path and share the upsert SQL/binding.

Queue writes do not change the facts being counted. Dispatcher, connection, caller transaction ownership and synchronization are preserved. Retrieval statistics, eligibility, priorities, reason merging, maximum dirty timestamp, creation timestamp, resetting attempts and rescheduling remain covered.

## Regression and reduced work

[DreamerRetrievalQueueTest](../../desktopApp/src/test/kotlin/ru/souz/memory/DreamerRetrievalQueueTest.kt) delegates instrumentation to real SQLite. Counts exclude schema creation and fixture setup. Scalar and retrieval queue contents are compared, excluding the generated wall-clock scheduling timestamps; those timestamps are checked separately against the actual invocation interval.

| Retrieved anchors / distinct regions | Base retrieval reads | Candidate retrieval reads | Base / candidate upsert preparations |
| --- | ---: | ---: | ---: |
| 1 / 1 | 2 | 2 | 1 / 1 |
| 24 / 1 | 25 | 2 | 24 / 1 |
| 301 / 301 | 302 | 3 | 301 / 1 |

The candidate executes one JDBC batch containing one entry per eligible anchor. SQLite still performs each row's upsert. These are query/preparation counts, not latency measurements or a claim of fewer per-anchor writes/commits.

The initial regression run on the unchanged production source failed three work-count assertions (25 versus 2 reads, 302 versus 3 reads, and a scalar update instead of a batch). Eight final cases cover scalar equivalence, duplicate/missing IDs, exact owner/scope isolation, status/retention/creator filters, normalization and unknown-enum fallbacks, global/project priority, conflict merging, 301 regions, caller rollback, batch failure propagation, empty inputs, and repository statistics.

## Verification

All Gradle commands use `JAVA_HOME=/Users/dumch/work/souz/.git/main-to-kmp/toolchains/temurin-21.jdk/Contents/Home` (Temurin `21.0.13+9-LTS`). Commands and raw evidence are retained in this checkout's ignored `build/issue-667/`. `isolation.init.gradle` supplies an in-memory PreferencesFactory, isolated test homes and log paths, and disables live agent evaluations. No application settings, OS permissions, shared services or global configuration are changed.

| Exact command (with the `JAVA_HOME` above for Gradle) | Result |
| --- | --- |
| `./gradlew :desktopApp:test --tests 'ru.souz.memory.DreamerRetrievalQueueTest' --no-daemon --console=plain` | Base: 6 cases, 3 expected work-count failures; first candidate: all 6 passed. The final 8-case version passed in the full graph below. |
| `./gradlew souzGateFast :desktopApp:test -I build/issue-667/isolation.init.gradle --no-daemon --console=plain` | Passed. Intermediate desktop suite: 416 cases / 150 skipped, zero failures/errors. |
| `npm ci --prefix quality` | Passed; 2 packages installed, zero reported vulnerabilities. |
| `./gradlew test :sharedLogic:allTests :sharedUI:allTests :koverXmlReport :koverHtmlReport koverLog souzDuplicationCheck -Psouz.coverage --no-parallel -I build/issue-667/isolation.init.gradle --no-daemon --console=plain` | Passed. Final desktop: 417 cases / 150 skipped, including all 8 new cases. Shared logic: 397 / 11; shared UI: 140 / 39; backend: 378 / 1. All modules have zero failures/errors. Coverage reports completed. Duplication gate passed with local authority `not_authoritative`; remote CI remains required. |
| `./gradlew souzGateFast :desktopApp:createReleaseDistributable :desktopApp:packageReleaseDmg :desktopApp:verifyMacReleaseShrinking -I build/issue-667/isolation.init.gradle --no-daemon --console=plain` | Passed on final sources, including fast gate and all 6 macOS artifact tests. |
| `python3 build/issue-667/prepare-gui.py` | Passed; creates an isolated copy and verifies all 248 production JARs against the release. |
| `/opt/homebrew/bin/python3 -B /Users/dumch/work/souz/.git/main-to-kmp/native_validation.py --prepare '/Users/dumch/work/souz/.git/main-to-kmp/attempts/issue-667/investigate-soa136dn/checkout/build/issue-667/Souz Issue 667 Isolated.app'` | Passed; existing stable signing identity and designated requirement retained. |
| `python3 build/issue-667/verify-gui.py` | Passed: process exit, strict deep signature, unchanged production JARs/source/fixture, exact SQLite jobs and integrity. |
| `git diff --check` and local Markdown target validation | Passed. |

Logs: `baseline-test.log`, `focused-test.log`, `desktop-gate.log`, `full-verification.log`, `release-gate.log`, `test-summary.json`, `native-prepare.json`, and `gui-evidence.json` under `build/issue-667/`.

## Packaged graphical scenarios

Source checkout: `/Users/dumch/work/souz/.git/main-to-kmp/attempts/issue-667/investigate-soa136dn/checkout`, at the base above with the uncommitted patch. Release: `desktopApp/build/compose/binaries/main-release/app/Souz AI.app`. Input copy: `build/issue-667/Souz Issue 667 Isolated.app`. Computer Use selected exactly `/Users/dumch/work/souz/.git/main-to-kmp/native-validation/Souz Workflow Validation.app` through `cua.getApp`, using LaunchServices.

The stable bundle ID is `ru.souz.workflow.validation`, with existing certificate leaf fingerprint `683BCFAE881BFA5854265C842608A02D24F2C7DB`. The bootstrap fixture is an additional test-only JAR; all 248 production JARs match the release byte for byte before and after validation. It forces this checkout's `build/issue-667/gui-home` and an in-memory PreferencesFactory before production startup. It never reads the stable profile or real application settings. Maintenance is Off; no model/provider inference is requested.

The fixture seeds synthetic global/project pairs and one session fact, then calls the exact packaged `enqueueDreamerRegionsForRetrievedFacts` with those IDs, a duplicate and a missing ID. It verifies the queue before opening the production main window. Source and JAR SHA-256 values, fixture identity, and observed scenario results are retained in `gui-evidence.json`.

| Required scenario | Result |
| --- | --- |
| Packaged SQLite entry point | Passed: four separate pending anchored jobs; priorities 6/6/8/8, attempts 0, exact retrieval dirty timestamp and rewrite reason. Session and missing IDs produce no job; duplicate ID produces no extra job. |
| Open Memory and inspect retained facts | Passed: CUA clicked the Memory icon; count 5, both global facts and both project facts visible. Scrolling exposed `SOUZ_667_SESSION`. |
| Inspect Dreamer queue | Passed: CUA opened the panel and observed `Pending 4 · Blocked 0`, Off, and disabled Run. |
| Clean shutdown | Passed: CUA chose Quit Souz AI from its application menu; process inspection confirmed exit without querying/relaunching the app. |
| Post-quit database and bundle assertions | Passed: five facts retained, exact four anchored jobs, `integrity_check=ok`, strict deep signature and all production/source/fixture hashes unchanged. |

Voice, hotkeys, local-model inference and live providers were not run; this change only affects SQLite retrieval queue work. No OS permission, signing identity, global configuration or shared service was changed. Remote CI, review, publication and owner merge approval remain with the wrapper.
