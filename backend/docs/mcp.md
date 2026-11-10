# MCP servers

The backend connects owner-configured remote MCP servers to the skills graph through the [shared MCP HTTP client](../../sharedLogic/docs/mcp-client.md). Orion client commands use their existing channel transport independently.

## Configuration

Trusted `GET /v1/me/settings` returns `settings.mcp`. `PATCH /v1/me/settings` replaces the complete map when `mcp` is supplied; omission preserves it and `{}` removes every connection.

```json
{
  "mcp": {
    "calendar": {
      "enabled": true,
      "description": "Read and create calendar events",
      "url": "https://calendar.example.com/mcp"
    }
  }
}
```

The map contains at most 64 servers. IDs contain 1–64 letters, digits, underscores or hyphens, starting with a letter or digit. Descriptions are whitespace-normalized and contain 1–240 characters. URLs use HTTP(S) without user information or fragments. Configuration is stored in the existing owner-scoped settings payload. Changes apply to new executions; an execution uses its captured configuration.

Operators can allow trusted internal endpoints with `MCP_PRIVATE_ENDPOINT_ALLOWLIST`, a comma-separated list of complete HTTP(S) URLs, for example `http://127.0.0.1:8765/mcp`. Hosts with exceptions accept only the listed URLs, including their scheme, port, path and query. The setting is deployment configuration and cannot be raised through user settings. Allowed endpoints trust their DNS answers; the default is an empty allowlist. The [transport policy](../../sharedLogic/docs/mcp-client.md#transport) defines destination checks and HTTP restrictions.

This integration provides no credential fields or OAuth flow. Legacy command-only settings are retained internally but never launched. HTTP-enabled legacy entries without descriptions use their IDs as discovery hints. MCP server selection is controlled by `mcp`; `enabledTools` continues to select compiled and JVM-plugin tools.

## Discovery and execution

1. After history input and memory recall, Skill inventory sends enabled server IDs and descriptions to the configured Skill classifier, using bounded conversation. JEV is primary, configured FRIDA is a failure fallback, and the execution LLM is the final classification fallback. A valid empty selection does not trigger a fallback.
2. Selected servers are queried through SDK initialization and paginated `tools/list`, within the shared client's [preload deadline](../../sharedLogic/docs/mcp-client.md#discovery-and-schemas). Unloaded servers remain available for explicit discovery.
3. The effective system prompt contains every enabled HTTP server ID and a short description, including unselected servers. It includes at most 20 loaded tool hints. Full schemas are not inserted automatically. The raw configured system prompt is unchanged.
4. `LoadMcpTools(serverId, offset?, limit?)` discovers a server and returns paged Skill ID summaries. The shared client's [pagination contract](../../sharedLogic/docs/mcp-client.md#discovery-and-schemas) defines response fields and bounds.
5. `GetSkillByName` returns one MCP tool's SDK-provided input/output schemas as JSON. `RunSkillCommand` delegates its arguments to SDK `tools/call`. Both use the current catalogue, so discovery is immediately usable in the same graph execution.

MCP Skills use the reserved `mcp.` namespace with collision-free encoded server and remote tool identities. Models use the returned IDs rather than construct them. Client declarations and file-backed Skills cannot claim this namespace. The MCP loader is part of the parent's small core discovery tool set and is not delegated. Children receive only explicitly selected tool snapshots through `RunSkillCommand`.

All server descriptions contribute routing hints, even when not selected for preloading. The lightweight tool-free route cannot advertise or execute MCP tools. Queued input reuses the active execution catalogue without repeating turn setup.

## Results and lifetime

Tool-level `isError` stays in the returned MCP result. Structured content, content blocks and metadata remain available to the model through ordinary tool results. Adapter-owned timeouts return `mcp_timeout`; other request failures return `mcp_request_failed`. Failed or timed-out actions can have an unknown remote outcome and are not automatically replayed.

Backend execution cleanup closes MCP sessions after child cleanup, including cancellation and failure. The host-owned HTTP client closes during backend shutdown. The shared client's [cleanup contract](../../sharedLogic/docs/mcp-client.md#cleanup) defines session termination and deadlines.

The backend reuses composed catalogue snapshots until client or MCP discovery changes them. Transport limits, category pagination and SDK schema limitations belong to the [shared client contract](../../sharedLogic/docs/mcp-client.md).

## Verification

```bash
./gradlew :sharedLogic:test --tests 'ru.souz.mcp.*'
./gradlew :agent:test --tests ru.souz.agent.nodes.NodesSkillInventoryTest
./gradlew :backend:test --tests ru.souz.backend.e2e.BackendMcpE2eTest
```

Backend scenarios use the production DI graph, PostgreSQL and the real SDK. Only external LLM/classifier/HTTP boundaries are replaced. They cover selected-server preloading, negative-selection recovery in the same run, schema discovery, actual invocation, session termination, owner-scoped settings, validation and restart persistence.
