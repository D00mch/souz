# Model assets and context budget

## Invariant

A chat profile's required download set includes its main GGUF, the linked local EmbeddingGemma profile, and a multimodal projector when the chat profile supports vision. Readiness, download prompts, and bulk download must use the same `requiredDownloadProfiles()` definition.

Projectors share the chat model's storage directory. `mmproj-F16.gguf` is the canonical download name; resolution also accepts the upstream BF16 name and the legacy aliases declared by each profile. Keep `visionProjectorCandidates` as the source of truth.

Context size is capped by the selected profile. Completion budget is reserved from tokenized prompt size, and multimodal generation recalculates it after `mtmd_tokenize` using the actual text-and-image token count.

## Why this is fragile

Checking only the main GGUF makes a model appear ready before embeddings or vision can work. Storing an auxiliary asset under its own profile ID prevents runtime discovery beside the chat model. Estimating a media prompt as text-only, or as zero tokens, can overflow the native context after images are expanded.

EmbeddingGemma query and document inputs use different prefixes; losing that distinction degrades retrieval without producing a hard error.

## Safe changes

- Add or remove linked assets through profile metadata and keep download, availability, and model-selection UI consumers aligned.
- Preserve the shared `storageId` for projectors and update candidate aliases only when runtime resolution and tests change together.
- Preserve embedding query/document formatting and the embedding profile's own context cap.
- Apply settings-driven context size only within model limits. Leave room for at least one completion token and retain context-expansion retry bounds.
- For media requests, perform budgeting only after `mtmd` reports the effective prompt token count. Do not enable non-media prompt-prefix reuse for media without validating cache semantics.

## Verification

Run `./gradlew :native:test`. Cover missing linked assets, shared projector storage, canonical and compatibility projector names, query/document embedding prefixes, profile context caps, oversized prompts, and multimodal completion budgeting from actual `mtmd` token counts.
