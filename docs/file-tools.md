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
- Works only on plain text, code, and config files that are valid UTF-8 text.
- Uses exact string replacement, not a caller-supplied patch.
- Input is:
  - `path`
  - `oldString`
  - `newString`
  - optional `replaceAll`
- Validation happens before any safe-mode permission prompt.
- Fails when:
  - `oldString` is empty
  - `oldString` and `newString` are identical
  - `oldString` is not found in the current file
  - `oldString` matches multiple locations and `replaceAll` is `false`
  - the file is not an editable text/code/config file
- The tool normalizes line endings internally for matching, preserves untouched line endings exactly, and uses the file's detected separator as a fallback for newly inserted lines.
- Outside safe mode, the tool writes immediately after validation.
- In safe mode, the tool does not write immediately. Each `EditFile` call is staged as a pending edit and returns `"Staged, not yet applied"`.
- Later `EditFile` calls in the same run see the staged virtual file state, not just the on-disk file state.
- Safe-mode review happens after the agent finishes tool use. The UI shows each staged `EditFile` call with its own diff preview and lets the user apply or discard selected changes.
- Selected edits are replayed in original order during the final apply step. If a selected edit no longer matches because an earlier staged edit was discarded, that edit is skipped and reported as a conflict.
- Before final apply, each touched file is reread from disk. If the file changed externally after staging started, pending edits for that file are skipped and reported as external conflicts.
- Writes use a temp file plus atomic-move fallback instead of invoking the system `patch` binary.

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
