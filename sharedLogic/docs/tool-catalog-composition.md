# Tool catalog composition

## Invariants

[`AgentToolCatalog`](../../agent/src/main/kotlin/ru/souz/agent/spi/AgentToolCatalog.kt) owns immutable tool membership and name indexing. Construction, filtering, transformation, and composition preserve `ToolCategory.entries` order and reject duplicate function names. Tool instances retain their invocation behavior; catalog immutability applies to the outer and inner maps.

Runtime factories expose their catalog through `.catalog`. Execution-bound LLM tools use the current execution's model and provider unless an explicit capability route is configured, such as the [vision model](../../backend/docs/images.md#model-input) for `ViewImage`; process-owned runtime catalogs do not retain execution settings.

Category discovery reads one filtered snapshot directly, with shared strict pagination and a 16 KiB result budget.

Skill lookup, invocation, and subagent selection read a host-supplied catalog provider. Each operation resolves one immutable snapshot; children retain only the capabilities selected at spawn, independently of later parent catalog changes.

## Backend composition

Use the [capability policy](../../backend/src/main/kotlin/ru/souz/backend/common/BackendToolCapabilityPolicy.kt) as the sole authority for advertising compiled tools, validating `enabledTools`, and selecting execution tools. Keep compiled web tools out of the backend catalogue. Persist explicit `enabledTools` selections only. An absent selection follows the advertised set as it grows.

The [execution catalogue](../../backend/src/main/kotlin/ru/souz/backend/agent/runtime/conversation/BackendExecutionToolCatalog.kt) filters compiled, execution-bound, and plugin tools by the execution's `enabledTools` snapshot before merging [client Skills](../../backend/docs/public-client-websocket.md#safe-change-guidance). Apply few-shot filtering after the merge.

## Safe changes

- Bind runtime tools in DI and include them in `RuntimeToolsFactory` together; verify invocation through the backend's composed catalogs.
- Add an LLM-dependent tool to the canonical name set and concrete catalog together.
- Use `AgentToolCatalog.filter` and `mapTools` for execution-specific selection and transformations; each returns an immutable catalog without changing its source.

## Verification

Run `./gradlew :agent:test :sharedLogic:test :backend:test`. Cover duplicate detection, category and source order, transformations, immutability, and backend catalog contents.
