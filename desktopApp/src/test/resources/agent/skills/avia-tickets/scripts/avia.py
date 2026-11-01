"""Flight prices from the Aviasales Data API (Travelpayouts). Called by avia.sh, not by the model directly.

Prices come from the cache of real Aviasales searches over the last 48 hours; the link opens a live
search where the final price is shown. Access data lives outside the skill bundle (see ACCESS_FILE),
resolved relative to this file because skills run inside a container with their own workdir.
No shebang on purpose: a bare `/usr/bin/...` line trips the bundle validator.
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.travelpayouts.com/aviasales/v3"
AUTOCOMPLETE = "https://autocomplete.travelpayouts.com/places2"
SITE = "https://www.aviasales.ru"
ACCESS_FILE = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), os.pardir, os.pardir, ".avia-auth"
)
AIRLINES = {
    "SU": "Аэрофлот", "DP": "Победа", "S7": "S7", "U6": "Уральские авиалинии", "UT": "Utair",
    "N4": "Nordwind", "5N": "Smartavia", "FV": "Россия", "WZ": "Red Wings", "A4": "Азимут",
    "Y7": "NordStar", "R3": "Якутия", "EO": "Pegas Fly", "I8": "Ижавиа", "YC": "Ямал",
    "TK": "Turkish Airlines", "EK": "Emirates", "QR": "Qatar Airways", "HY": "Uzbekistan Airways",
    "J2": "AZAL", "DV": "SCAT", "7R": "RusLine", "PC": "Pegasus", "XC": "Corendon", "AZ": "ITA Airways", "LH": "Lufthansa", "FZ": "flydubai", "G9": "Air Arabia", "KC": "Air Astana", "B2": "Belavia", "5P": "Nordwind", "CZ": "China Southern",
}


def fail(text):
    print("Error: " + text)
    raise SystemExit(1)


def read_access():
    try:
        with open(ACCESS_FILE, encoding="utf-8") as handle:
            data = json.load(handle)
    except FileNotFoundError:
        fail("avia access file is missing")
    except json.JSONDecodeError:
        fail("avia access file is malformed")
    if not data.get("key"):
        fail("avia access file has no key")
    return data["key"], data.get("marker", "")


def http_get(url, params, access=None):
    query = urllib.parse.urlencode(params, doseq=True)
    headers = {"User-Agent": "souz-avia-skill/1.0"}
    if access:
        headers["X-Access-Token"] = access
    request = urllib.request.Request(url + "?" + query, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        fail("aviasales service returned %s" % error.code)
    except urllib.error.URLError as error:
        fail("aviasales service unreachable: %s" % error.reason)
    except json.JSONDecodeError:
        fail("aviasales service returned non-JSON")


ALIASES = {
    "питер": "Санкт-Петербург", "спб": "Санкт-Петербург", "петербург": "Санкт-Петербург",
    "мск": "Москва", "екб": "Екатеринбург", "нижний": "Нижний Новгород", "новосиб": "Новосибирск",
    "ростов": "Ростов-на-Дону", "минводы": "Минеральные Воды", "калининград": "Калининград",
    "набережные челны": "Набережные Челны", "челны": "Набережные Челны", "владик": "Владивосток",
    "симф": "Симферополь", "крым": "Симферополь", "стамбул": "Стамбул", "дубай": "Дубай",
}


def resolve_city(name):
    """City name (Russian or Latin) or IATA code -> (code, Russian name)."""
    name = name.strip()
    name = ALIASES.get(name.lower().replace("ё", "е"), name)
    if len(name) == 3 and name.isascii() and name.isalpha():
        return name.upper(), name.upper()
    data = http_get(AUTOCOMPLETE, {"term": name, "locale": "ru", "types[]": "city"})
    if not data:
        fail("unknown city: %s" % name)
    code, resolved = data[0]["code"], data[0]["name"]
    country = data[0].get("country_name", "")
    if not _same_city(name, resolved):
        # autocomplete is fuzzy: "Кукуево" -> "Коккола". Tell the model so it can confirm with the user.
        print("\t".join(["CITY", name, resolved, code, country]))
    return code, resolved


def _same_city(asked, resolved):
    if asked.isascii():
        return True  # Latin input -> Cyrillic name is a normal transliteration, not a substitution
    a, r = asked.lower().replace("ё", "е"), resolved.lower().replace("ё", "е")
    return a == r or r.startswith(a[:4]) or a.startswith(r[:4])


_CODE_NAMES = {}


def city_name(code):
    """IATA code -> Russian city name (for special offers), cached."""
    if code not in _CODE_NAMES:
        try:
            data = http_get(AUTOCOMPLETE, {"term": code, "locale": "ru", "types[]": "city"})
            hit = next((d for d in data if d.get("code") == code), None)
            _CODE_NAMES[code] = "%s (%s)" % (hit["name"], hit.get("country_name", "")) if hit else code
        except SystemExit:
            _CODE_NAMES[code] = code
    return _CODE_NAMES[code]


def fmt_duration(minutes):
    hours, mins = divmod(int(minutes or 0), 60)
    return "%dч %02dм" % (hours, mins) if hours else "%dм" % mins


def fmt_flight(item, marker):
    departure = item.get("departure_at", "")[:16].replace("T", " ")
    back = item.get("return_at", "")[:16].replace("T", " ")
    transfers = int(item.get("transfers", 0) or 0)
    airline_code = item.get("airline", "")
    airline = AIRLINES.get(airline_code, airline_code)
    link = SITE + item.get("link", "")
    if marker:
        link += ("&" if "?" in link else "?") + "marker=" + marker
    price = int(item.get("price", 0) or 0)
    duration = item.get("duration_to") or item.get("duration") or 0
    return "\t".join([
        "FLIGHT", departure, back or "-", airline, str(item.get("flight_number", "") or ""),
        fmt_duration(duration), "прямой" if transfers == 0 else "пересадок: %d" % transfers,
        str(price), link,
    ])


def main():
    parser = argparse.ArgumentParser(description="Flight prices (Aviasales Data API)")
    parser.add_argument("--from", dest="origin", required=True, help="departure city or IATA")
    parser.add_argument("--to", dest="destination", help="destination city or IATA; omit for special offers")
    parser.add_argument("--when", help="date YYYY-MM-DD or whole month YYYY-MM")
    parser.add_argument("--return", dest="return_at", help="return date YYYY-MM-DD or YYYY-MM")
    parser.add_argument("--limit", type=int, default=5, help="how many options (default 5)")
    parser.add_argument("--direct", action="store_true", help="direct flights only")
    args = parser.parse_args()
    if args.limit < 1 or args.limit > 20:
        fail("limit must be 1..20")

    access, marker = read_access()
    origin_code, origin_name = resolve_city(args.origin)

    if not args.destination:
        data = http_get(API + "/get_special_offers", {"origin": origin_code, "currency": "rub"}, access)
        offers = (data.get("data") or [])[:args.limit]
        print("\t".join(["OFFERS_FROM", origin_name]))
        if not offers:
            print("NO_RESULTS\tno special offers from %s in cache" % origin_name)
            return
        for item in offers:
            dest = city_name(item.get("destination", ""))
            print(fmt_flight(item, marker).replace("FLIGHT\t", "FLIGHT\t→%s\t" % dest, 1))
        print("NOTE\tprices are from the cache of real searches within 48 hours, final price on the link")
        return

    dest_code, dest_name = resolve_city(args.destination)
    params = {
        "origin": origin_code, "destination": dest_code, "currency": "rub",
        "sorting": "price", "limit": args.limit,
        "one_way": "false" if args.return_at else "true",
    }
    if args.when:
        params["departure_at"] = args.when
    if args.return_at:
        params["return_at"] = args.return_at
    if args.direct:
        params["direct"] = "true"

    data = http_get(API + "/prices_for_dates", params, access)
    items = data.get("data") or []
    print("\t".join([
        "ROUTE", origin_name, dest_name, args.when or "any date",
        "round_trip" if args.return_at else "one_way", "direct_only" if args.direct else "any",
    ]))
    if not items:
        print("NO_RESULTS\tno cached prices %s → %s for these dates; try the whole month (YYYY-MM) or without --direct"
              % (origin_name, dest_name))
        return
    for item in items:
        print(fmt_flight(item, marker))
    print("NOTE\tprices are from the cache of real searches within 48 hours, final price on the link")


if __name__ == "__main__":
    main()
