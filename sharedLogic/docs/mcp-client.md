# MCP HTTP client

## Transport

`McpHttpSession` uses the official Kotlin MCP client SDK with Streamable HTTP. Each MCP request uses HTTP POST; the SDK accepts either a JSON response or an SSE stream carrying protocol messages and the final result. `listTools` and `callTool` await complete results. Streaming is a transport capability, independent of LLM token streaming.

A session borrows the dedicated host-owned `createMcpHttpClient` transport. It checks literal destinations and each DNS answer, uses no proxy, follows no redirects and retries no failed connections. Non-public destinations, including mixed public/private DNS answers, are rejected unless permitted by the operator's [endpoint allowlist](../../backend/docs/mcp.md#configuration). Provider credentials and transports are independent. MCP operation deadlines are capped at 60 seconds; tool invocations are never automatically replayed.

Wire responses, including JSON success/error bodies and each SSE connection, have a 1 MiB byte cap before SDK buffering and a JSON nesting limit of 64. Only UTF-8 and identity encoding are accepted; Ktor receives a canonical UTF-8 content type.

## Discovery and schemas

Optional initial preloading has one aggregate 10-second work deadline, followed by bounded session cleanup. Servers not loaded before that deadline retain explicit discovery. Parent cancellation propagates independently.

Tool discovery follows pagination and rejects duplicate names and repeated cursors. The execution admits at most 10,000 MCP tools with a 4 MiB retained-metadata budget shared across servers; a listing has at most 100 pages. Names have at most 128 UTF-8 bytes and no controls, descriptions at most 2,000 characters and 4,000 JSON bytes, and each schema at most 16 KiB. A failed listing does not return a partial catalogue.

`McpToolCatalog` belongs to one owner and execution. Discovery publishes an immutable catalogue only after a complete successful listing, and repeated loads reuse that execution's session. Skill IDs encode both server and tool identity without delimiter ambiguity. MCP-backed Skills expose SDK-provided schemas through `SkillToolSchemas` and execute through the ordinary `RunSkillCommand` path. Missing or mismatched owner identity cannot invoke the server.

`LoadMcpTools` lists 20 summaries by default and returns `totalTools` and nullable `nextOffset`. `GetSkillsNamesByCategory` and `GetSkillsByCategory` use the same pagination inputs and return `totalSkills` and nullable `nextOffset`. `offset` is a nonnegative integer within the JVM `Int` range; `limit` is an integer from 1 to 50, default 20. Strings, fractions and explicit nulls are rejected. Category offsets apply to the complete mixed catalogue, independently of the MCP tool-count limit. MCP summary and category detail pages have a 16 KiB byte budget. MCP pagination reads cached summaries without another network listing; category detail pages resolve one catalogue/index. Oversized category entries point to exact `GetSkillByName` lookup.

SDK 0.16.0 represents tool schema roots using `type`, `properties`, `required`, `$schema` and `$defs`. Other root keywords, including root `additionalProperties`, are not retained by its decoder. Nested property schemas remain JSON objects and preserve their keywords. Souz retains the SDK schema as JSON rather than narrowing it through its compiled-tool parameter DTO. The remote server remains responsible for argument validation. Full JSON Schema root fidelity requires an SDK change.

## Results

Tool results retain the SDK's content blocks, structured content, metadata and `isError`. SDK-encoded result JSON is passed directly to message content. A tool-level error is a returned result; a protocol or transport failure remains an exception at the session boundary. The catalogue maps adapter-owned timeouts and request failures to [tool errors](../../backend/docs/mcp.md#results-and-lifetime), logging server identity, operation and failure type without remote error text, arguments or endpoint URLs. Parent coroutine cancellation propagates.

## Cleanup

SDK `Client.close()` closes local transport resources. The adapter separately calls `StreamableHttpClientTransport.terminateSession()` before local closure, so session-bearing servers receive DELETE. Protocol notifications and DELETE use independent request/connect/socket deadlines of at most 500 ms, including the SDK's non-cancellable cancellation notification. Remote termination has a 500 ms bound and local close a one-second bound. Catalogue shutdown closes all admitted sessions concurrently; it releases the discovery mutex before network cleanup. Local close still runs when termination fails or stalls. Servers without session IDs require no termination request; HTTP 405 is accepted by the SDK. Cleanup runs independently of parent cancellation and logs the step on failure or timeout. Closing sessions leaves the borrowed HTTP client open.
