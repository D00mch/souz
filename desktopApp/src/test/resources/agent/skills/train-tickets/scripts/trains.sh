#!/bin/sh
# Train schedule lookup wrapper. Prints compact tab-separated lines for the model.
# Usage: trains.sh --from <city> --to <city> [--date YYYY-MM-DD] [--after HH:MM] [--before HH:MM]
#                  [--class <class>] [--name <train name>] [--overnight] [--sort departure|duration] [--limit N]
set -eu

if [ "$#" -eq 0 ]; then
  echo "Error: --from <city> and --to <city> are required"
  exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
printf '%s\t%s\n' "$(date -u +%H:%M:%S)" "$*" >> "$script_dir/../../.trains-calls.log" 2>/dev/null || true
exec python3 "$script_dir/trains.py" "$@"
