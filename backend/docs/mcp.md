# Remote MCP servers

Backend MCP uses Streamable HTTP. Desktop retains its separate [stdio, HTTP and OAuth integration](../../sharedLogic/src/jvmMain/kotlin/ru/souz/service/mcp/README.md). Each execution snapshots one owner's enabled servers and owns their sessions. Check owner identity on discovery and invocation.

Credential-free Client-Souz turns cannot read owner MCP configuration, advertise its servers, load tools or invoke them. Matching caller-supplied chat and device UUIDs do not establish owner authority.

## Configuration

Trusted `GET /v1/me/settings` returns `settings.mcp`. In `PATCH /v1/me/settings`, supplying `mcp` replaces the map, omitting it preserves the map, and `"mcp": {}` clears it.

```json
{"mcp":{"calendar":{"enabled":true,"description":"Calendar events","url":"https://calendar.example.com/mcp"}}}
```

Use HTTP(S) URLs without credentials or fragments. Souz connects to MCP servers without authentication. Legacy command-only entries are inert, and enabled legacy HTTP entries without a description use their ID. `enabledTools` governs compiled tools and JVM plugins independently. See [settings validation](../src/main/kotlin/ru/souz/backend/settings/service/McpSettings.kt) for ID, description, and count limits.

Set `MCP_PRIVATE_ENDPOINT_ALLOWLIST` in the backend environment to allow private destinations. It defaults to empty and accepts comma-separated full URLs. An allowlisted host accepts only the listed normalized URLs and trusts its DNS answers. See [Compose configuration](../../compose.yaml) and [destination policy](../../sharedLogic/src/jvmMain/kotlin/ru/souz/mcp/McpNetworkPolicy.kt).

## Discovery

The Skill classifier selects servers for preload under a shared deadline. Every enabled server remains explicitly discoverable, including after an empty selection or failed preload. The light route stays tool-free, and steering reuses the active catalogue.

- `LoadMcpTools(serverId, offset?, limit?)` returns `tools`, `totalTools`, and nullable `nextOffset`. Repeated loads reuse the session and cache successful empty listings.
- `GetSkillByName(skillId)` exposes the SDK-decoded schema. The SDK can omit root keywords such as `additionalProperties`. `RunSkillCommand` forwards arguments to `tools/call` for server-side validation.
- `GetSkillsNamesByCategory` and `GetSkillsByCategory` page one filtered catalogue snapshot independently of the MCP tool cap. Both return `totalSkills` and nullable `nextOffset`. Detail pages retain per-entry errors and advance past oversized entries with guidance to use exact lookup.

All three paging tools take a nonnegative integer `offset` (default 0) and an integer `limit` from 1 to 50 (default 20). The response byte budget can shorten a page. Continue at `nextOffset` until it is `null`. See [paging validation and budget](../../sharedLogic/src/commonJvmMain/kotlin/ru/souz/tool/skills/SkillDiscoveryPage.kt).

The [MCP catalogue](../../sharedLogic/src/jvmMain/kotlin/ru/souz/mcp/McpToolCatalog.kt) publishes complete listings atomically for immediate lookup and invocation in the same execution. Failed listings leave the previous snapshot intact. Reserve `mcp.` IDs against file and client Skills, and `LoadMcpTools` against client declarations. Children receive only selected immutable tool snapshots without the loader.

## Transport and limits

Use the dedicated transport without proxies, redirects, or connection retries. Make request bodies one-shot to prevent status-driven replay. Apply nesting guards to SSE data using the same whitespace handling as SDK decoding. Outside the operator allowlist, reject non-public literals and any DNS set containing a non-public answer. Accept only UTF-8 and identity encoding. Bound JSON and SSE bytes and nesting before SDK buffering. [Transport guards](../../sharedLogic/src/jvmMain/kotlin/ru/souz/mcp/McpHttpClient.kt)

Reject duplicate tool names and repeated listing cursors. Bound per-cursor and cumulative UTF-8 cursor bytes before retaining or reusing them. Cap work time, listing pages, and retained tools and metadata across all servers in an execution. See [MCP limits](../../sharedLogic/src/jvmMain/kotlin/ru/souz/mcp/McpLimits.kt).

## Results and cleanup

Preserve SDK result JSON, including `isError`. `mcp_timeout` and `mcp_request_failed` can leave the outcome unknown. Never replay tool calls automatically. Propagate cancellation and omit URLs, arguments, and remote errors from diagnostics.

After child cleanup, close sessions concurrently even on cancellation or failure. Keep independent deadlines for protocol notifications and remote termination, then always close local session resources. The backend closes the borrowed transport after application work stops. See [session lifecycle](../../sharedLogic/src/jvmMain/kotlin/ru/souz/mcp/McpHttpSession.kt).

## Verification

Run `./gradlew :agent:test :sharedLogic:jvmTest :backend:test` with Docker. See the [MCP runtime tests](../../sharedLogic/src/test/kotlin/ru/souz/mcp) and [backend scenarios](../src/test/kotlin/ru/souz/backend/e2e/BackendMcpE2eTest.kt).
