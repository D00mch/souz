# Issue 993 local-model validation

## Scope and candidate

The candidate replaces Qwen3 4B Instruct 2507 with Qwen3.5 4B and adds Qwen3.5 9B. [The owner’s scope decision](https://github.com/D00mch/souz/issues/993#issuecomment-6726606225) defers LFM2.5 24B-A2B.

Runtime parent: `98d62ced633cdec0204547bd24b561707dae7d0c`. Base: `875ee27e142eacbd997b1b5bfc5dc039a80b47c8`. The tested [response parser](../../native/src/main/kotlin/ru/souz/llms/local/LocalStrictJson.kt) has Git blob `7d08e26bfde53dfd17e0f828588ceba243b3cf20`. Validation host: macOS arm64, 16 GiB RAM. Validation date: November 10, 2026.

## Runtime behavior and regression checks

The [profiles](../../native/src/main/kotlin/ru/souz/llms/local/LocalModels.kt) use Q4_K_M weights, 8/16 GiB RAM gates, 8,192-token defaults and 32,768-token profile context caps. Explicit context settings retain their shared contract. The historical Qwen selector resolves to Qwen3.5 4B and uses a separate storage directory. Both Gemma profiles remain available.

Final-response recovery accepts bare numbers and application JSON without the internal final envelope. Arrays and quoted strings containing objects retain their complete value. Legacy `result` unwrapping applies only to an object with that single textual field. Tool-call fields still require valid call structure; malformed tool invocations are errors. The system contract asks for a final envelope with string content, including escaped JSON when the user requests JSON.

[Parser regressions](../../native/src/test/kotlin/ru/souz/local/LocalInferenceSupportTest.kt) cover the observed `3247` and `{"first":703,"second":16169}` failures, complete arrays/strings, metadata preservation, application objects with `result` plus other fields, and rejection of incomplete tool calls. Both new regression tests failed on the unchanged parser and passed with the repair. Existing tool-call conversion and recovery tests remain passing.

## Automated checks

Using the managed Temurin 21.0.13 toolchain, with inherited Java agent/options variables removed, the following commands passed:

```sh
./gradlew -I build/issue-993-validation/isolation.init.gradle :native:test --no-parallel --no-daemon --console=plain
./gradlew -I build/issue-993-validation/isolation.init.gradle souzGateFast :llms:test :native:test :desktopApp:test :sharedUI:jvmTest :desktopApp:createReleaseDistributable :desktopApp:verifyMacReleaseShrinking --no-parallel --no-daemon --console=plain
./gradlew -I build/issue-993-validation/isolation.init.gradle test :sharedLogic:allTests :sharedUI:allTests :koverXmlReport :koverHtmlReport koverLog -Psouz.coverage --no-parallel --no-daemon --console=plain
npm ci --prefix quality
./gradlew souzDuplicationCheck --no-parallel --no-daemon --console=plain
```

- Full JVM graph: 1,698 test cases, 225 skipped, zero failures/errors. All 63 native cases passed without skips.
- Release packaging, all six macOS shrinking checks and the standalone fast gate passed.
- The duplicate-code command passed; its local expensive-gate result remains `not_authoritative`, and does not establish remote CI success.
- Tests used in-memory preferences and a separate home/log directory. Opt-in live agent scenarios, Jev and Docker integration tests were disabled. No API keys were provisioned.
- Commands, synthetic fixtures, JAR hashes and reports are retained under `build/issue-993-validation/manual-*` until normal task cleanup.

## Native architecture checks

Real Qwen3.5 4B and 9B weights loaded and generated `SOUZ_ARCH_OK` with the packaged arm64 bridge and with the packaged x64 bridge under Rosetta. Linked EmbeddingGemma generated a finite, normalized 768-dimensional vector on each architecture. These checks used synthetic inputs and process-local `GGML_METAL_NO_RESIDENCY=1`.

The native harness called the tracked bridge ABI directly. The x64 checks ran under Rosetta on this arm64 Mac; they are not evidence from a physical Intel Mac. GUI checks below exercise the packaged application’s arm64 JVM provider and response parser.

## Packaged GUI candidate

The release app was copied to `build/issue-993-validation/Souz Manual JSON.app`. All 248 production JARs were retained byte for byte; only separate fixture JARs and launcher options were added. The managed preparation wrapper signed the candidate at its stable `Souz Workflow Validation.app` path with bundle ID `ru.souz.workflow.validation` and the existing certificate-bound identity.

The final in-memory fixture seeds the historical Qwen selector, English UI, an initial 8,192-token context, skipped onboarding and explicitly empty provider credentials. A standalone fixture check verified these settings before launch. An earlier incorrectly selected fixture opened onboarding; that app was closed without requests. No OS permissions or real-account settings were changed.

For complete-download validation, both cached Qwen directories were moved intact to `build/issue-993-validation/model-cache-before-download` while the app was stopped. No existing weights were deleted. The GUI therefore had to perform fresh transfers and finalization; the linked embedding weights remained available.

| Scenario | Qwen3.5 4B | Qwen3.5 9B |
| --- | --- | --- |
| Historical selector migration | Passed | Not applicable |
| Catalog and selection | Passed | Passed |
| Complete GUI download and finalization | Passed | Passed |
| Composer input | Passed | Passed |
| Normal chat | Passed | Passed |
| One Calculator invocation and final response | Passed | Passed |
| Two dependent Calculator calls and final response | Passed | Passed |
| Requested JSON object | Passed | Passed |
| Cancellation and successful next request | Passed | Passed |

The full GUI agent instruction/tool catalog correctly produced a context-budget error at the explicitly selected 8,192-token limit. Positive GUI checks use the available 32,000-token Context option, within the profile cap. The failed request was not counted as a passing chat.

Input became available after hiding the test app and reopening its exact stable path through Finder. Native About-dialog recovery alone did not restore input in this run. AppleScript and permission changes were not used.

Observed final answers:

- 9B: `PATCH_OK_9B`; Calculator `137 * 29` returned `3973`; dependent calls `37 * 19` then `703 * 23` returned `{"first":703,"second":16169}`.
- 4B: `PATCH_OK_4B`; Calculator `191 * 17` returned `3247`; dependent calls `23 * 31` then `713 * 11` returned `{"first":713,"second":7843}`.
- Each model’s active long-response request was stopped. The next request returned `AFTER_CANCEL_9B` or `AFTER_CANCEL_4B`. No partial streaming token was required to establish this UI cancellation check; request-ownership cancellation remains covered by native tests.
- Fresh GUI downloads finalized at 2,740,937,888 bytes (4B) and 5,680,522,464 bytes (9B). Both were subsequently used by the packaged application.

These are focused scenario checks, not a statistical reliability or performance benchmark. The same synthetic chat history was retained while switching models.

## Acceptance status

Local acceptance checks passed for the Qwen-only scope. Publication, CI, local and remote review, owner approval and merge remain separate workflow gates. No remote CI success or owner approval is inferred from these local results.
