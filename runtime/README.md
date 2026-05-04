# Runtime Module

`:runtime` contains the JVM runtime layer shared by the desktop KMP app (`:composeApp`) and the HTTP backend (`:backend`).

It owns provider clients, shared LLM runtime wiring, settings access, backend-safe tools, skill bundle storage/loading, and the sandbox contracts used by tools and skills.

## Sandbox Modes

`RuntimeSandboxFactory` chooses the active sandbox with `SOUZ_SANDBOX_MODE`.

- `local`: default when `SOUZ_SANDBOX_MODE` is unset.
- `docker`: uses `DockerRuntimeSandbox` and the `souz-runtime-sandbox:latest` image.

Local mode is enough for normal development:

```zsh
unset SOUZ_SANDBOX_MODE
./gradlew :composeApp:jvmRun
```

Docker mode runs sandboxed command execution and sandbox filesystem access through Docker:

```zsh
./gradlew :runtime:buildRuntimeSandboxImage
SOUZ_SANDBOX_MODE=docker ./gradlew :composeApp:jvmRun
```

The same image definition is used by local runs and Docker integration tests. The Dockerfile lives at:

```text
runtime/Dockerfile
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
runtime/docker/skills/paper-summarize-academic/
```

At container startup, `runtime/docker/entrypoint.sh` seeds bundled skills into the mounted sandbox state registry:

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
SOUZ_TEST_DOCKER=1 ./gradlew :runtime:test --tests 'ru.souz.runtime.sandbox.docker.*'
```

Those tests build `souz-runtime-sandbox:test` from `runtime/Dockerfile` when needed. They verify:

- container startup
- `bash`, `python3`, and Node execution
- sandbox filesystem safety
- the seeded `paper-summarize-academic` skill can be discovered through `SkillRegistryRepository`

Run regular runtime tests without Docker:

```zsh
./gradlew :runtime:test
```

## Skills Flow

`FileSystemSkillRegistryRepository` owns stored skill metadata, immutable bundle contents, and validation records. Its `FileSystemSkillRegistryConfig.scope` selects either desktop/local single-user storage or backend user-scoped storage.

Desktop and local Docker runtimes use the single-user skill storage scope. A stored skill lives under:

```text
{state}/skills/{skillId}/stored-skill.json
{state}/skills/{skillId}/bundles/{bundleHash}/...
{state}/skill-validations/{skillId}/policies/{policy}/{bundleHash}.json
```

The backend can still opt into the user-scoped storage scope for multi-user storage:

```text
{state}/skills/users/{encodedUserId}/skills/{skillId}/...
{state}/skill-validations/users/{encodedUserId}/skills/{skillId}/...
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
./gradlew :runtime:buildRuntimeSandboxImage
```

If Docker mode appears unexpectedly, check your shell profile, IDE run configuration, or launch script for `SOUZ_SANDBOX_MODE=docker`.

If `:runtime:buildRuntimeSandboxImage` cannot find Docker from Gradle, set the Docker CLI path explicitly:

```zsh
SOUZ_DOCKER_CLI=/opt/homebrew/bin/docker ./gradlew :runtime:buildRuntimeSandboxImage
```

If a seeded skill does not appear, remove the per-sandbox `state/skills/<skill>` directory under `~/.local/state/souz/runtime-sandboxes/docker/` and restart the container. The entrypoint does not overwrite existing `stored-skill.json` metadata.

## Runtime-Safe Tools

`:runtime` hosts the backend-safe tool catalog reused by backend and desktop wiring:

- files
- web search and research
- config
- data analytics
- calculator

Desktop-only integrations such as browser control, Calendar, Mail, Notes, Telegram, presentations, and OS automation stay in `:composeApp`.
