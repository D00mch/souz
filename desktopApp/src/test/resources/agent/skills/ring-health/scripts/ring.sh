#!/bin/sh
# Life Balance ring readings, newest first. Requires curl and python3.
# Usage: ring.sh <type> [pageSize] [page]
# pageSize: 1..100 (sleep default 7, otherwise 30); page: zero-based, default 0.
# Fetch older pages instead of from/to: date-filtered API requests can time out.
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
case "${1:-}" in
  auth-status|set-token)
    exec python3 "$script_dir/auth.py" "$@"
    ;;
esac
# Check authorization before reports so missing access is not hidden as missing data.
auth_file=$(python3 "$script_dir/auth.py" token-path) || exit 1

case "${1:-}" in
  summary|full)
    exec python3 "$script_dir/report.py" "$@"
    ;;
esac

if [ "$#" -lt 1 ] || [ "$#" -gt 3 ]; then
  echo "Error: usage: ring.sh <type> [pageSize] [page]" >&2
  exit 1
fi

type="$1"
page_size="${2:-}"
page="${3:-0}"

case "$type" in
  heartrate|sleep|stress|step|spo2|temperature|hrv) ;;
  *)
    echo "Error: type must be one of: heartrate sleep stress step spo2 temperature hrv" >&2
    exit 1
    ;;
esac

if [ -z "$page_size" ]; then
  if [ "$type" = "sleep" ]; then page_size=7; else page_size=30; fi
fi
case "$page_size" in
  *[!0-9]*) echo "Error: pageSize must be an integer 1..100" >&2; exit 1 ;;
esac
if ! [ "$page_size" -ge 1 ] 2>/dev/null || ! [ "$page_size" -le 100 ] 2>/dev/null; then
  echo "Error: pageSize must be in range 1..100" >&2
  exit 1
fi
case "$page" in
  *[!0-9]*) echo "Error: page must be a non-negative integer" >&2; exit 1 ;;
esac
if ! [ "$page" -ge 0 ] 2>/dev/null; then
  echo "Error: page must be a non-negative integer" >&2
  exit 1
fi

auth=$(head -n 1 "$auth_file" | tr -d ' \r\n')
if [ -z "$auth" ]; then
  echo "Error: auth file is empty" >&2
  exit 1
fi

url="https://app.life-balance.tech/external/sync/$type?page=$page&pageSize=$page_size"
response_file=$(mktemp)
trap 'rm -f "$response_file"' 0
trap 'exit 1' 1 2 15
http_status=$(curl -sS -m 15 -o "$response_file" -w '%{http_code}' \
  -H "Authorization: Bearer $auth" "$url" 2>/dev/null) || {
  echo "Error: ring API request failed" >&2; exit 1; }

case "$http_status" in
  2??) ;;
  *)
    python3 "$script_dir/errors.py" "$http_status"
    exit 1
    ;;
esac

# Parse JSON rather than editing it with a regex. Preserve nulls and pagination.
# The per-epoch phase encoding has no documented mapping; omit its bulky string.
python3 - "$response_file" "$type" <<'PY'
import json
import sys

try:
    with open(sys.argv[1], encoding="utf-8") as response:
        payload = json.load(response)
    if not isinstance(payload, dict) or not isinstance(payload.get("content"), list):
        raise ValueError("Expected a content array")
    for record in payload["content"]:
        if not isinstance(record, dict):
            raise ValueError("Expected object records")
        if sys.argv[2] == "sleep":
            record.pop("sleepQuality", None)
    output = json.dumps(payload, ensure_ascii=False, allow_nan=False)
except (OSError, ValueError):
    print("Error: unexpected ring API response", file=sys.stderr)
    sys.exit(1)
print(output)
PY
