# Runtime sandbox and skills

## Invariants

- Tools resolve a `RuntimeSandbox` from the current `ToolInvocationMeta`. The resolver maps invocation metadata to `SandboxScope` and may cache sandboxes by scope; tools must not cache a resolved path or sandbox for later users or conversations.
- `FileSystemSkillRegistryRepository` and `RunSkillCommand` must use the same `SkillStorageScope`. `SINGLE_USER` and `USER_SCOPED` have different bundle and validation paths.
- Skill metadata, immutable hash-addressed bundles, and validation records stay behind `SandboxFileSystem`. Bundle loading rejects escaping paths, symlinks, non-regular files, binary content, and invalid UTF-8.
- `RunSkillCommand` accepts only a skill activated for the current turn and keeps its script and working directory within that skill bundle.

## Why this is fragile

The same contracts back three different runtimes. JVM hosts select local or Docker mode; Android uses app-private filesystem roots and executes shell skills with POSIX `/system/bin/sh`. Android does not provide the Python or Node skill runtimes, and its `BASH` option is compatibility naming rather than a GNU Bash guarantee. A storage-scope mismatch makes an installed skill visible to activation but unavailable to command execution.

Docker mounts `/souz`, so bundled development skills live under `/opt/souz/skills` in the image and are seeded into registry-compatible state on startup. Seeding is non-overwriting: an existing skill record remains authoritative.

## Safe changes

- Pass `ToolInvocationMeta` through every file or command operation and resolve paths at the call boundary.
- Preserve path containment and bundle validation when adding repository or command features.
- Keep Android skill scripts POSIX-compatible unless Android explicitly gains another runtime.
- When changing skill layout or scope, update the repository, command tool, host DI wiring, Docker entrypoint, and tests together.

## Verification

Run:

```zsh
./gradlew :sharedLogic:jvmTest --tests 'ru.souz.runtime.sandbox.*' --tests 'ru.souz.skills.*' --tests 'ru.souz.tool.skills.*'
./gradlew :sharedLogic:compileAndroidMain
```

For Docker behavior, build the sandbox image and run the opt-in Docker tests described in the module `AGENTS.md`.
