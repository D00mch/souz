# Souz

[Website](https://souz.app) · [Contributing](docs/CONTRIBUTING.md)

Souz is a backend-only Kotlin/JVM AI assistant. A Ktor HTTP server runs request-scoped graph agents over cloud LLM providers, sandboxed tools, Skills, and PostgreSQL-backed conversations.

## Local backend

Run the backend and PostgreSQL locally with Docker Compose:

```bash
docker compose up --build
```

Connect to the backend at `http://127.0.0.1:8080` and PostgreSQL at `127.0.0.1:5432`. For port overrides and local defaults, see [Compose](compose.yaml).

If the Compose network overlaps with VPN or corporate routes, set `SOUZ_DOCKER_SUBNET` to an unused private subnet before startup.

If a VPN route is reachable from the host but not from the backend container, run the backend with host networking:

```bash
docker compose -f compose.yaml -f compose.backend-host-network.yaml up --build
```

Use the Compose development token to call a protected route locally:

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

For server-managed Codex OAuth, configure `CODEX_ACCESS_TOKEN`, `CODEX_REFRESH_TOKEN`, `CODEX_ACCOUNT_ID`, and `CODEX_EXPIRES_AT` (Unix epoch seconds) together, then select a Codex alias such as `gpt-5.5` through the settings API. Preserve the `postgres-data` volume and the same `SOUZ_MASTER_KEY` across container replacements so the backend can read rotated credentials. See the [single-process restriction for Codex OAuth](backend/docs/distributed-backend-boundary.md#invariant).

For custom endpoints and server-managed model aliases, see [named LLM connections](backend/docs/named-llm-connections.md).

## Project structure

See the [module map and dependency boundaries](AGENTS.md#module-map), [agent architecture](agent/README.md), and [runtime setup](sharedLogic/README.md). The [backend capability policy](backend/src/main/kotlin/ru/souz/backend/common/BackendToolCapabilityPolicy.kt) controls which compiled tools are exposed.

## Backend behavior

Protected routes derive user identity from [trusted proxy headers](backend/docs/trusted-proxy.md). Keep credential-free [Client-Souz routes](docs/public-souz-contract/README.md) on a trusted network.

### Storage

PostgreSQL stores structured data and durable execution state. Sandbox workspaces are filesystem-backed. See the [distributed deployment boundary](backend/docs/distributed-backend-boundary.md) before running multiple replicas.

### Configuration

Backend startup requires `SOUZ_MASTER_KEY`. Protected routes require a shared `SOUZ_BACKEND_PROXY_TOKEN`. Keep the Compose secrets for local development only. `POSTGRES_DSN` overrides the database address and name. Supply database credentials separately. See [backend configuration](backend/src/main/kotlin/ru/souz/backend/app/BackendAppConfig.kt) for defaults and validation.

`SOUZ_BACKEND_SYSTEM_PROMPT` sets the default system prompt. `SOUZ_BACKEND_POLICY_PROMPT` appends operator instructions even when a user or request supplies its own prompt.

Backend logs are one-line JSON on stdout. See [Logback configuration](backend/src/main/resources/logback.xml) for root and application log levels.

For bot setup, see [Telegram bindings](backend/docs/telegram-bindings.md#setup) and [VK bindings](backend/docs/vk-bindings.md#setup).

## Development and verification

Use JDK 21 and the Gradle wrapper. PostgreSQL-backed backend, job, and OAuth tests fail when Docker is unavailable.

For `./gradlew :backend:run`, start PostgreSQL and configure the database connection and backend secrets first.

```bash
./gradlew :backend:run
./gradlew build :backend:installDist :build-logic:check --no-parallel
./gradlew test :koverXmlReport :koverHtmlReport koverLog -Psouz.coverage --no-parallel
./gradlew souzGateFast --configuration-cache --configuration-cache-problems=fail
npm ci --prefix quality
./gradlew souzDuplicationCheck
```

Runtime Docker sandbox tests are opt-in. See [runtime sandbox setup](sharedLogic/README.md#sandbox-modes). Build the backend image with [scripts/build-backend-image.sh](scripts/build-backend-image.sh).

## Operational references

- [WebSocket ownership](backend/docs/public-client-websocket.md) and [subagents](docs/subagents.md)
- [Backend testing](backend/docs/testing.md) and [quality gates](docs/quality-gates.md)
- [External memory](backend/docs/external-memory.md), [conversation Knowledge](backend/docs/conversation-knowledge.md), and [personality settings](backend/docs/personality-settings.md)
- [Scheduled tasks](backend/docs/scheduled-tasks.md), [workspace hooks](docs/hooks.md), and [file tools](docs/file-tools.md)
- [S3 file storage](backend/docs/s3-storage.md) and [Prometheus metrics](backend/docs/metrics.md)

Read the owning `AGENTS.md` before changing a module.

## License

Copyright © 2026 Artur Dumchev and Shamil Khizriev

This program and the accompanying materials are made available under the terms of the Eclipse Public License 2.0 which is available at http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary Licenses when the conditions for such availability set forth in the Eclipse Public License, v. 2.0 are satisfied: GNU General Public License as published by the Free Software Foundation, either version 2 of the License, or (at your option) any later version, with the GNU Classpath Exception which is available at https://www.gnu.org/software/classpath/license.html.
