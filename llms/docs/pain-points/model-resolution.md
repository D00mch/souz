# Model resolution

## Invariants

The serialized default embeddings marker is exactly `Embeddings` and does not identify a provider. Raw model text is trimmed and resolved at input boundaries into explicit resolved, unknown, unsupported-provider, or ambiguous outcomes. Internal backend turn requests carry `LLMModel` rather than reparsing aliases.

An ambiguous embeddings alias prefers the configured embeddings model only when that model is one of the matching candidates. Unsupported configured providers, including Giga on backend, remain explicit failures.

## Safe changes

- Keep enum names available as unambiguous selectors when aliases overlap.
- Add aliases through the shared resolvers and cover whitespace and case normalization.
- Do not replace unknown, ambiguous, or unsupported selections with an unrelated fallback.

## Verification

Run `./gradlew :llms:test :backend:test` and cover every resolution outcome.
