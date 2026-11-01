"""Bounded reports over ring.sh's raw API transport; no clinical interpretation."""
import argparse
import datetime as dt
import json
import math
from pathlib import Path
import subprocess
import time
import sys
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

sys.dont_write_bytecode = True
from errors import RingHttpError

UTC = dt.timezone.utc
FIELDS = {
    "heartrate": ("heartRate",), "hrv": ("hrv",), "stress": ("stress",),
    "spo2": ("spo2",), "temperature": ("ntc", "bosh"),
    "step": ("step", "distance"), "sleep": ("totalSleepTime", "sleepScore", "minHeartRate", "avgHrv", "avgSpo2"),
}


def timestamp(record, kind):
    raw = record.get("startTime") if kind == "sleep" else record.get("date")
    if not isinstance(raw, str):
        return None
    try:
        value = dt.datetime.fromisoformat(raw.replace("Z", "+00:00"))
        return value if value.utcoffset() is not None else None
    except ValueError:
        return None


def number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def fetch(kind, page, deadline):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError
    script = Path(__file__).with_name("ring.sh")
    try:
        result = subprocess.run(["sh", str(script), kind, "100", str(page)],
                                capture_output=True, text=True, timeout=min(17, remaining))
    except subprocess.TimeoutExpired:
        raise TimeoutError from None
    if result.returncode:
        # Rebuild only known HTTP errors; never forward raw service bodies or stderr.
        try:
            failure = json.loads(result.stdout)
            status = failure.get("error", {}).get("httpStatus")
        except (ValueError, TypeError, AttributeError):
            status = None
        if type(status) is int and 300 <= status <= 599:
            raise RingHttpError(status)
        raise ValueError("api_error")
    try:
        payload = json.loads(result.stdout)
        if not isinstance(payload, dict) or not isinstance(payload.get("content"), list):
            raise ValueError
        if not all(isinstance(record, dict) for record in payload["content"]):
            raise ValueError
        return payload
    except (ValueError, TypeError):
        raise ValueError("invalid_response") from None


def collect(kind, start, end, deadline, loader=fetch, max_pages=15):
    records, seen, warnings = [], set(), set()
    stop = "page_limit"
    pages = 0
    previous_oldest = None
    failure = None
    for page in range(max_pages):
        try:
            payload = loader(kind, page, deadline)
        except TimeoutError:
            stop = "time_budget"
            break
        except RingHttpError as error:
            stop, failure = str(error), error.details
            break
        except ValueError as error:
            stop = str(error)
            break
        pages += 1
        batch = payload["content"]
        times = []
        added = 0
        for record in batch:
            moment = timestamp(record, kind)
            if moment is None:
                warnings.add("missing_or_ambiguous_timestamp")
                continue
            times.append(moment)
            fingerprint = json.dumps(record, sort_keys=True, ensure_ascii=False)
            if fingerprint in seen:
                warnings.add("identical_records_deduplicated")
                continue
            seen.add(fingerprint)
            if moment > end:
                warnings.add("future_records_excluded")
                continue
            records.append((moment, record))
            added += 1
        ordered = times == sorted(times, reverse=True)
        if not ordered:
            warnings.add("unexpected_record_order")
        if not batch or payload.get("isLast") is True:
            stop = "end_of_history"
            break
        if times and len(times) == len(batch) and ordered and max(times) < start:
            stop = "period_boundary"
            break
        if times and len(times) == len(batch) and ordered and min(times) < start:
            stop = "period_boundary"
            break
        oldest = min(times) if times else None
        if not added or (previous_oldest is not None and oldest is not None and oldest >= previous_oldest):
            stop = "pagination_stalled"
            break
        previous_oldest = oldest
    records.sort(key=lambda item: item[0], reverse=True)
    selected = [(moment, record) for moment, record in records if start <= moment <= end]
    return records, selected, {"pagesRead": pages, "stopReason": stop,
        "historyTraversalComplete": stop in ("end_of_history", "period_boundary") and not warnings,
        "warnings": sorted(warnings), **({"error": failure} if failure else {})}


def values(kind, records):
    result = {}
    for field in FIELDS[kind]:
        observed = [record[field] for _, record in records if number(record.get(field))]
        if kind == "temperature":
            observed = [value / 1000 for value in observed]
        result[field] = {"count": len(observed), "missingCount": len(records) - len(observed),
            "mean": sum(observed) / len(observed) if observed else None,
            "min": min(observed) if observed else None, "max": max(observed) if observed else None}
        if kind == "step":
            result[field]["sum"] = sum(observed) if observed else None
    return result


def public_record(record, kind):
    # Retain timestamps/provenance and documented measurements, no phase decoding.
    return {key: value for key, value in record.items()
            if key in (*FIELDS[kind], "date", "startTime", "calculatedStartTime", "sleepId", "source")}


def report(mode, days, zone, now=None, loader=fetch, budget_seconds=50):
    now = now or dt.datetime.now(UTC)
    local_now = now.astimezone(zone)
    midnight = local_now.replace(hour=0, minute=0, second=0, microsecond=0)
    first_day = midnight - dt.timedelta(days=days - 1)
    deadline = time.monotonic() + budget_seconds
    result = {"mode": mode, "generatedAt": now.isoformat(), "timezone": str(zone), "metrics": {},
        "notes": ["Latest measurement time is not a confirmed sync time.",
                  "History traversal does not confirm continuous wear or complete measurements.",
                  "Sleep records are sessions, not necessarily nights; phase encoding is not decoded.",
                  "HRV units and stress categories are not supplied by the API.",
                  "Temperature fields are skin sensor readings in degrees Celsius only in aggregates."]}
    for kind in ("sleep", "heartrate", "stress", "step", "hrv", "spo2", "temperature"):
        start = first_day if mode == "full" else (
            midnight if kind == "step" else now - dt.timedelta(days=7) if kind == "sleep"
            else now - dt.timedelta(hours=3))
        all_records, selected, coverage = collect(kind, start, now, deadline, loader)
        latest = all_records[0] if all_records else None
        metric = {"from": start.isoformat(), "to": now.isoformat(), "recordCount": len(selected),
            "latest": public_record(latest[1], kind) if latest else None,
            "latestAgeMinutes": (now - latest[0]).total_seconds() / 60 if latest else None,
            "coverage": coverage}
        if kind == "sleep":
            metric["sessions"] = [public_record(record, kind) for _, record in selected]
        else:
            metric["statistics"] = values(kind, selected)
        if mode == "full":
            grouped = {}
            for moment, record in selected:
                key = moment.astimezone(zone).date().isoformat()
                grouped.setdefault(key, []).append((moment, record))
            metric["byDay"] = {key: {"recordCount": len(items), "statistics": values(kind, items)}
                               for key, items in sorted(grouped.items())} if kind != "sleep" else {}
        result["metrics"][kind] = metric
        if coverage.get("error"):
            # A rejected request ends this report, retaining any earlier results.
            result.update(ok=False, error=coverage["error"],
                          partial=any(item["recordCount"] > 0 for item in result["metrics"].values()))
            break
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("summary", "full"))
    parser.add_argument("--days", type=int, choices=range(1, 8), default=3)
    parser.add_argument("--timezone", required=True, help="User's IANA timezone, e.g. Europe/Moscow")
    args = parser.parse_args()
    try:
        zone = ZoneInfo(args.timezone)
    except (ZoneInfoNotFoundError, ValueError):
        parser.error("unknown timezone")
    result = report(args.mode, args.days, zone)
    print(json.dumps(result, ensure_ascii=False, allow_nan=False))
    return 1 if result.get("ok") is False else 0


if __name__ == "__main__":
    sys.exit(main())
