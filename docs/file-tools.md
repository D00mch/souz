# File Tool Conventions

This document describes shared file-tool behavior and path conventions.

## Shared Path Rules

- `FilesToolUtil.applyDefaultEnvs()` expands leading `~`, `home`, `$HOME`, and `HOME`.
- Paths are validated against the canonical home directory and user-forbidden folders.
- Safety checks use canonical paths, so simple traversal/alias tricks are rejected.

## Canonical Souz File Roots

- Souz-specific roots are built from the user's documents folder:
  - `souzDocumentsDirectoryPath` -> `<documents>/souz`
  - `souzTelegramControlDirectoryPath` -> `<documents>/souz/telegram`
  - `souzWebAssetsDirectoryPath` -> `<documents>/souz/web_assets`

## Existing-File Path Normalization

- `FilesToolUtil.normalizeExistingFilePath()` is the shared path normalizer used by attachment-oriented flows.
- It trims whitespace, removes simple wrapping quotes, strips `file://`, expands `~`, and returns a canonical absolute path only if the target already exists as a regular file.
- Otherwise it returns `null`.
- Telegram attachment parsing reuses this helper so a file path can come either from an explicit parameter or from a path-like line embedded in message text.

## Tool Guarantees

### `ReadFile`

- Accepts any safe path that resolves to an existing regular file.
- Rejects directories and missing paths.
- Returns the entire file as text.
- If content exceeds `25000` characters, it returns a size-limit error instead of partial content.

### `ListFiles`

- Accepts any safe path that resolves to an existing directory.
- Walks the directory tree top-down from the requested base path.
- Skips forbidden directories and dot-prefixed entries.
- Returns an array-like string of absolute child paths rooted at the requested directory.
- Directories are suffixed with a trailing `/`.
- If the formatted result exceeds `25000` characters, it returns an explicit size-limit error.

### `NewFile`

- Creates either a text file or a directory.
- A trailing slash means "create a folder".
- File creation auto-creates missing parent directories.
- Refuses to overwrite an existing path.

### `EditFile`

- Works only on an existing safe file path.
- Applies a unified diff patch, not a whole-file replacement.
- Validation happens before any safe-mode permission prompt.
- Patch requirements:
  - standard unified diff headers (`---` and `+++`)
  - exactly one target file
  - target must match the requested path after `strip` handling
  - `strip` in `0..10`
- Supports common diff header forms including `a/` / `b/`, bare names, quoted paths, and absolute paths.
- Runs `patch --dry-run` first and only applies if the dry-run succeeds.
- Feeds patch text directly over stdin and rejects the internal `*** Begin Patch` wrapper format.
- In safe mode, the validated patch is sent through the permission broker, which allows the UI to show a patch preview before approval.

### `MoveFile`

- Moves only regular files, not directories.
- Both source and destination must be safe paths.
- Source and destination must differ.
- Destination must not already exist.
- Missing destination parent directories are created automatically.
- The move requests `ATOMIC_MOVE` first and falls back to a regular move if needed.

### `DeleteFile`

- Deletes by moving to Trash, not by unlinking in place.
- Works for both files and folders.
- Preferred path is the desktop-integrated Trash API when available.
- Fallback Trash targets are tried in this order:
  - `~/.Trash`
  - `~/.local/share/Trash/files`
  - `${java.io.tmpdir}/souz-trash`
- If a same-name item already exists in the fallback Trash target, the tool appends a timestamped suffix before moving.
