## Project Structure

```text
tool/web/
├── ToolInternetSearch.kt                    # Short factual lookup tool; concise answer plus cited sources
├── ToolInternetResearch.kt                  # Deeper research tool; plans queries and produces long-form reports
├── ToolWebImageSearch.kt                    # Image discovery tool with optional sandbox-safe local downloads
├── ToolWebPageText.kt                       # Single-page text extraction by URL
├── internal/
│   ├── InternetSearchExecutor.kt            # Orchestrates quick/research collection, synthesis, and report export
│   ├── InternetSearchInternals.kt           # Facade over prompt, parsing, formatting, and support helpers
│   ├── InternetSearchSupport.kt             # Shared models, constants, fallback strategy, and localized failures
│   ├── InternetSearchPrompts.kt             # JSON-only LLM prompts and untrusted-source handling rules
│   ├── InternetSearchDraftParser.kt         # Recovers/sanitizes LLM strategy and synthesis drafts
│   ├── InternetSearchReportFormatter.kt     # Builds markdown output and optional saved report previews
│   ├── WebResearchSupport.kt                # DuckDuckGo search, page extraction, image candidate discovery, pacing
│   ├── WebHttpSupport.kt                    # Shared Ktor HTTP client, timeouts, retries, and binary limits
│   ├── WebImageDownloader.kt                # Raster image validation and FilesToolUtil-backed storage
│   ├── WebModels.kt                         # Web search/image result DTOs
│   └── WebToolSupport.kt                    # Web input validation and shared request headers
└── AGENTS.md                                # This file
```

## Implementation Notes

- This package is shared `:runtime` code used by desktop and backend. Keep it UI-free, backend-safe, and independent of desktop browser automation.
- Web tools are registered under `ToolCategory.WEB_SEARCH` by `RuntimeToolsFactory`; desktop registers the same four tools in `composeApp`'s `ToolsFactory`.
- `ToolInternetSearch` and `ToolInternetResearch` are thin public tool adapters. Both delegate to `InternetSearchExecutor` and return serialized `InternetSearchToolOutput`.
- `ToolWebPageText` delegates directly to `WebResearchClient.extractPageText`, which fetches HTML, removes script/style/noscript/svg nodes with Jsoup, normalizes whitespace, and clamps output length.
- `ToolWebImageSearch` delegates to `WebResearchClient.searchImages`; when `downloadImages=true`, `WebImageDownloader` validates raster MIME/extension with Tika and writes through `FilesToolUtil`.

## Search Flow

- Quick lookup (`InternetSearch`) validates the query, clamps `maxSources` to `2..5`, searches one query, collects up to 5 DuckDuckGo results, extracts up to `3_500` chars per page, then asks the configured chat model for a concise JSON answer.
- Deep research (`InternetResearch`) first asks the configured chat model for a JSON strategy with diverse search queries. If planning fails, it falls back to query variants from `InternetSearchSupport`.
- Research collection clamps sources to `1..16`, uses up to 6 search queries, asks DuckDuckGo for up to 8 results per query, deduplicates by normalized URL, and extracts up to `10_500` chars per page.
- Synthesis prompts wrap snippets and page text as untrusted evidence. Keep this boundary intact: source text must never be treated as instructions.
- Synthesis must be grounded by non-empty `answer` plus valid `usedSourceIndexes`. If the first LLM draft is malformed or ungrounded, the executor makes a rescue call; if that also fails, it returns a partial fallback digest.
- Output separates `results` (all studied sources) from `sources` (only cited source indexes). Keep indexes stable because markdown citations use `[1]`, `[2]`, etc.
- Long complete research reports over `8_000` inline chars are saved as `.md` under the sandbox-resolved Souz documents `internet_research` directory; the inline response becomes a preview with `reportFilePath`.

## Provider and HTTP Behavior

- Text search currently uses DuckDuckGo HTML endpoints (`duckduckgo.com/html/` then `html.duckduckgo.com/html/`) through `WebResearchClient.searchWeb`.
- Provider failures are classified as `PROVIDER_BLOCKED` for anti-bot/captcha/403/429 cases and `PROVIDER_UNAVAILABLE` for timeouts and 5xx-style failures.
- `WebResearchClient` paces requests per host by at least `1_200ms`; keep cancellation propagation explicit and do not swallow `CancellationException`.
- `WebHttpSupport` owns the shared Ktor CIO client, user agent, redirects, timeouts, retry policy, `Retry-After` handling, and binary read limits.
- Image search first queries Wikimedia Commons, then falls back to image candidates from DuckDuckGo result pages. It rejects document-like URLs and SVGs before returning candidates.

## Development Notes

- Prefer injecting `WebResearchClient`, `WebHttpSupport`, clocks, and sleep functions for tests instead of adding global state.
- Use `FilesToolUtil` plus `ToolInvocationMeta` for any file output so local and Docker sandbox modes keep working.
- Keep public tool contracts small; add helper behavior under `internal/` unless a new tool entry point is required.
- Existing regression coverage for this package lives in `composeApp/src/jvmTest/kotlin/ru/souz/tool/web/`.
