# Images

## Model input

User uploads, camera images, and screenshots use owner-bound file references. Keep pixels, automatic observations, and availability notes request-local. Summaries receive no pixels. `ViewImage(imagePath | imagePaths, question)` reads sandbox files without S3 and returns an ordinary tool result. [Attachment projection](../src/main/kotlin/ru/souz/backend/llm/AttachmentImages.kt)

`SOUZ_ATTACHMENTS_VISION_MODEL` selects a vision-capable [named connection](named-llm-connections.md) or built-in OpenAI, Codex, or Anthropic model. If unset, use the current model. The dedicated route uses its own credentials and parameters, shares execution budgets and accounting, and sends only its observations to text models and their fallbacks. Reuse the prepared input on retries of the same route. [Vision routing](../src/main/kotlin/ru/souz/backend/llm/BackendExecutionLlmChatApi.kt)

Preserve image order and file IDs. Select up to five images from user messages and current tool results. Reserve space before compaction and before copying dedicated vision context. Count only supplied pixels after projection and check the prepared request against the server context budget. Configured models must fit the per-image allowance at the maximum projection size. Unavailable or invalid images become request-local notes. Propagate cancellation and unexpected storage errors. [Request budget](../../llms/src/main/kotlin/ru/souz/llms/RequestBudget.kt)

Accept PNG, JPEG, and still WebP. Validate decodability before storage or paid admission. Keep decoding headless and bound input bytes, dimensions, original raster memory, and concurrency. Vision uses a shared, orientation-corrected projection. Editing keeps the original bytes. [Source validation and limits](../../sharedLogic/src/main/kotlin/ru/souz/llms/runtime/ImageSource.kt)

## Generation and editing

`SOUZ_IMAGE_MODEL` selects a supported OpenAI image model, named images connection, or Codex selector. Both image model settings validate selectors at startup and check provider access on invocation. [Selector validation](../src/main/kotlin/ru/souz/backend/llm/ImageModels.kt) · [Generation defaults](../../sharedLogic/src/main/kotlin/ru/souz/llms/runtime/ImageCapabilities.kt)

`GenerateImage(prompt, outputPath?, imagePaths?)` saves to the sandbox. `GenerateAttachmentImage(prompt, fileIds?)` requires S3 and returns an owned reference. Use `AttachFileToReply` to select delivery. Both accept up to five ordered editing sources and require one valid output image. Codex additionally requires completed generation. Claim a durable, execution-scoped receipt before paid attachment generation. Identical successful requests reuse the saved file. Uncertain attempts return `image_previously_attempted` even after restart. Never retry them automatically. Sandbox generation has no durable receipt. [Paid generation](../src/main/kotlin/ru/souz/backend/attachments/ImageGeneration.kt)

## Client tool images

Normalize MCP image blocks, embedded image resources, and image links into owned references before ACK and persistence. Without S3, reject image content with `tool_image_unavailable`. Resolve owned links using storage metadata, strip client-supplied attachment metadata, and preserve non-image links. Bound result content and downloads. Require pinned public addresses and disable redirects, proxies, and connection retries. [Client image handling](../src/main/kotlin/ru/souz/backend/client/ClientImages.kt)

Reserve each result once. Prepare outside the receive loop and writer lock, with bounded concurrency and deadlines. Keep storage and paid-claim SQL bounded. On timeout or cancellation, settle the caller while retaining capacity until work exits. Send the ACK before completing the caller when the socket permits. [Socket coordination](../src/main/kotlin/ru/souz/backend/http/routes/PublicClientConnection.kt) · [Reply selection](attachments.md#tools-and-selection)

## Verification

Run `./gradlew :llms:test :agent:test :sharedLogic:test :backend:test`. [Image tests](../../sharedLogic/src/test/kotlin/ru/souz/llms/ImageGatewayTest.kt) cover decoding, orientation and provider payloads. [Backend workflows](../src/test/kotlin/ru/souz/backend/e2e/BackendImagesE2eTest.kt) cover ownership, paid receipts and socket concurrency; [vision routing](../src/test/kotlin/ru/souz/backend/llm/BackendExecutionLlmChatApiTest.kt) covers limits, budgets and fallback isolation. Backend fixtures require PostgreSQL/MinIO; provider responses are stubbed.
