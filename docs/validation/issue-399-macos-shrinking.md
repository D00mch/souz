# Issue 399: macOS release shrinking validation

Checkout base: `ad5b7bd1e38e97da7055ff37aad8f6e08cd3613b` (`origin/kmp`). Validation date: 2026-10-29, Europe/Moscow. [Issue 399](https://github.com/D00mch/souz/issues/399) was read through the GitHub connector; it is open, labeled `desktop`, and has no discussion comments.

## Implementation and correctness evidence

The baseline disabled ProGuard in `desktopApp/build.gradle.kts` and disabled shrinking in `desktopApp/proguard-rules.pro`. The candidate enables shrinking only for macOS release tasks. Debug builds and other operating systems retain their existing configuration. Optimization, obfuscation, and JAR joining are disabled through the [Compose ProGuard integration](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html#minification-and-obfuscation).

Shrinking is limited to the statically referenced Material icon catalog. All other runtime JARs are restored byte for byte before packaging. This includes every Souz production module, provider implementation, reflection/serialization entry point, local-model runtime, and native integration. Native-resource preparation, runtime privacy descriptions, signing, and packaging remain in the existing task chain. The old post-packaging desktop-JAR replacement was removed because Compose prepares the release classpath and uses `desktopApp.jar` when ProGuard is enabled.

The artifact regression tests compare prepared JAR SHA-256 digests, packaged entry contents, class entries and service descriptors, check all icon class references in packaged application bytecode, construct retained icons through a classloader isolated from test dependencies with `-Xverify:all`, and verify the deep code signature. macOS packaging rewrites ZIP metadata, signs embedded Mach-O libraries, and extracts Skiko's host library/checksum. Raw prepared JARs must match their original inputs exactly; packaged entry comparisons ignore archive headers and only those native signing/extraction outputs. An independent comparison with the baseline includes every native entry and confirms that the packaging transformations themselves are unchanged.

Two concrete failures informed the final configuration:

- Keep rules alone allowed ProGuard to drop multi-release entries, including Jackson's Java 11/17/21 implementations and JNativeHook's Java 11 classes. The artifact tests detected this. Restoring unshrunk runtime JARs preserves those entries and their resources exactly.
- Disabling preverification produced `VerifyError: Expecting a stackmap frame at branch target 20` in `automirrored.rounded.ArrowBackKt.getArrowBack`. A small standalone Java probe had passed, demonstrating why that probe was insufficient. Preverification is enabled in the candidate; the [ProGuard manual](https://www.guardsquare.com/manual/configuration/usage#preverification-options) describes its JVM stack-frame role.

The first diagnostic run also failed to create the removal report because its parent directory did not exist. The report uses the directory created by Compose for `proguardReleaseJars`: `desktopApp/build/compose/tmp/proguardReleaseJars/removed.txt`.

## Toolchain, commands, and results

All Gradle invocations use the repository wrapper and this process-local setting:

```sh
export JAVA_HOME=/Users/dumch/work/souz/.git/main-to-kmp/toolchains/temurin-21.jdk/Contents/Home
"$JAVA_HOME/bin/java" -version
```

Verified output: Temurin OpenJDK `21.0.13+9-LTS`, ARM64. The build requests the Java 21 toolchain. No vendor-check bypass, global configuration changes, credentials provisioning, CI or wrapper edits, live provider evaluations, or other checkouts were used.

Baseline, before editing sources:

```sh
./gradlew :desktopApp:createReleaseDistributable :desktopApp:packageReleaseDmg --no-daemon --console=plain
```

Exit 0, `BUILD SUCCESSFUL`. The baseline `.app` and DMG were copied to `build/issue-399/baseline/` before rebuilding. Log: `build/issue-399/baseline-packaging.log`.

Candidate packaging, with exactly the same command/flags as the baseline:

```sh
./gradlew :desktopApp:createReleaseDistributable :desktopApp:packageReleaseDmg --no-daemon --console=plain
```

Exit 0, `BUILD SUCCESSFUL`. Log: `build/issue-399/matched-flags-candidate.log`.

Final regression checks against that existing bundle:

```sh
./gradlew :desktopApp:verifyMacReleaseShrinking -x :desktopApp:createReleaseDistributable \
  -I build/issue-399/isolate-tests-with-home.init.gradle --no-daemon --console=plain
```

Exit 0; all six tests passed with zero skips. The already-successful packaging task is excluded only to inspect the measured bundle without rebuilding or re-signing it. Log: `build/issue-399/artifact-tests-final.log`; XML: `desktopApp/build/test-results/verifyMacReleaseShrinking/TEST-ru.souz.packaging.MacReleaseShrinkingTest.xml`.

The local init script gives each test task an isolated `user.home`, matching process `HOME`, and an in-memory Java Preferences factory, and disables opt-in live scenarios. The factory is compiled into `build/issue-399/isolation/isolation.jar`; no production application code is changed for isolation.

Repository verification:

```sh
npm ci --prefix quality
./gradlew souzGateFast souzDuplicationCheck :build-logic:check \
  -I build/issue-399/isolate-buildlogic.init.gradle --no-daemon --console=plain
./gradlew test :sharedLogic:allTests :sharedUI:allTests :koverXmlReport :koverHtmlReport koverLog \
  -Psouz.coverage -Pkotlin.compiler.execution.strategy=in-process --no-parallel --continue --no-daemon --console=plain \
  -I build/issue-399/isolate-tests-with-home.init.gradle
git diff --check
```

`npm ci` exited 0 with no vulnerabilities. The gate invocations exited 0; `gates-final.log` also records a passing `:build-logic:check`. The fast gate reported two existing advisories in unchanged `DeferredToolModifyPermissionBroker.kt` and `MemoryUiState.kt`; all blocking checks passed. The duplication check matched both baselines (252 production duplicated tokens, zero test duplicated tokens). Its overall authority is `not_authoritative` locally because exact-checkout authority belongs to GitHub Actions. The final-source gate passed. The full JVM test-and-coverage graph exited 0, including `:desktopApp:test`, `:sharedLogic:allTests`, `:sharedUI:allTests`, XML/HTML coverage reports and `koverLog` (55.4171% aggregate line coverage). Its reports list 1,498 cases, 223 skipped, zero failures and errors; the log distinguishes executed and up-to-date tasks. Subsequent edits refined only the opted-in artifact assertions; all six final artifact tests passed separately. The final `souzGateFast souzDuplicationCheck` rerun after those edits also exited 0. Logs: `build/issue-399/gates-final.log`, `build/issue-399/gates-last.log`, and `build/issue-399/tests-coverage-final.log`. The coverage dry run, `./gradlew test :sharedLogic:allTests :sharedUI:allTests :koverXmlReport :koverHtmlReport koverLog -Psouz.coverage --no-parallel --dry-run --no-daemon --console=plain`, exited 0 and contains no packaging tasks (`build/issue-399/coverage-task-graph.log`).

Diagnostic logs are retained separately: `candidate-packaging.log` (missing report directory), `candidate-packaging-rerun.log` (interrupted during frame recomputation), `candidate-validation.log` (four artifact-test failures), and `final-candidate-validation.log` (interrupted to restore required preverification). A further run, `verified-candidate.log`, failed after ProGuard completed because filename-prefix matching was ambiguous for two `library-desktop-1.9.3.jar` dependencies. The final code uses Compose's exact generated input/output pairs and verifies that their keys match the entire input classpath. The initial coverage run (`tests-coverage.log`) unintentionally included the packaging test as a Kover dependency and failed after its duplicate shrinker invocation was stopped. The packaging test is excluded from Kover instrumentation and report dependencies, following [Kover's task exclusion contract](https://kotlin.github.io/kotlinx-kover/gradle-plugin/#exclusion-of-test-tasks). These runs are not passing validation evidence. A later packaging run (`packaging-final.log`) passed packaging, icon construction and class/service preservation but failed two overly strict archive-byte assertions because macOS packaging changes ZIP metadata and native signatures. The final tests check raw preparation bytes and packaged payloads at their respective boundaries. Failed XML is preserved under `build/issue-399/initial-packaging-tests/` and `build/issue-399/archive-header-tests/`.

## Size measurement

Both builds use the same checkout, Java 21 installation, default host architecture, default native-resource selection, ad-hoc signing, packaging tasks, and flags. Sizes are logical bytes, not `du` allocated blocks. The app measurement sums `stat().st_size` for every recursive `is_file()` entry, following file symlinks. The DMG measurement uses the file's `stat().st_size`.

| Artifact | Baseline bytes | Candidate bytes | Reduction |
| --- | ---: | ---: | ---: |
| `Souz AI.app` | 356,364,508 | 317,779,765 | 38,584,743 bytes (10.83%) |
| `Souz AI-1.0.7.dmg` | 264,540,616 | 226,932,362 | 37,608,254 bytes (14.22%) |

The baseline icon JARs contain 11,398 classes and total 38,829,833 bytes. The candidate icon JARs contain 75 classes and total 228,688 bytes. All 68 retained icon getters construct successfully, and all 74 icon class references in packaged application bytecode are present. The tests retain 66,246 non-icon class entries and all service descriptors. This is an observed packaged-size cost, not a detector hypothesis. No runtime performance or startup-time improvement is claimed.

Baseline bundle: 391 files; sorted inventory SHA-256 `2035c59ad0eacabe0337823c968dcead8c628ce11d4c609d99c4442426f915ac`. Inventory input is, for each sorted file, its relative POSIX path, NUL, and the raw SHA-256 digest of its contents. Measurements are recorded in `build/issue-399/baseline-inventory.json` and `build/issue-399/candidate-inventory.json`. The candidate has 391 files and inventory SHA-256 `9565924ea6cf6e7b0eb4f3ec22ca3522b77bdeef8d0ea3154e1fc44e11642aad`; DMG SHA-256 `4f7d24ecb74925a653db8c88895dcd8e9efee97453be7027ab7a12fa4b44fa46`. JAR payload digests sort entry names and hash each name, NUL and its content SHA-256, ignoring only directory entries and ZIP headers. The independent baseline comparison found identical full entry contents in all 246 non-icon JARs, including their signed native entries, and identical bytes in all 39 standalone native libraries (`build/issue-399/baseline-payload-comparison.json`).

Reproduce the measurements with the prepared standard-library helper:

```sh
python3 build/issue-399/measure-distribution.py \
  'build/issue-399/baseline/Souz AI.app' 'build/issue-399/baseline/Souz AI-1.0.7.dmg'
python3 build/issue-399/measure-distribution.py \
  'desktopApp/build/compose/binaries/main-release/app/Souz AI.app' \
  'desktopApp/build/compose/binaries/main-release/dmg/Souz AI-1.0.7.dmg'
```

## Graphical validation and outstanding owner checks

Candidate bundle:

```text
/Users/dumch/work/souz/.git/main-to-kmp/attempts/issue-399/investigate-txaud64b/checkout/desktopApp/build/compose/binaries/main-release/app/Souz AI.app
```

LaunchServices returned exit 0 using the isolated launcher. Candidate PID 60653 initialized its bundled Temurin Java 21 and local llama/Metal bridge from the temporary home. A screen capture showed the desktop, and CoreGraphics returned no windows owned by that process. A visible first-run/main window could not be confirmed from this tool session; this is not evidence of a missing graphical login or a proven shrinking regression. Logs: `build/issue-399/launch-services.log`, `/tmp/souz-399-6cvpbyvx/launch.log`, `build/issue-399/candidate-windows.json`, and `build/issue-399/candidate-launch.png`.

The candidate was terminated with SIGTERM; direct process inspection confirmed exit. No application query was made after exit. `codesign --verify --deep --strict` passed, and app/DMG inventories remained unchanged (`build/issue-399/post-launch-inventory.json`). Use the prepared isolated launcher from an ordinary macOS Terminal:

```sh
python3 /Users/dumch/work/souz/.git/main-to-kmp/attempts/issue-399/investigate-txaud64b/checkout/build/issue-399/launch-isolated.py
```

The launcher creates a temporary home, supplies an in-memory Java Preferences factory, redirects logs, and explicitly clears provider credentials and MCP configuration in the launched process. It uses `open -n --env`, including per-process overrides because a sanitized `open` environment alone does not clear LaunchServices' inherited environment. It prints the home and log locations. Do not use default application preferences for validation.

A screenshot and `/dev/console` ownership establish a graphical login session. `AXIsProcessTrusted()` returned `false`; querying System Events did not complete and that diagnostic process was terminated without changing permissions. This does not establish that no graphical session exists.

`/usr/sbin/system_profiler SPAudioDataType -json` reports only Mac mini speakers and no input device. Voice capture and hotkey operation remain unverified on this hardware. No OS permissions or global settings were changed.

Owner checks still required on the exact candidate: first-run/main window, settings and light/dark themes with icons, local model and embedding downloads including cancel/resume, local model loading and generation, active-request cancellation followed by a successful request, a synthetic XLSX read through the document tool, native file selection, clipboard paste, Dock hide/restore, and voice capture/hotkey/transcription on a host with an input device and already-authorized access. Use local providers and synthetic data. Do not change global permissions or run live cloud-provider evaluations for these checks. Inspect process exit directly after quitting; do not query the app through automation after quitting because that may relaunch it without isolation.

The investigation stopped for interactive confirmation. The non-voice interactive checks were completed on this exact bundle as recorded below; voice and voice hotkeys were excluded by explicit owner instruction. Publication, CI, review and owner merge approval remain separate workflow gates. Automated packaging, artifact checks, JVM tests/coverage, repository gates, native-payload comparison, signature verification, local documentation links and `git diff --check` passed. Evidence from prior candidate checkouts does not validate this bundle.

## Interactive follow-up on the measured candidate

Checked on 2026-10-29 at the owner's request through CUA using `build/issue-399/launch-isolated.py`. Candidate PID: `71989`. The isolated home is `/var/folders/fy/kybk4yvs34gcmnv87r3tbgz00000gn/T/souz-399-ugxgyu34`. This session used local Qwen3 and local EmbeddingGemma with cloud-provider credentials and MCP configuration cleared by the launcher.

Confirmed in the running packaged application:

- First-run screen and visible main chat window without entering API keys.
- Qwen3 download cancellation, retained partial file of 70,890,035 bytes, and resumption from that offset. Qwen3 and EmbeddingGemma both completed downloading.
- Local generation returned `SOUZ_399_READY`. A long request was cancelled; the log recorded `Cancelled by facade`, the UI returned to idle, and a subsequent request returned `SOUZ_399_AFTER_CANCEL`.
- Model and general settings opened. Light and dark themes rendered navigation icons, text and controls correctly.
- Clipboard paste entered the exact test prompt into the composer.
- The native file chooser selected the repository's synthetic `price.xlsx` fixture copied into the isolated home as `souz-399-check.xlsx`. The app displayed the attachment and executed `ExcelRead` with `operation=CELL`, `sheet=Report`, and `range=A1:C3`. It returned `A001: Laptop, Price: 1000` and `A002: Mouse, Price: 50`, matching independent extraction from the XLSX XML.

Voice and voice hotkeys were explicitly excluded from this follow-up by the owner: “Давай не будем проверять голос, а остальное проверь за меня.” They have not been reported as passed. No OS permissions were changed.

The owner confirmed that Cmd+H followed by clicking Souz in the Dock restored the same window with `SOUZ_399_READY` and the XLSX result. CUA subsequently observed the existing chat unchanged. This one check used owner observation because CUA could not bind the Dock app (two `timeoutReached` results).

The app inventory remained at 391 files and 317,779,765 logical bytes with SHA-256 `9565924ea6cf6e7b0eb4f3ec22ca3522b77bdeef8d0ea3154e1fc44e11642aad`. `codesign --verify --deep --strict` passed after the checks. The isolated log also contains background memory-capture warnings about local-model JSON missing the required `type` field; the tested chat and XLSX flows completed, and packaging causation has not been established.

Structured evidence is saved beside this checkout in `../manual-validation-20261029-current.json`. All non-voice checks in the owner-agreed scope passed. This follow-up records test evidence; it does not grant merge approval. The operator may submit the validated candidate through the regular worker publication, CI and review gates.

The test candidate quit with Cmd+Q. Direct process inspection confirmed PID `71989` exited. No app query or relaunch was made after quitting.
