#!/bin/sh
# Ostrovok hotel search wrapper. Prints compact tab-separated lines for the model.
# Usage: ostrovok.sh --city <city> --checkin YYYY-MM-DD [--checkout YYYY-MM-DD] [--adults N]
#                    [--sort popular|cheap|rating|center] [--price-min N] [--price-max N] [--stars 4,5]
#                    [--min-rating 5-9] [--type hotel|apartment|hostel|guesthouse] [--breakfast]
#                    [--free-cancel] [--metro] [--limit N] [--skip N] (see SKILL.md for the full list)
set -eu

if [ "$#" -eq 0 ]; then
  echo "Error: --city <city> and --checkin YYYY-MM-DD are required"
  exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
printf '%s\t%s\n' "$(date -u +%H:%M:%S)" "$*" >> "$script_dir/../../.ostrovok-calls.log" 2>/dev/null || true
exec python3 "$script_dir/ostrovok.py" "$@"
