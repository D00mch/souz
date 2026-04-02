## Project Structure
```text
ui/main/
├── CompactChatSearchPanel.kt          # Compact in-title-bar chat search panel UI and keyboard focus behavior
├── MainScreen.kt                      # Main screen composables and UI-to-event wiring
├── MainViewModel.kt                   # Main state/event/effect orchestration for the chat window
├── MainDTO.kt                         # MainState, MainEvent, MainEffect, chat DTOs, attachments, and chat search state
├── ChatInputWithQuickSettings.kt      # Chat input UI, model/context selectors, send/mic controls
├── ChatAttachmentUi.kt                # Attachment visuals (icons/colors), thumbnail decode, size formatting
├── ThinkingProcessPanel.kt            # Thinking/trace panel rendering from agent history
├── usecases/                          # Use-case layer for business logic behind the main screen
│   ├── MainUseCasesFactory.kt         # Builds and wires all use cases used by MainViewModel
│   ├── MainUseCaseOutput.kt           # Shared use-case output contract (state reducer/effect)
│   ├── ChatUseCase.kt                 # Agent execution, streaming updates, chat message lifecycle
│   ├── ChatSearchUseCase.kt           # Query/result calculation and navigation state for in-chat search
│   ├── ChatAttachmentsUseCase.kt      # Finder/file-drop integration and attachment metadata building
│   ├── FinderPathExtractor.kt         # Extracts/normalizes filesystem paths from model responses
│   ├── ToolModifyReviewUseCase.kt     # Deferred tool-modify review/approval flow for chat messages
│   ├── VoiceInputUseCase.kt           # Hotkey recording + speech recognition pipeline
│   ├── SpeechUseCase.kt               # Speech queue and `isSpeaking` state synchronization
│   ├── PermissionsUseCase.kt          # Onboarding + runtime approval orchestration (tool permissions + pluggable selection dialogs)
│   └── SpeechRecognitionProvider.kt   # Speech recognition interface + enabled/disabled providers
└── INFO.md                            # This file
```

Notes:
- Data flow is unidirectional: `MainScreen` sends `MainEvent` -> `MainViewModel` delegates to use cases -> use cases emit `MainUseCaseOutput` reducers/effects -> `MainState` updates.
- Tool/file approval flows are split by responsibility: `PermissionsUseCase` handles generic tool and selection approvals, while `ToolModifyReviewUseCase` owns deferred file-modification review state.
- In-chat search keeps query/result computation in `ChatSearchUseCase`; composables only render the compact title-bar panel and scroll/highlight the matched message container without modifying markdown content.
- To add a new user action, update `MainDTO.kt` (`MainEvent`), handle it in `MainViewModel.kt`, and keep domain logic in `usecases/` instead of composables.
- Main regression coverage for this package is in `composeApp/src/jvmTest/kotlin/ru/souz/ui/main/MainViewModelTest.kt`.
