# Remote MCP servers

Souz uses the Kotlin MCP SDK with Streamable HTTP. Each execution snapshots one owner's enabled servers, owns their sessions, and checks owner identity on discovery and tool calls.

## Configuration

Trusted `GET /v1/me/settings` returns `settings.mcp`. In `PATCH /v1/me/settings`, supplying `mcp` replaces the map, omitting it preserves the map, and `"mcp": {}` clears it.

```json
{"mcp":{"calendar":{"enabled":true,"description":"Calendar events","url":"https://calendar.example.com/mcp"}}}
```

Use HTTP(S) URLs without credentials or fragments. Souz connects to MCP servers without authentication. Legacy command-only entries are inert, and enabled legacy HTTP entries without a description use their ID. `enabledTools` governs compiled tools and JVM plugins independently. See [settings validation](../src/main/kotlin/ru/souz/backend/settings/service/McpSettings.kt) for ID, description, and count limits.

Set `MCP_PRIVATE_ENDPOINT_ALLOWLIST` in the backend environment to allow private destinations. It defaults to empty and accepts comma-separated full URLs. An allowlisted host accepts only the listed normalized URLs and trusts its DNS answers. See [Compose configuration](../../compose.yaml) and [destination policy](../../sharedLogic/src/main/kotlin/ru/souz/mcp/McpNetworkPolicy.kt).

## Discovery

The Skill classifier selects servers for preload under a shared deadline. Every enabled server remains explicitly discoverable, including after an empty selection or failed preload. The light route stays tool-free, and steering reuses the active catalogue.

- `LoadMcpTools(serverId, offset?, limit?)` returns `tools`, `totalTools`, and nullable `nextOffset`. Repeated loads reuse the session and cache successful empty listings.
- `GetSkillByName(skillId)` exposes the SDK-decoded schema. The SDK can omit root keywords such as `additionalProperties`. `RunSkillCommand` forwards arguments to `tools/call` for server-side validation.
- `GetSkillsNamesByCategory` and `GetSkillsByCategory` page one filtered catalogue snapshot independently of the MCP tool cap. Both return `totalSkills` and nullable `nextOffset`. Detail pages retain per-entry errors and advance past oversized entries with guidance to use exact lookup.

All three paging tools take a nonnegative integer `offset` (default 0) and an integer `limit` from 1 to 50 (default 20). The response byte budget can shorten a page. Continue at `nextOffset` until it is `null`. See [paging validation and budget](../../sharedLogic/src/main/kotlin/ru/souz/tool/skills/SkillDiscoveryPage.kt).

The [MCP catalogue](../../sharedLogic/src/main/kotlin/ru/souz/mcp/McpToolCatalog.kt) publishes complete listings atomically for immediate lookup and invocation in the same execution. Failed listings leave the previous snapshot intact. Reserve `mcp.` IDs against file and client Skills, and `LoadMcpTools` against client declarations. Children receive only selected immutable tool snapshots without the loader.

## Transport and limits

The dedicated host-owned transport disables proxies, redirects, and connection-failure retries. For hosts outside the allowlist, reject non-public literals and any DNS set containing a non-public answer. Bound JSON and SSE response bytes and nesting before SDK buffering. See [transport guards](../../sharedLogic/src/main/kotlin/ru/souz/mcp/McpHttpClient.kt).

Reject duplicate tool names and repeated listing cursors. Cap work time, listing pages, and retained tools and metadata across all servers in an execution. See [MCP limits](../../sharedLogic/src/main/kotlin/ru/souz/mcp/McpLimits.kt).

## Results and cleanup

Preserve SDK result JSON, including `isError`. `mcp_timeout` and `mcp_request_failed` may leave the remote outcome unknown. Never automatically replay tool calls. Propagate caller cancellation and keep URLs, arguments, and remote error text out of diagnostics.

After child cleanup, close sessions concurrently even on cancellation or failure. Keep independent deadlines for protocol notifications and remote termination, then always close local session resources. The backend closes the borrowed transport after application work stops. See [session lifecycle](../../sharedLogic/src/main/kotlin/ru/souz/mcp/McpHttpSession.kt).

## Verification

Run `./gradlew :agent:test :sharedLogic:test :backend:test` with Docker. See the [MCP runtime tests](../../sharedLogic/src/test/kotlin/ru/souz/mcp) and [backend scenarios](../src/test/kotlin/ru/souz/backend/e2e/BackendMcpE2eTest.kt).
