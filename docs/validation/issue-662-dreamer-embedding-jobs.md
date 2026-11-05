# Issue 662: Dreamer embedding enqueue validation

Candidate: base `4b36104933b03717a965ed5ff5354bd4109fa79d` plus the uncommitted
worker and regression-test changes in this checkout. Validation date: 2026-11-04.
Decision: **ready**; automated checks pass and the isolated GUI scenarios below were completed on 2026-11-05.

## Finding and intervention

The open [issue](https://github.com/D00mch/souz/issues/662) has no discussion comments.
The current desktop DI supplies an embedding model to `MemoryMaintenanceWorker`.
`processDreamerRegionRewrite` calls `commitDreamerReplacement` inside one SQLite
transaction on `Dispatchers.IO`. Its replacement loop prepares and executes the
same `memory_index_jobs` upsert for each fact. This is actual repeated statement
preparation, not evidence of per-fact connections, commits, or UI-thread blocking.

The patch enqueues all inserted `(factId, contentHash)` pairs with one prepared
JDBC batch after provenance insertion, inside the same transaction. Owner, model,
hash, timestamps, conflict handling, job claim and rollback boundaries remain the
same. Other connections cannot observe intermediate transaction state. The default
quality gate requires disjoint pairs of source facts; the 24-fact region permits
at most 12 replacements.

`DreamerEmbeddingJobsTest` delegates instrumented connections to real SQLite and
checks statement preparations/executions, queue contents, evidence, supersession,
source retirement and job completion. For 12 replacements, preparations decrease
from 12 to 1, with 12 scalar JDBC updates replaced by one `executeBatch` containing
12 entries. SQLite still writes 12 rows; no timing improvement is claimed.

The new tests run against the original worker produced the expected two failures:
the one-fact case executed one scalar update, and the 12-fact case prepared 12
statements. All five cases pass with the patch, including absent embedding model,
concurrent job dirtiness, and a real SQLite trigger aborting the second enqueue.
That failure rolls back the first queued row, replacement facts, evidence,
supersession, source retirement and completion; retry succeeds after dropping the
test trigger. Test databases use JUnit temporary directories.
An isolated in-memory SQLite probe using the packaged driver also reports
`SQLiteException`, SQL state `null`, error code `19` for both scalar and batched
constraint failures (`build/issue-662/batch-error-check.log`). This preserves the
exception class exposed by the maintenance controller's error-code reporting.

## Commands and results

Every Gradle command below used the process-local prefix
`JAVA_HOME=/Users/dumch/work/souz/.git/main-to-kmp/toolchains/temurin-21.jdk/Contents/Home`.
That installation's `bin/java -version` reports Temurin `21.0.13+9-LTS`.
Logs and preserved XML results are under `build/issue-662/`.

| Command | Result |
| --- | --- |
| `./gradlew :desktopApp:test --tests ru.souz.memory.DreamerEmbeddingJobsTest --no-daemon --console=plain` | Original-worker regression: exit 1, 5 tests, 2 expected performance-assertion failures; `baseline-tests.log`, `baseline-tests.xml`. |
| `./gradlew :desktopApp:test --tests 'ru.souz.memory.*' --no-daemon --console=plain` | Exit 0, 65 tests, no failures/skips; `memory-tests.log`, `memory-test-results/`. |
| `npm ci --prefix quality` | Exit 0; `npm-ci.log`. |
| `./gradlew souzGateFast souzDuplicationCheck :desktopApp:test :desktopApp:createReleaseDistributable :desktopApp:verifyMacReleaseShrinking --no-daemon --console=plain` | Exit 0; `required-checks.log`. Desktop: 373 tests, 150 skipped, no failures. Packaged classpath/signature checks pass. |
| `./gradlew test :sharedLogic:allTests :sharedUI:allTests :koverXmlReport :koverHtmlReport koverLog -Psouz.coverage --no-parallel --no-daemon --console=plain` | Exit 0; `coverage.log`. XML reports contain 1,601 tests, 201 skipped, no failures/errors. No opt-in live-provider evaluation enabled. |
| `./gradlew souzGateFast --no-daemon --console=plain` | Exit 0 after adding this report; `final-gate.log`, fast gate reports pass. |
| `git diff --check` | Exit 0. |

## Packaged candidate and GUI checks

Source checkout:
`/Users/dumch/work/souz/.git/main-to-kmp/attempts/issue-662/investigate-1v2w08sp/checkout`.
Original release: `desktopApp/build/compose/binaries/main-release/app/Souz AI.app`.
Worker SHA-256: `ebb982b7326726d5271ec2d3e61f4e612b0b7a07604e4228bf3606a60affed99`.
Test SHA-256: `608f5d9d48ec4e34acec08561950a570141940875c49c715bbb1e0f662a2e658`.

The checkout-local `build/issue-662/prepare_gui.py` compiles a deterministic Java
consolidator against the exact packaged production JARs. The packaged worker
processes six synthetic global facts into three replacements, three pending
embedding jobs and six supersession links; SQLite integrity is `ok`.
This offline harness passes. It submits no model request.

The test copy is `build/issue-662/Souz Issue 662 Isolated.app`. Its extra bootstrap
JAR forces an isolated `user.home`, startup-log directory and in-memory
`PreferencesFactory` before calling the production entry point. Maintenance is
Off for GUI inspection. The final isolated home is `build/issue-662/gui-home`.
All 248 production JARs match the original release byte for byte.

The authorized preparation command (exit 0) was:

```sh
/opt/homebrew/bin/python3 -B /Users/dumch/work/souz/.git/main-to-kmp/native_validation.py --prepare '/Users/dumch/work/souz/.git/main-to-kmp/attempts/issue-662/investigate-1v2w08sp/checkout/build/issue-662/Souz Issue 662 Isolated.app'
```

Computer Use selected this exact returned path through `cua.getApp`:
`/Users/dumch/work/souz/.git/main-to-kmp/native-validation/Souz Workflow Validation.app`.
Wrapper identity: `683BCFAE881BFA5854265C842608A02D24F2C7DB`;
bundle ID: `ru.souz.workflow.validation`.
`/usr/bin/codesign --verify --deep --strict` on that exact path exits 0.

| Scenario | Result |
| --- | --- |
| Select running candidate and observe main window | Passed through the exact stable app path on 2026-11-05. Owner foreground activation was needed before coordinate clicks and keyboard input worked; a synthetic draft was typed and cleared without submission. Unattended activation is not proven. |
| Open Memory and see `SOUZ_662_REPLACEMENT_0`, `_1`, `_2`, active count 3 | Passed through CUA; Dreamer remains Off. |
| Inspect each replacement and its two `SOUZ_662_EVIDENCE_` excerpts | Passed through CUA; opened and scrolled each detail pane. The three records show evidence pairs 0/1, 2/3 and 4/5 respectively. |
| Quit through GUI | Passed with Command-Q; process inspection confirmed the exact stable candidate exited. |
| Voice, native hotkeys, local generation, document tools, live providers | Not run; outside this SQLite enqueue change. |

After GUI shutdown, read-only SQLite assertions confirm three active replacements,
six retired source facts, three pending embedding jobs with the exact content hashes,
six supersession links, both evidence excerpts for each replacement, completed
maintenance, and integrity `ok`. Strict deep signature verification and all 248
production JAR comparisons pass; worker/test source hashes still match those above.

No OS permissions or shared services were changed. Earlier CUA `cgWindowNotFound`
and focus failures do not establish a missing graphical session. This check used
owner foreground activation and does not establish a permanent unattended CUA fix.
Original blocked agent output and validation evidence are preserved outside source
control in the current attempt. The operator completion evidence identifies this
exact candidate, synthetic fixture and the observed scenarios.
