# Souz

[Website](https://souz.app) · [Contributing](docs/CONTRIBUTING.md)

Souz is a backend-only Kotlin/JVM AI assistant. A Ktor HTTP server runs request-scoped graph agents over cloud LLM providers, sandboxed tools, Skills, and PostgreSQL-backed conversations.

The backend supports public Client-Souz HTTP/WebSocket integrations, trusted-proxy APIs, event replay, streaming, cancellation, subagents, scheduled jobs, workspace hooks, Telegram and VK bot bindings, encrypted provider credentials, and optional Hindsight memory. Client tools and device capabilities are delegated through the external client contract.

## Local backend

Run the backend and PostgreSQL locally with Docker Compose:

```bash
docker compose up --build
```

The backend listens on `http://127.0.0.1:8080` and PostgreSQL listens on `127.0.0.1:5432`. Override host ports with `SOUZ_BACKEND_HOST_PORT` and `SOUZ_POSTGRES_HOST_PORT` when needed. Local defaults use `SOUZ_MASTER_KEY=local-dev-master-key` and `SOUZ_BACKEND_PROXY_TOKEN=local-dev-proxy-token`.

Prometheus scrapes `GET /metrics`; see [backend metrics](backend/docs/metrics.md) for access restrictions, accounting and queries.

Docker Compose uses `SOUZ_DOCKER_SUBNET`, defaulting to `10.254.250.0/24`, for the local service network. Set it to another private subnet before `docker compose up` if that range overlaps with VPN or corporate routes.

If a VPN route is reachable from the host but not from the backend container, run the backend with host networking:

```bash
docker compose -f compose.yaml -f compose.backend-host-network.yaml up --build
```

Protected `/v1` routes expect proxy-injected headers:

```bash
curl -H 'X-Souz-Proxy-Auth: local-dev-proxy-token' \
  -H 'X-User-Id: 00000000-0000-0000-0000-000000000001' \
  http://127.0.0.1:8080/v1/bootstrap
```

### Local backend model and key setup

The backend stores provider keys and the default chat model per trusted user. Start Docker Compose first, then set both through the `/v1` API with the same `X-User-Id`.

Store an OpenAI API key for the local user:

```bash
curl -X PUT http://127.0.0.1:8080/v1/me/provider-keys/openai \
  -H 'Content-Type: application/json' \
  -H 'X-Souz-Proxy-Auth: local-dev-proxy-token' \
  -H 'X-User-Id: 00000000-0000-0000-0000-000000000001' \
  -d '{"apiKey":"sk-..."}'
```

Set that user's default model to an OpenAI API model:

```bash
curl -X PATCH http://127.0.0.1:8080/v1/me/settings \
  -H 'Content-Type: application/json' \
  -H 'X-Souz-Proxy-Auth: local-dev-proxy-token' \
  -H 'X-User-Id: 00000000-0000-0000-0000-000000000001' \
  -d '{"defaultModel":"gpt-5.2"}'
```

Codex models use one server-managed OAuth session because the refresh token, account ID, and expiry must stay together. Configure `CODEX_ACCESS_TOKEN`, `CODEX_REFRESH_TOKEN`, `CODEX_ACCOUNT_ID`, and `CODEX_EXPIRES_AT` on the backend, then select a Codex alias such as `gpt-5.5` through the settings API. The backend stores rotated OAuth credentials in Postgres with application-layer encryption. Docker Compose persists them in `postgres-data`; preserve that data and use the same `SOUZ_MASTER_KEY` when recreating the backend container. The per-user provider-key endpoint does not accept `codex`.

## Project structure

| Module | Responsibility |
| --- | --- |
| `:backend` | Ktor HTTP/WebSocket host, application lifecycle, persistence composition, integrations |
| `:agent` | Skills graph, execution kernels, continuation, subagents, Skill discovery and approval |
| `:graph-engine` | Framework-free typed graph execution |
| `:llms` | Provider-neutral LLM contracts, model resolution, token accounting, state paths |
| `:sharedLogic` | Remote provider clients, runtime tools, Skills, local/Docker sandboxes |
| `:skill-oauth-api` | Provider-neutral Skill OAuth contracts |
| `:skill-oauth-impl` | OAuth providers, callback routes, encrypted PostgreSQL storage |
| `:job-api` | Owner-scoped job contracts |
| `:job-impl` | PostgreSQL scheduling, leased workers, retries |

`build-logic` is a separate included build for repository quality tooling. All product modules use Kotlin/JVM with standard `src/main` and `src/test` source sets and Java 21.

Each backend execution owns one steerable `SkillsGraphBasedAgent`. Its fixed core tools discover the enabled catalog through compact Skill inventory; full instructions and commands load on demand. `SpawnSubagent` creates an isolated bounded model/tool loop with explicitly selected capabilities. See [agent architecture](agent/README.md), [subagents](docs/subagents.md), and [runtime setup](sharedLogic/README.md).

Backend tools include filesystem operations, PDF/text extraction, image viewing and generation, CSV/Excel analytics, chart export, calculator, OAuth, channel messaging, and scheduling. Chart export uses JFreeChart in headless mode and produces 800×600 PNGs. The backend policy excludes compiled web tools; web search and device MCP capabilities come from client Skills.

Supported chat providers are Qwen, AiTunnel, Anthropic, OpenAI-compatible APIs, and Codex OAuth. Server-managed [named connections](backend/docs/named-llm-connections.md) support independent endpoints, credentials, model IDs, subagents, and summarization. Giga is explicitly unsupported by the backend.

## Backend behavior

- Most `/v1/**` routes trust identity only from proxy-managed headers:
  - `X-User-Id`
  - `X-Souz-Proxy-Auth`
- `X-User-Id` is treated as opaque and provisioned through `UserRepository.ensureUser(userId)`.
- `POST /v1/chats`, `GET /v1/ws`, `GET /v1/chats/{chatId}/ws`, and `GET /v1/chats/{chatId}/threads/{threadId}` are credential-free Client-Souz exceptions for trusted environments.
- HTTP chat creation accepts trusted UUID `userId` from the body; WebSocket `chat.create` accepts `payload.userId`. WebSocket `message.submit.payload.device.userId` must match the stored chat owner.
- Other request bodies are never trusted for user identity.
- Each chat, execution, option, and setting is scoped to the trusted user.
- The backend composes request-scoped execution from shared agent contracts and graph nodes.
- Backend turns run through one request-scoped steerable `SkillsGraphBasedAgent` under `AgentId.SKILLS_GRAPH`.

### Storage

PostgreSQL is the backend's only structured-data store. JDBC, HikariCP, and Flyway provide durable event replay, per-chat message/event sequence numbers, one active execution per chat, optimistic locking for `agent_conversation_state`, and durable tool-call audit rows.
Telegram bot tokens are encrypted at rest via `TELEGRAM_TOKEN_ENCRYPTION_KEY`, pending links use one-time `/start <secret>` commands with only the secret hash stored server-side, and binding setup drops pending Telegram updates before long polling starts.
VK community tokens use `VK_TOKEN_ENCRYPTION_KEY`. With `ENABLE_BACKEND_VK_FEATURE=true`, trusted-proxy clients manage bindings through `GET`, `PUT` (`{"token":"..."}`), and `DELETE /v1/chats/{chatId}/vk-bot`. Send the returned `pendingLinkCommand` as a private message to the community to link the account. Enable Bots Long Poll and `message_new` events in the community settings.
Runtime sandbox workspaces remain filesystem-backed and are independent from backend database persistence.

### Configuration

```bash
# Server
SOUZ_BACKEND_HOST=127.0.0.1
SOUZ_BACKEND_PORT=8080
SOUZ_BACKEND_PROXY_TOKEN=replace-with-shared-proxy-secret
SOUZ_MASTER_KEY=replace-with-settings-secret

# Server-managed provider keys, optional. Docker Compose local setup
# usually uses /v1/me/provider-keys/{provider} instead.
OPENAI_API_KEY=...
ANTHROPIC_API_KEY=...
QWEN_KEY=...
AITUNNEL_KEY=...
CODEX_ACCESS_TOKEN=...
CODEX_REFRESH_TOKEN=...
CODEX_ACCOUNT_ID=...
CODEX_EXPIRES_AT=... # Unix epoch seconds

# Feature flags
SOUZ_FEATURE_WS_EVENTS=true
SOUZ_FEATURE_WS_AUTOMATIC_MEMORY_RECALL=false
SOUZ_FEATURE_STREAMING_MESSAGES=true
SOUZ_FEATURE_TOOL_EVENTS=true
SOUZ_FEATURE_OPTIONS=true
ENABLE_BACKEND_TG_FEATURE=true

# Logging
# One-line JSON logs go to stdout.
LOG_LEVEL=INFO
SOUZ_APP_LOG_LEVEL=INFO

# Optional proxy-allowlisted User-Agent for outbound web tools.
SOUZ_WEB_USER_AGENT=ProxyApprovedClient/1.0

# Optional system prompts: the default one and operator rules appended to every chat's prompt.
SOUZ_BACKEND_SYSTEM_PROMPT=...
SOUZ_BACKEND_POLICY_PROMPT=...

# Telegram bot
SOUZ_TELEGRAM_POLLING_MAX_CONCURRENCY=4
# Generate once with: openssl rand -base64 32
TELEGRAM_TOKEN_ENCRYPTION_KEY=...

# VK bot
ENABLE_BACKEND_VK_FEATURE=false
SOUZ_VK_POLLING_MAX_CONCURRENCY=4
# Required when enabled; Base64 of 32 random bytes
VK_TOKEN_ENCRYPTION_KEY=...

# Scheduled tasks: planned runs per user per day, concurrent runs per process (one per user), run timeout
# The daily value also caps launches per user per UTC day.
SOUZ_SCHEDULED_TASK_RUNS_PER_USER_PER_DAY=100
SOUZ_SCHEDULED_TASK_CONCURRENT_RUNS=16
SOUZ_SCHEDULED_TASK_RUN_TIMEOUT_MILLIS=300000

# Provider retries
SOUZ_BACKEND_PROVIDER_MAX_429_RETRIES=2
SOUZ_BACKEND_PROVIDER_BACKOFF_BASE_MS=500
SOUZ_BACKEND_PROVIDER_BACKOFF_MAX_MS=5000

# Postgres
# Optional JDBC URL override for multi-host/TLS deployments.
POSTGRES_DSN=jdbc:postgresql://123.45.67.89:5432,123.45.67.890:5432:5432/souz_preprod?sslmode=require
SOUZ_BACKEND_DB_HOST=127.0.0.1
SOUZ_BACKEND_DB_PORT=5432
SOUZ_BACKEND_DB_NAME=souz
SOUZ_BACKEND_DB_USER=souz
SOUZ_BACKEND_DB_PASSWORD=...
SOUZ_BACKEND_DB_SCHEMA=public
SOUZ_BACKEND_DB_MAX_POOL_SIZE=10
SOUZ_BACKEND_DB_CONNECTION_TIMEOUT_MS=30000
```

The server host must not be blank, and the port must be between `1` and `65535`; invalid values fail configuration validation during startup. `POSTGRES_DSN` must be a PostgreSQL JDBC URL and, when set, replaces `SOUZ_BACKEND_DB_HOST`, `SOUZ_BACKEND_DB_PORT`, and `SOUZ_BACKEND_DB_NAME`; user and password still come from `SOUZ_BACKEND_DB_USER` and `SOUZ_BACKEND_DB_PASSWORD`. `SOUZ_MASTER_KEY` is required for backend startup. Backend Logback writes one-line JSON records to stdout, including timestamp, level, logger, thread, message, formatted message, MDC, SLF4J key-value pairs, and throwable details. `LOG_LEVEL` controls the root logger, while `SOUZ_APP_LOG_LEVEL` keeps Souz application diagnostics such as public WebSocket request flow visible even when third-party logging is quieter. `SOUZ_WEB_USER_AGENT` overrides the browser-like default sent by web tools, including the HTTPS CONNECT request when a JVM HTTP proxy is selected. `SOUZ_BACKEND_SYSTEM_PROMPT` replaces the built-in default system prompt, and a prompt from user settings or a request replaces it in turn. `SOUZ_BACKEND_POLICY_PROMPT` follows that prompt in every chat; users cannot replace or remove it through settings or requests. `TELEGRAM_TOKEN_ENCRYPTION_KEY` is required when the Telegram bot feature is enabled and must be Base64 that decodes to exactly 32 bytes; generate one with `openssl rand -base64 32`. Without `SOUZ_BACKEND_PROXY_TOKEN`, public routes remain available but `/v1/**` requests return `backend_misconfigured`.

Backend executions snapshot each user's effective `enabledTools`. The snapshot filters compiled tool-backed Skills once and is retained when an execution resumes from an option. Built-in Client-Souz Skills are merged afterward only for public client executions. The backend model sees only Skill core tools and reaches catalog capabilities through inventory, discovery, and `RunSkillCommand`.

Run the backend:

```bash
./gradlew :backend:run
```

By default it binds to `127.0.0.1:8080`.

## Development and verification

Use JDK 21 and the Gradle wrapper. Docker is required for PostgreSQL-backed backend, job, and OAuth tests; these tests fail rather than skip when Docker is unavailable.

```bash
./gradlew :backend:run
./gradlew build :backend:installDist :build-logic:check --no-parallel
./gradlew test :koverXmlReport :koverHtmlReport koverLog -Psouz.coverage --no-parallel
./gradlew souzGateFast --configuration-cache --configuration-cache-problems=fail
npm ci --prefix quality
./gradlew souzDuplicationCheck
```

Runtime Docker sandbox tests are separately opt-in; see [runtime sandbox setup](sharedLogic/README.md#sandbox-modes). Build the backend image with `scripts/build-backend-image.sh`.

Operational references:

- [Public client contract](docs/public-souz-contract/README.md) and [WebSocket ownership](backend/docs/public-client-websocket.md)
- [Backend testing](backend/docs/testing.md) and [quality gates](docs/quality-gates.md)
- [External memory](backend/docs/external-memory.md), [conversation Knowledge](backend/docs/conversation-knowledge.md), and [personality settings](backend/docs/personality-settings.md)
- [Scheduled tasks](backend/docs/scheduled-tasks.md), [workspace hooks](docs/hooks.md), and [file tools](docs/file-tools.md)
- [Telegram bindings](backend/docs/telegram-bindings.md), [VK bindings](backend/docs/vk-bindings.md), and [S3 file storage](backend/docs/s3-storage.md)
- [Metrics](backend/docs/metrics.md) and [trusted proxy](backend/docs/trusted-proxy.md)

Read the owning `AGENTS.md` before changing a module.

## License

Copyright © 2026 Artur Dumchev and Shamil Khizriev

This program and the accompanying materials are made available under the terms of the Eclipse Public License 2.0 which is available at http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary Licenses when the conditions for such availability set forth in the Eclipse Public License, v. 2.0 are satisfied: GNU General Public License as published by the Free Software Foundation, either version 2 of the License, or (at your option) any later version, with the GNU Classpath Exception which is available at https://www.gnu.org/software/classpath/license.html.
