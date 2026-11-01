#!/bin/bash
set -euo pipefail

# No network calls and no token live here. Every call to
# health-api.cloud.huawei.com is made by the model via the SafeApiCall tool
# (the backend injects the OAuth token; this script never sees it). This
# script only does local work: building a request body with correct epoch-ms
# bounds, and turning a Health Kit response read from stdin into readable text.
#
# Python is invoked as `python3 -c` on purpose (never `python3 -` with a
# heredoc): a heredoc feeds the *source* over stdin, leaving nothing for
# json.load(sys.stdin) to read. Inline -c also imports no local module, so no
# __pycache__/.pyc is ever written into the bundle -- the skill registry
# hard-rejects any bundle containing a .pyc.

cmd="${1:-}"
arg1="${2:-}"
arg2="${3:-}"
arg3="${4:-}"

DEFAULT_TZ="+03:00"

usage() {
  cat >&2 <<'EOF'
Usage:
  hh_api.sh metrics
  hh_api.sh range <YYYY-MM-DD> [<YYYY-MM-DD>] [<+HH:MM>]
  hh_api.sh build-body <metric> <YYYY-MM-DD> [<YYYY-MM-DD>] [<+HH:MM>]
  hh_api.sh parse-daily   [<+HH:MM>]   # reads sampleSet:polymerize body on stdin
  hh_api.sh parse-samples [<+HH:MM>]   # reads sampleSet:polymerize body on stdin
  hh_api.sh parse-sleep   [<+HH:MM>]   # reads sampleSet:polymerize body on stdin
  hh_api.sh parse-workouts [<+HH:MM>]  # reads activityRecords body on stdin
EOF
}

if [[ -z "$cmd" ]]; then
  usage
  exit 2
fi

# metric -> Huawei dataTypeName. Only data types verified against working
# integrations are listed; adding one is a new line here plus a row in SKILL.md.
is_tz_offset() {
  [[ "${1:-}" =~ ^[+-][0-9][0-9]:[0-9][0-9]$ ]]
}

pick_tz() {
  if is_tz_offset "${1:-}"; then echo "$1"; else echo "$DEFAULT_TZ"; fi
}

# SpO2 and skin temperature are deliberately absent: Huawei did not grant this
# app the oxygenSaturation.read / bodyTemperature.read scopes, so every call
# would 403. To restore them, get those scopes approved in the Huawei console,
# add them back to SKILL.md's oauthScopes, and re-add the rows here:
#   spo2			com.huawei.continuous.blood_oxygen
#   spo2-instant		com.huawei.instantaneous.blood_oxygen
#   temperature		com.huawei.instantaneous.skin_temperature
metrics_table() {
  cat <<'EOF'
steps			com.huawei.continuous.steps.delta
heart-rate		com.huawei.instantaneous.heart_rate
heart-rate-continuous	com.huawei.continuous.heart_rate.delta
stress			com.huawei.continuous.stress.delta
sleep			com.huawei.sleep.detail
EOF
}

resolve_data_type() {
  local metric="$1" line
  line="$(metrics_table | awk -F'\t+' -v m="$metric" '$1 == m {print $2}')"
  if [[ -z "$line" ]]; then
    echo "ERROR: unknown metric '$metric'. Known: $(metrics_table | awk -F'\t+' '{printf "%s ", $1}')" >&2
    exit 2
  fi
  echo "$line"
}

# Shared Python helper: prints startTime/endTime epoch ms for a date range.
compute_range() {
  local start_date="$1" end_date="$2" tz="$3"
  python3 -c '
import sys
from datetime import datetime, timedelta, timezone

start_date, end_date, tz_raw = sys.argv[1], sys.argv[2], sys.argv[3]

def parse_tz(s):
    if not s or s[0] not in "+-" or len(s) != 6 or s[3] != ":":
        raise SystemExit("ERROR: timezone offset must look like +03:00, got: " + repr(s))
    sign = 1 if s[0] == "+" else -1
    return timezone(sign * timedelta(hours=int(s[1:3]), minutes=int(s[4:6])))

def parse_day(s, tz):
    try:
        d = datetime.strptime(s, "%Y-%m-%d")
    except ValueError:
        raise SystemExit("ERROR: date must be YYYY-MM-DD, got: " + repr(s))
    return datetime(d.year, d.month, d.day, tzinfo=tz)

tz = parse_tz(tz_raw)
start = parse_day(start_date, tz)
end = parse_day(end_date, tz) + timedelta(days=1) - timedelta(milliseconds=1)
if end < start:
    raise SystemExit("ERROR: end date is before start date")
print(int(start.timestamp() * 1000))
print(int(end.timestamp() * 1000))
' "$start_date" "$end_date" "$tz"
}

cmd_range() {
  local start_date="$1" end_date="$2" tz="$3"
  local bounds
  bounds="$(compute_range "$start_date" "$end_date" "$tz")"
  echo "startTime=$(echo "$bounds" | sed -n 1p)"
  echo "endTime=$(echo "$bounds" | sed -n 2p)"
}

cmd_build_body() {
  local metric="$1" start_date="$2" end_date="$3" tz="$4"
  local data_type bounds
  data_type="$(resolve_data_type "$metric")"
  bounds="$(compute_range "$start_date" "$end_date" "$tz")"
  printf '{"startTime":%s,"endTime":%s,"polymerizeWith":[{"dataTypeName":"%s"}]}' \
    "$(echo "$bounds" | sed -n 1p)" "$(echo "$bounds" | sed -n 2p)" "$data_type"
}

# Every parse-* command shares the same walk over the polymerize response:
#   group[] -> sampleSet[] -> samplePoints[] -> value[]
parse_response() {
  local mode="$1" tz="$2"
  python3 -c '
import json, sys
from datetime import datetime, timedelta, timezone

mode, tz_raw = sys.argv[1], sys.argv[2]

def parse_tz(s):
    if not s or s[0] not in "+-" or len(s) != 6 or s[3] != ":":
        raise SystemExit("ERROR: timezone offset must look like +03:00, got: " + repr(s))
    sign = 1 if s[0] == "+" else -1
    return timezone(sign * timedelta(hours=int(s[1:3]), minutes=int(s[4:6])))

tz = parse_tz(tz_raw)

raw = sys.stdin.read().strip()
if not raw:
    raise SystemExit("ERROR: no response body on stdin")
try:
    obj = json.loads(raw)
except json.JSONDecodeError as e:
    raise SystemExit("ERROR: stdin is not valid JSON: %s" % e)

# A Huawei error body carries code/message instead of group[].
if isinstance(obj, dict) and "group" not in obj and ("code" in obj or "message" in obj):
    raise SystemExit("ERROR: Huawei returned %s: %s" % (obj.get("code"), obj.get("message")))

def local(ms):
    return datetime.fromtimestamp(ms / 1000.0, tz)

def numeric(entry):
    for key in ("integerValue", "floatValue", "doubleValue", "longValue"):
        if entry.get(key) is not None:
            return entry[key]
    return None

def points():
    for grp in (obj.get("group") or []):
        for ss in (grp.get("sampleSet") or []):
            for sp in (ss.get("samplePoints") or []):
                yield sp

def fmt(v):
    if isinstance(v, float) and v == int(v):
        return str(int(v))
    return str(v)

if mode == "samples":
    rows = 0
    for sp in points():
        start = sp.get("startTime")
        if start is None:
            continue
        fields = []
        for entry in (sp.get("value") or []):
            n = numeric(entry)
            if n is None:
                continue
            fields.append("%s=%s" % (entry.get("fieldName", "value"), fmt(n)))
        if not fields:
            continue
        print("%s | %s" % (local(start).strftime("%Y-%m-%d %H:%M"), " ".join(fields)))
        rows += 1
    if rows == 0:
        print("NO DATA")

elif mode == "daily":
    agg = {}
    order = []
    for sp in points():
        start = sp.get("startTime")
        if start is None:
            continue
        day = local(start).strftime("%Y-%m-%d")
        for entry in (sp.get("value") or []):
            n = numeric(entry)
            if n is None:
                continue
            key = (day, entry.get("fieldName", "value"))
            if key not in agg:
                agg[key] = []
                order.append(key)
            agg[key].append(n)
    if not order:
        print("NO DATA")
    else:
        for key in sorted(order):
            vals = agg[key]
            total = sum(vals)
            print("%s | %s | n=%d sum=%s avg=%s min=%s max=%s" % (
                key[0], key[1], len(vals), fmt(total),
                fmt(round(total / len(vals), 1)), fmt(min(vals)), fmt(max(vals)),
            ))

elif mode == "sleep":
    nights = {}
    order = []
    rows = 0
    for sp in points():
        start, end = sp.get("startTime"), sp.get("endTime")
        if start is None or end is None:
            continue
        s_local, e_local = local(start), local(end)
        minutes = round((end - start) / 60000.0)
        # A night that starts before midnight belongs to the morning it ends on.
        night = e_local.strftime("%Y-%m-%d")
        labels = []
        for entry in (sp.get("value") or []):
            n = numeric(entry)
            val = n if n is not None else entry.get("stringValue")
            if val is None:
                continue
            labels.append("%s=%s" % (entry.get("fieldName", "value"), fmt(val)))
        print("%s | %s-%s | %d min | %s" % (
            night, s_local.strftime("%H:%M"), e_local.strftime("%H:%M"),
            minutes, " ".join(labels) if labels else "-",
        ))
        rows += 1
        if night not in nights:
            nights[night] = 0
            order.append(night)
        nights[night] += minutes
    if rows == 0:
        print("NO DATA")
    else:
        print("")
        for night in sorted(order):
            total = nights[night]
            print("TOTAL %s | %d min (%dh %02dm)" % (night, total, total // 60, total % 60))

else:
    raise SystemExit("ERROR: unknown parse mode " + mode)
' "$mode" "$tz"
}

parse_workouts() {
  local tz="$1"
  python3 -c '
import json, sys
from datetime import datetime, timedelta, timezone

tz_raw = sys.argv[1]

def parse_tz(s):
    if not s or s[0] not in "+-" or len(s) != 6 or s[3] != ":":
        raise SystemExit("ERROR: timezone offset must look like +03:00, got: " + repr(s))
    sign = 1 if s[0] == "+" else -1
    return timezone(sign * timedelta(hours=int(s[1:3]), minutes=int(s[4:6])))

tz = parse_tz(tz_raw)

raw = sys.stdin.read().strip()
if not raw:
    raise SystemExit("ERROR: no response body on stdin")
try:
    obj = json.loads(raw)
except json.JSONDecodeError as e:
    raise SystemExit("ERROR: stdin is not valid JSON: %s" % e)

records = obj.get("activityRecords")
if records is None:
    if "code" in obj or "message" in obj:
        raise SystemExit("ERROR: Huawei returned %s: %s" % (obj.get("code"), obj.get("message")))
    records = []

if not records:
    print("NO DATA")
    raise SystemExit(0)

def local(ms):
    return datetime.fromtimestamp(ms / 1000.0, tz)

def fmt(v):
    if isinstance(v, float) and v == int(v):
        return str(int(v))
    return str(v)

for rec in records:
    start, end = rec.get("startTime"), rec.get("endTime")
    when = local(start).strftime("%Y-%m-%d %H:%M") if start else "?"
    minutes = round((end - start) / 60000.0) if (start and end) else "?"
    name = rec.get("name") or rec.get("activityType") or rec.get("id") or "activity"
    extras = []
    summary = rec.get("activitySummary") or {}
    for stat in (summary.get("dataSummary") or []):
        for entry in (stat.get("value") or []):
            for key in ("integerValue", "floatValue", "doubleValue", "longValue"):
                if entry.get(key) is not None:
                    extras.append("%s=%s" % (entry.get("fieldName", "value"), fmt(entry[key])))
                    break
    print("%s | %s | %s min%s" % (
        when, name, minutes, (" | " + " ".join(extras)) if extras else "",
    ))
' "$tz"
}

case "$cmd" in
  metrics)
    printf 'metric\tdataTypeName\n'
    metrics_table
    ;;
  range)
    [[ -n "$arg1" ]] || { echo "ERROR: range requires <YYYY-MM-DD>" >&2; exit 2; }
    # The end date is optional, so a lone offset in its place is still a tz.
    if is_tz_offset "$arg2"; then
      cmd_range "$arg1" "$arg1" "$arg2"
    else
      cmd_range "$arg1" "${arg2:-$arg1}" "$(pick_tz "$arg3")"
    fi
    ;;
  build-body)
    [[ -n "$arg1" && -n "$arg2" ]] || { echo "ERROR: build-body requires <metric> <YYYY-MM-DD>" >&2; exit 2; }
    if is_tz_offset "$arg3"; then
      cmd_build_body "$arg1" "$arg2" "$arg2" "$arg3"
    else
      cmd_build_body "$arg1" "$arg2" "${arg3:-$arg2}" "$(pick_tz "${5:-}")"
    fi
    ;;
  parse-samples)
    parse_response samples "${arg1:-$DEFAULT_TZ}"
    ;;
  parse-daily)
    parse_response daily "${arg1:-$DEFAULT_TZ}"
    ;;
  parse-sleep)
    parse_response sleep "${arg1:-$DEFAULT_TZ}"
    ;;
  parse-workouts)
    parse_workouts "${arg1:-$DEFAULT_TZ}"
    ;;
  *)
    echo "Unknown command: $cmd" >&2
    usage
    exit 2
    ;;
esac
