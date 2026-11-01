#!/bin/sh
# Flight price lookup wrapper. Prints compact tab-separated lines for the model.
# Usage: avia.sh --from <city> [--to <city>] [--when YYYY-MM-DD|YYYY-MM] [--return ...] [--direct] [--limit N]
set -eu

if [ "$#" -eq 0 ]; then
  echo "Error: --from <city> is required"
  exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
printf '%s\t%s\n' "$(date -u +%H:%M:%S)" "$*" >> "$script_dir/../../.avia-calls.log" 2>/dev/null || true
exec python3 "$script_dir/avia.py" "$@"
