# Issue 677: recorder channel publication

Candidate: `28cea82085f73d0b80eedaa789a0b09d02c2858d` plus this patch.
Date: 2026-11-08 (Europe/Moscow). Checkout:
`/Users/dumch/work/souz/.git/main-to-kmp/attempts/issue-677/investigate-5_ru4e0l/checkout`.
Decision: blocked on native functional validation; implementation and automated
checks are complete.

Outstanding native checks are tracked in
[manual validation issue #1017](https://github.com/D00mch/souz/issues/1017).
The fix is published for review as a draft; creating the tracking issue does not
establish a passing native result or clear the workflow blocker.

## Finding and correctness

The live [issue](https://github.com/D00mch/souz/issues/677) is open with `desktop`
and `performance` labels and zero discussion comments. `origin/kmp` and HEAD both
identify the base above. Desktop DI supplies one `ActiveSoundRecorderImpl` to
`InMemoryAudioRecorder` (voice input) and `ActiveRecorderPcmAudioFrameSource`
(ambient streaming). The capture job's native `TargetDataLine.read` loop acquires
`stateMutex` for every completed PCM frame to read `activeChannel`. Default frames
are 640 bytes / 20 ms, nominally 50 mutex acquisitions per second.

This is real repeated synchronization, but no user-visible latency, significant
CPU cost, or production contention was measured. The detector's implication of a
costly lock is a hypothesis. Holding the lock around the loop would prevent stop
from detaching the channel during a blocked native read. Hoisting the channel
itself would retain the wrong session across recording restarts.

The [recorder](../../desktopApp/src/main/kotlin/ru/souz/service/audio/ActiveSoundRecorder.kt)
publishes its current channel through `MutableStateFlow`. Capture reads `.value`
per completed frame; lifecycle operations retain their mutex. The cached
coroutines 1.11.0 JVM bytecode confirms `StateFlowImpl.getValue` uses
`AtomicReferenceFieldUpdater.get`, without a mutex or monitor acquisition.
`build/issue-677/state-flow-bytecode.txt` preserves that inspection. Frame copying,
partial reads, capacity 3,072, non-blocking `trySend`, stop/drain, streaming and
close retain their existing boundaries. A stop can close a snapshotted channel
before `trySend`; sending was already outside the lifecycle critical section and
closed/full sends remain safely rejected.

## Reduced work and regression coverage

[ActiveSoundRecorderTest](../../desktopApp/src/test/kotlin/ru/souz/service/audio/ActiveSoundRecorderTest.kt)
controls a blocking Java Sound line without hardware, accounts or application
settings. It instruments the private lifecycle mutex without adding a production
test API. Each completed read is acknowledged by arrival at the next native read,
so measurements include completed frame delivery rather than queued work.

| Same 120-frame fixture | Original recorder | Candidate |
| --- | --- | --- |
| Capture-loop lifecycle mutex acquisitions | 120 | 0 |

The original source from `git show HEAD:desktopApp/src/main/kotlin/ru/souz/service/audio/ActiveSoundRecorder.kt`
was temporarily written in this checkout for the count comparison, then restored
in a `finally` block. Its expected sole failure is `expected 0 but was 120`.
Candidate tests also verify all returned PCM bytes, partial/zero reads, independent
frame buffers, stop/restart during a blocked read, idle-frame discard, live
collector completion, overflow dropping, start-failure recovery, duplicate-start
rejection, idempotent preparation, and close unblocking a native read. No wall-time
speedup is claimed.

## Commands and results

Gradle commands below use process-local
`JAVA_HOME=/Users/dumch/work/souz/.git/main-to-kmp/toolchains/temurin-21.jdk/Contents/Home`
and `SOUZ_AGENT_INTEGRATION_TESTS_ON=false`. `bin/java -version` verified Temurin
`21.0.13+9-LTS`. No opt-in live provider evaluation ran. Logs and preserved XML
reports are under ignored `build/issue-677/`.

| Exact command | Result |
| --- | --- |
| `./gradlew :desktopApp:test --tests 'ru.souz.service.audio.*' --no-daemon --console=plain` | Exit 0; 12 tests, zero failures/errors/skips. `audio-tests.log`, `candidate-audio-test-results/`. |
| `./gradlew :desktopApp:test --tests 'ru.souz.service.audio.ActiveSoundRecorderTest.capture publishes frames without acquiring the lifecycle mutex' --no-daemon --console=plain` | Original source only: exit 1, one expected lock-count failure (120). `baseline-lock-count.log`, `baseline-lock-count-results/`. |
| `./gradlew :desktopApp:test test :sharedLogic:allTests :sharedUI:allTests :koverXmlReport :koverHtmlReport koverLog -Psouz.coverage --no-parallel --no-daemon --console=plain` | Candidate exit 0; 1,686 tests, 201 skipped, zero failures/errors. Desktop: 435 tests, 150 skipped; all 12 audio tests pass. `coverage-tests.log`, `coverage-test-results/`. |
| `npm ci --prefix quality` | Exit 0; `npm-ci.log`. |
| `./gradlew souzGateFast souzDuplicationCheck :desktopApp:createReleaseDistributable :desktopApp:verifyMacReleaseShrinking --no-daemon --console=plain` | Exit 0. Fast gate passes. Duplication report is locally `not_authoritative`; CI owns exact-checkout authority. All six packaged classpath/signature tests pass. `policy-packaging.log`, `packaging-test-results/`. |
| `/usr/sbin/system_profiler SPAudioDataType -json` | Exit 0; only Mac mini Speakers, output channels 2, no input device. `audio-devices.json`. |
| `/opt/homebrew/bin/python3 -B /Users/dumch/work/souz/.git/main-to-kmp/native_validation.py --session-status` | Exit 0; `ready`, console UID 501. `session-status.json`. |
| `python3 build/issue-677/prepare_gui.py` | Exit 0; isolated bootstrap and in-memory preferences, all 248 production JARs unchanged. `gui-preparation.log`, `gui/candidate.json`. |
| `python3 build/issue-677/verify_candidate.py` | Exit 0; post-GUI strict signature, all 248 production JAR hashes, source/fixture hashes, six packaging tests and exited candidate PID verified. `post-gui-checks.log`, `gui/validation.json`. |
| `./gradlew souzGateFast --no-daemon --console=plain` | Final candidate exit 0, fast gate passes; `final-gate.log`. |
| `git diff --check` | Exit 0. All nine local links in the affected instructions and new documents resolve (Python path-existence check). |

## Exact packaged candidate and graphical checks

Release: `desktopApp/build/compose/binaries/main-release/app/Souz AI.app`.
Isolated input: `build/issue-677/Souz Issue 677 Input.app`.
The input adds one bootstrap JAR and Java configuration; production JARs are
preserved. Its in-memory PreferencesFactory seeds synthetic settings and a
loopback-only selected model; no model request was submitted. Bootstrap forces
this checkout's `build/issue-677/gui-home` before the production entry point.
`gui/startup.log` confirms the isolated home and Java 21. No real account,
credential or application settings were used.

The authorized stable preparation command passed:

```sh
/opt/homebrew/bin/python3 -B /Users/dumch/work/souz/.git/main-to-kmp/native_validation.py --prepare '/Users/dumch/work/souz/.git/main-to-kmp/attempts/issue-677/investigate-5_ru4e0l/checkout/build/issue-677/Souz Issue 677 Input.app'
```

CUA selected exactly the returned app:
`/Users/dumch/work/souz/.git/main-to-kmp/native-validation/Souz Workflow Validation.app`.
Bundle ID: `ru.souz.workflow.validation`. Existing signing identity:
`683BCFAE881BFA5854265C842608A02D24F2C7DB`. Strict deep signature verification
passed before and after the checks. `gui/candidate.json` and `gui/validation.json`
bind this checkout/base, production JARs, fixture and source hashes to the app.

| UI scenario | Result |
| --- | --- |
| LaunchServices startup/main UI | Passed; exact-path `cua.getApp` shows selected `offline-677-fixture`. |
| Short composer typing | Failed to establish input focus: AX/coordinate clicks and `typeText('677')` produced no text, including after the authorized AppKit activation helper, AX refresh and Raise. No fixture was pasted or request submitted. This is not attributed to the recorder patch. |
| Native microphone startup | Failed: two Voice input clicks reached packaged capture, both reporting `No line matching interface TargetDataLine supporting format PCM_SIGNED 16000.0 Hz, 16 bit, mono, 2 bytes/frame, little-endian is supported.` |
| Recovery from missing microphone/retry | Passed; second click initiates another start and the UI remains idle with Voice input enabled. |
| Native capture/stop and consecutive recording restart | Not run; no supported input device. |
| Native right Option capture hotkey | Not run; successful capture is unavailable. No native-hook permission check is claimed. |
| Native ambient streaming start/stop and voice exclusion | Not run; no supported input device. |
| Quit with native capture active | Not run; capture cannot start. |
| Normal native application-menu quit | Passed; CUA Quit Souz AI, then process inspection confirmed PID 83899 exited. Candidate was not queried/relaunched afterward. |
| Live providers, local generation, document tools | Not run; outside this recorder publication change. |

The authorized focus fallback was exactly:
`/usr/bin/osascript /Users/dumch/work/souz/.git/main-to-kmp/activate-candidate.applescript '/Users/dumch/work/souz/.git/main-to-kmp/native-validation/Souz Workflow Validation.app'`.
It exited 0 (`Activated candidate PID 83899`), but did not establish typing.
`gui/ax-observations.json` preserves the relevant states; startup logs preserve
both native device failures. No OS permission, TCC, global configuration, signing
identity, shared service, automation, branch or GitHub state was changed.

## Required owner checks

Provide a supported input audio device and establish foreground input on the
exact isolated candidate above. With synthetic speech and review-before-send
enabled, verify microphone-button and right Option capture, stop and local
transcription, two consecutive recordings, ambient streaming start/stop with
voice exclusion, and quitting during active capture without a hang. These checks
must not submit to a live model provider. The current graphical session is active;
this blocker is hardware/functional validation and input focus, not a locked
session or `cgWindowNotFound`. `needs_human_input=true`.
