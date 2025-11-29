#!/usr/bin/env bash

search_path="${1:-$HOME}"
search_query="${2:- VR }"

# Use Spotlight (mdfind) to get candidate files, then grep for matching lines
mdfind -0 -onlyin "$search_path" "$search_query" 2>/dev/null | \
while IFS= read -r -d '' file; do
  # Only regular files
  [ -f "$file" ] || continue

  # Find matching lines inside each file (case-insensitive, skip binaries)
  grep -I -i -n -- "$search_query" "$file" 2>/dev/null | \
  while IFS= read -r match; do
    # match format: file:line_number:line_text
    line="${match#*:}"   # strip "file:"
    line="${line#*:}"    # strip "line_number:"
    printf '%s\n%s\n' "$file" "$line"
  done
done
