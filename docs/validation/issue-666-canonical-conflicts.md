# Issue 666: Dreamer canonical conflict validation

Candidate: `041a56876d6172faba467b83ff75295db3f2d501` plus this patch.
Date: 2026-11-06. Checkout:
`/Users/dumch/work/souz/.git/main-to-kmp/attempts/issue-666/investigate-jbz2mrfy/checkout`.

## Finding and intervention

The live [issue](https://github.com/D00mch/souz/issues/666) is open, labeled
desktop/performance, with no discussion comments. The current
[worker](../../desktopApp/src/main/kotlin/ru/souz/memory/MemoryMaintenanceWorker.kt)
processes at most ten jobs sequentially. After consolidation and quality checks,
each rewrite containing canonical keys executes a conflict SELECT on a separate
connection. This is actual repeated SQLite/JDBC work; the detector establishes
neither elapsed cost nor UI-thread blocking. Connections run on `Dispatchers.IO`.

The [schema](../../desktopApp/src/main/kotlin/ru/souz/memory/SqliteMemorySchema.kt)
already enforces `memory_active_canonical_unique` over owner, scope and canonical
key for active, non-null keys. Successful inserts need no preflight conflict read.
The worker relies on that index and performs the existing external-conflict query
only after the corresponding unique-constraint exception and complete transaction
rollback. An external conflict completes the guarded job without replacement;
in-region collisions, duplicate candidate keys and unrelated SQL failures remain
errors. Only the SQLite extended unique-error code and exact canonical-column
diagnostic enter this fallback. An unrecognized diagnostic is rethrown.

This preserves sequential job processing, source retirement, evidence,
supersession, embedding jobs and the dirty-time completion guard. There is no
run-wide snapshot of canonical keys, cache, model call inside a transaction, new
lock or shared transaction across jobs. Another writer during consolidation is
observed by the unique index. Conflict-heavy work can cost more: it attempts and
rolls back writes before checking the external conflict. No general latency or
conflict-frequency claim is made.

## Reproducible work counts and regression coverage

[DreamerCanonicalConflictTest](../../desktopApp/src/test/kotlin/ru/souz/memory/DreamerCanonicalConflictTest.kt)
wraps real JDBC connections while delegating SQL to SQLite. Setup and assertions
are outside the measured section; each job has two facts in a distinct project
scope. The same test was run before and after the production edit.

| Successful canonical rewrites | Original conflict queries / connections | Candidate conflict queries / connections |
| --- | ---: | ---: |
| 1 | 1 / 7 | 0 / 6 |
| 10 | 10 / 52 | 0 / 42 |

The fourteen focused cases cover these counts; external conflicts present before
or inserted through another connection during consolidation; rollback of a
successful earlier batch entry; reuse of a retiring source's key; null keys;
retired/foreign-owner/foreign-scope facts; duplicate candidates; an omitted
in-region key; unrelated unique errors; redirtied jobs; and continuation to
another scope after a conflict. They use JUnit temporary directories and the
default quality gate. Existing memory tests additionally cover evidence/embedding
failures, retry/backoff, provenance, quality rejection and stale-job protection.

## Commands and results

Every Gradle command below uses the process-local prefix
`JAVA_HOME=/Users/dumch/work/souz/.git/main-to-kmp/toolchains/temurin-21.jdk/Contents/Home SOUZ_AGENT_INTEGRATION_TESTS_ON=false`.
The supplied `bin/java -version` reports Temurin `21.0.13+9-LTS`.
Logs, XML reports, fixture sources and hashes are retained under this checkout's
ignored `build/issue-666/` directory.

| Exact command | Result |
| --- | --- |
| `./gradlew :desktopApp:test --tests ru.souz.memory.DreamerCanonicalConflictTest --no-daemon --console=plain` | Original production code: exit 1; twelve initial cases, six expected assertions fail because successful rewrites still query. `baseline-tests.log` and `baseline-tests.xml` retain both measurements above. |
| `./gradlew :desktopApp:test --tests 'ru.souz.memory.*' --no-daemon --console=plain` | Exit 0; 87 tests, zero failures/errors/skips before adding the final two focused cases. `memory-tests.log`, `memory-test-results/`. |
| `npm ci --prefix quality` | Exit 0; `npm-ci.log`. |
| `./gradlew souzGateFast souzDuplicationCheck :desktopApp:test :desktopApp:createReleaseDistributable :desktopApp:verifyMacReleaseShrinking --no-daemon --console=plain` | Final exit 0; fast gate passes; duplication matches its baselines (local report says `not_authoritative`, so CI still owns exact-checkout authority). Desktop: 409 tests, 150 skipped, zero failures/errors, including all fourteen new cases. Six packaged tests pass. `required-checks.log`, `desktop-test-results/`, `packaging-test-results/`. The initial invocation failed a test-style membership rule; direct comparisons fix it. That failure is retained in `initial-required-checks.log`. |
| `./gradlew test :sharedLogic:allTests :sharedUI:allTests :koverXmlReport :koverHtmlReport koverLog -Psouz.coverage --no-parallel --no-daemon --console=plain` | Exit 0; 1,660 tests, 201 skipped, zero failures/errors. `coverage.log`, `coverage-test-results/`. No opt-in live evaluation. |
| `./gradlew souzGateFast --no-daemon --console=plain` | Exit 0 after this report; `final-gate.log`. |
| `git diff --check` | Exit 0. |

## Packaged desktop checks

Original release: `desktopApp/build/compose/binaries/main-release/app/Souz AI.app`.
An isolated copy at `build/issue-666/Souz Issue 666 Input.app` adds a fixture JAR
and launcher configuration; all 248 production JARs match the release byte for
byte. The launcher forces this checkout's `build/issue-666/gui-home` and an
in-memory `PreferencesFactory` before loading production code. A deterministic
consolidator calls the exact packaged worker: one project successfully rewrites
two facts, another skips a canonical conflict and retains its three facts.
Maintenance is Off for subsequent GUI observation. No GUI model request was sent.

The final fixture uses English-region settings and an OpenAI-compatible loopback
provider at `127.0.0.1:54567`. Its embedding endpoint handles Content-Length and
chunked bodies; both framing self-tests pass. Background index embeddings reached
the local fixture. An initial fixture omitted the English-region setting and
fell back to AI Tunnel; its automatic background embedding request received an
unauthenticated 401. This fixture error was corrected before the final checks;
the final console log has no such provider failure. No real account or credential
was used. The fixture server was stopped after validation.

Preparation command:

```sh
/opt/homebrew/bin/python3 -B /Users/dumch/work/souz/.git/main-to-kmp/native_validation.py --prepare '/Users/dumch/work/souz/.git/main-to-kmp/attempts/issue-666/investigate-jbz2mrfy/checkout/build/issue-666/Souz Issue 666 Input.app'
```

Computer Use selected the returned exact path through `cua.getApp`:
`/Users/dumch/work/souz/.git/main-to-kmp/native-validation/Souz Workflow Validation.app`.
Bundle ID: `ru.souz.workflow.validation`. Existing certificate identity:
`683BCFAE881BFA5854265C842608A02D24F2C7DB`. The source checkout, production/test
hashes, fixture hash, all production JAR hashes and wrapper identity are recorded
in `candidate.json`, `native-prepare.log` and `validation.json`.

| UI scenario | Result |
| --- | --- |
| Isolated LaunchServices startup and main window | Passed; console confirms the checkout home. Final model label is `OpenAI-compatible: offline-fixture`. |
| Settings navigation and return | Passed. |
| Composer short input and clearing | Passed on the final fixture: `s` appears, then Command-A/Backspace empties the input and disables Send. No request submitted. |
| Memory successful replacement and conflict retention | Passed: four active facts, exactly `SOUZ_666_REPLACEMENT_success`, the existing canonical fact and both conflict sources. |
| Replacement and retained evidence | Passed: both `SOUZ_666_EVIDENCE_success_0/1` excerpts on the Dreamer replacement and each `SOUZ_666_EVIDENCE_conflict_0/1` on its retained active source. |
| Memory search and clearing | Passed: short `s` input, exact pasted `SOUZ_666_REPLACEMENT_success` yields count one; clearing restores count four. |
| Normal Command-Q shutdown | Passed; process inspection confirms PID 88242 exited without querying/relaunching the app afterward. |
| Voice, hotkeys, local generation, document tools, live providers | Not run; outside this SQLite change. |

Initial typing had no visible effect after clicks, Raise, reselection and the
authorized AppKit activation helper. After correcting/restarting the fixture and
activating that exact running app, the final short typing, paste and clearing
checks passed. The permanent cause of focus recovery is unproven. The read-only
session diagnostic reported `ready`, console UID 501. No OS permission, signing
identity, global configuration or shared service was changed.

After final shutdown, strict deep codesign verification, all production
JAR/source/fixture hashes and read-only SQLite assertions pass: four active and
two retired facts; one Dreamer replacement; all three conflict facts active;
six evidence rows; two supersession links; one pending embedding job with the
matching owner/hash; both jobs Done with attempt count one; integrity `ok`.
No owner checks remain for this change. CI, review and owner merge approval remain
the wrapper's responsibility.
