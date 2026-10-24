# Execution lifecycle

## Invariant

`AgentExecutionKernelFactory` creates an isolated skills-graph execution with its own context factory and executor. Backend conversation runtime owns execution state and continuation; concurrent executions must not share graph instances or mutable context.

`ToolLoopGraphBasedAgent` implements the same `Agent` contract with a bounded model/tool loop. It preserves the caller's context and exposes streaming, step callbacks, and cancellation through the shared execution delegate. Its turn counter resets per execution; concurrent executions require separate agent instances. Delegation and context isolation belong to `SubagentTool`.

`submitToActiveRun` is an explicit continuation path for an open agent execution. The steerable agent owns its execution-scoped `ActiveRunInputController`; the backend can reserve the controller and publish a role-preserving history-plus-execute batch after durable commit. Submission returns `false` when no run is open or the run has sealed before finalization.

The continuation controller guards its mailbox and pending-submission count with one coroutine mutex. Producers prepare or commit input outside that mutex, allowing queued input and LLM/tool boundaries to progress. Each group of pending submissions shares one completion signal that gates closure. An empty final boundary waits for either published input or completion of the group, then rechecks the mailbox. Publication and reservation release are non-cancellable; caller cancellation propagates afterward. `SteerableChatNode` separately owns the active LLM child: it selects between child completion and execute notification, cancels only that child when execute wins, and lets parent or provider cancellation propagate. Started tools remain non-interruptible, so queued batches follow their results. Queued work returns directly to the main LLM without repeating turn setup. Explicit agent cancellation closes the controller before cancelling the graph job.

Each accepted continuation advances an execution-scoped stream revision under the mailbox mutex. Every replacement LLM request captures that revision before its provider child starts, and every streamed text chunk carries the captured value. Consumers discard chunks from older revisions; they do not infer chunk ownership from collection time.

The request-scoped kernel exposes one steerable skills graph under `AgentId.SKILLS_GRAPH`; unsupported persisted agent IDs normalize to the configured skills graph. The graph advertises and executes its fixed core Skill tools, or no tools for an automatically routed simple reply. Those tools discover capabilities from the request-scoped catalog through the Skill inventory, and queued input keeps the same core-tool boundary for the remainder of the execution.

### Model routing

The skills graph asks `TurnClassifier` once after history input, before memory or any LLM call. Complexity and recall are independent decisions. Only a confident simple decision without recall suppresses `InitialWait`; missing or failed classification keeps recall and waiting enabled. Cancellation propagates. Tool loops and active-run input do not repeat setup.

`AgentSettings.routing` selects the simple or complex target from that decision; failed classification selects complex. An explicit model remains unchanged. Automatically routed simple replies expose no tools, remove previous Skill inventory and host context, and skip inventory and enrichment. Other replies rebind core tools to the selected model, so a `SpawnSubagent` child inherits it. The selected settings, without routing, serve the rest of the turn. Conversation instructions, context validation and compaction preserving the active exchange apply to every selected model.

Automatically routed simple replies send the last 20 completed user turns and their plain assistant replies, all system messages, and the entire active exchange. Previous tool calls/results, injected memory and host context, and summaries are excluded from this request-only view. Current memory, attachments, tool results and queued input remain intact in the active exchange. Apply the context budget and any compaction to this view without trimming stored history. Strong, explicit and classifier-fallback routes use full history. The selected view lasts through the active execution and resets with the next turn.

## Why this is fragile

Execution context, continuation state, model routing, and event delivery have related lifetimes. Sharing them across executions can overwrite newer context or attribute work to another execution. Completed-turn memory capture is graph-owned and uses successful graph finalization as its boundary.

## Safe changes

- Agent graph retries require `Node(retryable = true)` and an `LLMException`; opt in only provider operations. Tool execution and enclosing coordination nodes must remain non-retryable. Steerable requests run through a nested graph with the active runtime so retries retain consumed input without restarting the mailbox loop. History compaction belongs to request preparation in the retryable LLM node.
- Route mid-run input through the active controller owned by the steerable agent; do not start another graph to enqueue input.
- Keep continuation state execution-scoped. Reserve and publish under the mailbox mutex, but run producer callbacks and wait for notifications outside it. Release every reservation even when its producer fails or is cancelled. Hosts must protect accepted durable commits from cancellation until their input is returned; publication and reservation release must also finish before cancellation propagates.
- Bundle durable history with the execute input that claims it. Preserve stored roles and do not add a passive history wake-up path.
- Keep a completed LLM response provisional until the controller accepts the tool or final boundary. Replan from the pre-attempt context when queued input wins, and seal before memory-aware finalization.
- Publish `AssistantMessage` only after accepting an assembled tool response, before its tools execute and outside provider retries. Preserve nonblank assistant blocks, ordering and whitespace; exclude reasoning, rejected attempts and final answers. Stream accumulators belong to the collecting coroutine and need no concurrent containers.
- Advance the stream revision with accepted input and attach the captured revision where `NodesLLM` produces each chunk.
- Keep controller closure suspending and serialized before explicit graph cancellation. Do not reintroduce a separate job-based acceptance gate.
- Preserve cancellation propagation through `AgentExecutor`, `Agent`, and `GraphExecutionDelegate`.
- Tool telemetry records one completion in `finally`, retaining the typed failure for host accounting even when cancellation interrupts runtime failure-event delivery.
- Skill selection receives descriptions from the same filtered catalog used for inventory. Keep the focused history and typed model/provider route intact.
- Keep memory recall after history input and turn classification, before Skill inventory. Identify injected memory through its structural provenance marker and remove the previous turn's injection even when the host disables `automaticMemoryRecall` or the turn classifier skips recall. The flag does not disable explicit memory tools or completed-turn capture. Keep capture inside graph finalization: snapshot the completed exchange, schedule only after finalization succeeds, and isolate capture failures from the returned turn.

## Verification

Run `./gradlew :agent:test`. Cover cancellation, continuation acceptance and stream revisions, invocation metadata, memory finalization failure/cancellation, model routing, and isolation between request-scoped kernels.
