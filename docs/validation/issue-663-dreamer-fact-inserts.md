# Issue 663: Dreamer fact insert validation

Candidate: base `c2a49c016abeff2ef6501548f76b906987a26b3a` plus this patch.
Date: 2026-11-06. Decision: **ready** after operator completion of the input checks.
The initial blocked observations below are retained as diagnostic history.

## Finding and change

The live [issue](https://github.com/D00mch/souz/issues/663) is open, labeled desktop
and performance, with no discussion comments. Desktop DI supplies a consolidator
and embedding model to `MemoryMaintenanceWorker`. `processDreamerRegionRewrite`
calls `commitDreamerReplacement` inside one transaction on `Dispatchers.IO`.
The original `insertDreamerFact` prepares and executes a scalar fact insert and
prepares an evidence batch for each candidate. This is repeated JDBC/native
statement preparation; it does not establish per-fact commits, network round trips,
or UI-thread blocking.

The patch uses one fact batch and one evidence batch per replacement transaction.
Facts precede their evidence. It preserves the job's dirty-time guard, source
retirement, candidate order, generated IDs, hashes, fields, deduplicated evidence,
supersession and embedding queue. The schema has no production insert triggers;
other connections cannot observe the transaction's intermediate state. The
default quality gate requires disjoint pairs of source facts, bounding the
24-fact region to twelve replacements. No synchronization or transaction boundary
changes are involved.

`DreamerEmbeddingJobsTest` extends the existing real-SQLite fixture and instruments
JDBC calls while delegating all SQL to SQLite. For twelve replacements:

| Work | Original | Candidate |
| --- | ---: | ---: |
| Fact statement preparations | 12 | 1 |
| Fact scalar updates | 12 | 0 |
| Fact batches / entries | 0 / 0 | 1 / 12 |
| Evidence statement preparations | 12 | 1 |
| Evidence batches / entries | 12 / 24 | 1 / 24 |

SQLite still writes twelve facts and twenty-four evidence rows. No elapsed-time
benefit is claimed. One-replacement and twelve-replacement tests check all fact
fields, null/non-null canonical keys, hashes, first-source order, deduplication,
evidence text, retirement, provenance, completion and embedding jobs. SQLite
triggers abort a later fact, evidence or embedding insert; the entire replacement
rolls back and succeeds on retry after removing the trigger. A job dirtied during
consolidation performs no inserts. Databases use JUnit temporary directories.
The packaged SQLite driver returns `org.sqlite.SQLiteException`, SQL state null,
error code 19 for both scalar and batched constraint errors (`batch-errors.log`),
preserving the controller's error-class reporting.

## Automated checks

All Gradle commands use the process-local prefix
`JAVA_HOME=/Users/dumch/work/souz/.git/main-to-kmp/toolchains/temurin-21.jdk/Contents/Home`.
Its `bin/java -version` reports Temurin `21.0.13+9-LTS`. Logs and preserved XML
results are in this checkout's ignored `build/issue-663/` directory.

| Exact command | Result |
| --- | --- |
| `./gradlew :desktopApp:test --tests ru.souz.memory.DreamerEmbeddingJobsTest --no-daemon --console=plain` | Original worker: exit 1, seven tests, two expected batching assertion failures; `baseline-tests.log`, `baseline-tests.xml`. An initial fixture used an invalid canonical-key prefix; it was corrected before this retained measurement. |
| `./gradlew :desktopApp:test --tests 'ru.souz.memory.*' --no-daemon --console=plain` | Exit 0; 67 tests, zero failures/errors/skips; `memory-tests.log`, `memory-test-results/`. |
| `npm ci --prefix quality` | Exit 0; `npm-ci.log`. |
| `./gradlew souzGateFast souzDuplicationCheck :desktopApp:test :desktopApp:createReleaseDistributable :desktopApp:verifyMacReleaseShrinking --no-daemon --console=plain` | Exit 0; `required-checks.log`. Fast gate passes. Duplicate-code check matches both baselines; exact-checkout CI authority is unavailable locally, as reported by the task. Desktop: 387 tests, 150 skipped, zero failures/errors. Six packaged classpath/signature tests pass. |
| `SOUZ_AGENT_INTEGRATION_TESTS_ON=false ./gradlew test :sharedLogic:allTests :sharedUI:allTests :koverXmlReport :koverHtmlReport koverLog -Psouz.coverage --no-parallel --no-daemon --console=plain` | Exit 0; `coverage.log`, `coverage-test-results/`. XML: 1,638 tests, 201 skipped, zero failures/errors. No opt-in live provider evaluation. |
| `./gradlew souzGateFast --no-daemon --console=plain` | Exit 0 after this report; `final-gate.log`. |
| `git diff --check` | Exit 0. |

## Exact packaged fixture

Checkout:
`/Users/dumch/work/souz/.git/main-to-kmp/attempts/issue-663/investigate-hyrfhaq2/checkout`.
Original release: `desktopApp/build/compose/binaries/main-release/app/Souz AI.app`.
Worker SHA-256: `a4a545ab72ad88a62c6eb5e2a399c1ae6335be95922874469ff0dd2ae39c61de`.
Test SHA-256: `331bd07642ab90f86fb1aae58773168a51b7103953b2833500eac9ea329dde87`.

`build/issue-663/gui-src/GuiSeed.java` runs the exact packaged worker with a
deterministic consolidator over six synthetic sources, creating three replacements
and pending embeddings without provider requests. `IsolatedBootstrap` sets
`user.home` to this checkout's `build/issue-663/gui-home` before loading production
code. `IsolatedPreferences` supplies in-memory preferences, a synthetic owner,
completed onboarding, empty credentials and maintenance Off. The fixture launcher
is an additional JAR; all 248 production JARs match the original release.
`candidate.json` and `validation.json` bind the source, fixture and JAR hashes.

The first fixture attempted a Java agent, which cannot start with this packaged
runtime's absent `java.instrument`/`libinstrument` (`launch-stderr.log`). Replacing
only the fixture entry point with a plain Java launcher resolved startup.

The authorized preparation command was:

```sh
/opt/homebrew/bin/python3 -B /Users/dumch/work/souz/.git/main-to-kmp/native_validation.py --prepare '/Users/dumch/work/souz/.git/main-to-kmp/attempts/issue-663/investigate-hyrfhaq2/checkout/build/issue-663/Souz Issue 663 Input.app'
```

Computer Use selected its exact returned app:
`/Users/dumch/work/souz/.git/main-to-kmp/native-validation/Souz Workflow Validation.app`.
Bundle ID: `ru.souz.workflow.validation`; existing certificate fingerprint:
`683BCFAE881BFA5854265C842608A02D24F2C7DB`. Strict deep signature verification
and all production JAR/source/fixture hashes pass after GUI shutdown.

| UI scenario | Result |
| --- | --- |
| Isolated LaunchServices startup and main window | Passed; console confirms the checkout home and in-memory preferences. |
| Settings navigation and return | Passed. |
| Three active `SOUZ_663_REPLACEMENT_0..2` facts | Passed; Memory count is three. |
| Evidence and detail scrolling | Passed; visible pairs are `SOUZ_663_EVIDENCE_0/1`, `2/3`, and `4/5` for their respective replacements. |
| Short composer input and clearing | Failed to verify. AX/coordinate clicks and `typeText('SOUZ_663_DRAFT')` produce no visible text. The authorized activation helper, fresh AX state, coordinate input and Raise do not restore typing. `setValue` reports the element is not settable. No request was sent. |
| Short Memory search input | Failed to verify; `typeText('SOUZ_663')` and `pressKey('s')` produce no visible query. |
| Normal Command-Q shutdown | Passed; process inspection confirms exit. An earlier post-quit AX query relaunched the same isolated fixture; the final quit was followed only by process inspection. |
| Voice, hotkeys, local generation, document tools, live providers | Not run; outside this SQLite change. |

Read-only SQLite assertions after shutdown pass: three active replacements, six
retired sources, all six supersession links, both exact evidence excerpts per
replacement, three pending embedding jobs with matching hashes/owners, one Done
job with attempt count one, and integrity `ok`.

The read-only `native_validation.py --session-status` diagnostic reports `ready`,
console UID 501. This is an input/focus blocker, not evidence of a locked session
or a proven production regression. No OS permissions, global configuration or
shared services were changed. The owner still needs to foreground this exact
isolated app, verify short synthetic composer input and clearing without sending,
verify Memory search input and clearing, and quit normally. Preserve the patch
and existing evidence; these unverified interactions prevent a ready result.


## Operator input completion

On 2026-11-06 the owner brought the exact stable candidate to the foreground via
VNC. Connection alone, app reselection and a Computer Use helper restart had not
restored text input. After the foreground transition, Computer Use visibly entered
`SOUZ_TEST` in the composer and cleared it with Command-A/Backspace; Send returned
to disabled and no request was sent. In Memory, individual key presses produced
`s`, `so`, `sou`, then `souz`. Pasting `SOUZ_663_REPLACEMENT_1` displayed exactly
one matching fact. Clearing the query restored all three synthetic replacements.
Command-Q exited the exact candidate and Computer Use inventory confirmed exit.

These checks complete the previously outstanding owner checks; the initial failed
observations remain historical evidence. Automatic foregrounding remains unresolved.
Rapid typing of a longer search string dropped characters on the first try; exact
paste and sequential single-key input passed. General high-speed typing and
unattended focus recovery are not claimed.

Before finalization, the wrapper rechecked the exact production/test source hashes,
all 248 production JAR hashes, the fixture hash, strict bundle signature, retained
zero-failure test reports and read-only SQLite integrity. The issue discussion,
labels and current KMP base were unchanged, with no managed or linked open PR.
The CI, local review, owner approval and publication gates remain required.
