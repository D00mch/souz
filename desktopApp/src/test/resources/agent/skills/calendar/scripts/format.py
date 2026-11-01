"""Formats raw Google Calendar API JSON into speech-friendly lines.

Run with the PYTHON runtime. Takes no credentials and makes no network calls: the
model fetches the raw JSON itself via SafeApiCall (the backend injects the real
access token there — this script never sees it) and hands the response body here
purely for deterministic date/duration formatting, so the model doesn't have to
compute things like per-day busy hours or free-gap arithmetic itself.

Intentionally no shebang line: the Souz static validator rejects a bundle whose
text contains the bare word that a `/usr/bin/...` shebang would introduce here.

Usage:
  format.py events <raw_json>   format an events.list response
  format.py next <raw_json>     closest upcoming event from an events.list response
  format.py busy <raw_json>     per-day load and free gaps from an events.list response
  format.py created <raw_json>  format an events.insert response
"""
import json
import sys
from datetime import datetime, timedelta, timezone

MSK = timezone(timedelta(hours=3))


def die(message, code=1):
    print("Error: " + message, file=sys.stderr)
    raise SystemExit(code)


def load_json(raw):
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        die("input is not valid JSON")


def parse_moment(raw):
    if not raw:
        return None, True
    if "dateTime" in raw:
        return datetime.fromisoformat(raw["dateTime"]), False
    if "date" in raw:
        return datetime.fromisoformat(raw["date"] + "T00:00:00+03:00"), True
    return None, True


def rows_from(payload):
    rows = []
    for item in payload.get("items") or []:
        if item.get("status") == "cancelled":
            continue
        start, all_day = parse_moment(item.get("start") or {})
        end, _ = parse_moment(item.get("end") or {})
        if start is None:
            continue
        rows.append({
            "start": start,
            "end": end,
            "all_day": all_day,
            "title": (item.get("summary") or "Без названия").strip(),
            "location": (item.get("location") or "").strip(),
        })
    rows.sort(key=lambda row: row["start"])
    return rows


def hhmm(moment):
    return moment.astimezone(MSK).strftime("%H:%M")


def as_line(row):
    start = row["start"].astimezone(MSK)
    if row["all_day"]:
        when = start.strftime("%d.%m") + " весь день"
    elif row["end"]:
        when = start.strftime("%d.%m ") + hhmm(row["start"]) + "-" + hhmm(row["end"])
    else:
        when = start.strftime("%d.%m ") + hhmm(row["start"])
    line = when + "\t" + row["title"]
    if row["location"]:
        line += "\t@ " + row["location"]
    return line


def cmd_events(payload):
    rows = rows_from(payload)
    if not rows:
        print("NO_EVENTS")
        return
    for row in rows:
        print(as_line(row))


def cmd_next(payload):
    rows = rows_from(payload)
    if not rows:
        print("NO_EVENTS")
        return
    print(as_line(rows[0]))


def cmd_busy(payload):
    rows = rows_from(payload)
    if not rows:
        print("NO_EVENTS")
        return
    by_day = {}
    for row in rows:
        day = row["start"].astimezone(MSK).date()
        by_day.setdefault(day, []).append(row)
    for day in sorted(by_day):
        day_rows = by_day[day]
        timed = [r for r in day_rows
                 if not r["all_day"] and r["end"] and r["end"].astimezone(MSK).date() == day]
        load = timedelta()
        for row in timed:
            load += row["end"] - row["start"]
        print("DAY=%s\tEVENTS=%d\tBUSY_HOURS=%.1f"
              % (day.strftime("%d.%m"), len(day_rows), load.total_seconds() / 3600))
        for earlier, later in zip(timed, timed[1:]):
            gap = later["start"] - earlier["end"]
            if gap >= timedelta(minutes=45):
                print("  FREE\t%s-%s\t%.1fч"
                      % (hhmm(earlier["end"]), hhmm(later["start"]),
                         gap.total_seconds() / 3600))


def cmd_created(payload):
    if not payload.get("id"):
        print("NOT_CREATED")
        raise SystemExit(1)
    moment, _ = parse_moment(payload.get("start") or {})
    stamp = moment.astimezone(MSK).strftime("%d.%m %H:%M") if moment else "?"
    print("CREATED\t%s\t%s" % (stamp, (payload.get("summary") or "").strip()))


def main():
    argv = sys.argv[1:]
    if len(argv) < 2:
        die("usage: format.py {events|next|busy|created} <raw_json>", 2)
    command, raw_json = argv[0], argv[1]
    payload = load_json(raw_json)
    if command == "events":
        cmd_events(payload)
    elif command == "next":
        cmd_next(payload)
    elif command == "busy":
        cmd_busy(payload)
    elif command == "created":
        cmd_created(payload)
    else:
        die("usage: format.py {events|next|busy|created} <raw_json>", 2)


if __name__ == "__main__":
    main()
