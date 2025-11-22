#!/usr/bin/env bash
# Returns lines in format: "<bundle-id> <app-name>" like:
# com.apple.Terminal Terminal
# com.apple.Safari Safari
# com.google.Chrome Google Chrome ...

set -euo pipefail
shopt -s nullglob

ROOTS=(/Applications "/System/Applications" "$HOME/Applications")

collect_apps() {
  if command -v mdfind >/dev/null 2>&1; then
    mdfind -0 "kMDItemContentType == 'com.apple.application-bundle'" -onlyin "${ROOTS[@]}"
  else
    # Slower fallback
    for r in "${ROOTS[@]}"; do
      find "$r" -type d -name "*.app" -prune -print0 2>/dev/null || true
    done
  fi
}

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

while IFS= read -r -d '' app; do
  # Skip nested helpers inside another .app bundle
  [[ "$app" =~ \.app/.*\.app$ ]] && continue

  # Skip Xcode Developer tools (Simulator, Instruments, etc.)
  if [[ "$app" == /Applications/Xcode*.app/Contents/Developer/* ]] || [[ "$app" == */Contents/Developer/* ]]; then
    continue
  fi

  plist="$app/Contents/Info.plist"
  [[ -f "$plist" ]] || continue

  # Read bundle id and name; very fast and robust
  bid=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$plist" 2>/dev/null || true)
  [[ -n "${bid:-}" ]] || continue

  # Skip background-only services (not user-opened apps)
  bg=$(/usr/libexec/PlistBuddy -c 'Print :LSBackgroundOnly' "$plist" 2>/dev/null || echo "0")
  [[ "$bg" == "1" ]] && continue

  # Prefer DisplayName, then Name, then folder name
  name=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleDisplayName' "$plist" 2>/dev/null \
         || /usr/libexec/PlistBuddy -c 'Print :CFBundleName' "$plist" 2>/dev/null \
         || basename "$app" .app)

  # Output "bundle.id Name"
  printf '%s %s\n' "$bid" "$name" >>"$tmp"
done < <(collect_apps)

# De-dup by bundle id and sort by name
# (Keeps first-seen name for any duplicate ids)
LC_ALL=C awk '!seen[$1]++ {print}' "$tmp" | LC_ALL=C sort -k2,2f
