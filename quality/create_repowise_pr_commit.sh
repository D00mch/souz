#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 6 ]]; then
  echo "usage: $0 BASE HEAD MESSAGE AUTHOR_NAME AUTHOR_EMAIL TIMESTAMP" >&2
  exit 2
fi

base_sha="$1"
head_sha="$2"
commit_message="$3"
author_name="$4"
author_email="$5"
timestamp="$6"

git cat-file -e "$base_sha^{commit}"
git cat-file -e "$head_sha^{commit}"
test -z "$(git replace --list)"

if ! merged_tree="$(git merge-tree --write-tree "$base_sha" "$head_sha")"; then
  printf '%s\n' "$merged_tree" >&2
  echo "PR head does not merge cleanly with its event base" >&2
  exit 1
fi
git cat-file -e "$merged_tree^{tree}"

export GIT_AUTHOR_NAME="$author_name"
export GIT_AUTHOR_EMAIL="$author_email"
export GIT_AUTHOR_DATE="$timestamp"
export GIT_COMMITTER_NAME="$author_name"
export GIT_COMMITTER_EMAIL="$author_email"
export GIT_COMMITTER_DATE="$timestamp"

repowise_pr_sha="$(git commit-tree "$merged_tree" \
  -p "$base_sha" -m "$commit_message")"

test "$(git show -s --format=%P "$repowise_pr_sha")" = "$base_sha"
test "$(git show -s --format=%T "$repowise_pr_sha")" = "$merged_tree"
test "$(git rev-list --count "$base_sha..$repowise_pr_sha")" = "1"

printf '%s\n' "$repowise_pr_sha"
