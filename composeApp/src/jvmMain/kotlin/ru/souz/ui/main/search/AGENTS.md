## Project Structure
```text
ui/main/search/
├── ChatSearchModels.kt                # Search state, match/range models, and per-message projection contracts
├── ChatSearchProjector.kt             # Projects chat messages into searchable plain-text / markdown / code parts
├── ChatSearchEngine.kt                # Query updates, reindexing, navigation, and case-insensitive match range discovery
├── ChatSearchHighlighting.kt          # Shared AnnotatedString helpers for plain text and code-block highlighting
├── MarkdownSearchTraversal.kt         # Converts rendered markdown content into visible searchable text with stable offsets
├── MarkdownSearchAnnotator.kt         # Markdown annotator that reapplies search highlights to rendered markdown leaves
├── CompactChatSearchPanel.kt          # Compact search UI, open/focus state, and keyboard navigation handling
└── AGENTS.md                          # This file
```

Notes:
- User messages are indexed as a single `PlainTextSearchPartProjection`; assistant messages are split into markdown text and code-block parts so highlighting can be rendered per visible block.
- Markdown match offsets are based on visible rendered text, not raw markdown source. If you change markdown flattening rules in `MarkdownSearchTraversal.kt`, keep `MarkdownSearchAnnotator.kt` in sync or highlights will drift from indexed matches.
- `ChatSearchEngine.findSearchMatchRanges` is case-insensitive and returns non-overlapping matches by advancing the cursor by `query.length` after each hit.
- `CompactChatSearchPanel` owns only presentation state (`isOpen`, focus/select-all activation, Enter/Shift+Enter/Escape handling). Query text, matches, and active result selection live in `MainState.chatSearch`.
- Search projections are meant to be reused between indexing and rendering. `MainViewModel` caches them for reindex/refresh flows, while `MainScreen` uses the matching projection to compute per-part highlight ranges.
- Regression coverage for this area lives in `composeApp/src/jvmTest/kotlin/ru/souz/ui/main/search/ChatSearchEngineTest.kt`, `composeApp/src/jvmTest/kotlin/ru/souz/ui/main/search/ChatSearchProjectorTest.kt`, `composeApp/src/jvmTest/kotlin/ru/souz/ui/main/usecases/ChatSearchUseCaseTest.kt`, and the chat-level integration checks in `composeApp/src/jvmTest/kotlin/ru/souz/ui/main/MainViewModelTest.kt`.
