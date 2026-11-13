# Execution lifecycle

## Invariant

The [execution kernel](../src/main/kotlin/ru/souz/agent/AgentExecutionKernel.kt) isolates each execution and normalizes unsupported persisted agent IDs to `SKILLS_GRAPH`. The host owns durable execution state.

### Model routing

Only confidently tool-free requests qualify for the simple route. Jev sends attachments and oversized current requests directly to complex, without truncating the current request to make it eligible. The simple route advertises and executes no tools and withholds output until selected. Complex and fallback calls retain core tools. Classifier failure uses the turn's configured model. The selected provider and model serve later calls and become the default child route. All routes share [request preparation and compaction](skills-oriented-graph.md#context-budget-and-compaction). [Model calls](../src/main/kotlin/ru/souz/agent/nodes/NodesLLM.kt)

Classification receives bounded, untrusted capability hints from the filtered inventory without schemas or additional discovery. Treat earlier dialogue as reference and keep the current request separate. [Jev](../../sharedLogic/src/main/kotlin/ru/souz/llms/runtime/JevClassifier.kt)

Simple routing suppresses `InitialWait` for the execution. Other routes can emit it once with the classifier's category when available. The host owns [best-effort waiting delivery](../../backend/docs/execution-openapi-and-events.md#live-and-replayed-events).

## Why this is fragile

Publishing input before durable commit can put unsaved history in the model context. Sealing while a reservation is pending can lose committed input. A superseded response can leak text or start tools unless the controller governs response acceptance and stream revisions.

## Safe changes

- Reserve the open [active-run mailbox](../src/main/kotlin/ru/souz/agent/runtime/ActiveRunInputController.kt) before committing. Run the producer outside the mailbox lock. Publish only after commit, bundling durable history in stored role order with the execute input that claims it. Protect accepted commits through batch construction, publication and reservation release. Release failed reservations and propagate cancellation after cleanup. Keep explicit controller closure serialized before graph cancellation.
- Keep LLM responses provisional until the controller accepts the tool batch or seals for finalization. [Steering](../src/main/kotlin/ru/souz/agent/nodes/SteerableChatNode.kt) cancels only the superseded model attempt and replans from its input context. Started tools finish before queued input returns directly to the LLM. Parent and provider cancellation still propagate.
- Advance the revision on each accepted continuation. Tag chunks with the revision captured before the attempt starts and discard older revisions at consumption. Emit ordered, nonblank assistant blocks only after tool-batch acceptance and before execution. Preserve whitespace and exclude reasoning and final answers.
- Restrict graph retries to provider nodes handling `LLMException`. Keep compaction inside request preparation and preserve consumed input across retries. Retrying a tool batch or its coordination can replay side effects. [Tool telemetry](../src/main/kotlin/ru/souz/agent/runtime/AgentToolExecutor.kt) records one completion with the typed failure even if cancellation interrupts event delivery.
- Run [memory recall](../src/main/kotlin/ru/souz/agent/nodes/NodesMemory.kt) after history input and before Skill inventory and host context enrichment. Clear structurally marked recall even when automatic recall is disabled or fails. Explicit memory tools and capture remain independent of that flag. Snapshot the accepted exchange at graph finalization and schedule asynchronous capture only after finalization succeeds. Capture failures stay isolated from the returned turn.

## Verification

Run `./gradlew :agent:test` for runtime changes. See [mailbox tests](../src/test/kotlin/ru/souz/agent/runtime/ActiveRunInputControllerTest.kt), [model and steering tests](../src/test/kotlin/ru/souz/agent/nodes/NodesLLMTest.kt), [memory tests](../src/test/kotlin/ru/souz/agent/nodes/NodesMemoryTest.kt), and [kernel isolation tests](../src/test/kotlin/ru/souz/agent/AgentExecutionKernelFactoryTest.kt).
