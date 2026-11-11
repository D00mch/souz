# Remote MCP servers

Souz uses the official Kotlin MCP SDK with Streamable HTTP. Connections belong to one owner and execution; Orion client tools retain their independent channel transport.

## Configuration

Trusted `GET /v1/me/settings` returns `settings.mcp`. `PATCH /v1/me/settings` replaces that map when supplied; omission preserves it and `{}` clears it:

```json
{"mcp":{"calendar":{"enabled":true,"description":"Calendar events","url":"https://calendar.example.com/mcp"}}}
```

There are at most 64 servers. IDs match `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`; whitespace-normalized descriptions contain 1–240 characters. HTTP(S) URLs cannot contain credentials or fragments. Settings retain trimmed URLs, and both settings and transport validation use OkHttp parsing. Configuration is owner-scoped and captured for each execution. Legacy command-only entries are never launched; enabled legacy HTTP entries without descriptions use their IDs. MCP has no authentication fields or OAuth flow. `enabledTools` continues to govern compiled tools and JVM plugins independently.

`MCP_PRIVATE_ENDPOINT_ALLOWLIST` is an operator-controlled comma-separated list of complete URLs. An allowlisted host accepts only the listed scheme, port, path and query, after URL normalization, and trusts its DNS answers. The default allowlist is empty; user settings cannot change it.

## Discovery

After history input and memory recall, `AgentToolServerSource` feeds server descriptions into the Skill classifier chain: primary classifier, failure fallback classifier, then execution LLM. A valid empty selection stays empty. Selected servers preload within one 10-second work deadline, followed by bounded cleanup; every enabled server remains explicitly discoverable.

The effective system prompt contains escaped server IDs and bounded descriptions, plus at most 20 loaded-tool hints. Schemas require discovery. All servers also contribute complexity-routing hints. The configured system prompt stays unchanged, the light route remains tool-free, and queued input reuses the active execution catalogue.

- `LoadMcpTools(serverId, offset?, limit?)` initializes and lists a server's tools. It returns `tools`, `totalTools` and nullable `nextOffset`; repeated loads reuse the session and successful empty lists.
- `GetSkillByName(skillId)` returns an SDK-provided schema. `RunSkillCommand` sends arguments to `tools/call`. A successful listing is immediately available to both in the same run.
- `GetSkillsNamesByCategory` and `GetSkillsByCategory` page one filtered catalogue snapshot, returning `totalSkills` and nullable `nextOffset`. Details retain per-entry errors; an oversized entry advances with guidance to use exact lookup.

All three paging tools accept a nonnegative `Int` offset and a limit of 1–50 (default 20), rejecting strings, fractions, nulls and overflow. Pages reserve 512 bytes for their envelope within a 16 KiB budget. Category offsets span the complete mixed catalogue independently of the MCP tool limit.

MCP Skills use collision-free encoded IDs in the reserved `mcp.` namespace. Client declarations and file-backed Skills cannot claim those IDs; client declarations also reserve the loader. Children receive only selected immutable tool snapshots, without the loader. Catalogue composition is cached until client or MCP discovery changes membership.

## Transport and limits

`ProviderHttpClients.mcp` is an explicitly supplied, host-owned transport. It rejects private/reserved literal destinations and every non-public DNS answer, including mixed answers, unless operator-allowlisted. It uses no proxy, redirects, or connection-failure retries. Requests have a maximum 60-second operation deadline; tools are never automatically replayed.

Before SDK buffering, each response (JSON or SSE connection) is limited to 1 MiB and JSON nesting to 64. Only UTF-8 and identity encoding are accepted. Streamable HTTP accepts JSON or SSE protocol responses; `listTools` and `callTool` await complete results, independently of LLM token streaming.

Discovery rejects duplicate names, repeated cursors and more than 100 pages. An execution admits at most 10,000 tools and 4 MiB of retained metadata across servers. A listing consumes the remaining execution budget and publishes nothing on failure. Names contain 1–128 UTF-8 bytes without controls; descriptions contain at most 2,000 characters and 4,000 JSON bytes; each schema is at most 16 KiB.

SDK 0.16.0 preserves schema-root `type`, `properties`, `required`, `$schema` and `$defs`; other root keywords, including `additionalProperties`, are omitted by its decoder. Nested schemas retain their keywords. `SkillToolSchemas` preserves this JSON without narrowing it to compiled-tool parameter DTOs. The remote server validates arguments.

## Results and cleanup

Result JSON passes directly from the SDK to message content, preserving content blocks, structured content, metadata and `isError`. Tool-level failures remain results. Adapter-owned timeouts map to `mcp_timeout`; other failures map to `mcp_request_failed`. Their remote outcome may be unknown. Diagnostics include server, operation and failure type, excluding endpoint URLs, arguments and remote error text. Owner identity is checked before invocation; caller cancellation propagates.

Execution finalization closes sessions after child cleanup, even on cancellation or failure. All admitted sessions close concurrently outside the discovery mutex. DELETE and protocol notifications, including SDK cancellation notifications, have independent deadlines of at most 500 ms. Session termination has a 500 ms bound; local closure always follows with a one-second bound. Servers without session IDs need no DELETE; SDK termination accepts HTTP 405. Closing sessions leaves the borrowed HTTP client open; backend shutdown closes it after application work stops.

## Verification

Run `./gradlew :agent:test :sharedLogic:test :backend:test`. MCP tests exercise the SDK, real HTTP wire guards, owner boundaries, atomic discovery, paging, and deadlines. Backend scenarios use production DI and PostgreSQL with external LLM/classifier/HTTP fixtures.
