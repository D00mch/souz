#!/bin/sh
# Afisha wrapper. Prints compact tab-separated lines for the model.
# Usage: afisha.sh ideas --city <city> [--date ...] [--kids|--age N] [--type ...] [--near <place>] [--budget N]
#        afisha.sh find --city <city> [--query <text>] [--type movie|concert|theatre|event|...] [--date ...]
#        afisha.sh sessions --city <city> (--id <id> | --query <text>) [--near <place>] [--date ...] [--after HH:MM]
#        afisha.sh place --city <city> (--name <venue> | --near <place> [--kind cinema|theatre|...]) [--date ...]
#        afisha.sh cities [--search <text>]
set -eu

if [ "$#" -eq 0 ]; then
  echo "Error: a command (ideas, find, sessions, place, cities) is required"
  exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
printf '%s\t%s\n' "$(date -u +%H:%M:%S)" "$*" >> "$script_dir/../../.afisha-calls.log" 2>/dev/null || true
exec python3 "$script_dir/afisha.py" "$@"
