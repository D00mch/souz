# Issue 678: default-browser regex reuse

Base: `853a460739d3195e7ea001cd1b3b3555a5f67629` plus this patch.
Date: 2026-11-08. Checkout:
`/Users/dumch/work/souz/.git/main-to-kmp/attempts/issue-678/investigate-362r8jh3/checkout`.

## Finding and correctness

The live [issue](https://github.com/D00mch/souz/issues/678) is open with
`desktop` and `performance` labels and no discussion comments. Current
[browser detection](../../desktopApp/src/main/kotlin/ru/souz/tool/browser/DefaultBrowser.kt)
does compile the fixed role regex inside the dictionary loop, but only after
both HTTP-handler checks succeed. The detector's implication that every
dictionary incurs compilation is too broad. No user-visible latency or
end-to-end speedup was measured.

Callers include `ToolOpenDefaultBrowser.invoke`, `ToolCreateNewBrowserTab.invoke`,
`DesktopDataExtractor.browserHistory`, and
`DesktopAgentRuntimeEnvironment.defaultBrowserDisplayName`. The last caller reads
the preference repeatedly rather than retaining a selected browser.

The patch shares one private, immutable compiled regex per classloader. It keeps
the exact expression, DOT_MATCHES_ALL, dictionary scan order, first-role selection,
Safari fallback, case-insensitive classification, shell command and error
propagation. Preference values and match results are not cached. Inspection of
the cached Kotlin runtime's `Regex.find` bytecode confirms it obtains a separate
`Pattern.matcher(input)` for each search (`build/issue-678/kotlin-regex-bytecode.txt`).

[DefaultBrowserTest](../../desktopApp/src/test/kotlin/ru/souz/tool/browser/DefaultBrowserTest.kt)
uses synthetic shell output. Its six tests cover browser classification,
multiline whitespace, missing/HTTPS-only handlers, consecutive incomplete HTTP
handlers, first-role/first-handler precedence, changed preferences across calls,
absence of retained matches, and the original shell exception. All six pass
against both the original implementation and the candidate.

## Compilation counts

`build/issue-678/CompileCount.java` launches a fresh Java 21 VM through JDI and
counts entries into `Pattern.compile(String, int)` whose stack contains
`DefaultBrowserKt`. The target calls the actual private parser 100 times through
reflection and asserts every returned bundle ID/null. Each fixture runs in a
separate VM. The baseline uses the original compiled `DefaultBrowserKt.class`,
copied before the production edit, ahead of the same runtime classpath used by
the candidate. It does not execute the shell or read application preferences.

| Fixture, 100 parses | Baseline compilations | Candidate compilations |
| --- | ---: | ---: |
| One complete HTTP handler | 100 | 1 |
| Three incomplete HTTP handlers followed by a complete one | 400 | 1 |
| No HTTP handler | 0 | 1 |

The candidate's one compilation occurs during class initialization, including
when no HTTP match is needed. These are work counts, not timings. The probe
source, original class, runtime classpath and count outputs are retained under
ignored `build/issue-678/`. Candidate `javap -p -c` output there also places the
regex constructor in `static {}`, outside the parser.

Exact probe commands (each exited 0):

```sh
/Users/dumch/work/souz/.git/main-to-kmp/toolchains/temurin-21.jdk/Contents/Home/bin/javac --add-modules jdk.jdi -d build/issue-678 build/issue-678/CompileCount.java
/Users/dumch/work/souz/.git/main-to-kmp/toolchains/temurin-21.jdk/Contents/Home/bin/java --add-modules jdk.jdi -cp build/issue-678 CompileCount build/issue-678/baseline normal
/Users/dumch/work/souz/.git/main-to-kmp/toolchains/temurin-21.jdk/Contents/Home/bin/java --add-modules jdk.jdi -cp build/issue-678 CompileCount desktopApp/build/classes/kotlin/main normal
/Users/dumch/work/souz/.git/main-to-kmp/toolchains/temurin-21.jdk/Contents/Home/bin/java --add-modules jdk.jdi -cp build/issue-678 CompileCount build/issue-678/baseline incomplete
/Users/dumch/work/souz/.git/main-to-kmp/toolchains/temurin-21.jdk/Contents/Home/bin/java --add-modules jdk.jdi -cp build/issue-678 CompileCount desktopApp/build/classes/kotlin/main incomplete
/Users/dumch/work/souz/.git/main-to-kmp/toolchains/temurin-21.jdk/Contents/Home/bin/java --add-modules jdk.jdi -cp build/issue-678 CompileCount build/issue-678/baseline missing
/Users/dumch/work/souz/.git/main-to-kmp/toolchains/temurin-21.jdk/Contents/Home/bin/java --add-modules jdk.jdi -cp build/issue-678 CompileCount desktopApp/build/classes/kotlin/main missing
```

`baseline-count.txt` records 400 calls and 100 correct results for the
incomplete-handler fixture.

## Verification

Gradle uses process-local
`JAVA_HOME=/Users/dumch/work/souz/.git/main-to-kmp/toolchains/temurin-21.jdk/Contents/Home`.
`bin/java -version` verified Temurin `21.0.13+9-LTS`. The temporary init script
`build/issue-678/isolation/tests.init.gradle` adds an in-memory PreferencesFactory
to test processes, gives each module an isolated `user.home` inside this checkout,
and explicitly disables live scenario opt-in. It leaves the dependency caches
and global settings unchanged. Logs and XML copies are under `build/issue-678/`.

| Exact command | Result |
| --- | --- |
| `./gradlew :desktopApp:test --tests ru.souz.tool.browser.DefaultBrowserTest :desktopApp:issue678RuntimeClasspath --init-script build/issue-678/isolation/tests.init.gradle --no-daemon --console=plain` | Baseline exit 0; six focused tests pass. The temporary init-script task exports the runtime classpath. |
| `./gradlew :desktopApp:test :desktopApp:createReleaseDistributable :desktopApp:verifyMacReleaseShrinking souzGateFast --init-script build/issue-678/isolation/tests.init.gradle --no-daemon --console=plain` | Exit 0; desktop 441 tests, 150 skipped, zero failures/errors; six packaging tests pass; fast gate passes. |
| `npm ci --prefix quality` | Exit 0; zero vulnerabilities. |
| `./gradlew test :sharedLogic:allTests :sharedUI:allTests :koverXmlReport :koverHtmlReport koverLog -Psouz.coverage --no-parallel souzDuplicationCheck --init-script build/issue-678/isolation/tests.init.gradle --no-daemon --console=plain` | Exit 0; 1,692 tests, 225 skipped, zero failures/errors; coverage reports generated. Duplicate-code check passes (production 252 tokens, tests 0, both unchanged); local authority is `not_authoritative`, with exact-checkout authority reserved for CI. |
| `git diff --check` | Exit 0, including the final patch and this report. |

Default skips include 144 live-model scenarios, eight opt-in Docker tests,
24 unrelated OAuth Docker tests whose dependency was unavailable, 39 shared UI
tests guarded for the libGL environment, one opt-in JEV test and nine ignored
tests. These scenarios are not claimed as executed or passing. The affected
browser tests and six release-artifact tests have no skips.

The initial command
`./gradlew :desktopApp:test --tests ru.souz.tool.browser.DefaultBrowserTest :desktopApp:jar --init-script build/issue-678/isolation/tests.init.gradle --no-daemon --console=plain`
exited 1 because the temporary isolation script replaced Gradle's test classpath
and prevented JUnit loading. Adding the isolation JAR in
`doFirst` preserved the configured classpath; the corrected invocation above
passed. No repository build configuration changed.

The final `./gradlew souzGateFast --no-daemon --console=plain` exited 0;
`final-gate.log` reports `pass`. Python path-existence checks resolved both local
links in this report. Final `git diff --check` exited 0; the two new files were
also checked for trailing whitespace.

## Packaged and graphical checks

Release: `desktopApp/build/compose/binaries/main-release/app/Souz AI.app`.
Isolated input: `build/issue-678/Souz Issue 678 Input.app`.
`python3 build/issue-678/prepare_gui.py` exited 0, preserving all 248 production
JARs and adding only a test bootstrap JAR/configuration. The bootstrap forces
this checkout's `build/issue-678/gui-home` and an in-memory PreferencesFactory
before production startup. The startup log confirms the isolated home, Java 21
and 600 successful assertions against the packaged parser using synthetic HTTP
handlers, alternating valid matches and missing roles.

The following authorized commands both exited 0:

```sh
/opt/homebrew/bin/python3 -B /Users/dumch/work/souz/.git/main-to-kmp/native_validation.py --session-status
/opt/homebrew/bin/python3 -B /Users/dumch/work/souz/.git/main-to-kmp/native_validation.py --prepare '/Users/dumch/work/souz/.git/main-to-kmp/attempts/issue-678/investigate-362r8jh3/checkout/build/issue-678/Souz Issue 678 Input.app'
```

Session status was `ready`, console UID 501. CUA selected the exact returned
LaunchServices candidate:
`/Users/dumch/work/souz/.git/main-to-kmp/native-validation/Souz Workflow Validation.app`.
Its existing identity is `683BCFAE881BFA5854265C842608A02D24F2C7DB`, bundle ID
`ru.souz.workflow.validation`. `gui/candidate.json` and `gui/validation.json`
bind this checkout/base, source hashes, bootstrap hash and production JAR hashes
to that app.

| Scenario | Result |
| --- | --- |
| Main window, logo, quick actions and browser card | Passed; visible through CUA. |
| Open Models settings and return to the main screen | Passed; each transition confirmed through AX state. |
| Application-menu Quit | Passed; process inspection confirmed exit, without querying/relaunching afterward. |
| Packaged parser with repeated synthetic handlers | Passed; 600 assertions before production entry point. |
| Composer typing/submission, native browser opening/tab automation | Not run; no UI, shell-command or automation changes. Detection behavior is covered with synthetic shell output. |
| Voice, local generation, document tools and live providers | Not run; outside the regex change. No model request was submitted. |

Post-GUI `python3 build/issue-678/verify_gui.py` exited 0. It runs
`/usr/bin/codesign --verify --deep --strict` on the exact stable app above and
checks source, bootstrap and all 248 production JAR hashes, packaged parser
assertions and candidate process exit. `post-gui-verification.log` records success.
No real account, credential file, permission, TCC setting, global configuration,
shared service or signing identity was changed.
