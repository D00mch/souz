#!/bin/sh
# Headlines fetcher wrapper. Prints compact tab-separated lines for the model.
# Usage: news.sh [--topic <тема>] [--limit N] [--hours N] [--set-city <город>] [--show-city]
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
printf '%s\t%s\n' "$(date -u +%H:%M:%S)" "$*" >> "$script_dir/../../.news-calls.log" 2>/dev/null || true
exec python3 "$script_dir/news.py" "$@"
