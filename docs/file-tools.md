# File Tool Conventions

File tools resolve paths in the invocation's sandbox. Use `~`, `$HOME`, or `home` for its home directory. The sandbox checks effective paths against the workspace root and forbidden folders. [Path resolution and Souz documents directory](../sharedLogic/src/main/kotlin/ru/souz/runtime/files/FilesToolUtil.kt) · [Sandbox rules](../sharedLogic/docs/runtime-sandbox-and-skills.md)

The attachment path normalizer trims whitespace, unwraps simple quotes, strips `file://`, and expands `~`. It returns a canonical absolute path for an existing regular file and `null` otherwise. [Normalization](../sharedLogic/src/main/kotlin/ru/souz/runtime/files/FilesToolUtil.kt#L448)

## Tool Guarantees

- `ListFiles` walks a safe existing directory and returns a bracketed string of absolute child paths, with `/` after directories. LOCAL skips forbidden paths and dot-prefixed directories. DOCKER hides all dot-prefixed entries and rejects unsafe non-symlink descendants. Listings over 25,000 characters return an error. [Tool and example](../sharedLogic/src/main/kotlin/ru/souz/tool/files/ToolListFiles.kt) · [LOCAL](../sharedLogic/src/main/kotlin/ru/souz/runtime/sandbox/local/LocalSandboxFileSystem.kt) · [DOCKER](../sharedLogic/src/main/kotlin/ru/souz/runtime/sandbox/docker/DockerSandboxFileSystem.kt)
- `NewFile` creates a text file and any missing parent directories. A trailing slash creates a directory. Existing paths are rejected. [Tool and examples](../sharedLogic/src/main/kotlin/ru/souz/tool/files/ToolNewFile.kt)
- `EditFile` replaces exact text in an existing UTF-8 text, code, or config file. Empty or missing search text and no-op replacements fail. Multiple matches require `replaceAll`. Matching normalizes line endings and preserves untouched text. If the file changed after the edit was prepared, read it again and retry. The backend catalogue controls tool availability. [Tool and example](../sharedLogic/src/main/kotlin/ru/souz/tool/files/ToolModifyFile.kt) · [Replacement rules](../sharedLogic/src/main/kotlin/ru/souz/tool/files/ToolModifyFilePlanner.kt)
- `MoveFile` moves a regular file to a distinct, unused path and creates missing destination parents. [Tool and example](../sharedLogic/src/main/kotlin/ru/souz/tool/files/ToolMoveFile.kt)
- `DeleteFile` moves files and directories to the sandbox's Trash and adds a timestamp suffix on name collisions. [Tool and example](../sharedLogic/src/main/kotlin/ru/souz/tool/files/ToolDeleteFile.kt) · [LOCAL Trash locations](../sharedLogic/src/main/kotlin/ru/souz/runtime/sandbox/local/LocalSandboxFileSystem.kt)
