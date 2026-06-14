## Project Structure
```text
ui/main/
├── MainScreen.kt                      # Desktop main screen composables and UI-to-event wiring
├── ChatInputWithQuickSettings.kt      # Chat input UI, model/context selectors, send/mic controls
├── ChatAttachmentUi.kt                # Attachment visuals (icons/colors), thumbnail decode, size formatting
├── usecases/                          # Use-case layer for business logic behind the main screen
│   ├── DesktopAttachmentHostPorts.kt  # Swing/drop/thumbnail adapters for common attachment orchestration
│   ├── VoiceInputUseCase.kt           # Hotkey recording + speech recognition pipeline
└── AGENTS.md                          # This file
```

Notes:
- `MainViewModel`, `MainDTO`, `ChatUseCase`, shared DTOs, chat search, attachment orchestration, path extraction, speech queue state, permission orchestration, and tool-modify review logic live in `sharedUI/src/commonJvmMain/kotlin/ru/souz/ui/main`.
- Data flow is unidirectional: `MainScreen` sends `MainEvent` -> `MainViewModel` delegates to use cases -> use cases emit `MainUseCaseOutput` reducers/effects -> `MainState` updates.
- Tool/file approval flows are split by responsibility: `PermissionsUseCase` handles generic tool and selection approvals, while `ToolModifyReviewUseCase` owns deferred file-modification review state.
- Desktop speech recognition providers live in `:sharedLogic` under `ru.souz.service.speech`; `VoiceInputUseCase` stays in `jvmMain` and implements the common `VoiceInputController` port.
- To add a new user action, update `MainDTO.kt` (`MainEvent`), handle it in `MainViewModel.kt`, and keep domain logic in `usecases/` instead of composables.
- Main regression coverage for this package is in `sharedUI/src/jvmTest/kotlin/ru/souz/ui/main/MainViewModelTest.kt`.
