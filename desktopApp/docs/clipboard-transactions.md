# Clipboard transactions

`TextUnderSelection`, `TextReplace`, and `GetClipboard` share one process-wide reentrant lock at their blocking desktop boundary. Ownership covers the complete clipboard snapshot, temporary marker or replacement text, native copy/paste, read/settle period, and restoration of the original transferable. Selection reads can call `GetClipboard` while holding ownership; independent agents and tool instances must wait, including ordinary reads that would otherwise expose temporary contents.

Suspend entry points use interruptible IO execution. Cancellation while waiting leaves the clipboard untouched; cancellation during a transaction restores the snapshot before releasing ownership. Once copy or paste dispatch is attempted, interruption of the native command or polling cannot bypass settling before restoration and release. Copy polls for completion for up to 240 ms; if interrupted before completion, it retains ownership for a further 240 ms. Paste settles for 70 ms. Native operations remain cooperative, and external applications can still change the OS clipboard independently.

Run `./gradlew :desktopApp:test --tests 'ru.souz.tool.textReplace.*'`. The regressions use an in-memory AWT clipboard and mocked native commands, covering overlapping copy/paste/read calls, failures, and cancellation without accessing the user's clipboard or sending keystrokes.
