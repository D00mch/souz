# Shared Logic

`:sharedLogic` is the Kotlin/JVM runtime layer used by the backend. Production code and resources live under `src/main`; tests live under `src/test`.

It provides remote LLM clients, Skill storage and command execution, sandboxed filesystem access, and backend-safe runtime tools. Application composition and persistence ownership stay in `:backend`.

## Jev classification

`ru.souz.jev.JevClient` evaluates named yes/no questions through the [hosted TypeSafe API](https://api.typesafe.ai/docs)
and returns their probabilities. Shared runtime DI binds it lazily using the host-owned HTTP client.
Jev's fixed transport is OkHttp HTTP/1.1 with a shared pool, 60-second idle retention, and automatic
connection-failure retries disabled. Backend shutdown closes it after application work stops.
The measurement reference is [PR #852](https://github.com/D00mch/souz/pull/852).
The skills graph selects relevant file-backed Skill descriptions with Jev and execution-LLM fallback; it does not classify tool categories. Inventory keeps every exact ID and adds only selected descriptions as bounded, escaped metadata. Full instructions and supporting files load on demand with approval where enabled.

`JevClassifier` sends the conversation as plain text built by `jevState`: the new user message first, then earlier
non-empty user and assistant messages newest first, each cut to its first 300 characters. Each Skill gets one
yes/no question naming it with its description; the shared `true` and `false` sides say that a step of the request
must need it and that a mentioned topic alone does not count. Skill selection uses the last two earlier messages. A whole message that is one of a fixed set of thanks, greeting or goodbye
phrases skips Skill selection: it gets no selected Skill descriptions. Confirmations such as "да" or "ок"
and fragments such as "тебе" are still classified, since they may approve an offered action or answer a question.

Backend turn preparation uses one Jev request with independent `simple` and `skip_memory` Noul questions.
A simple reply requires probability at least 0.9; skipping recall requires at least 0.8. Input contains the
full current message and at most two previous nonempty messages truncated to 300 characters each,
with roles and available speaker labels. Attachments force complex routing. Ordinary conversation, continuations
and newly supplied personal facts can be answered without tools or recall; missing saved facts require
recall. Saving new information is independent. Classification failure uses conservative preparation.

| Variable | Behavior |
| --- | --- |
| `JEV_TOKEN` | Required, nonblank Bearer credential for Jev and its live tests. |
| `JEV_MODEL` | Hosted model ID or alias; defaults to `jev-latest`. |
| `JEV_THRESHOLD` | Finite probability in `[0, 1]`; defaults to `0.5`. Skills must score strictly above it. |
| `FRIDA_URL` | Optional FRIDA-Decisions `POST /pooling` endpoint of its vLLM server; enables the FRIDA fallback. |
| `FRIDA_TOKEN` | Optional Bearer credential for `FRIDA_URL`. |
| `FRIDA_MODEL` | Optional served model name sent with each FRIDA request. |

Export `JEV_TOKEN` in the environment that starts Souz, then run:

```zsh
JEV_MODEL=jev-latest JEV_THRESHOLD=0.5 ./gradlew :backend:run
```

Each classifier request (Jev, FRIDA) times out after 2 seconds. With `FRIDA_URL` set, shared runtime DI binds
`FridaClassifier` under `FRIDA_FALLBACK_TAG`, and FRIDA answers whenever Jev fails: invalid configuration such as a
missing `JEV_TOKEN`, a timeout, a network or HTTP error (bad key, rate limit, no credit, outage), or an invalid response.
A low-confidence Jev answer is still an answer. Skill selection
runs Jev, then FRIDA, then the current execution LLM, and keeps ID-only inventory when all of them fail. Cancellation
propagates. FRIDA asks one choice question over the same state and descriptions, with a `none` option for Skills; it
selects the top option plus every option at least 3 above the mean margin, and nothing when `none` wins.

Other shared-runtime callers can inject `JevClient` directly, without a graph or tool catalog:

```kotlin
import com.fasterxml.jackson.databind.JsonNode
import ru.souz.jev.JevClient
import ru.souz.llms.restJsonMapper

suspend fun needsCalendar(jev: JevClient, message: String): Double {
    return jev.evaluate(
        state = restJsonMapper.valueToTree<JsonNode>(message),
        questions = mapOf("calendar" to "Does this request require calendar access?"),
    ).getValue("calendar")
}
```

Optional `criteria` describe each question's `true` and `false` sides. Callers can pass
credentials and a model to `JevClient(http, token, model)`. The client validates probabilities
but leaves selection policy to callers and never closes the supplied transport.

Regular tests use mock HTTP responses. Run the opt-in hosted test with the same `JEV_TOKEN`:

```zsh
SOUZ_TEST_JEV=1 ./gradlew :sharedLogic:test --tests 'ru.souz.jev.JevIntegrationTest' --rerun-tasks
```

Description listing reads stored metadata or bounded, strict UTF-8 loose frontmatter and isolates malformed Skills. Exact lookup or invocation returns `skill_invalid_bundle` with the Skill ID, parsing problem, and repair guidance.

## Sandbox Modes

`DefaultRuntimeSandboxFactory` chooses the active sandbox with `SOUZ_SANDBOX_MODE`.

- `local`: default when `SOUZ_SANDBOX_MODE` is unset.
- `docker`: uses `DockerRuntimeSandbox` and the `souz-runtime-sandbox:latest` image.

Local mode is enough for normal development:

```zsh
unset SOUZ_SANDBOX_MODE
./gradlew :backend:run
```

Docker mode runs sandboxed command execution and sandbox filesystem access through Docker:

```zsh
./gradlew :sharedLogic:buildRuntimeSandboxImage
SOUZ_SANDBOX_MODE=docker ./gradlew :backend:run
```

The same image definition is used by local runs and Docker integration tests. The Dockerfile lives at:

```text
sharedLogic/Dockerfile
```

The default image name expected by the app is:

```text
souz-runtime-sandbox:latest
```

## Docker Image

The runtime sandbox image provides:

- `bash`
- `python3`
- Node.js
- a development copy of the `paper-summarize-academic` skill

The paper skill source lives under:

```text
sharedLogic/docker/skills/paper-summarize-academic/
```

At container startup, `sharedLogic/docker/entrypoint.sh` seeds bundled skills into the mounted sandbox state registry:

```text
/souz/state/skills/paper-summarize-academic/stored-skill.json
/souz/state/skills/paper-summarize-academic/bundles/{bundleHash}/...
```

In Docker mode, `~` resolves to `/souz/home` inside the container and to the per-sandbox host directory under:

```text
~/.local/state/souz/runtime-sandboxes/docker/
```

The bind mount covers `/souz`, so Docker image files copied directly to `/souz/...` would be hidden at runtime. Bundled image assets are kept under `/opt/souz/skills` and copied into the mounted sandbox state registry on startup.

## Testing Docker Sandbox

Docker integration tests are opt-in:

```zsh
SOUZ_TEST_DOCKER=1 ./gradlew :sharedLogic:test --tests 'ru.souz.runtime.sandbox.docker.*'
```

Those tests build `souz-runtime-sandbox:test` from `sharedLogic/Dockerfile` when needed. They verify:

- container startup
- `bash`, `python3`, and Node execution
- sandbox filesystem safety
- the seeded `paper-summarize-academic` skill can be discovered through `SkillRegistryRepository`

Run regular runtime tests without Docker:

```zsh
./gradlew :sharedLogic:test
```

## Skills Flow

`FileSystemSkillRegistryRepository` owns stored skill metadata, immutable bundle contents, and validation records.
The backend composes command, Knowledge, memory, Skill discovery, and delegation tools with its request-scoped catalog. Registry storage is opt-in through
`fileSystemSkillRegistryDiModule`; general runtime DI does not install it implicitly. The backend binds its PostgreSQL `ConversationKnowledgeStore`; see the [Knowledge storage contract](docs/runtime-sandbox-and-skills.md).

Skills use one host-local storage layout:

```text
{state}/skills/{skillId}/stored-skill.json
{state}/skills/{skillId}/bundles/{bundleHash}/...
{state}/skill-validations/{skillId}/policies/{policy}/{bundleHash}.json
```

The Docker image seeds the bundled paper skill directly into the registry-compatible sandbox state:

```text
/souz/state/skills/paper-summarize-academic/stored-skill.json
/souz/state/skills/paper-summarize-academic/bundles/{bundleHash}/...
```

The agent pipeline selects skills returned by:

```kotlin
SkillRegistryRepository.listSkills(userId)
```

The current runtime integration test covers that the Docker-seeded skill is visible through the registry.

## Troubleshooting

If Docker mode fails with:

```text
Docker sandbox image 'souz-runtime-sandbox:latest' is unavailable.
```

build the image:

```zsh
./gradlew :sharedLogic:buildRuntimeSandboxImage
```

If Docker mode appears unexpectedly, check your shell profile, IDE run configuration, or launch script for `SOUZ_SANDBOX_MODE=docker`.

If `:sharedLogic:buildRuntimeSandboxImage` cannot find Docker from Gradle, set the Docker CLI path explicitly:

```zsh
SOUZ_DOCKER_CLI=/path/to/docker ./gradlew :sharedLogic:buildRuntimeSandboxImage
```

If a seeded skill does not appear, remove the per-sandbox `state/skills/<skill>` directory under `~/.local/state/souz/runtime-sandboxes/docker/` and restart the container. The entrypoint does not overwrite existing `stored-skill.json` metadata.

## Runtime tools

`RuntimeToolsFactory` builds an immutable catalog of filesystem, PDF/text, analytics, calculator, and optional OAuth tools. Backend capability policy selects the advertised and executable subset; client web search is supplied separately through Client-Souz Skills. Execution-bound image tools use the current execution's LLM routing.

Chart export uses JFreeChart in headless mode. CSV/XLS/XLSX input, category aggregation, BAR/LINE/SCATTER/PIE chart types, 800×600 PNG output, and sandboxed input/output paths are shared across LOCAL and DOCKER execution. `openAfterCreate` is accepted for compatibility and does not open a GUI.
