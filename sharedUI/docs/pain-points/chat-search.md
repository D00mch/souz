# Chat search

## Invariant

Search offsets describe visible rendered text, not raw Markdown. User messages use one plain-text projection; assistant messages use separate Markdown and code-block parts so each rendered block can receive its own highlights. `MarkdownSearchTraversal` and `MarkdownSearchAnnotator` must implement the same flattening and offset rules.

The projection used to index a message is also the rendering contract for that message. Query text, matches, and active selection belong to `MainState.chatSearch`; the compact search panel owns only open/focus and keyboard presentation state.

## Why it is fragile

Markdown syntax, hidden nodes, whitespace, or code-block handling can change visible text length. If indexing and annotation parse the message differently, match ranges remain valid for one representation but highlight the wrong characters in another.

## Safe-change guidance

- Change traversal, annotation, and projection rules together.
- Reuse indexed projections during rendering instead of independently rebuilding searchable parts.
- Preserve case-insensitive, non-overlapping match discovery unless the product behavior is intentionally changed.
- Route query and navigation actions through the ViewModel rather than moving search state into composables.

## Verification

Run `./gradlew :sharedUI:jvmTest`. Cover projection shape, Markdown-visible offsets, code blocks, repeated matches, navigation, and message reindexing.
