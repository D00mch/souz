# Execution lifecycle

## Invariant

The [execution kernel](../src/main/kotlin/ru/souz/agent/AgentExecutionKernel.kt) isolates each skills-graph execution and normalizes unsupported persisted agent IDs to `SKILLS_GRAPH`. The host owns durable execution state. The [skills graph](skills-oriented-graph.md) owns core-tool exposure, and [subagents](subagents.md) describes parent-owned child cleanup and the bounded child loop.

### Model routing

The first model call can route to a simple or complex target. The simple target has empty advertised and executable tool sets, and its stream stays private until the classifier selects it. Complex and fallback calls retain core tools. Classifier failure falls back to the turn's configured model. The selected provider and model serve subsequent calls and become the default child route. All targets share [request preparation and compaction](skills-oriented-graph.md#invariant). See [model calls](../src/main/kotlin/ru/souz/agent/nodes/NodesLLM.kt) for speculative execution and fallback.

Classification receives bounded, untrusted [capability hints](../src/main/kotlin/ru/souz/agent/nodes/NodesSkillInventory.kt) from the filtered inventory, separate from dialogue and without tool schemas. [Jev](../../sharedLogic/src/main/kotlin/ru/souz/llms/runtime/JevClassifier.kt) separates the current request from reference history.

Simple routing suppresses `InitialWait` for the execution. Other routes can emit it once with the classifier's category when available. The host owns [best-effort waiting delivery](../../backend/docs/execution-openapi-and-events.md#live-and-replayed-events).

## Why this is fragile

Publishing input before durable commit can put unsaved history in the model context. Sealing while a reservation is pending can lose committed input. A superseded response can leak text or start tools unless the controller governs response acceptance and stream revisions.

## Safe changes

- Reserve the open [active-run mailbox](../src/main/kotlin/ru/souz/agent/runtime/ActiveRunInputController.kt) before committing. Run the producer outside the mailbox lock. Publish only after commit, bundling durable history in stored role order with the execute input that claims it. Protect accepted commits through batch construction, publication and reservation release. Release failed reservations and propagate cancellation after cleanup. Keep explicit controller closure serialized before graph cancellation.
- Keep LLM responses provisional until the controller accepts the tool batch or seals for finalization. [Steering](../src/main/kotlin/ru/souz/agent/nodes/SteerableChatNode.kt) cancels only the superseded model attempt and replans from its input context. Started tools finish before queued input returns directly to the LLM. Parent and provider cancellation still propagate.
- Advance the revision on each accepted continuation. Tag chunks with the revision captured before the attempt starts and discard older revisions at consumption. Emit ordered, nonblank assistant blocks only after tool-batch acceptance and before execution. Preserve whitespace and exclude reasoning and final answers.
- Restrict graph retries to provider nodes handling `LLMException`. Keep compaction inside request preparation and preserve consumed input across retries. Retrying a tool batch or its coordination can replay side effects. [Tool telemetry](../src/main/kotlin/ru/souz/agent/runtime/AgentToolExecutor.kt) records one completion with the typed failure even if cancellation interrupts event delivery.
- Run [memory recall](../src/main/kotlin/ru/souz/agent/nodes/NodesMemory.kt) after history input and before Skill inventory and host context enrichment. Clear prior injected memory even when automatic recall is disabled. Explicit memory tools and capture remain independent. Snapshot the accepted exchange at graph finalization and schedule capture only after finalization succeeds. Capture failures stay isolated from the returned turn.

## Verification

Run `./gradlew :agent:test` for runtime changes. See [mailbox tests](../src/test/kotlin/ru/souz/agent/runtime/ActiveRunInputControllerTest.kt), [model and steering tests](../src/test/kotlin/ru/souz/agent/nodes/NodesLLMTest.kt), [memory tests](../src/test/kotlin/ru/souz/agent/nodes/NodesMemoryTest.kt), and [kernel isolation tests](../src/test/kotlin/ru/souz/agent/AgentExecutionKernelFactoryTest.kt).
