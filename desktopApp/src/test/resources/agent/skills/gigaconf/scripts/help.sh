#!/bin/sh
# Looks up the GigaConf transcripts and prints compact material for the model.
# Usage: help.sh "<вопрос пользователя>"
set -eu

question="${1:-}"
if [ -z "$question" ]; then
  echo "Error: question is required" >&2
  exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec python3 "$script_dir/lookup.py" "$question"
