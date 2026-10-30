# Desktop GFM tables — issue 476

Validation date: 2026-10-30. Base commit: `1c35e678314fc325a1677395a816f57c3fb86565`.
Candidate: this checkout's uncommitted issue-476 patch. Issue description and empty discussion were read through the GitHub connector; no GitHub mutations were made.

## Current-code evidence and change

The base desktop chat uses `Text` for user messages (`ChatBubble` in `sharedUI/src/jvmMain/kotlin/ru/souz/ui/main/MainScreen.kt`) and the Markdown renderer's default components for assistant text (`MessageMarkdown` in `sharedUI/src/commonMain/kotlin/ru/souz/ui/common/MessageMarkdown.kt`). The pinned renderer 0.27 parses GFM table AST nodes but has no default table component. This is a missing rendering path, not a performance-detector finding.

`ChatSearchProjector` identifies top-level user tables while retaining surrounding user content literally. Desktop chat supplies `ChatMarkdownComponents` for user tables and both assistant rendering paths (ordinary and search). The table component uses the existing Markdown inline builder, text/link/code styles and divider color; shared column widths, delimiter alignment, normalized body cell counts and a visible horizontal scrollbar provide the table layout. Its fallback preserves the renderer's ordinary list dispatch.

Table indexing uses the same cell AST/normalization as rendering and excludes delimiter syntax, hidden destinations and excess body cells. Indexed source-to-visible leaf ranges make highlights independent of cell callback order. Existing message, streaming, execution, native integration and module dependency boundaries are retained.

Regression tests cover explicit/default column alignment, escaped pipes, missing/excess body cells, literal surrounding user prose, invalid/fenced table examples, both message roles' visible inline content, and repeated/out-of-order cell highlighting. Existing search navigation, code-block and ViewModel suites are included in the shared UI tests.

## Commands and results

All Gradle commands use the repository wrapper with process-local
`JAVA_HOME=/Users/dumch/work/souz/.git/main-to-kmp/toolchains/temurin-21.jdk/Contents/Home`.
`"$JAVA_HOME/bin/java" -version` reported Temurin `21.0.13+9-LTS`.

Test invocations use `--init-script build/issue-476/isolate-tests.gradle`. This local helper adds an in-memory `PreferencesFactory` from a bootstrap JAR, gives each test task a home/log directory under `build/issue-476/test-home`, removes inherited provider credential variables from the test environment and disables live integration opt-ins. It does not change repository automation, test selection, global configuration or permissions. Local fixture/helper sources and raw logs are retained under ignored `build/issue-476/`.

| Exact wrapper arguments (following the JAVA_HOME assignment) | Result / log under `build/issue-476/` |
| --- | --- |
| `./gradlew :sharedUI:compileKotlinJvm --no-daemon --console=plain` | Exit 0; `compile.log` |
| `./gradlew :sharedUI:build :sharedUI:jvmTest souzGateFast --init-script build/issue-476/isolate-tests.gradle --no-daemon --console=plain` | Exit 0; `ui-gate.log` (initial pass) |
| `npm ci --prefix quality` | Exit 0; `npm-ci.log`; 0 vulnerabilities reported |
| `./gradlew :sharedUI:build :sharedUI:jvmTest souzGateFast souzDuplicationCheck --init-script build/issue-476/isolate-tests.gradle --no-daemon --console=plain` | Exit 0; `final-ui-gate-duplication.log` |
| `./gradlew test :sharedLogic:allTests :sharedUI:allTests :koverXmlReport :koverHtmlReport koverLog -Psouz.coverage --no-parallel --init-script build/issue-476/isolate-tests.gradle --no-daemon --console=plain` | Exit 0; `final-coverage.log`; XML and HTML reports generated |
| `./gradlew :desktopApp:createReleaseDistributable :desktopApp:verifyMacReleaseShrinking --init-script build/issue-476/isolate-tests.gradle --no-daemon --console=plain` | Exit 0; `package.log`; 6 artifact tests, 0 failures/errors/skips |
| `git diff --check` | Exit 0 (final check) |

The standalone fast gate passes all blocking checks. Advisory warnings concern unchanged `DeferredToolModifyPermissionBroker.kt:81` and `MemoryUiState.kt:53`. Duplication matches the reviewed baseline: production 252 duplicated tokens (delta 0), tests 0 (delta 0). Its local exact-checkout preflight is `not_authoritative`, as documented in `docs/quality-gates.md`; authoritative CI remains the wrapper's responsibility.

The initial combined command was `./gradlew test :sharedLogic:allTests :sharedUI:allTests :sharedUI:build :koverXmlReport :koverHtmlReport koverLog souzGateFast souzDuplicationCheck -Psouz.coverage --no-parallel --init-script build/issue-476/isolate-tests.gradle --no-daemon --console=plain`. It exited 1 at `souzGateFastReport`: `-Psouz.coverage` introduced generated self-dependencies in each module's unclassified `kover` configuration. Its tests and coverage reports completed, but that command is not reported as passing. The separate required gate and coverage invocations both exited 0 without modifying quality tooling. Diagnostic: `combined-coverage-gate-failure.json`; full command log: `coverage-gate-duplication.log`.

## Isolated packaged graphical validation

Joint validation completed on 2026-10-31 (Europe/Moscow) on this exact candidate.
The owner restored native keyboard focus; Codex performed the table interactions
through Computer Use. All required graphical scenarios passed.

| Scenario | Result |
| --- | --- |
| User and completed assistant GFM tables | Header/body rows render in both roles |
| Alignment | Left, center, right and default alignment verified visually |
| Inline Markdown | Bold, emphasis, inline code and link labels render in both roles |
| Links | User and assistant links each reached loopback `/link` with HTTP 200 |
| Wide tables | Both visible horizontal scrollbars revealed `END_476` |
| Surrounding content | User markup outside tables remains literal; assistant bullet, nested and numbered lists remain present |
| Search | `Bold` and `code` each showed 1/2 in the user table and 2/2 in the assistant table, with matching highlights |
| Search dismissal | Panel/highlights disappeared while tables remained rendered |
| Themes | Both roles were readable in Light and Dark |
| Shutdown | Isolated candidate exited and local fixture server stopped |

The packaged original is `desktopApp/build/compose/binaries/main-release/app/Souz AI.app`.
The isolated test copy is `build/issue-476/Souz476-w54b4l3i.app`, with process-local
home, in-memory preferences and a bootstrap that removes inherited provider credentials.
It selects the synthetic loopback model at `http://127.0.0.1:18476/v1`, English,
non-streaming output and completed onboarding. No production JAR was changed.

Original bundle inventory SHA-256: `e3484635103c4892ad185ad8f0c1d8ac56d9c586587d0add60eda346a0087305`.
Isolated bundle inventory SHA-256: `4ccc6a2879feeb6305762e5314c0de5ad46b7861673b81db9700a5bcf539fa94`.
Both fingerprints were unchanged after validation, all 248 production JARs matched
byte for byte, and both bundles passed `codesign --verify --deep --strict`.
Inventory hashes encode sorted relative path, NUL, and binary SHA-256 file digest.

Fixtures are `build/issue-476/user-fixture.md` and `assistant-fixture.md`.
Their SHA-256 values are `e0432608e03ce975e8930e5d5d480dff01bb1ef29c44be34de7246854613ed8c`
and `3091b9a755b455901153c9b4e43de604f2a53d93539326d65c57a13d379cafa8`.
The test server is `python3 -B build/issue-476/fixture.py`; it binds only to loopback.
Its request reader accepts both Content-Length and chunked bodies. Both transport
forms were checked independently after the original Content-Length-only reader
failed on the application's chunked Ktor requests. This helper correction does not
change application code or the deterministic assistant response.

The original unattended run returned `cgWindowNotFound`. Exact-path selection worked
in this desktop session, but keyboard focus required owner interaction, including
after the links opened the browser. A visible CUA window or successful AppKit
activation did not establish working keyboard input. No permission changes were made.
Use visible table scrollbars when synthetic horizontal wheel events do not move them.

Structured identity, fixture hashes, scenario results and diagnosis are retained in
`manual-validation-20261031.json` beside this attempt's checkout, outside source control.
The original blocked report and validation document are preserved there as well.

Automated results: shared UI 130 tests (39 skipped), desktop 337 (150 skipped),
backend 330 (0 skipped), each with 0 failures/errors. Release artifact verification
has 6 passing tests. Existing opt-in/live and ignored tests are not represented as
exercised. Authoritative CI, GitHub review, local review and owner merge approval
remain required through the worker.
