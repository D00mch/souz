---
name: brave-image-search
description: Find existing web images, film posters and covers in good resolution. Choose from small previews, then send the original file to Telegram/VK or return its direct original URL for a TV or another tool. Поиск картинок, постеров и обложек, отправка файла или ссылка на оригинал для ТВ.
commands:
  find_and_review:
    inputs: [query, kind]
    steps:
      - id: preview
        script: scripts/fast.py
        runtime: PYTHON
        args: [prepare, "${inputs.query}", "${inputs.kind}"]
      - id: reviewed
        tool: ViewImage
        arguments:
          imagePath: "${preview}"
          question: "Choose one numbered image for this request: ${inputs.query}. Image kind: ${inputs.kind}. Originals already passed format and resolution checks. These are intentionally small previews: decide ONLY whether the subject, season, language and image type match. Do not assess source quality from these previews. For a poster reject stills, logos and photos of merchandise. Reply index=N and one short reason, or NONE if nothing matches. Do not transcribe the images."
      - id: result
        script: scripts/fast.py
        runtime: PYTHON
        args: [pack, "${preview}", "${reviewed}"]
    returns: "${result}"
  original_url:
    inputs: [preview, index]
    steps:
      - id: original
        script: scripts/fast.py
        runtime: PYTHON
        args: [original, "${inputs.preview}", "${inputs.index}"]
    returns: "${original}"
  attach_candidate:
    inputs: [preview, index]
    steps:
      - id: path
        script: scripts/fast.py
        runtime: PYTHON
        args: [path, "${inputs.preview}", "${inputs.index}"]
      - id: attached
        tool: AttachFileToReply
        arguments:
          path: "${path}"
          presentation: image
    returns: "${attached}"
  send_image:
    inputs: [preview, index, channel]
    steps:
      - id: path
        script: scripts/fast.py
        runtime: PYTHON
        args: [path, "${inputs.preview}", "${inputs.index}"]
      - id: channels
        tool: ListActiveChannels
        arguments: {}
      - id: destination
        script: scripts/fast.py
        runtime: PYTHON
        args: [channel, "${channels}", "${inputs.channel}"]
      - id: caption
        script: scripts/fast.py
        runtime: PYTHON
        args: [caption, "${inputs.preview}", "${inputs.index}"]
      - id: attached
        tool: AttachFileToReply
        arguments:
          path: "${path}"
          presentation: image
      - id: sent
        tool: SendMessageToChannel
        arguments:
          channelType: "${inputs.channel}"
          channelId: "${destination}"
          text: "${caption}"
          fileIds: ["${attached.file.fileId}"]
    returns: "${sent}"
  attach_image:
    inputs: [path]
    steps:
      - id: attached
        tool: AttachFileToReply
        arguments:
          path: "${inputs.path}"
          presentation: image
    returns: "${attached}"
---

# Web images

Handle ordinary requests in the main agent; no subagent is needed for one image.
Use the composite commands below, not separate search/download/view/attach calls.

## Find and choose

```json
{"skillId":"brave-image-search","arguments":{"composite":"find_and_review","inputs":{"query":"Carnival Row season 2 poster","kind":"poster"},"timeoutMillis":120000}}
```

`kind` is `poster`, `cover`, `photo` or `image`. Put the actual subject, requested
season/year and language in the query. English titles often work well for foreign
films. The script handles query variants, ranking and parallel downloads. It stops
once a batch yields acceptable originals, with a limit of three searches and six
downloads. Originals must decode correctly, have a short side >=1000 and a long
side >=1400 pixels. No Brave thumbnail is substituted and no upscaling is done.

The same command calls `ViewImage` once on a JPEG contact sheet of at most
1200×640 pixels. Each candidate has a number. Source quality is already accepted;
vision only selects the matching subject and image type. Do not ask for an extra
quality review, inspect full-size originals or require a second candidate.

Read the returned `preview` path and `review.result`; choose the recommended
`index`. If review says NONE, refine the subject and run once more. A tool error
is not a successful review. Do not attach/send an unselected candidate.

## Choose the requested output

For Telegram/VK, call one command that finds the linked channel, attaches the
selected ORIGINAL and passes its fileId directly to delivery:

```json
{"skillId":"brave-image-search","arguments":{"composite":"send_image","inputs":{"preview":"/souz/workspace/brave-image-search/JOB_ID/review.jpg","index":"0","channel":"telegram"},"timeoutMillis":60000}}
```

`channel` is `telegram` or `vk`. Send only when requested. Inspect the returned
`success`; a script/composite exitCode of zero alone does not prove delivery.
Never automatically retry a failed or uncertain send. If multiple channels of
that type exist, resolve the destination instead of selecting one arbitrarily.

For a TV or a tool requiring a URL, use this separate exit without attaching or
sending a file:

```json
{"skillId":"brave-image-search","arguments":{"composite":"original_url","inputs":{"preview":"/souz/workspace/brave-image-search/JOB_ID/review.jpg","index":"0"}}}
```

It returns `originalUrl`, `pageUrl`, `width`, `height` and `sizeBytes`. Pass
`originalUrl` to the TV/display tool when requested. It is the direct URL of the
same downloaded original, not a Brave thumbnail, source HTML page, local preview
or private S3 URL. Returning a URL does not itself display anything on the TV.

For an attachment in the current chat, `attach_candidate` takes only `preview`
and `index`, returning `file.fileId`. It uses the original, never the review sheet.
Do not call `AttachFileToReply` directly in children: provider-filled empty
`fileId` conflicts with `path`. Composites supply the correct argument shape.

These commands use existing compiled tools. `find_and_review` needs `ViewImage`;
`attach_candidate` needs `AttachFileToReply`; `send_image` also needs
`ListActiveChannels` and `SendMessageToChannel`. `original_url` needs no compiled
tools. If delegation is genuinely needed, select these required tools along with
this skill. Do not discover tools separately just to call an available composite.

The helper uses the installed `/opt/souz/pdf-venv/bin/python` environment and
backend-forwarded `BRAVE_SEARCH_API_KEY`; never embed or print that key. All
originals, previews and manifests stay in `/souz/workspace/brave-image-search/`,
outside the skill bundle. The legacy `scripts/images.py` search/download commands
and `attach_image` remain for explicit diagnostic or small-preview requests;
they are not the normal path.
