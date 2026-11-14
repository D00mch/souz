# Tool catalog composition

## Invariants

Keep membership immutable and reject duplicate function names across categories. Preserve category order during filtering, transformations, and composition. [Catalog contract](../../agent/src/main/kotlin/ru/souz/agent/spi/AgentToolCatalog.kt)

Execution-bound LLM tools inherit the current model and provider unless a capability has its own route. Keep execution settings out of process-owned catalogs. [Vision routing](../../backend/docs/images.md#model-input)

Category discovery reads one filtered snapshot directly, with shared strict pagination and a 16 KiB result budget.

Skill lookup, invocation, and subagent selection read a host-supplied catalog provider. Each operation resolves one immutable snapshot; children retain only the capabilities selected at spawn, independently of later parent catalog changes.

## Backend composition

Use one policy for advertising compiled tools, validating `enabledTools`, and selecting execution tools. Only client `web.search` can populate the backend's `WEB_SEARCH` category. Persist explicit `enabledTools` selections only so users without one inherit newly advertised tools. [Capability policy](../../backend/src/main/kotlin/ru/souz/backend/common/BackendToolCapabilityPolicy.kt)

The [execution catalogue](../../backend/src/main/kotlin/ru/souz/backend/agent/runtime/conversation/BackendExecutionToolCatalog.kt) filters compiled, execution-bound, and plugin tools by the execution's `enabledTools` snapshot before merging [client Skills](../../backend/docs/public-client-websocket.md#safe-change-guidance). Apply few-shot filtering after the merge.

## Safe changes

- Bind runtime tools in DI and include them in `RuntimeToolsFactory` together; verify invocation through the backend's composed catalogs.
- Add an LLM-dependent tool to the canonical name set and concrete catalog together.

## Verification

Run `./gradlew :agent:test :sharedLogic:test :backend:test`. Cover duplicate detection, category and source order, transformations, immutability, and backend catalog contents.
