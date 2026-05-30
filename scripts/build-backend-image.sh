#!/usr/bin/env bash
set -euo pipefail

IMAGE_TAG="${1:-souz-backend:latest}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

docker build \
  -f "$REPO_ROOT/backend.Dockerfile" \
  -t "$IMAGE_TAG" \
  "$REPO_ROOT"
