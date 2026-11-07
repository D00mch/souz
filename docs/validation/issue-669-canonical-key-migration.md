# Desktop canonical-key migration validation

Issue: [#669](https://github.com/D00mch/souz/issues/669). Base: `2730d3a5417da40d7449f4e5378a77522c93f5f8`.

## Verified cost and invariants

Desktop DI constructs [`SqliteMemoryRepository`](../../desktopApp/src/main/kotlin/ru/souz/memory/SqliteMemoryRepository.kt) for the desktop state root. Repository operations enter through `withConnection` on its IO dispatcher; initialization holds `initMutex` and calls [`SqliteMemorySchema.ensureCurrentSchema`](../../desktopApp/src/main/kotlin/ru/souz/memory/SqliteMemorySchema.kt) inside one transaction. Maintenance status can also initialize through `initializeSqliteMemoryRepository`. `applyMigration` calls `normalizeLegacyCanonicalKeys` only when migration v4 is absent. This is a legacy-database initialization cost, not a repeated chat or UI query. The live issue has no discussion comments.

The migration reads legacy rows in `updated_at desc, id asc` order. V2's unique active canonical-key index and non-null owner/scope columns make an owner/scope/key-to-fact map sufficient for conflict checks. The snapshot includes active facts without legacy slots. Each planned update releases the row's previous key and claims its normalized key before considering the next row; a key held by a later row continues to block earlier rows. Retired facts do not reserve keys. The ordered prepared batch preserves SQLite uniqueness checks and the existing transaction/rollback boundary. Content hashing and normalization use the existing functions.

The snapshot adds memory proportional to active keyed facts; batch parameters add memory proportional to legacy rows. Every legacy fact still requires a row update. The proven reduction is in indexed conflict queries, SQL preparation and JDBC execution calls, not the number of changed rows or a measured startup-time improvement.

## Reproducible work counts

[`SqliteCanonicalKeyMigrationTest`](../../desktopApp/src/test/kotlin/ru/souz/memory/SqliteCanonicalKeyMigrationTest.kt) wraps real JDBC connections to temporary SQLite files. Counters exclude fixture setup, migration metadata and assertion queries. Its baseline run used unchanged production source; the candidate adds assertions for the reduced counts.

| Active legacy rows | Baseline reads | Candidate reads | Baseline update preparations/executions | Candidate update preparations/batch executions |
| --- | ---: | ---: | ---: | ---: |
| 1 | 2 | 2 | 1 / 1 | 1 / 1 |
| 32 | 33 | 2 | 32 / 32 | 1 / 1 |
| 1,001 | 1,002 | 2 | 1,001 / 1,001 | 1 / 1 |

The candidate batches contain exactly 1, 32 and 1,001 entries respectively. Reopening performs no normalization reads or updates. An empty legacy input performs one read and no active-key read or batch. The pinned SQLite JDBC driver (`3.45.1.0`) prepares the native statement in `CorePreparedStatement`'s constructor; the prepared batch reuses it.

Focused regressions cover timestamp and ID priority, conflicts with previously normalized rows and facts without slots, self keys, owner and both scope dimensions, retired facts, Unicode/invalid/oversized keys, keys released earlier versus later, content hashes, untouched facts, migration idempotence and rollback/retry after a late SQLite failure. Existing `MemoryCoreTest` covers migration from v1 and subsequent canonical-key replacement.

## Commands and results

All Gradle invocations use the wrapper and process-local `JAVA_HOME=/Users/dumch/work/souz/.git/main-to-kmp/toolchains/temurin-21.jdk/Contents/Home`; `java -version` reports Temurin `21.0.13+9-LTS`. Logs and process-local isolation support are under `build/issue-669/`. No opt-in live provider evaluation is enabled.

| Command | Result |
| --- | --- |
| `./gradlew :desktopApp:test --tests ru.souz.memory.SqliteCanonicalKeyMigrationTest --no-daemon --console=plain` | Passed before and after the implementation; 9 tests each. Baseline/candidate JDBC counts above. |
| `./gradlew souzGateFast :desktopApp:test -I build/issue-669/isolation.init.gradle --no-daemon --console=plain` | Initial isolation harness failed JUnit startup because it appended to the classpath during early configuration; the interrupted gate lacked a Detekt report. The harness was corrected to append in `doFirst`. |
| `./gradlew :desktopApp:test -I build/issue-669/isolation.init.gradle --no-daemon --console=plain` | Passed: 426 tests, 150 skipped, no failures/errors. Each test JVM uses an isolated home and in-memory Java preferences. |
| `./gradlew souzGateFast --no-daemon --console=plain` | Passed; all fast gate checks pass. |
| `npm ci --prefix quality` | Passed; 2 packages installed, 0 vulnerabilities. |
| `./gradlew test :sharedLogic:allTests :sharedUI:allTests :koverXmlReport :koverHtmlReport koverLog -Psouz.coverage -I build/issue-669/isolation.init.gradle --no-parallel --no-daemon --console=plain` | Passed; 1,677 tests, 225 skipped, no failures/errors. XML/HTML coverage reports generated. |
| `./gradlew souzDuplicationCheck :desktopApp:createReleaseDistributable :desktopApp:packageReleaseDmg :desktopApp:verifyMacReleaseShrinking --no-daemon --console=plain` | Passed, including all 6 release-artifact tests. Local duplicate-code check matches baseline: production 252 tokens, tests 0. Exact-checkout preflight is `not_authoritative` locally as documented in `docs/quality-gates.md`; no CI authority is claimed. |

## Packaged GUI and persistence checks

Source checkout: `/Users/dumch/work/souz/.git/main-to-kmp/attempts/issue-669/investigate-xwbzd2qs/checkout`, base above plus the uncommitted patch. Source app: `desktopApp/build/compose/binaries/main-release/app/Souz AI.app`.

An isolated copy, `build/issue-669/Souz Issue 669 Input.app`, adds an in-memory `PreferencesFactory`, a bootstrap main class and a checkout-local `build/issue-669/gui-home`. All 248 production JARs match the source distribution byte for byte. The bootstrap forces that synthetic home before entering the production main function; maintenance is Off, provider credentials are empty and the selected provider points to loopback port 9. No provider request was submitted.

Preparation commands (Java tools use the same configured Java 21):

```sh
python3 build/issue-669/prepare-gui.py
$JAVA_HOME/bin/javac -cp 'desktopApp/build/compose/binaries/main-release/app/Souz AI.app/Contents/app/*' -d build/issue-669/isolation-classes build/issue-669/SeedMemory.java
$JAVA_HOME/bin/java -cp 'build/issue-669/isolation-classes:desktopApp/build/compose/binaries/main-release/app/Souz AI.app/Contents/app/*' SeedMemory build/issue-669/gui-home/.local/state/souz/memory.db
/opt/homebrew/bin/python3 -B /Users/dumch/work/souz/.git/main-to-kmp/native_validation.py --prepare '/Users/dumch/work/souz/.git/main-to-kmp/attempts/issue-669/investigate-xwbzd2qs/checkout/build/issue-669/Souz Issue 669 Input.app'
```

The seed harness initially failed Java compilation against Kotlin's value-class constructor; using its public synthetic constructor through reflection resolved the fixture build. An initial Java-agent isolation fixture failed before application startup with `Module java.instrument may be missing from runtime image`. LaunchServices stdout/stderr established that cause after two bounded CUA startup selections; the session diagnostic was `ready`, UID 501. Replacing the fixture agent with the bootstrap main class resolved launch without changing the production runtime or JARs. The final input was prepared again through the same wrapper and selected by its exact returned path with `cua.getApp`.

Final tested app: `/Users/dumch/work/souz/.git/main-to-kmp/native-validation/Souz Workflow Validation.app`. Wrapper bundle ID: `ru.souz.workflow.validation`; existing signing identity: `683BCFAE881BFA5854265C842608A02D24F2C7DB`. No signing identity, OS permission or global configuration was changed.

| Scenario | Result |
| --- | --- |
| Launch exact stable candidate through CUA/LaunchServices | Passed with the final bootstrap fixture; main window shows `issue-669-offline`. |
| Open Memory on the pre-v4 synthetic database | Passed: count 3, `SOUZ_669_NEWEST`, `SOUZ_669_OLDER`, `SOUZ_669_INVALID`; Dreamer Off. |
| Open each fact and its evidence | Passed: exact `SOUZ_669_EVIDENCE_NEWEST`, `SOUZ_669_EVIDENCE_OLDER`, `SOUZ_669_EVIDENCE_INVALID` markers. Opening/cancelling the newest fact's editor also retains title/body. No fact was saved or deleted. |
| Read-only SQLite assertions after normal Command-Q | Passed: newest key `user.preference.color`; older colliding and invalid keys null; exact expected SHA-256 content hashes; all facts Active under the synthetic owner; 3 sources, 3 evidence rows, one v4 marker, integrity `ok`. |
| Intentional restart of exact isolated candidate and reopen Memory | Passed: same three facts visible. A second normal quit leaves the read-only SQLite snapshot byte-identical to the first snapshot JSON. |
| Final process, signature and production classpath checks | Passed: candidate process absent, `codesign --verify --deep --strict` exits 0, all 248 production JARs in source/input/stable apps match the saved hashes. |
| Voice, native hotkeys, local-model generation | Not run; this patch changes only legacy SQLite normalization. |

Post-GUI commands:

```sh
python3 build/issue-669/check-gui-db.py > build/issue-669/gui-db-final.json
cmp build/issue-669/gui-db-first.json build/issue-669/gui-db-final.json
/usr/bin/codesign --verify --deep --strict '/Users/dumch/work/souz/.git/main-to-kmp/native-validation/Souz Workflow Validation.app'
git diff --check
```

Source SHA-256: `SqliteMemorySchema.kt` = `61db7a0698d29bd54821e8bd890b0964b92e7891c5bb218df5785bf424f34b34`; `SqliteCanonicalKeyMigrationTest.kt` = `7ec61792b770be3f7822b5454d53a048a68afbf998421638bdf2d8c325672212`. `build/issue-669/gui-validation.json` records source/bootstrap/production JAR hashes, wrapper identity and scenario results; `gui-db-first.json`, `gui-db-final.json` and the saved AX observations provide the synthetic persistence/UI evidence. Final local-link validation and `git diff --check` pass.
