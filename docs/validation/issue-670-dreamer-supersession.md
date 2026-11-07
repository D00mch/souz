# Issue 670: Dreamer supersession batching

Candidate: `b9417311891c1b1b8a6ca10342a5de6b4d1eb33b` plus this patch.
Date: 2026-11-08 (Europe/Moscow). Checkout:
`/Users/dumch/work/souz/.git/main-to-kmp/attempts/issue-670/investigate-n_htirlq/checkout`.

## Finding and change

The live [issue](https://github.com/D00mch/souz/issues/670) is open, labeled
`desktop` and `performance`, with no discussion comments. Desktop DI supplies
`MemoryMaintenanceWorker` to `DesktopMemoryMaintenanceController`.
`runOnce` → `processDreamerRegionRewrite` → `commitDreamerReplacement` reaches
`insertSupersedes` for each replacement candidate. Each call prepares identical
SQL and executes a batch for that candidate's sources. This is real repeated JDBC
preparation/batch dispatch, within one existing transaction on `Dispatchers.IO`.
It does not establish network N+1 requests, repeated commits or UI-thread blocking.

The worker sends all candidate/source links through one prepared JDBC batch after
inserting replacement facts and evidence, before enqueuing embeddings. The same
transaction, candidate/source order, per-candidate distinct filtering,
`insert or ignore`, owner, reason and timestamp are preserved. Each fact's
`supersedes_fact_id` still identifies its first source. No locks or job boundaries
change. The default quality gate requires at least two disjoint source facts per
candidate; the 24-source region bounds this to 12 replacements and 24 links.

## Reduced work and correctness

[DreamerEmbeddingJobsTest](../../desktopApp/src/test/kotlin/ru/souz/memory/DreamerEmbeddingJobsTest.kt)
wraps JDBC while delegating execution to real SQLite. Setup and assertions are
outside the counted section. The same regression ran before and after the
production edit:

| Replacements / links | Original prepares / batch executions | Candidate prepares / batch executions |
| --- | --- | --- |
| 1 / 2 | 1 / 1 | 1 / 1 |
| 12 / 24 | 12 / 12 | 1 / 1 |

SQLite still inserts the same rows; no latency improvement is claimed. The
original-worker run fails exactly the 12-replacement prepare-count assertion.
Tests verify every replacement/source mapping, owner, reason, timestamp,
first-source pointer, evidence, source retirement, embedding jobs and completion.
Repeated input IDs are normalized. A trigger aborts the fourth supersession insert
(after links for an earlier candidate): all links, replacements, evidence,
retirement and job completion roll back; retry succeeds after removing the trigger.
Existing cases cover absent embedding models and a job dirtied during consolidation
with zero insert preparations. All test data use temporary databases.

## Commands and results

Gradle commands use the process-local prefix
`JAVA_HOME=/Users/dumch/work/souz/.git/main-to-kmp/toolchains/temurin-21.jdk/Contents/Home`.
Its `bin/java -version` reports Temurin `21.0.13+9-LTS`.
Logs and preserved XML reports are under the ignored `build/issue-670/`.

| Exact command | Result |
| --- | --- |
| `./gradlew :desktopApp:test --tests 'ru.souz.memory.DreamerEmbeddingJobsTest' --no-daemon --console=plain` | Original worker: exit 1; 8 tests, one expected count failure. `baseline-tests.log`, `baseline-DreamerEmbeddingJobsTest.xml`. |
| `./gradlew :desktopApp:test --tests 'ru.souz.memory.*' --no-daemon --console=plain` | Exit 0; 107 tests, no failures/errors/skips. `memory-tests.log`, `memory-test-results/`. |
| `npm ci --prefix quality` | Exit 0; `npm-ci.log`. |
| `./gradlew :desktopApp:test souzGateFast souzDuplicationCheck test :sharedLogic:allTests :sharedUI:allTests :koverXmlReport :koverHtmlReport koverLog -Psouz.coverage --no-parallel --no-daemon --console=plain` with `SOUZ_AGENT_INTEGRATION_TESTS_ON=false` | Exit 1; new test's temporary constant membership collection rejected, fixed with `when`. Combining the gate with coverage also classifies Kover self-dependencies as unclassified; subsequent commands separate those lanes. `repository-checks.log`, `initial-gate-failure.json`. |
| `./gradlew souzGateFast souzDuplicationCheck --no-daemon --console=plain` | Exit 0; fast gate passes. Duplicate-code check passes locally; report is `not_authoritative` because CI owns exact-checkout authority. `policy-checks.log`. |
| `./gradlew :desktopApp:test test :sharedLogic:allTests :sharedUI:allTests :koverXmlReport :koverHtmlReport koverLog -Psouz.coverage --no-parallel --no-daemon --console=plain` with `SOUZ_AGENT_INTEGRATION_TESTS_ON=false` | Exit 0; 1,678 tests, 201 skipped, zero failures/errors. Desktop: 427 tests, 150 skipped. `coverage-tests.log`, `coverage-test-results/`. No live-provider evaluation enabled. |
| `./gradlew :desktopApp:createReleaseDistributable :desktopApp:verifyMacReleaseShrinking --no-daemon --console=plain` | Exit 0; all six packaged classpath/signature tests pass. `packaging.log`, `packaging-test-results/`. |

## Packaged candidate and GUI checks

Release: `desktopApp/build/compose/binaries/main-release/app/Souz AI.app`.
`python3 build/issue-670/prepare_gui.py` builds a deterministic Java consolidator
against those packaged JARs and calls the packaged worker with six synthetic
global facts. It produces three replacements and six supersession links without
calling a model. Fixture sources, hashes, worker output and AX observations are
under `build/issue-670/gui/`; `candidate.json` binds source and JAR hashes to this
checkout and base. Read-only post-GUI assertions verify all candidate/source
mappings, first-source pointers, owner/reason/timestamps, both evidence excerpts,
three active and six retired facts, one completed job and SQLite integrity `ok`.

The isolated input copy is `build/issue-670/Souz Issue 670 Input.app`.
Its bootstrap main sets this checkout's `build/issue-670/gui-home` before calling
the production entry point; its Java configuration uses an in-memory
`PreferencesFactory`. Maintenance stays Off. A loopback OpenAI-compatible fixture
handles Content-Length and chunked bodies; both framing self-tests pass. Its log
contains the two self-test embedding requests and one automatic application
embedding request, all to loopback. No chat request was submitted. The fixture
process was stopped after validation.

An initial test-copy Java-agent launcher failed before application startup because
the packaged runtime excludes `java.instrument`. LaunchServices output records
that in `gui/initial-javaagent-startup.log`; it was a harness error, corrected by
the bootstrap main (`python3 build/issue-670/repair_gui.py`, exit 0). A preliminary
bootstrap compile also rejected calling Kotlin's synthetic `main(String[])`;
the fixture calls the public zero-argument `main()` instead. Production JARs and
the release runtime are unchanged. Initial CUA selection timeouts therefore do
not establish a graphical-session failure.

The final authorized preparation command exited 0:

```sh
/opt/homebrew/bin/python3 -B /Users/dumch/work/souz/.git/main-to-kmp/native_validation.py --prepare '/Users/dumch/work/souz/.git/main-to-kmp/attempts/issue-670/investigate-n_htirlq/checkout/build/issue-670/Souz Issue 670 Input.app'
```

CUA selects the exact returned path:
`/Users/dumch/work/souz/.git/main-to-kmp/native-validation/Souz Workflow Validation.app`.
Bundle ID: `ru.souz.workflow.validation`. Existing signing identity:
`683BCFAE881BFA5854265C842608A02D24F2C7DB`.
Strict deep `codesign --verify --deep --strict` passes before/after inspection;
all 248 production JARs, source hashes and final fixture hash match.
The read-only session diagnostic reports `ready`, console UID 501.

| UI scenario | Result |
| --- | --- |
| LaunchServices startup of final isolated candidate and settings navigation | Passed through CUA; selected model is `OpenAI-compatible: offline-670-fixture`. |
| Memory active replacements | Passed: count 3, `SOUZ_670_REPLACEMENT_1`, `_2`, `_3`, Dreamer Off. |
| Each replacement's evidence | Passed: opened and scrolled each detail pane; visible pairs `SOUZ_670_EVIDENCE_1/2`, `_3/4`, `_5/6`. |
| Retired source retention | Passed: Retired filter count 6; scrolled the list to verify sources 5 and 6. |
| Normal shutdown | Passed: Command-Q; process inspection confirms exit without querying/relaunching the candidate afterward. |
| Voice, hotkeys, local generation, document tools, live providers | Not run; outside this SQLite batching change. |

No OS permissions, global settings or shared services were changed. No owner
checks remain for this change. CI, review and merge approval remain with the
wrapper.

Final verification commands:

- `python3 build/issue-670/verify_candidate.py`: exit 0; signature, source/fixture
  and production JAR hashes, read-only SQLite invariants/integrity and exited
  candidate checks; `post-gui-checks.log`, `validation.json`.
- `./gradlew souzGateFast --no-daemon --console=plain` with the Java prefix above:
  exit 0; `final-gate.log` reports pass.
- `git diff --check`: exit 0. The validation report's local test link resolves.
