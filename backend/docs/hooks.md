# Workspace hook admission and recovery

See the [operator guide](../../docs/hooks.md) for configuration, HTTP behavior, and limits.

## Ownership and admission

Resolve owners from the host allowlist and workspace. Match DOCKER definitions to the workspace owner. For trusted LOCAL files, filter readable foreign-owner definitions before validating other fields. Fail closed on invalid auth or conflicting IDs. Keep YAML, credentials, and payloads out of logs. [Definition loading](../src/main/kotlin/ru/souz/backend/hooks/HookDefinitions.kt)

Authorize before admitting a receipt or initializing agent, Skill, or OAuth work. Check Bearer auth before taking an intake slot. Bound verifier I/O and share intake permits per configured owner across reloads. Release permits on completion or cancellation. Run trusted verifier snapshots in the owner's sandbox, keep request data out of shell commands, and clean up temporary files on completion or cancellation. Use the verified event ID and recheck the loaded definition after verification. [Verifier](../src/main/kotlin/ru/souz/backend/hooks/HookVerifier.kt)

Serialize deduplication and quota checks per owner. Count active receipts against the hook queue limit and new receipts against the owner's UTC daily limit. Commit each new receipt with its hidden chat before acknowledging. Duplicates reuse the existing receipt and chat. Use the receipt ID as the execution ID and the sole basis for hook recovery and budgets. [Receipt and quota transactions](../src/main/kotlin/ru/souz/backend/hooks/HookStore.kt)

Hook authentication authorizes the integration, not instructions in event data. Hooks and their option continuations cannot use destructive memory tools; those require an authenticated interactive owner message.

## Execution and recovery

Run hooks in one backend process and serialize dispatch per hook. Check the saved owner and revision before dispatch. Initial turns and option continuations share execution capacity and timeouts. `waiting_option` releases capacity while keeping the receipt running and its queue position and budget intact. [Dispatch and recovery](../src/main/kotlin/ru/souz/backend/hooks/HookService.kt)

Recovery resumes pending receipts and preserves `waiting_option`. Fail interrupted receipt-linked work without replaying uncertain effects. Persist or repair the terminal event before finishing its receipt, including when the execution state is already terminal. [Execution finalization](../src/main/kotlin/ru/souz/backend/execution/service/AgentExecutionService.kt) · [Recovery scope](distributed-backend-boundary.md)

All conversation provider attempts share per-user admission, provider concurrency and request/token quotas, including ordinary WebSocket, proxy HTTP, bots and children. Concurrency admission waits cancellably and retains capacity through call and cancellation cleanup. These limits are process-local; caller-selected public UUIDs do not replace ingress abuse controls. Apply persistent hook budgets only to receipt-linked executions. Acquire provider capacity and per-user admission before reserving a hook attempt, including during stream collection. Reserve the per-event and per-owner UTC daily budgets together before each attempt, including retries, nested tools, and continuations. If either quota rejects the reservation, charge neither. Preserve lower request output limits when applying the hook cap. [Provider admission](../src/main/kotlin/ru/souz/backend/llm/quota/ExecutionQuotaManager.kt) · [Hook budget](../src/main/kotlin/ru/souz/backend/hooks/HookLlmBudget.kt)

## Verification

For runtime changes, run `./gradlew :backend:test --tests '*HookVerifierTest' --tests '*BackendHooksE2eTest'` with local Python and Docker. Run the backend suite for lifecycle or provider integration changes.
