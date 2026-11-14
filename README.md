# Souz

[Website](https://souz.app) · [Releases](https://github.com/D00mch/souz/releases) · [Contributing](docs/CONTRIBUTING.md)

Souz is a Kotlin Multiplatform AI assistant with a Compose Desktop app and a Ktor HTTP backend. Shared graph agents use local and cloud models, scoped memory, Skills, and sandbox-aware tools. Desktop adds user approvals, OS integrations, voice and ambient interaction; the backend owns PostgreSQL-backed conversations and client integrations.

## Installation

```bash
brew tap D00mch/tap
brew install --cask souz-ai
```

Or download the latest build from [GitHub Releases](https://github.com/D00mch/souz/releases).

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

See the [module map and dependency boundaries](AGENTS.md#module-map), [agent architecture](agent/README.md), and [runtime setup](sharedLogic/README.md).

Desktop UI and OS integrations stay in their owning modules; shared runtime code remains usable by the headless backend. See [desktop composition](desktopApp/AGENTS.md), [shared UI](sharedUI/AGENTS.md), [local models](native/docs/model-assets-and-context-budget.md), [desktop memory and chat](sharedUI/docs/chat-execution.md), and [desktop MCP](sharedLogic/src/jvmMain/kotlin/ru/souz/service/mcp/README.md).

## Backend behavior

Keep credential-free Client-Souz routes on a trusted network. See the [public contract](docs/public-souz-contract/README.md), [trusted proxy](backend/docs/trusted-proxy.md), and [distributed deployment boundary](backend/docs/distributed-backend-boundary.md).

Backend startup requires `SOUZ_MASTER_KEY`. Protected routes require a shared `SOUZ_BACKEND_PROXY_TOKEN`. Keep the Compose secrets for local development only. `POSTGRES_DSN` overrides the database address and name. Supply database credentials separately. See [backend configuration](backend/src/main/kotlin/ru/souz/backend/app/BackendAppConfig.kt) for defaults and validation.

`SOUZ_BACKEND_SYSTEM_PROMPT` sets the default system prompt. `SOUZ_BACKEND_POLICY_PROMPT` appends operator instructions even when a user or request supplies its own prompt.

Backend logs are one-line JSON on stdout. See [Logback configuration](backend/src/main/resources/logback.xml) for root and application log levels.

For bot setup, see [Telegram bindings](backend/docs/telegram-bindings.md#setup) and [VK bindings](backend/docs/vk-bindings.md#setup).

## Development and verification

To run without Compose, start PostgreSQL, configure backend secrets, then run `./gradlew :backend:run` with JDK 21. Build a local distribution with `./gradlew :backend:installDist`. [Verification](AGENTS.md#verification) · [Quality gates](docs/quality-gates.md) · [Backend image build](scripts/build-backend-image.sh)

Run the desktop app with `./gradlew :desktopApp:run`. Run its UI tests with `./gradlew :sharedUI:jvmTest`. For native bridge builds, see [bridge packaging](native/docs/bridge-build-and-packaging.md). For macOS packaging and releases, see [release guidance](docs/release.md) and the scripts below.

```bash
scripts/kmp-build-macos-universal.sh
scripts/kmp-build-macos-dev.sh
scripts/prepare-homebrew-release.sh
```

## Operational references

- [WebSocket ownership](backend/docs/public-client-websocket.md) and [subagents](docs/subagents.md)
- [Backend testing](backend/docs/testing.md)
- [External memory](backend/docs/external-memory.md), [conversation Knowledge](backend/docs/conversation-knowledge.md), and [personality settings](backend/docs/personality-settings.md)
- [Scheduled tasks](backend/docs/scheduled-tasks.md), [workspace hooks](docs/hooks.md), and [file tools](docs/file-tools.md)
- [S3 file storage](backend/docs/s3-storage.md) and [Prometheus metrics](backend/docs/metrics.md)

Read the owning `AGENTS.md` before changing a module.

## License

Copyright © 2026 Artur Dumchev and Shamil Khizriev

This program and the accompanying materials are made available under the terms of the Eclipse Public License 2.0 which is available at http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary Licenses when the conditions for such availability set forth in the Eclipse Public License, v. 2.0 are satisfied: GNU General Public License as published by the Free Software Foundation, either version 2 of the License, or (at your option) any later version, with the GNU Classpath Exception which is available at https://www.gnu.org/software/classpath/license.html.
