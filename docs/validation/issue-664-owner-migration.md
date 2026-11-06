# Desktop owner migration — issue 664

Base: `9516b828f318d0a997ee732bd207d83dad402d23`. The [issue](https://github.com/D00mch/souz/issues/664) and its empty discussion were read through the GitHub connector.

## Finding and intervention

Desktop DI supplies `DesktopMemoryOwnerProvider.currentOwnerId()` to `SqliteMemoryRepository`. Its first operation enters `ensureInitialized` on the IO dispatcher, under the initialization mutex, and runs `SqliteMemorySchema.ensureCurrentSchema` in one transaction. Migration 3 invokes `migrateLegacyOwner`; subsequent initialization also checks whether a single existing owner should be adopted.

The detector's loop is over at most six different owner tables, not over facts. Each table's `UPDATE` does distinct required work. JDBC batching would still execute those different updates inside SQLite, and no measured benefit from batching them is claimed.

The avoidable work is `ownerTables`: it probes `sqlite_master` separately for each allowlisted table. One catalogue query supplies the existing table names, then an in-memory filter preserves the allowlist and its order. Views and unrelated tables remain excluded. Discovery is refreshed at each call because migration 6 can create `memory_fact_supersedes` after migration 3. Owner predicates, bound values, update order, migration markers, dispatcher, mutex and transaction boundaries are unchanged.

## Regression evidence

[SqliteMemorySchemaTest](../../desktopApp/src/test/kotlin/ru/souz/memory/SqliteMemorySchemaTest.kt) executes real SQLite statements in JUnit temporary databases. Its JDBC wrapper counts executed prepared queries and updates without replacing results. Fixture setup is excluded. The same eight cases passed against the baseline with baseline count assertions, then against the candidate with reduced count assertions.

| Fixture | Baseline catalogue queries | Candidate catalogue queries | Owner updates, both versions |
| --- | ---: | ---: | ---: |
| Migration 3, 1 legacy row per owner table | 12 | 2 | 6 |
| Migration 3, 24 legacy rows per owner table | 12 | 2 | 6 |
| Reopen after either migration | 6 | 1 | 0 |

The first migration also checks existing owners, explaining its two catalogue discoveries. This demonstrates fewer SQLite/JDBC queries during initialization, not a wall-clock speedup or reduced per-row update work.

Coverage includes all six owner tables, other/target owner retention, whitespace normalization, absent/blank/legacy targets, single-owner adoption, multiple-owner refusal, idempotent reopen, missing tables, views, unrelated owner tables, schema evolution between discoveries, and complete rollback/retry after a failure on the last owner table. Existing `MemoryCoreTest` covers upgrading an actual V1 database and preserving memory retrieval behavior.

## Verification

All Gradle commands use `JAVA_HOME=/Users/dumch/work/souz/.git/main-to-kmp/toolchains/temurin-21.jdk/Contents/Home` (verified Temurin `21.0.13+9-LTS`). Temporary test configuration is confined to ignored `build/issue-664/`: test JVMs use isolated homes and an in-memory PreferencesFactory. Live provider evaluations are disabled.

| Exact command | Result |
| --- | --- |
| `./gradlew :desktopApp:test --tests ru.souz.memory.SqliteMemorySchemaTest -I build/issue-664/isolation.init.gradle --no-daemon --console=plain` | Baseline: 8 cases passed; recorded 12 catalogue queries on migration and 6 on reopen. This initial schema-only run did not access preferences. |
| `./gradlew souzGateFast :desktopApp:test -I build/issue-664/isolation.init.gradle --no-daemon --console=plain` | Passed after correcting temporary test isolation; 395 desktop cases, 150 skipped, zero failures/errors. Fast gate passed. Candidate counts are 2 and 1. |
| `npm ci --prefix quality` | Passed. |
| `./gradlew test :sharedLogic:allTests :sharedUI:allTests :koverXmlReport :koverHtmlReport koverLog souzDuplicationCheck -Psouz.coverage --no-parallel -I build/issue-664/isolation.init.gradle --no-daemon --console=plain` | Passed. Backend: 378 cases / 1 skipped; desktop: 395 / 150; shared logic: 397 / 11; shared UI: 140 / 39. Zero failures/errors. Coverage reports completed. Duplication task exited 0 with local authority `not_authoritative`; authoritative CI remains required. |

An initial combined gate/test invocation failed because the temporary init script replaced the test runtime classpath while adding its PreferencesFactory. The script uses a bootstrap append instead; the successful rerun loaded JUnit and all gate reports. Production build configuration was not changed. Logs and baseline/candidate XML are retained in `build/issue-664/`.

| Exact command | Result |
| --- | --- |
| `./gradlew :desktopApp:createReleaseDistributable :desktopApp:packageReleaseDmg :desktopApp:verifyMacReleaseShrinking -I build/issue-664/isolation.init.gradle --no-daemon --console=plain` | Passed; all 6 macOS artifact cases passed, none skipped. |
| `python3 build/issue-664/prepare-gui.py` | Passed; copied the release, added test-only in-memory preferences/isolation and seeded synthetic data; all 249 production JARs matched. |
| `/opt/homebrew/bin/python3 -B /Users/dumch/work/souz/.git/main-to-kmp/native_validation.py --prepare '/Users/dumch/work/souz/.git/main-to-kmp/attempts/issue-664/investigate-fvmd4gap/checkout/build/issue-664/Souz Issue 664 Isolated.app'` | Passed; existing stable bundle ID and certificate-bound identity retained. |
| `/usr/bin/codesign --verify --deep --strict '/Users/dumch/work/souz/.git/main-to-kmp/native-validation/Souz Workflow Validation.app'` | Passed after GUI validation. |
| `./gradlew souzGateFast -I build/issue-664/isolation.init.gradle --no-daemon --console=plain` | Final run passed. |
| `git diff --check` and local Markdown link validation | Passed. |

## Packaged GUI and database checks

Source checkout: `/Users/dumch/work/souz/.git/main-to-kmp/attempts/issue-664/investigate-fvmd4gap/checkout`, at the base above plus this uncommitted patch. The release is `desktopApp/build/compose/binaries/main-release/app/Souz AI.app`; its isolated input copy is `build/issue-664/Souz Issue 664 Isolated.app`.

Computer Use selected the exact stable app `/Users/dumch/work/souz/.git/main-to-kmp/native-validation/Souz Workflow Validation.app` through `cua.getApp`, using LaunchServices. Its bundle ID is `ru.souz.workflow.validation`, with certificate leaf `683BCFAE881BFA5854265C842608A02D24F2C7DB`. There was no prior running stable instance to quit. The console diagnostic reported `ready`, UID 501. No activation fallback or owner input was needed.

The fixture uses an in-memory PreferencesFactory, a checkout-local `build/issue-664/gui-home`, fixed owner `desktop-owner`, and maintenance Off. Before launch, migration 3 was absent, with two legacy-owner facts and one foreign-owner fact. All six owner tables contain synthetic rows. No real settings, accounts, provider requests or OS permission changes were used.

| Scenario | Result |
| --- | --- |
| Open Memory after legacy migration | Passed: count 2, `SOUZ_664_LEGACY_A` and `SOUZ_664_LEGACY_B`; foreign-owner fact absent. |
| Inspect each migrated fact | Passed: both original bodies, Active/Global metadata and their evidence excerpts visible. |
| Normal shutdown | Passed: Quit through the application menu, then exact executable absent from process inspection. |
| Reopen through the exact stable path | Passed: Memory still shows count 2 and both original facts; foreign-owner fact absent. |
| Read-only SQLite assertions after each shutdown | Passed: expected owners in all six tables, foreign-owner rows retained, all three evidence/source links retained, one migration-3 marker, `integrity_check=ok`. Counts and links are unchanged after restart. |
| Final code and signature checks | Passed: strict deep codesign verification, all 249 production JARs identical across release/input copy/stable candidate, and unchanged production/test source hashes. App stopped normally. |

Voice, hotkeys and local inference were not run; they are outside the owner-table discovery change. This validation does not claim those scenarios passed. Full source hashes, JAR hashes, fixture paths and individual scenario results are retained in `build/issue-664/gui-validation.json`; preparation identity is in `build/issue-664/native-prepare.json`. Remote CI, review and owner merge approval remain separate.
