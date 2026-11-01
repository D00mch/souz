#!/bin/sh
# SberHealth doctors wrapper. Prints compact tab-separated lines for the model.
# Usage: doctor.sh find --city <city> --speciality <name> [--near <place>] [--order rating|price|experience] ...
#        doctor.sh specialities --city <city> [--search <text>]
set -eu

if [ "$#" -eq 0 ]; then
  echo "Error: a command (find, specialities) is required"
  exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
printf '%s\t%s\n' "$(date -u +%H:%M:%S)" "$*" >> "$script_dir/../../.doctor-calls.log" 2>/dev/null || true
exec python3 "$script_dir/doctor.py" "$@"
