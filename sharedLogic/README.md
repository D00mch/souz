# Shared Logic Module

`:sharedLogic` is the Kotlin Multiplatform runtime layer shared by the desktop app and HTTP backend.

Shared provider clients, settings and memory contracts, sandbox contracts, skill storage/loading, and portable tools live under `src/commonJvmMain`. Desktop/backend-only configuration, native local models, local and Docker sandboxes, MCP transports, speech services, and Office/PDF tooling live under `src/jvmMain`. OS-bound desktop services and tools remain in `:desktopApp`.

## Jev classification

`ru.souz.jev.JevClient` evaluates named yes/no questions through the [hosted TypeSafe API](https://api.typesafe.ai/docs)
and returns their probabilities. Shared runtime DI binds it lazily using the host-owned HTTP client.
Jev's fixed transport is OkHttp HTTP/1.1 with a shared pool, 60-second idle retention, and automatic
connection-failure retries disabled. Backend shutdown closes it after application work stops.
The measurement reference is [PR #852](https://github.com/D00mch/souz/pull/852).
The classic `GraphBasedAgent` can select multiple tool categories with Jev independently of its conversational
model. The skills graph, including backend conversations, selects relevant file-backed Skill descriptions with Jev and execution-LLM fallback; it does not classify tool categories. Inventory keeps every exact ID and adds only selected descriptions as bounded, escaped metadata. Full instructions and supporting files load on demand with approval where enabled.

`JevClassifier.classify` and `selectSkills` send the conversation as plain text built by `jevState`: the new user message first, then earlier
non-empty user and assistant messages newest first, each cut to its first 300 characters. Every category and Skill gets one
yes/no question naming it with its description; the shared `true` and `false` sides say that a step of the request
must need it and that a mentioned topic alone does not count. Categories use the classic graph's history window,
Skills the last two earlier messages. A whole message that is one of a fixed set of thanks, greeting or goodbye
phrases skips both selections: it gets no direct tools and no Skill descriptions. Confirmations such as "да" or "ок"
and fragments such as "тебе" are still classified, since they may approve an offered action or answer a question.

| Variable | Behavior |
| --- | --- |
| `SOUZ_CLASSIFIER` | Unset or `llm`: existing LLM classifier with regex fallback. `jev`: Jev classifier. |
| `JEV_TOKEN` | Required, nonblank Bearer credential for Jev and its live tests. |
| `JEV_MODEL` | Hosted model ID or alias; defaults to `jev-latest`. |
| `JEV_THRESHOLD` | Finite probability in `[0, 1]`; defaults to `0.5`. Categories and Skills must score strictly above it. |
| `FRIDA_URL` | Optional FRIDA-Decisions `POST /pooling` endpoint of its vLLM server; enables the FRIDA fallback. |
| `FRIDA_TOKEN` | Optional Bearer credential for `FRIDA_URL`. |
| `FRIDA_MODEL` | Optional served model name sent with each FRIDA request. |

Export `JEV_TOKEN` in the environment that starts Souz, then run:

```zsh
SOUZ_CLASSIFIER=jev JEV_MODEL=jev-latest JEV_THRESHOLD=0.5 ./gradlew :desktopApp:run
```

Each classifier request (Jev, FRIDA) times out after 2 seconds. With `FRIDA_URL` set, shared runtime DI binds
`FridaClassifier` under `FRIDA_FALLBACK_TAG`, and FRIDA answers whenever Jev fails: invalid configuration such as a
missing `JEV_TOKEN`, a timeout, a network or HTTP error (bad key, rate limit, no credit, outage), or an invalid response.
A low-confidence Jev answer is still an answer. Category selection runs Jev twice, then FRIDA, then regex; Skill selection
runs Jev, then FRIDA, then the current execution LLM, and keeps ID-only inventory when all of them fail. Cancellation
propagates. FRIDA asks one choice question over the same state and descriptions, with a `none` option for Skills; it
selects the top option plus every option at least 3 above the mean margin, and nothing when `none` wins.
Only enabled, nonempty categories are evaluated; an empty catalog makes no request. No matches or `HELP`
exposes all available tools.

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

`JevClient.choose(state, instructions, options)` asks one choice question and returns every option's probability.
`JevClassifier.isComplex` asks one Noul question: a `simple` probability of at least `0.9` admits a confident text-only answer requiring no tools. Attachments and current requests longer than 4,000 characters select complex without calling JEV. Requests of exactly 4,000 characters can still be classified in full; only reference history is truncated. Its structured state separates `current_request`, up to nine non-system messages in `previous_dialogue` (4,000 characters each, including tool results), and `available_capabilities` from prepared inventory. Past tasks supply references, not pending work; supplied successful memory results can supply facts. Uncertainty and missing facts select complex. Logs contain probabilities and counts, not dialogue. When Jev fails, the turn keeps its own model without asking FRIDA.

Optional `criteria` describe a question's `true` and `false` sides. Callers can pass
credentials and a model to `JevClient(http, token, model)`. The client validates probabilities
but leaves selection policy to callers and never closes the supplied transport.

Regular tests use mock HTTP responses. Run the opt-in hosted test with the same `JEV_TOKEN`:

```zsh
SOUZ_TEST_JEV=1 ./gradlew :sharedLogic:jvmTest --tests 'ru.souz.jev.JevIntegrationTest' --rerun
```

Description listing reads stored metadata or bounded, strict UTF-8 loose frontmatter and isolates malformed Skills. Exact lookup or invocation returns `skill_invalid_bundle` with the Skill ID, parsing problem, and repair guidance.

## Sandbox Modes

On JVM hosts, `DefaultRuntimeSandboxFactory` chooses the active sandbox with `SOUZ_SANDBOX_MODE`.

- `local`: default when `SOUZ_SANDBOX_MODE` is unset.
- `docker`: uses `DockerRuntimeSandbox` and the `souz-runtime-sandbox:latest` image.

Local mode is enough for normal development:

```zsh
unset SOUZ_SANDBOX_MODE
./gradlew :desktopApp:run
```

Docker mode runs sandboxed command execution and sandbox filesystem access through Docker:

```zsh
./gradlew :sharedLogic:buildRuntimeSandboxImage
SOUZ_SANDBOX_MODE=docker ./gradlew :desktopApp:run
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
SOUZ_TEST_DOCKER=1 ./gradlew :sharedLogic:jvmTest --tests 'ru.souz.runtime.sandbox.docker.*'
```

Those tests build `souz-runtime-sandbox:test` from `sharedLogic/Dockerfile` when needed. They verify:

- container startup
- `bash`, `python3`, and Node execution
- sandbox filesystem safety
- the seeded `paper-summarize-academic` skill can be discovered through `SkillRegistryRepository`

Run regular runtime tests without Docker:

```zsh
./gradlew :sharedLogic:jvmTest
```

## Skills Flow

`FileSystemSkillRegistryRepository` owns stored skill metadata, immutable bundle contents, and validation records.
Hosts that own a request-scoped catalog can install command, Knowledge, and memory tools through
`portableSkillRuntimeToolsDiModule`. The full `portableSkillToolsDiModule` includes those runtime tools plus
catalog-dependent Skill discovery and delegation. Filesystem-backed hosts opt into registry storage through
`fileSystemSkillRegistryDiModule`; general runtime DI installs none of these modules implicitly.
Hosts must also bind `ConversationKnowledgeStore`; see the [Knowledge storage contract](docs/runtime-sandbox-and-skills.md).

Skills use one host-local storage layout:

```text
{state}/skills/{skillId}/stored-skill.json
{state}/skills/{skillId}/bundles/{bundleHash}/...
{state}/skill-validations/{skillId}/policies/{policy}/{bundleHash}.json
```

The Docker image seeds the bundled paper skill directly into the registry-compatible desktop scope:

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
SOUZ_DOCKER_CLI=/opt/homebrew/bin/docker ./gradlew :sharedLogic:buildRuntimeSandboxImage
```

If a seeded skill does not appear, remove the per-sandbox `state/skills/<skill>` directory under `~/.local/state/souz/runtime-sandboxes/docker/` and restart the container. The entrypoint does not overwrite existing `stored-skill.json` metadata.

## Runtime-Safe Tools

`:sharedLogic` hosts the backend-safe tool catalog reused by backend and desktop wiring:

- files
- web search and research
- data analytics
- calculator

OS-bound desktop integrations such as browser control, Calendar, Mail, Notes, TDLight Telegram, app launch, text replacement, audio, permissions, and desktop automation live in `:desktopApp`. Backend wiring should continue to use only the backend-safe catalog and avoid instantiating desktop-only services.
