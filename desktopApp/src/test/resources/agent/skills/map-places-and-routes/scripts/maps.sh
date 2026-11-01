#!/bin/sh
# 2GIS places and routes wrapper. Prints compact tab-separated lines for the model.
# Usage: maps.sh search|card|route|tour|loop --city <city> ... (see SKILL.md)
set -eu

if [ "$#" -eq 0 ]; then
  echo "Error: a command (search, card, route, tour, loop) is required"
  exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
printf '%s\t%s\n' "$(date -u +%H:%M:%S)" "$*" >> "$script_dir/../../.maps-calls.log" 2>/dev/null || true
exec python3 "$script_dir/maps.py" "$@"
