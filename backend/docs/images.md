# Images

## Model input

Images let the assistant inspect user uploads, camera/screenshots and sandbox files. Persist only owned file references: pixels, automatic observations and availability notes belong to the current request. Explicit `ViewImage` answers remain ordinary tool results. Summaries never receive pixels.

`SOUZ_ATTACHMENTS_VISION_MODEL` optionally selects a named connection or built-in OpenAI, Codex or Anthropic model. It uses independent credentials/parameters, shares execution accounting and budgets, and stays outside text-model fallbacks. Analyze once before text retries; explicit calls to the vision route receive pixels directly. Reuse prepared pixels for retries of the same route. Without this setting, use the current model. `ViewImage(imagePath | imagePaths, question)` also works without S3.

Keep image order and file IDs aligned. Select at most five images from user messages and current tool results. Bound source context, include the current question once, and validate prepared requests against the server context budget. Unsupported, missing, expired, corrupt or oversized images become availability notes; cancellation and unexpected storage errors propagate.

PNG/JPEG/WebP sources allow 20 MB, 40 million pixels and 16,384 pixels per edge. Validate decodability before storage or paid admission, with a 64 MiB original-raster budget and two concurrent decodes. Vision normalizes JPEG EXIF orientation, limits edges to 2,048 pixels and respects provider encoded-size limits. Editing retains original bytes. Processing stays headless.

## Generation and editing

`SOUZ_IMAGE_MODEL` selects a supported OpenAI image model, named images connection or Codex selector. Default: `gpt-image-2.5-sunburst`, PNG, 1024×1024, medium. Both model settings reject invalid selections at startup; provider access is checked on invocation.

`GenerateImage(prompt, outputPath?, imagePaths?)` saves to the sandbox. `GenerateAttachmentImage(prompt, fileIds?)` requires S3 and returns an owned reference; only `AttachFileToReply` selects delivery. Both accept up to five ordered editing sources and require one valid output image. Codex additionally requires completed generation.

Claim an execution-scoped receipt before the paid call. Identical successful requests reuse their saved file; uncertain attempts return `image_previously_attempted`, including after restart. Never automatically retry them. Sandbox generation has no durable receipt.

## Client tool images

Normalize MCP images and image links into owned references before ACK and persistence. Storage metadata decides owned-link type; preserve non-image links and distrust client attachment metadata. Allow five images and 20 MB total per result. Downloads require public addresses, pinned DNS, bounded bodies and no redirects, proxies or retries.

Reserve each result once, then prepare it outside the socket receive loop and writer lock. Bound preparation jobs; ACK precedes caller completion. Every failed or cancelled reservation releases its waiter. File references survive Skills, composites and subagents; children never select the parent’s reply.

## Verification

Run `./gradlew :sharedLogic:test :backend:test`. [Image tests](../../sharedLogic/src/test/kotlin/ru/souz/llms/ImageGatewayTest.kt) cover decoding, orientation and provider payloads. [Backend workflows](../src/test/kotlin/ru/souz/backend/e2e/BackendImagesE2eTest.kt) cover ownership, paid receipts and socket concurrency; [vision routing](../src/test/kotlin/ru/souz/backend/llm/BackendExecutionLlmChatApiTest.kt) covers limits, budgets and fallback isolation. Backend fixtures require PostgreSQL/MinIO; provider responses are stubbed.
