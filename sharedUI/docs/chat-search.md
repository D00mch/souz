# Chat search

## Invariant

Search offsets describe visible rendered text, not raw Markdown. User messages retain plain-text projections outside top-level GFM tables; their tables use Markdown projections. Assistant messages use separate Markdown and code-block parts so each rendered block can receive its own highlights. `MarkdownSearchTraversal` and `MarkdownSearchAnnotator` share indexed source-to-visible leaf ranges.

Desktop chat tables use the renderer's GFM cell AST and inline styling. Header cells define column counts, missing body cells are empty, and excess cells are ignored. Column widths are shared across rows; tables wider than the message area scroll horizontally. Search separates cells with tabs and rows with newlines, omits delimiter rows and cell-edge whitespace, and uses source offsets rather than callback order because individual cells can recompose independently.

Cell widths include padding and are capped at 1024 dp; longer content wraps. User table render sources include the complete message's reference definitions before the table, while the definitions remain literal in surrounding user text. Search indexes only the table and shifts its source offsets past those hidden definitions.

The renderer 0.27 custom hook consumes unknown nodes, including list items. `ChatMarkdownComponents` preserves recursive dispatch to the existing components for non-table nodes; retain that fallback when updating the renderer.

The projection used to index a message is also the rendering contract for that message. Query text, matches, and active selection belong to `MainState.chatSearch`; the compact search panel owns only open/focus and keyboard display state.

## Why it is fragile

Markdown syntax, hidden nodes, whitespace, or code-block handling can change visible text length. If indexing and annotation parse the message differently, match ranges remain valid for one representation but highlight the wrong characters in another.

## Safe-change guidance

- Change traversal, annotation, and projection rules together.
- Reuse indexed projections during rendering instead of independently rebuilding searchable parts.
- Preserve case-insensitive, non-overlapping match discovery unless the product behavior is intentionally changed.
- Route query and navigation actions through the ViewModel rather than moving search state into composables.

## Verification

Run `./gradlew :sharedUI:jvmTest`. Cover projection shape, Markdown-visible offsets, code blocks, repeated matches, navigation, and message reindexing.
