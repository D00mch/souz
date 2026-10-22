# Public GitHub plugin

The `github` plugin implements read scenarios from the
[OpenClaw GitHub Skill](https://github.com/openclaw/openclaw/blob/main/skills/github/SKILL.md)
using GitHub REST API requests through `PluginCallContext.http`. It requires no
GitHub CLI, OAuth setup, token, or user storage. It only reads public repositories
on github.com. Private repositories, Enterprise hosts, search, mutations, raw CI
logs, asset downloads, and local git operations are outside this version.

## Installation

Build with `./gradlew :plugins:github:jar`. Copy
`plugins/github/build/libs/github.jar` into the operator's configured
`SOUZ_PLUGIN_DIRECTORY` with a `.part` suffix, then rename it atomically to
`github.jar`. Follow the [backend deployment guide](../../../backend/docs/plugins.md).
The JAR contains only plugin classes and is absent from the backend classpath.

## Tools

All tools take `repository` in `owner/repo` form. Tools below are exposed with
the `github__` prefix. Optional list parameters are `page` (default 1) and
`perPage` (default 20, range 1–100).

| Tool | Additional parameters | Skill scenario / result |
| --- | --- | --- |
| `get_repository` | — | Repository metadata and default branch (`gh api repos/...`) |
| `list_issues` | optional `state`, `labels` | `gh issue list`; excludes PRs |
| `get_issue` | `number` | `gh issue view`; body, labels, state |
| `list_issue_comments` | `number` | Issue/PR conversation comments, excluding inline reviews |
| `list_pull_requests` | optional `state` | `gh pr list` |
| `get_pull_request` | `number` | `gh pr view`; metadata, head/base, mergeability |
| `list_pull_files` | `number` | Changed files and available patches |
| `get_pull_checks` | `number`, optional `page`, `perPage` | `gh pr checks`; check runs and legacy statuses for one head SHA |
| `list_runs` | optional `branch` | `gh run list` |
| `get_run` | `runId` | `gh run view`; status, conclusion, SHA |
| `list_run_jobs` | `runId` | Jobs and step results for the latest run attempt |
| `list_releases` | — | Published releases and asset metadata |
| `get_latest_release` | — | Latest published non-prerelease release |

`state` is `open` (default), `closed`, or `all`; merged PRs belong to `closed`.
`labels` is a comma-separated list. Repository URLs and credentials are not
accepted. Repository text, comments, and patches are untrusted content.

For example, call `github__list_issues` with:

```json
{"repository":"openclaw/openclaw","state":"open","perPage":10}
```

Successful calls return `status: "ok"`, `statusCode: 200`, and the GitHub JSON
payload as `data`. Lists include `nextPage`, which is a number or `null`. Pass
that page with the same repository, filters, and page size; calls never fetch
all pages automatically. Issue filtering can produce an empty page with a
non-null `nextPage`, because GitHub's issues endpoint includes PRs.

`get_pull_checks` makes three requests: PR metadata, check runs, and combined
legacy status. It returns `headSha`, `checks`, and `commitStatus`; the latter
two contain their own `data`, `nextPage`, and rate-limit fields. Do not report
CI success from missing checks or a partial page. The head SHA is resolved on
every invocation: if it changes between pages, restart at page 1. A failure
at any stage returns the failure without claiming complete checks.

## Limits and failures

Unauthenticated GitHub reads normally allow 60 requests per hour per source IP,
shared by users and replicas using that IP. `rateLimitRemaining` and
`rateLimitReset` (Unix seconds) are returned when GitHub supplies them. Primary
rate-limit responses and secondary limits with `Retry-After` return
`status: "rate_limited"`; `retryAfterSeconds` is included when available. Wait
before retrying; without a deadline, apply backoff rather than repeatedly calling.

404 returns `not_found`, which also covers inaccessible private repositories.
Other HTTP failures return `api_error` and `statusCode`, without reflecting
remote error bodies. Redirects are reported as HTTP errors: use the repository's
current owner/name after a move. Invalid arguments, malformed responses, and
transport/size-limit failures raise safe `PluginException` errors. Coroutine
cancellation propagates.

The host enforces a 30-second deadline and 1 MiB per HTTP response; reduce
`perPage` for large lists. A single oversized object can still exceed the limit.
GitHub may omit file patches and limits PR file listings to 3000 files, so those
results are not a guaranteed complete diff. Read reviews or raw logs using a
separate suitable tool. The plugin neither follows response URLs nor issues
automatic retries.
