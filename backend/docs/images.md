# Images

## Model input

Images let the assistant inspect user uploads, camera/screenshots and sandbox files. Persist only owned file references: pixels, automatic observations and availability notes belong to the current request. Explicit `ViewImage` answers remain ordinary tool results. Summaries never receive pixels.

`SOUZ_ATTACHMENTS_VISION_MODEL` optionally selects a named connection or built-in OpenAI, Codex or Anthropic model. It uses independent credentials/parameters, shares execution accounting and budgets, and stays outside text-model fallbacks. Analyze once before text retries; `ViewImage` dispatches pixels directly to that route. Reuse prepared pixels for retries of the same route. Without this setting, use the current model. KMP hosts retain local `ViewImage`: projected images are materialized as temporary native-input files and removed on completion or cancellation. Automatic attachment projection supports OpenAI, Codex and Anthropic; other chat providers receive availability notes. `ViewImage(imagePath | imagePaths, question)` also works without S3.

Keep image order and file IDs aligned. Select at most five images from user messages and current tool results. Bound source context, include the current question once, and validate prepared requests against the server context budget. Reserve 6,144 tokens per selected image before compaction; after projection count only supplied pixels. Reserve that space before copying dedicated-vision context; summaries reserve none. This allowance ignores base64 length and is not exact billing; configured models must fit it at 2,048 pixels (see [OpenAI](https://developers.openai.com/api/docs/guides/images-vision#calculating-costs) and [Claude](https://platform.claude.com/docs/en/build-with-claude/vision#image-limits-and-costs)). Unsupported, missing, expired, corrupt or oversized images become availability notes; cancellation and unexpected storage errors propagate.

PNG, JPEG and still WebP sources allow 20 MB, 40 million pixels and 16,384 pixels per edge. Validate decodability before storage or paid admission, with a 64 MiB original-raster budget and two concurrent decodes. Vision normalizes JPEG EXIF orientation and prepares one projection with edges at most 2,048 pixels and at most 5 MB of base64 data, shared by all providers. Five projections contain at most 25 MB of encoded images. Editing retains original bytes. WebP canvas and frame dimensions must agree before decoding. Processing stays headless.

At most two image requests per backend instance may load and retain sources or projections. Chat projection, attachment generation/editing, and sandbox `ViewImage`/`GenerateImage` share this capacity. Acquire capacity before storage or sandbox access and retain it through provider admission, retries, fallbacks, result storage and cancellation cleanup. Waiting requests retain only file references or sandbox paths. Streams prepare images during collection and hold capacity until collection exits; text-only chat requests and summaries bypass this limit.

## Generation and editing

`SOUZ_IMAGE_MODEL` selects a supported OpenAI image model, named images connection or Codex selector. Default: `gpt-image-2.5-sunburst`, PNG, 1024×1024, medium. Both model settings reject invalid selections at startup; provider access is checked on invocation.

`GenerateImage(prompt, outputPath?, imagePaths?)` saves to the sandbox. `GenerateAttachmentImage(prompt, fileIds?)` requires S3 and returns an owned reference; only `AttachFileToReply` selects delivery. Both accept up to five ordered editing sources and require one valid output image. Codex additionally requires completed generation.

Claim an execution-scoped receipt before the paid call. Identical successful requests reuse their saved file; uncertain attempts return `image_previously_attempted`, including after restart. Never automatically retry them. Sandbox generation has no durable receipt.

## Client tool images

Normalize MCP image blocks, embedded image resources and image links into owned references before ACK and persistence. Without S3, reject image content with `tool_image_unavailable`. Storage metadata decides owned-link type; preserve non-image links and distrust client attachment metadata. Allow 64 content blocks, 16 owned attachment links, five images and 20 MB of images per result. Resolve distinct attachment IDs in one owner-scoped query. Downloads require public addresses, pinned DNS, bounded bodies and no redirects, proxies or retries.

Reserve each result once, then prepare it outside the socket receive loop and writer lock. Allow 16 result jobs per socket and eight attachment preparations per backend instance. Preparation has 20 seconds and ACK sending has five seconds. Attachment SQL statements have a 10-second query timeout; their borrowed connections have a 15-second network timeout, restored before pool return. The same bounds protect paid-attempt claims. Cancellation and timeouts settle the caller without freeing capacity until underlying work exits. ACK precedes caller completion, including preparation timeout when the ACK can be sent. File references survive Skills, composites and subagents; children never select the parent’s reply.

## Verification

Run `./gradlew :llms:test :agent:test :sharedLogic:jvmTest :backend:test`. [Image tests](../../sharedLogic/src/test/kotlin/ru/souz/llms/ImageGatewayTest.kt) cover decoding, orientation and provider payloads. [Backend workflows](../src/test/kotlin/ru/souz/backend/e2e/BackendImagesE2eTest.kt) cover ownership, paid receipts and socket concurrency; [vision routing](../src/test/kotlin/ru/souz/backend/llm/BackendExecutionLlmChatApiTest.kt) covers limits, budgets and fallback isolation. Backend fixtures require PostgreSQL/MinIO; provider responses are stubbed.
