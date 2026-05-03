# Runtime Module

The `:runtime` module contains shared JVM runtime infrastructure reused by both the desktop app (`:composeApp`) and the HTTP backend (`:backend`).

It is responsible for:

- provider clients and shared LLM runtime wiring;
- settings and config access;
- backend-safe tool implementations;
- skill bundle loading and validation storage;
- sandbox-aware filesystem and command execution contracts.

## Sandbox modes

`RuntimeSandboxFactory` selects the active sandbox mode from `SOUZ_SANDBOX_MODE`.

- `local`: default mode when `SOUZ_SANDBOX_MODE` is unset.
- `docker`: opt-in mode backed by `DockerRuntimeSandbox`.

If you just want the app to start locally, use local mode:

```zsh
unset SOUZ_SANDBOX_MODE
# or
export SOUZ_SANDBOX_MODE=local
```

## Docker sandbox image

Docker mode expects this image to exist locally:

```text
souz-runtime-sandbox:latest
```

Build it from the repository root with:

```zsh
docker build -t souz-runtime-sandbox:latest runtime/src/test/resources/docker/runtime-sandbox
```

Then start the app with Docker sandboxing enabled:

```zsh
export SOUZ_SANDBOX_MODE=docker
./gradlew :composeApp:jvmRun
```

The Docker image used by the runtime is intentionally minimal. Its Dockerfile currently lives at `runtime/src/test/resources/docker/runtime-sandbox/Dockerfile` and provides `bash`, `python3`, and Node.js inside the sandbox container.

## Troubleshooting

If startup fails with an error like:

```text
Docker sandbox image 'souz-runtime-sandbox:latest' is unavailable.
```

then either:

1. switch back to local mode; or
2. build the Docker image shown above before running with `SOUZ_SANDBOX_MODE=docker`.

If Docker mode appears unexpectedly, check your shell profile, IDE run configuration, or any launcher script that may be exporting `SOUZ_SANDBOX_MODE=docker`.

## Runtime-safe tools

The `:runtime` module hosts the backend-safe tool catalog reused by the backend and desktop runtime wiring:

- files;
- web search and research;
- config;
- data analytics;
- calculator.

Desktop-only integrations such as Mail, Calendar, Notes, browser control, Telegram, and other OS-bound tools stay in `:composeApp`.
