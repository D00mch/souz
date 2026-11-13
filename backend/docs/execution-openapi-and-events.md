# Execution, OpenAPI, and events

## Invariant

Restore saved agent state by stable chat ID into a request-scoped runtime. The public `threadId` is the execution ID. [Execution ownership](distributed-backend-boundary.md#invariant)

Finalize in a non-cancellable scope. Fence the commit against cancellation and loss of ownership. Save the answer, continuation, chat timestamp, and completed status together before publishing completion. Each lifecycle write preserves concurrent changes to fields owned elsewhere. [Finalization](../src/main/kotlin/ru/souz/backend/execution/service/AgentExecutionFinalizer.kt)

`waiting_option` means the option and its event are durable and the continuation committed with the status change. A partial handoff remains running and is cancelled on shutdown. Answering joins the handoff before resuming. Cleanup preserves a saved wait unless explicitly cancelled. Each execution snapshots its effective compiled-tool selection, `narrateSteps`, `intermediateMessages`, and optional reasoning effort for continuations. Legacy rows with missing settings resume with narration off, intermediate messages on, and effort unset. See the [request factory](../src/main/kotlin/ru/souz/backend/execution/service/AgentExecutionRequestFactory.kt).

## Safe-change guidance

- Register background jobs before their execution body runs. Run cancellation persistence and terminal emission in non-cancellable cleanup, including cancellation before startup. Keep the job registered until cleanup finishes. [Launcher](../src/main/kotlin/ru/souz/backend/execution/service/AgentExecutionLauncher.kt) · [Host shutdown rules](../AGENTS.md#boundaries)
- Preserve stored terminal outcomes during cleanup and repair their missing events. PostgreSQL deduplicates terminal events for both execution APIs. The application-owned public startup handoff persists failures independently of socket delivery. See [public acceptance and ACK ordering](public-client-websocket.md#safe-change-guidance) and the [distributed routing and recovery boundary](distributed-backend-boundary.md).
- Advance `basedOnMessageSeq` only through input the runtime has observed. Load the durable gap through the execute trigger. Client history enters the session with the submit that claims it. History inserted before a terminal assistant row enters the session after that saved response. [Session cursor](../src/main/kotlin/ru/souz/backend/agent/runtime/conversation/BackendConversationRuntime.kt) · [History ordering test](../src/test/kotlin/ru/souz/backend/e2e/BackendPublicHistoryContractE2eTest.kt)
- Keep nested model calls in the execution's credential, timeout, and usage scope. Preserve explicit routes and reject unsupported providers at selection and dispatch. Unary fallback is limited to accessible models after provider failures. Keep user-key failures user-scoped. Streaming and dedicated summary calls stay on their selected models. [Provider fallback](../src/main/kotlin/ru/souz/backend/llm/BackendExecutionLlmChatApi.kt) · [Provider transport lifecycle](../../sharedLogic/docs/provider-http-lifecycle.md)

### Live and replayed events

- Keep proxy `message.delta` live-only. Follow the [public delivery contract](../../docs/public-souz-contract/README.md#delivery-and-retries) and [attachment snapshot rules](attachments.md#websocket-transport) for public replay.
- Keep waiting publication in the execution scope. Narration, a parent tool start, or a turn outcome can close pending delivery immediately. Publication and cancellation must be ordered without blocking on a stalled lookup. Preserve separate narration blocks, custom prompts, and the memory notice. [Progress contract](../../docs/public-souz-contract/README.md#intermediate-assistant-messages)
- Subscribe bot observers before launch and send only that execution's live progress in order through the bounded queue and fenced reply path. Keep sends best-effort and observers ineligible for client commands. Cancel and join the sender and close its subscription before the final reply or on lease loss. [Observer lifecycle](../src/main/kotlin/ru/souz/backend/execution/service/AgentExecutionService.kt)
- Sanitize tool audit previews once without mutating the source. Persist them regardless of event settings. [Event sink](../src/main/kotlin/ru/souz/backend/agent/runtime/BackendAgentRuntimeEventSink.kt)

### OpenAPI

- Give ordinary HTTP routes explicit metadata with stable operation IDs, tags, inputs, success responses, structured errors, and trusted-proxy security where applicable. Runtime descriptions and reflection cover helpers and conditional routes. Limit compiler inference to explicitly commented paths. Exclude WebSocket routes and their upgrade fallback at both levels and maintain their schemas in the [public contract](../../docs/public-souz-contract/README.md). [Route metadata](../src/main/kotlin/ru/souz/backend/http/BackendOpenApi.kt) · [Compiler configuration](../build.gradle.kts) · [Socket routes](../src/main/kotlin/ru/souz/backend/http/routes/PublicClientSocket.kt)
- Preserve the replay union with structurally distinct canonical and legacy payloads. [Event schemas](../src/main/kotlin/ru/souz/backend/http/BackendEventOpenApi.kt)

## Deployment compatibility

Stop or drain builds that cannot decode `intermediateMessages` before settings schema 5 writes begin. Roll back to a compatible build or remove the field from stored settings before restarting an older build.

## Diagnostics

The finalizer logs cumulative token usage after a successful commit, including nested calls and option continuations. A crash after commit can lose the log entry. Sum completed-execution tokens from exported JSON Lines.

```sh
jq -s '[.[] | (.kvpList // [] | add) | select(.event == "execution.token_usage") | .total_tokens | tonumber] | add // 0' backend.jsonl
```

## Verification

Run `./gradlew :backend:test` for runtime changes. Cover [cancellation and option recovery](../src/test/kotlin/ru/souz/backend/e2e/BackendExecutionFinalizationE2eTest.kt), [live delivery and durable replay](../src/test/kotlin/ru/souz/backend/e2e/BackendPublicWebSocketE2eTest.kt), and [OpenAPI coverage and WebSocket exclusion](../src/test/kotlin/ru/souz/backend/e2e/BackendCompositionE2eTest.kt).
