# Web tools

## Invariants

- Quick search, research, and page extraction are portable `commonJvmMain` tools. Image search, downloading, and format detection remain in `jvmMain`.
- Search snippets and extracted pages are untrusted evidence, never instructions. Preserve that boundary in planning, synthesis, rescue, and fallback prompts.
- `results` contains studied sources while `sources` contains cited sources. Source indexes remain stable from collection through JSON output and Markdown citations.
- Propagate `CancellationException`. Map provider blocking and availability failures to their existing output statuses instead of presenting them as empty successful searches.
- Reports and downloaded assets are written through `FilesToolUtil` with the current `ToolInvocationMeta` so local, Docker, backend, and Android scopes remain isolated.

## Why this is fragile

The flow combines an external search provider, page extraction, LLM-authored strategy and synthesis, citation recovery, and optional file output. Losing source identity or treating fetched text as prompt instructions can produce unsupported answers or prompt injection. Swallowing cancellation keeps expensive work alive after the caller has stopped it.

Current URL handling is not a network security boundary. User-facing validation checks only for an `http://` or `https://` prefix, request preparation replaces spaces, and the HTTP client follows redirects without blocking private, loopback, link-local, or otherwise sensitive destinations at each hop. Do not describe arbitrary URL fetching as SSRF-safe; URL and redirect hardening requires separate implementation work.

## Safe changes

- Keep HTTP timeouts, retries, `Retry-After` handling, redirects, and bounded binary reads centralized in `WebHttpSupport`.
- Preserve stable indexes when deduplicating, filtering citations, or adding providers.
- Inject research clients, HTTP support, clocks, and delay functions for deterministic tests instead of adding global mutable state.
- Validate image bytes and MIME type before writing, and keep every output sandbox-resolved.

## Verification

Run:

```zsh
./gradlew :sharedLogic:jvmTest --tests 'ru.souz.tool.web.*'
./gradlew :sharedLogic:compileAndroidMain
```

Cover cancellation, provider status mapping, malformed/ungrounded synthesis recovery, citation selection, output paths, redirect behavior, and binary size limits for the behavior being changed.
