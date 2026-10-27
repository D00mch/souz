# Backend plugins

Plugins coexist with filesystem Skills and LOCAL/DOCKER sandboxes. They are
trusted operator-installed Kotlin/JVM JARs and run without sandbox commands.
The [SDK contract](../../plugin-api/docs/plugin-contract.md) describes authoring,
storage, HTTP, tool schemas, and the saved-links example.

## Configuration

- `SOUZ_PLUGIN_DIRECTORY` / `souz.plugins.directory`: existing operator-owned
  installation directory. Omission disables plugin discovery.
- `SOUZ_PLUGIN_CACHE_DIRECTORY` / `souz.plugins.cacheDirectory`: durable cache;
  defaults to `.cache` beneath the installation directory. Mount it writable and
  keep it separate when the installation directory is read-only.

Keep both paths outside user workspaces. Publish `name.jar.part` using an atomic
rename to `name.jar`; changes are reconciled every five seconds. Activation failures
identify the installation path, attempted ID/version/digest when readable, stage,
and safe operator guidance. Cache integrity failures are isolated per artifact. The previous generation remains
available. Remove a JAR to retire it for new executions. Preserve cached artifacts
needed by waiting executions; a missing artifact fails the resume. Replicas scan
independently and need the relevant cache for any execution they resume.

## Availability and ownership

Every installed plugin is available to existing and new users by default.
`GET /v1/bootstrap` includes `capabilities.plugins` entries with `id`, `version`,
`tools`, and the user's `enabled` value. Existing settings read/patch operations
expose `disabledPlugins`, defaulting to `[]`. Patch it with plugin IDs to opt out;
an empty array re-enables all plugins. Opt-outs survive uninstall/reinstall.
Existing `enabledTools` continues to govern compiled tools only. Plugin categories
do not expand the compiled-tool allowlist.

Accepted executions snapshot enabled plugin generations into metadata and preserve
them across option waits. Setting changes and artifact updates affect subsequent
executions. The existing Skill inventory, lookup, invocation, and subagent catalog
work with plugin tool names. Omit `arguments` from `RunSkillCommand` to load only
the selected schema; provide `arguments` (including `{}`) to execute. Validation
returns up to 32 JSON Pointer errors and the expected schema without argument
values, before running the handler or making network requests.

PostgreSQL `plugin_data` stores `(user_id, plugin_id, record_key)` JSONB records.
Invocation identity comes from `ToolInvocationMeta`, never model arguments.
Queries use the application's datasource and bind both ownership keys. Plugin state
survives conversation deletion, disabling, and uninstall. User deletion cascades.
Atomic single-record updates serialize state transitions and claims across
executions, replicas, and plugin generations; callbacks perform no IO.

Plugin HTTP traffic uses a dedicated application-owned Ktor/OkHttp client with
engine-level DNS validation. The client has no provider credentials, default
authorization, shared cookies, proxy, redirects, or automatic retries. Literal IP
checks are performed before requests because numeric literals bypass DNS.

SDK 1.1 also exposes invocation-bound OAuth, sandbox file import/export, and
channel delivery. OAuth reuses the optional application gateway without exposing
tokens; its provider allowlist and shared per-user grants apply. Delivery uses
`ChannelProviderRegistry` with the authenticated owner. File operations resolve
the current user's LOCAL/DOCKER sandbox on every call. These services remain
available to installed plugins independently of compiled-tool preferences.
See the [Gmail migration](../../plugins/gmail/docs/gmail-workflow.md) for importing
Python connection state and replacing hook prompts.

## Verification

Run `./gradlew :plugin-runtime:test :plugins:saved-links:test :plugins:gmail:test :backend:test` with
Docker available. The production-wired plugin tests use real JAR loading and
PostgreSQL, substituting only the external HTTP/LLM boundaries.

## Invocation diagnostics

Durable `plugin.call.finished` events contain `invocationId`, `pluginId`, actual
`toolName`, `version`, `digest`, invocation `status`, optional domain `outcome` and
`errorCode`, and `durationMs`. Receipts cover parent and child calls and are
available in the existing event audit/replay APIs and public WebSocket stream.
They carry no arguments, response bodies, credentials, or stored values.
`status` is `succeeded`, `rejected` (schema validation), `failed`, or `cancelled`;
`outcome` is explicitly reported by the plugin through the SDK. A valid GitHub
429 response has `status:succeeded` and `outcome:rate_limited`. Generic
`RunSkillCommand` audit status continues to describe the enclosing tool dispatch.
No client ACK or `tool.result` is required for plugin receipts. Only public
`tool.call.started` requests client execution. Receipts do not finish the thread.
A process crash or persistence failure can interrupt publication after a plugin
side effect. Receipt failures log safe identifiers without changing the result or
masking cancellation; receipts are diagnostics, not an exactly-once guarantee.
