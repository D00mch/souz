#!/bin/sh
# How-to-get-there wrapper: flights and trains between two cities in one call.
# Usage: route.sh --from <city> --to <city> [--date YYYY-MM-DD]
set -eu

if [ "$#" -eq 0 ]; then
  echo "Error: --from <city> and --to <city> are required"
  exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
printf '%s\t%s\n' "$(date -u +%H:%M:%S)" "$*" >> "$script_dir/../../.route-calls.log" 2>/dev/null || true
exec python3 "$script_dir/route.py" "$@"
