## Source Set Role

`sharedUI/src/commonJvmMain` is the Android/Desktop reusable UI logic layer. Put code here when it can run on Android and desktop JVM without AWT/Swing, desktop Compose window APIs, macOS/JNA, Telegram desktop runtime classes, `:native`, or desktop filesystem/mail/app-launch side effects.

Current common JVM responsibilities:
- ViewModel base classes, DTO/state/event/effect contracts, shared main/settings/setup/tool/memory/folder ViewModels, and model/key availability helpers.
- Host-port contracts for platform actions such as external links, path opening/picking/metadata, attachments, audio/speech, permission prompts, calendar listing, background indexing, support logs, Telegram settings, and local model UI orchestration.
- Portable chat/search/attachment/path/tool-review/memory logic and small shared primitives that do not impose a desktop or Android layout.

Keep in `jvmMain`:
- Desktop window chrome, AWT/Swing pickers and drag/drop `Transferable` adapters, Finder/open actions, macOS vibrancy, support-log archive/mail handoff, Telegram desktop bindings, local native model coordination, and desktop speech/global hotkey behavior.

Keep in `androidMain`:
- Android-specific Compose routes/screens that consume the common ViewModels and bind Android/no-op host-port implementations for desktop-only capabilities.

When adding Android behavior, prefer binding a no-op or Android-safe implementation of a common host port over adding Android checks inside shared ViewModels or composables.
