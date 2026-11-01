"""Train timetable from Tutu.ru open data (via Travelpayouts). Called by trains.sh.

The API returns the regular timetable of direct trains between two stations and, per carriage
class, the lowest price ever seen on the route. Checked against the live site on 2026-09-25:
for a date two weeks ahead real prices were 3-10 times higher, so the floor is reported
separately and never as the price for a date. Links go straight to tutu.ru, without partner redirects.
No shebang on purpose: a bare `/usr/bin/...` line trips the bundle validator.
"""
import argparse
import datetime
import difflib
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

API = "https://suggest.travelpayouts.com/search"
SITE = "https://www.tutu.ru"
HERE = os.path.dirname(os.path.abspath(__file__))
STATIONS_FILE = os.path.join(HERE, "stations.json")

CLASSES = {
    "plazcard": "плацкарт", "coupe": "купе", "lux": "СВ", "soft": "люкс", "sedentary": "сидячий",
}
CLASS_ORDER = ["sedentary", "plazcard", "coupe", "lux", "soft"]
CLASS_ALIASES = {
    "плацкарт": "plazcard", "купе": "coupe", "св": "lux", "спальный": "lux", "люкс": "soft",
    "мягкий": "soft", "сидячий": "sedentary", "сидячие": "sedentary",
}
CITY_ALIASES = {
    "питер": "санкт-петербург", "спб": "санкт-петербург", "петербург": "санкт-петербург",
    "ленинград": "санкт-петербург", "мск": "москва", "екб": "екатеринбург",
    "нижний": "нижний новгород", "ростов": "ростов-на-дону", "минводы": "минеральные воды",
    "новосиб": "новосибирск", "владик": "владивосток", "челны": "набережные челны",
}


def fail(text):
    print("Error: " + text)
    raise SystemExit(1)


def norm(text):
    text = text.lower().replace("ё", "е").strip()
    text = re.sub(r"^(г\.|город|ст\.|станция|вокзал)\s*", "", text)
    return re.sub(r"\s+", " ", text)


def load_stations():
    with open(STATIONS_FILE, encoding="utf-8") as handle:
        return json.load(handle)  # code -> [name, number of routes]


def resolve_station(query, stations):
    """Returns (code, name, exact). Prefers the city-wide code, then the busiest station."""
    wanted = norm(query)
    wanted = CITY_ALIASES.get(wanted, wanted)
    by_busy = sorted(stations.items(), key=lambda item: -item[1][1])
    for code, (name, _) in by_busy:
        if norm(name) == wanted:
            return code, name, True
    for code, (name, _) in by_busy:
        if re.match(re.escape(wanted) + r"([ \-.(]|$)", norm(name)):
            return code, name, True
    names = {norm(name): code for code, (name, _) in by_busy}
    close = difflib.get_close_matches(wanted, list(names), n=1, cutoff=0.8)
    if close:
        code = names[close[0]]
        return code, stations[code][0], False
    return None, None, False


def suggestions(query, stations):
    names = sorted({name for name, _ in stations.values()})
    return difflib.get_close_matches(query.strip().capitalize(), names, n=3, cutoff=0.5)


def fetch_trips(dep_code, arr_code):
    params = urllib.parse.urlencode({"service": "tutu_trains", "term": dep_code, "term2": arr_code})
    request = urllib.request.Request(API + "?" + params, headers={"User-Agent": "souz-trains-skill/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=25) as response:
            data = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        fail("train schedule service returned %s" % error.code)
    except urllib.error.URLError as error:
        fail("train schedule service unreachable: %s" % error.reason)
    except json.JSONDecodeError:
        fail("train schedule service returned non-JSON")
    if isinstance(data, dict):
        return data.get("trips") or []
    return []


def parse_date(text):
    try:
        day = datetime.date.fromisoformat(text)
    except ValueError:
        fail("date must be YYYY-MM-DD, got %s" % text)
    if day < datetime.date.today():
        fail("date %s is in the past" % text)
    return day


def minutes(hhmm):
    parts = hhmm.split(":")
    return int(parts[0]) * 60 + int(parts[1])


def human_duration(seconds):
    hours, rest = divmod(int(seconds) // 60, 60)
    days, hours = divmod(hours, 24)
    text = ("%d сут " % days if days else "") + ("%d ч" % hours if hours else "")
    return (text + (" %d мин" % rest if rest else "")).strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--from", dest="origin", required=True)
    parser.add_argument("--to", dest="destination", required=True)
    parser.add_argument("--date")
    parser.add_argument("--after")
    parser.add_argument("--before")
    parser.add_argument("--class", dest="car_class")
    parser.add_argument("--name")
    parser.add_argument("--overnight", action="store_true")
    parser.add_argument("--sort", choices=["departure", "duration"], default="departure")
    parser.add_argument("--limit", type=int, default=5)
    args = parser.parse_args()

    stations = load_stations()
    ends = []
    for query in (args.origin, args.destination):
        code, name, exact = resolve_station(query, stations)
        if not code:
            hint = ", ".join(suggestions(query, stations)) or "-"
            print("STATION_UNKNOWN\t%s\t%s" % (query, hint))
            return
        if not exact:
            print("STATION\t%s\t%s" % (query, name))
        ends.append((code, name))
    (dep_code, dep_name), (arr_code, arr_name) = ends

    day = parse_date(args.date) if args.date else None
    car_class = None
    if args.car_class:
        car_class = CLASS_ALIASES.get(norm(args.car_class), args.car_class)
        if car_class not in CLASSES:
            fail("unknown class %s" % args.car_class)

    trips = fetch_trips(dep_code, arr_code)
    rows = []
    for trip in trips:
        prices = {item["type"]: item["price"] for item in trip.get("categories") or [] if item.get("price")}
        dep, arr = trip["departureTime"][:5], trip["arrivalTime"][:5]
        travel = int(trip.get("travelTimeInSeconds") or 0)
        days_later = (minutes(dep) * 60 + travel) // 86400
        name = (trip.get("name") or "").strip()
        if car_class and car_class not in prices:
            continue
        if args.name and norm(args.name) not in norm(name):
            continue
        if args.after and minutes(dep) < minutes(args.after):
            continue
        if args.before and minutes(dep) > minutes(args.before):
            continue
        if args.overnight and days_later < 1:
            continue
        rows.append({"trip": trip, "prices": prices, "dep": dep, "arr": arr, "travel": travel,
                     "days_later": days_later, "name": name})

    date_text = day.strftime("%d.%m.%Y") if day else ""
    schedule = SITE + "/poezda/rasp_d.php?" + urllib.parse.urlencode(
        [("nnst1", dep_code), ("nnst2", arr_code)] + ([("date", date_text)] if day else []))
    print("ROUTE\t%s\t%s\t%s\t%d" % (dep_name, arr_name, day.isoformat() if day else "any", len(rows)))
    if not trips:
        print("NO_TRAINS\tno direct trains between %s and %s" % (dep_name, arr_name))
        return
    if not rows:
        print("NO_MATCH\t%d direct trains exist, none fit the filters" % len(trips))
        print("LINK_ALL\t%s" % schedule)
        return

    floors = {}
    for row in rows:
        for kind, price in row["prices"].items():
            floors[kind] = min(price, floors.get(kind, price))
    if args.sort == "duration":
        rows.sort(key=lambda row: (row["travel"], minutes(row["dep"])))
    else:
        rows.sort(key=lambda row: minutes(row["dep"]))
    rows = rows[:max(1, min(args.limit, 15))]

    urls = []
    for row in rows:
        trip = row["trip"]
        if day and trip.get("numberForUrl"):
            urls.append(SITE + "/poezda/order/?" + urllib.parse.urlencode([
                ("dep_st", trip["departureStation"]), ("arr_st", trip["arrivalStation"]),
                ("tn", trip["numberForUrl"]), ("date", date_text)]))
        else:
            urls.append(schedule)
    links = urls + [schedule]

    for row, link in zip(rows, links):
        trip = row["trip"]
        arrive = row["arr"] + (" +%d" % row["days_later"] if row["days_later"] else "")
        classes = ", ".join(CLASSES.get(kind, kind) for kind in CLASS_ORDER if kind in row["prices"])
        stations_text = "%s → %s" % (stations.get(trip["departureStation"], [trip["departureStation"]])[0],
                                     stations.get(trip["arrivalStation"], [trip["arrivalStation"]])[0])
        label = row["name"] or ("фирменный" if trip.get("firm") else "-")
        print("TRAIN\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s" % (
            row["dep"], arrive, human_duration(row["travel"]), trip["trainNumber"], label,
            stations_text, classes or "-", link))
    print("LINK_ALL\t%s" % links[-1])
    if floors:
        print("PRICE_FLOOR\t%s" % "; ".join("%s от %d" % (CLASSES.get(kind, kind), floors[kind])
                                           for kind in CLASS_ORDER if kind in floors))
    print("NOTE\tregular timetable in Moscow time; not every train runs every day and times can shift "
          "by a few minutes. PRICE_FLOOR is the lowest price ever seen on the route, real prices for a "
          "date are usually much higher. Live prices and seats are only on Tutu by the link")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
