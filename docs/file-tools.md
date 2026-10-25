# File Tool Conventions

This document describes shared file-tool behavior and path conventions.

## Shared Path Rules

- `FilesToolUtil.applyDefaultEnvs()` resolves paths through the invocation's sandbox, including leading `~`, `home`, and `$HOME`.
- Paths are validated against the canonical home directory and user-forbidden folders.
- Safety checks use canonical paths, so simple traversal/alias tricks are rejected.

## Canonical Souz File Roots

- `souzDocumentsDirectoryPath` resolves the sandbox's Souz documents directory.

## Existing-File Path Normalization

- `FilesToolUtil.normalizeExistingFilePath()` is the shared path normalizer used by attachment-oriented flows.
- It trims whitespace, removes simple wrapping quotes, strips `file://`, expands `~`, and returns a canonical absolute path only if the target already exists as a regular file.
- Otherwise it returns `null`.

## Tool Guarantees

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
- Input and path validation happen before the write; backend execution policy controls permission to invoke the tool.
- Fails when:
  - `oldString` is empty
  - `oldString` and `newString` are identical
  - `oldString` is not found in the current file
  - `oldString` matches multiple locations and `replaceAll` is `false`
  - the file is not an editable text/code/config file
- The tool normalizes line endings internally for matching, preserves untouched line endings exactly, and uses the file's detected separator as a fallback for newly inserted lines.
- The tool writes immediately after validation.
- Before applying the prepared replacement, it rereads the file. If the file changed after preparation, it fails and asks the caller to read again rather than overwriting the external change.
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
- LOCAL sandbox Trash targets are tried in this order:
  - `~/.Trash`
  - `~/.local/share/Trash/files`
  - `${java.io.tmpdir}/souz-trash`
- If a same-name item already exists in the Trash target, the tool appends a timestamped suffix before moving.
