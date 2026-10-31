# Images

## Model input

The execution LLM boundary resolves owned `FileContent` image references into request-local image blocks. OpenAI-compatible, Codex and Anthropic adapters support them. It selects at most five images from user messages and the trailing group of tool results, in message/file order. Tool images use a synthetic user message after the tool results. Stored messages, sessions and summaries retain references, never pixels or synthetic messages. Missing or expired images produce an availability note; unsupported providers receive metadata and an explicit vision-unavailable note.

`ViewImage(imagePath | imagePaths, question)` uses the same chat model route, credentials, retries and accounting. Sandbox access is checked before reading pixels. PNG, JPEG and WebP sources are limited to 20 MB each, 40 million pixels and 16,384 pixels per edge. Vision scales images to a maximum 2,048-pixel edge; editing uses the original bytes. The backend uses headless ImageIO with a WebP decoder, without local inference or native image services.

## Generation and editing

`SOUZ_IMAGE_MODEL` / `souz.image.model` selects the shared image model for both tools; the default is `gpt-image-1`. A Codex model identity such as `CodexGpt61Sol` selects the Codex OAuth connection. `gpt-image-1` uses the existing OpenAI credentials; a name from [named connections](named-llm-connections.md) uses that connection's endpoint, API key and model ID. It must implement the OpenAI images API. There is no separate provider switch. The output request uses PNG, 1024×1024 and medium quality.

- `GenerateImage(prompt, outputPath?, imagePaths?)` saves its result in the caller's sandbox.
- `GenerateAttachmentImage(prompt, fileIds?)` requires S3 and saves an owned image reference. It does not select a reply attachment; `AttachFileToReply` performs that separate action.

Both accept up to five ordered source images for editing, use the same gateway and preserve original source bytes. Source ownership, availability and format are checked before calling the provider. Codex requires exactly one image and a completed stream; an interrupted stream is a failure even if it already contained pixels.

For attachment generation, a PostgreSQL receipt claims `(owner, execution, fingerprint)` before the provider call. The fingerprint includes the selected connection/model, normalized prompt and ordered source IDs/hashes. A repeated successful request in the same execution returns its saved file. An attempt without a saved result returns `image_previously_attempted`, including after restart: no automatic repeat of an uncertain paid operation. Receipts are deleted with their execution; there are no background generation retries. Sandbox generation has no durable receipt.

## Client tool images

Existing Client-Souz `tool.result` accepts MCP `image` and image `resource_link` content. The backend validates and saves pixels before persisting or acknowledging the result, replacing them with an `attachment:` reference and canonical `FileContent`. Client-provided attachment metadata grants no access. Failed tool results omit their images. A result allows five images and 20 MB total; invalid images fail the result with `tool_image_unavailable`.

HTTP(S) resources must resolve exclusively to public addresses. Downloads pin the checked DNS results, disable redirects/proxies/retries, and have a ten-second call timeout and bounded body. `attachment:` links must already belong to the caller. A result replay reuses the persisted outcome; it does not download again. Local files, private endpoints and arbitrary URL credentials are unsupported.

The references pass through existing Skill, composite-command and subagent results. This feature does not add attachment upload/delivery fields to the public WebSocket protocol or implement Telegram/VK transport.
