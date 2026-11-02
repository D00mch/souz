# Images

## Model input

The execution LLM boundary resolves owned `FileContent` image references into request-local image blocks. OpenAI-compatible, Codex and Anthropic adapters support them. It selects at most five images from user messages and the trailing group of tool results, in message/file order. Tool images use a synthetic user message after the tool results. Stored messages, sessions and summaries retain references, never pixels or synthetic messages. Missing, expired, unsupported, damaged or oversized images produce an availability note instead of aborting the text turn. Unsupported providers receive metadata and an explicit vision-unavailable note. Cancellation propagates.

`ViewImage(imagePath | imagePaths, question)` uses the same chat model route, credentials, retries and accounting. Sandbox access is checked before reading pixels. PNG, JPEG and WebP sources are limited to 20 MB each, 40 million pixels and 16,384 pixels per edge. Before decoding, `ImageSource.read` also limits the estimated full-source raster to 64 MiB, accounting for channel count, bit depth and palette expansion. This check precedes subsampling because decoders can allocate full-sized internal buffers. At most two images per process are decoded/resized concurrently on the CPU dispatcher; this is a raster limit, not a total JVM heap budget. Accepted images must decode successfully, without decoder warnings. Decoded rasters are released before returning; only original bytes and an optional encoded vision projection remain request-local.

Vision uses decoder subsampling followed by resizing to a maximum 2,048-pixel edge; editing uses the original bytes. The backend uses headless ImageIO with a WebP decoder, without local inference or native image services.

## Generation and editing

`SOUZ_IMAGE_MODEL` / `souz.image.model` selects the shared image model for both tools; the default is `gpt-image-2.5-sunburst`. Direct OpenAI image IDs from the adapter's [supported model set](../../sharedLogic/src/main/kotlin/ru/souz/llms/openai/OpenAIImageGenerationGateway.kt), including supported dated snapshots, use the existing OpenAI credentials. A Codex model identity such as `CodexGpt61Sol` selects the Codex OAuth connection. A name from [named connections](named-llm-connections.md) uses that connection's endpoint, API key and exact model ID; compatible deployments can use IDs outside the built-in set. The endpoint must implement the OpenAI images API. There is no separate provider switch.

The image route is resolved once before HTTP startup. Unknown, blank or retired built-in selections (including `gpt-image-1`) reject startup with a configuration error. Validation does not call a provider: account access and credentials are checked when generation is requested. The model, PNG/1024×1024/medium output defaults and prompt-length limit have one owner in [image contracts](../../sharedLogic/src/main/kotlin/ru/souz/llms/runtime/ImageCapabilities.kt).

- `GenerateImage(prompt, outputPath?, imagePaths?)` saves its result in the caller's sandbox.
- `GenerateAttachmentImage(prompt, fileIds?)` requires S3 and saves an owned image reference. It does not select a reply attachment; `AttachFileToReply` performs that separate action.

Both accept up to five ordered source images for editing, use the same gateway and preserve original source bytes. Source ownership, availability and bounded decodability are checked before claiming an attempt or calling the provider. Generated images use the same validation before saving. Codex requires exactly one image and a completed stream; an interrupted stream is a failure even if it already contained pixels.

For attachment generation, a PostgreSQL receipt claims `(owner, execution, fingerprint)` before the provider call. The fingerprint includes the selected connection/model, normalized prompt and ordered source IDs/hashes. A repeated successful request in the same execution returns its saved file. An attempt without a saved result returns `image_previously_attempted`, including after restart: no automatic repeat of an uncertain paid operation. Receipts are deleted with their execution; there are no background generation retries. Sandbox generation has no durable receipt.

## Client tool images

Existing Client-Souz `tool.result` accepts MCP `image` and image `resource_link` content. The backend validates and saves pixels before persisting or acknowledging the result, replacing them with an `attachment:` reference and canonical `FileContent`. For owned `attachment:` links, storage metadata determines the type: non-image links remain tool data and do not consume image limits. The backend removes client-provided internal `attachment` fields, including without S3; only validated images receive canonical file references. Failed tool results omit their images. A result allows five images and 20 MB total; invalid images fail the result with `tool_image_unavailable`.

HTTP(S) resources must resolve exclusively to public addresses. Downloads pin the checked DNS results, disable redirects/proxies/retries, and have a ten-second call timeout and bounded body. `attachment:` links must already belong to the caller. A result replay reuses the persisted outcome; it does not download again. Local files, private endpoints and arbitrary URL credentials are unsupported.

The references pass through existing Skill, composite-command and subagent results. This feature does not add attachment upload/delivery fields to the public WebSocket protocol or implement Telegram/VK transport.

## Verification

[ImageGatewayTest](../../sharedLogic/src/test/kotlin/ru/souz/llms/ImageGatewayTest.kt) covers malformed images, high-bit-depth raster budgets, concurrent decoding, resizing and provider serialization. [BackendImagesE2eTest](../src/test/kotlin/ru/souz/backend/e2e/BackendImagesE2eTest.kt) covers screenshot rejection before storage, invalid sources before paid-attempt admission, and continued text turns with unreadable images in history. Keep validation before persistence/admission, preserve reference-only history, and propagate cancellation. Run `./gradlew :sharedLogic:test --tests ru.souz.llms.ImageGatewayTest :backend:test --tests ru.souz.backend.e2e.BackendImagesE2eTest`; backend tests require PostgreSQL/MinIO Docker fixtures and stub the provider boundary, as described in [Testing](testing.md).
