# Issue 993 local-model validation

## Scope and publication status

This candidate replaces Qwen3 4B Instruct 2507 with Qwen3.5 4B and adds Qwen3.5 9B. LFM2.5 24B-A2B is deferred by the owner and is absent from the model selectors, local catalog, prompt families and tests. Its reasoning parser and per-profile completion-budget changes are excluded.

Publication status: **draft**. Required GUI chat and complete download scenarios remain unverified. CI, review and owner approval still apply before merge.

Base: `875ee27e142eacbd997b1b5bfc5dc039a80b47c8` (`kmp`). Validation host: macOS arm64, 16 GiB RAM. Gradle uses Temurin 21.0.13 from the managed local toolchain.

## Implemented behavior

- [Local profiles](../../native/src/main/kotlin/ru/souz/llms/local/LocalModels.kt) use Q4_K_M Qwen3.5 weights, 8/16 GiB RAM gates, 8,192-token default contexts and 32,768-token profile caps. Explicitly configured contexts retain their existing contract; the runtime completion cap remains 1,024 tokens.
- [Model selectors](../../llms/src/main/kotlin/ru/souz/llms/DTO.kt) preserve the historical Qwen enum name and alias while resolving them to Qwen3.5 4B. A separate storage directory prevents old Qwen3 weights from satisfying readiness. Gemma profiles remain available.
- [Prompt rendering](../../native/src/main/kotlin/ru/souz/llms/local/LocalPromptRenderer.kt) supplies Qwen3.5's non-thinking assistant prefix and retains the strict JSON final/tool protocol and correlated tool-result history.
- [Catalog tests](../../native/src/test/kotlin/ru/souz/local/LocalModelCatalogTest.kt) cover the exact catalog, RAM gates, selector migration, old-weight isolation, linked assets, and dependent tool turns through chat and streaming with a simulated bridge. [Cancellation tests](../../native/src/test/kotlin/ru/souz/local/NativeConcurrentRequestsTest.kt) cover both Qwen profiles.

## Current-candidate checks

All checks below passed after removing LFM. No live agents, external notifications or GitHub mutations were used for verification.

With the managed Java 21 toolchain supplied through process-local `JAVA_HOME`:

```sh
./gradlew test :sharedLogic:allTests :sharedUI:allTests :koverXmlReport :koverHtmlReport koverLog -Psouz.coverage --no-parallel --no-daemon --console=plain
npm ci --prefix quality
./gradlew souzGateFast souzDuplicationCheck :desktopApp:createReleaseDistributable :desktopApp:verifyMacReleaseShrinking --no-parallel --no-daemon --console=plain
git diff --check
```

- JVM graph: 1,696 test cases, 201 skipped, zero failures/errors. This includes 11 `llms` and 61 `native` cases, with no skips in either affected module.
- Release packaging and all six macOS shrinking checks passed.
- Fast gate: `pass`. Local duplicate-code check: `pass`; the expensive lane remains `not_authoritative` outside GitHub Actions. No remote CI result is claimed.
- Local documentation links passed validation.

The gate is run separately from coverage instrumentation; combining them exposes Kover's test-only self dependencies as unclassified module dependencies. No quality policy was changed.

## Earlier candidate evidence

The earlier candidate, which included LFM code, passed direct local-runtime probes for both Qwen models: chat, RAW JSON, one tool, dependent tools, cancellation and a subsequent request. These probes used real Qwen weights and synthetic tools on arm64. They are evidence for that earlier build, not a rerun against this final candidate.

Its isolated GUI showed migration of the old Qwen selection, the RAM-filtered model list, download progress/cancellation, and 9B selection from a verified seeded cache. Composer input failed, so GUI chat/tool/cancellation scenarios were not run. A complete-partial download fixture started a fresh transfer and was cancelled; completed GUI download is unverified.

The bundled bridge was not changed. The earlier arm64 probes loaded Qwen3.5 successfully; x64 execution remains unverified. No new native-model or GUI probe is claimed for the candidate published here.

## Remaining checks

- Prepare an isolated packaged build of the published candidate, then verify composer input and normal chat, one tool, dependent tools, cancellation and a successful next request for both Qwen models.
- Complete a download through the GUI and verify finalization; a seeded cache does not establish this result.
- Verify bridge healthcheck and real Qwen loading/generation on macOS x64.

Keep the PR in draft until required acceptance checks pass. LFM validation is outside this candidate's scope.
