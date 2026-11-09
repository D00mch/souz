# MCP HTTP client

`McpHttpSession` uses the official Kotlin MCP client SDK with Streamable HTTP. A session borrows a host-owned HTTP client configured with the SSE and timeout plugins. The caller closes the session; the HTTP client remains owned by the host.

Tool discovery follows pagination, rejects duplicate names and repeated cursors, and bounds one catalogue to 100 pages and 10,000 tools. A failed listing does not return a partial catalogue. Requests are bounded and propagate coroutine cancellation. Tool invocations are never automatically replayed by the adapter.

Tool results retain the SDK's content blocks, structured content, metadata and `isError`. A tool-level error is a returned result; a protocol or transport failure remains an exception.

SDK 0.16.0 represents tool schema roots using `type`, `properties`, `required`, `$schema` and `$defs`. Other root keywords, including root `additionalProperties`, are not retained by its decoder. Nested property schemas remain JSON objects and preserve their keywords. Souz retains the SDK schema as JSON rather than narrowing it through its compiled-tool parameter DTO. The remote server remains responsible for argument validation. Full JSON Schema root fidelity requires an SDK change.
