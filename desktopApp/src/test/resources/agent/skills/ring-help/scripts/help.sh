#!/bin/sh
# Looks up the SberDevices help base and prints compact material for the model.
# Usage: help.sh "<вопрос пользователя>"
set -eu

question="${1:-}"
if [ -z "$question" ]; then
  echo "Error: question is required" >&2
  exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
printf '%s\t%s\n' "$(date -u +%H:%M:%S)" "$question" >> /tmp/ring-help-calls.log 2>/dev/null || true
exec python3 "$script_dir/lookup.py" "$question"
