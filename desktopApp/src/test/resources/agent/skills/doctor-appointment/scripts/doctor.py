"""Doctors with free appointment slots from the SberHealth (DocDoc) partner API. Called by doctor.sh.

Every request carries the partner id (pid) from the access file outside the bundle. The API sits
behind a bot filter that answers the first request with a cookie and a 307 to the same address,
so cookies are kept between runs (COOKIE_FILE) and the redirect is followed once. The partner
limits are small: the city and speciality lists are cached for a week (CACHE_FILE), one search is
one request. The API returns no booking link, only the doctor's Alias; the link is built as
https://docdoc.ru/doctor/<Alias> for Moscow and https://<city Alias>.docdoc.ru/doctor/<Alias> for other
cities (spb.docdoc.ru for Saint Petersburg, checked by Valera on 2026-10-04: docdoc.ru shows no such doctor).
No shebang on purpose: the bundle validator flags a bare `/usr/bin/...` line.
"""
import argparse
import datetime
import difflib
import http.cookiejar
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.docdoc.ru/public/rest/1.0.13"
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, os.pardir, os.pardir)
ACCESS_FILE = os.path.join(ROOT, ".sberhealth-auth")
COOKIE_FILE = os.path.join(ROOT, ".sberhealth-cookies")
CACHE_FILE = os.path.join(ROOT, ".sberhealth-cache.json")
GIS_FILE = os.path.join(ROOT, ".2gis-auth")
CACHE_DAYS = 7
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36"
DOCTOR_PAGE = "https://%sdocdoc.ru/doctor/%s"
ONLINE_SERVICE = "https://lk.sberhealth.ru/catalog/onlayn-konsultatsii"
ALIASES = {"питер": "Санкт-Петербург", "спб": "Санкт-Петербург", "петербург": "Санкт-Петербург",
           "мск": "Москва", "екб": "Екатеринбург", "нижний": "Нижний Новгород", "нн": "Нижний Новгород",
           "подмосковье": "Московская область", "мо": "Московская область",
           "московская обл": "Московская область", "ростов": "Ростов-на-Дону",
           "новосиб": "Новосибирск", "челны": "Набережные Челны", "питербург": "Санкт-Петербург"}
ORDERS = {"rating": "-rating", "price": "price", "experience": "-experience"}


def fail(text):
    print("Error: " + text)
    raise SystemExit(1)


def pid():
    try:
        with open(ACCESS_FILE, encoding="utf-8") as handle:
            return str(json.load(handle)["pid"])
    except (OSError, ValueError, KeyError):
        fail("sberhealth access file is missing or malformed")


def opener():
    jar = http.cookiejar.MozillaCookieJar(COOKIE_FILE)
    try:
        jar.load(ignore_discard=True, ignore_expires=True)
    except (OSError, http.cookiejar.LoadError):
        pass
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar)), jar


def call(path, params=None):
    query = urllib.parse.urlencode(dict(params or {}, pid=pid()), doseq=True)
    browser, jar = opener()
    request = urllib.request.Request("%s%s?%s" % (API, path, query), headers={
        "User-Agent": UA, "Accept": "application/json", "Accept-Language": "ru"})
    try:
        with browser.open(request, timeout=25) as response:
            body = response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        fail("sberhealth returned %s%s" % (error.code, " (blocked by their bot filter)" if error.code == 403 else ""))
    except (urllib.error.URLError, OSError) as error:
        fail("sberhealth unreachable: %s" % getattr(error, "reason", error))
    finally:
        try:
            jar.save(ignore_discard=True, ignore_expires=True)
            os.chmod(COOKIE_FILE, 0o600)
        except OSError:
            pass
    try:
        return json.loads(body)
    except ValueError:
        fail("sberhealth returned a page instead of data (bot filter); try again in a minute")


def cached(key, loader):
    try:
        with open(CACHE_FILE, encoding="utf-8") as handle:
            cache = json.load(handle)
    except (OSError, ValueError):
        cache = {}
    entry = cache.get(key)
    if entry and time.time() - entry.get("at", 0) < CACHE_DAYS * 86400:
        return entry["data"]
    data = loader()
    cache[key] = {"at": time.time(), "data": data}
    with open(CACHE_FILE, "w", encoding="utf-8") as handle:
        json.dump(cache, handle, ensure_ascii=False)
    return data


def norm(text):
    return re.sub(r"\s+", " ", (text or "").lower().replace("ё", "е")).strip()


def find_city(name):
    cities = cached("cities", lambda: call("/city").get("CityList") or [])
    wanted = norm(ALIASES.get(norm(name), name))
    for city in cities:
        if norm(city.get("Name")) == wanted:
            return city
    close = difflib.get_close_matches(wanted, [norm(c.get("Name")) for c in cities], n=1, cutoff=0.8)
    return next((c for c in cities if close and norm(c.get("Name")) == close[0]), None)


def specialities(city):
    def load():
        data = call("/speciality/city/%s/onlySimple/1" % city["Id"])
        items = data.get("SpecList") or data.get("SpecialityList") or data.get("List") or []
        return [{"Id": s.get("Id"), "Name": s.get("Name"), "Alias": s.get("Alias")} for s in items]
    return cached("specialities:%s" % city["Id"], load)


def find_speciality(city, name):
    items = specialities(city)
    wanted = norm(name)
    for item in items:
        if norm(item["Name"]) == wanted:
            return item
    for item in items:
        if wanted in norm(item["Name"]) or norm(item["Name"]).startswith(wanted[:6]):
            return item
    close = difflib.get_close_matches(wanted, [norm(i["Name"]) for i in items], n=1, cutoff=0.7)
    return next((i for i in items if close and norm(i["Name"]) == close[0]), None)


GIS_PLACE_TYPES = ("branch,building,street,adm_div,attraction,station,station.metro,station_platform,"
                   "crossroad,parking,gate,road")


def gis_items(path, params, key):
    query = urllib.parse.urlencode(dict(params, key=key))
    try:
        with urllib.request.urlopen("https://catalog.api.2gis.com" + path + "?" + query, timeout=15) as response:
            return (json.loads(response.read().decode("utf-8")).get("result") or {}).get("items") or []
    except (urllib.error.URLError, OSError, ValueError):
        return []


def geocode(place, city_name):
    """Address, metro or district -> point via 2GIS, the same way the maps skill finds places.

    The geocoder alone does not know metro stations («метро Новогиреево» came back empty), so the
    place is searched inside the city's region first and the geocoder is only the fallback."""
    try:
        with open(GIS_FILE, encoding="utf-8") as handle:
            key = json.load(handle)["key"]
    except (OSError, ValueError, KeyError):
        return None
    region = next(iter(gis_items("/2.0/region/search", {"q": city_name}, key)), None)
    if region:
        found = gis_items("/3.0/items", {"q": place, "region_id": region["id"], "type": GIS_PLACE_TYPES,
                                         "fields": "items.point", "page_size": 5}, key)
        point = next((i["point"] for i in found if i.get("point")), None)
        if point:
            return point
    found = gis_items("/3.0/items/geocode", {"q": "%s, %s" % (city_name, place), "fields": "items.point"}, key)
    return next((i["point"] for i in found if i.get("point")), None)


def doctor_page(city, alias):
    """Doctor pages live on the city's own site: docdoc.ru is Moscow (and, most likely, the Moscow region),
    spb.docdoc.ru is Saint Petersburg (checked), other cities follow the same <city Alias>.docdoc.ru scheme."""
    city_alias = (city.get("Alias") or "").strip().lower()
    return DOCTOR_PAGE % ("" if city_alias in ("", "msk", "mo") else city_alias + ".", alias)


def slots_of(doctor):
    """Free slots as (datetime text, clinic id) from whichever field the API filled."""
    found = []
    raw = doctor.get("Slots") or doctor.get("DoctorSlotList") or []
    if isinstance(raw, dict):
        raw = [dict(v, ClinicId=v.get("ClinicId") or k) if isinstance(v, dict) else {"ClinicId": k, "List": v}
               for k, v in raw.items()]
    for entry in raw if isinstance(raw, list) else []:
        if not isinstance(entry, dict):
            continue
        clinic = entry.get("ClinicId")
        inner = entry.get("List") or entry.get("Slots") or entry.get("Data") or [entry]
        for slot in inner if isinstance(inner, list) else []:
            if not isinstance(slot, dict):
                continue
            start = slot.get("StartTime") or slot.get("DateTime") or slot.get("Date")
            if start:
                found.append((str(start), slot.get("ClinicId") or clinic))
    return sorted(found)


def human_slot(text):
    try:
        moment = datetime.datetime.fromisoformat(text.replace(" ", "T")[:19])
    except ValueError:
        return text
    today = datetime.date.today()
    day = ("сегодня" if moment.date() == today else "завтра" if moment.date() == today + datetime.timedelta(days=1)
           else moment.strftime("%d.%m"))
    return "%s %s" % (day, moment.strftime("%H:%M"))


def clinic_text(doctor, clinic_id):
    """Clinic name, address and the nearest metro with walking minutes."""
    infos = doctor.get("ClinicsInfo") or []
    chosen = next((i for i in infos if isinstance(i, dict) and (clinic_id is None or str(i.get("ClinicId")) == str(clinic_id))),
                  infos[0] if infos else None)
    if not chosen:
        return "-"
    address = " ".join(str(x) for x in (chosen.get("Street"), chosen.get("House")) if x)
    names = {str(s.get("Id")): s.get("Name") for s in doctor.get("Stations") or [] if isinstance(s, dict)}
    nearest = sorted((d for d in chosen.get("StationsDistance") or [] if isinstance(d, dict) and d.get("TimeWalking")),
                     key=lambda d: d["TimeWalking"])
    metro = ""
    if nearest and names.get(str(nearest[0].get("Station"))):
        metro = "м. %s, %d мин пешком" % (names[str(nearest[0]["Station"])], max(1, round(nearest[0]["TimeWalking"] / 60)))
    return ", ".join(x for x in (chosen.get("ClinicName") or "клиника", address, metro) if x)


def years(value):
    try:
        n = int(value)
    except (TypeError, ValueError):
        return "стаж -"
    word = "лет" if 11 <= n % 100 <= 14 else {1: "год", 2: "года", 3: "года", 4: "года"}.get(n % 10, "лет")
    return "стаж %d %s" % (n, word)


def telemed(doctor):
    """Remote consultation the doctor offers in any clinic: chat and/or phone."""
    kinds = set()
    for item in doctor.get("Telemed") or []:
        if isinstance(item, dict):
            if item.get("Chat"):
                kinds.add("чат")
            if item.get("Phone"):
                kinds.add("звонок")
    return ", ".join(sorted(kinds))


def cmd_find(args):
    city = find_city(args.city)
    if not city:
        print("CITY_UNKNOWN\t%s" % args.city)
        return
    speciality = find_speciality(city, args.speciality)
    if not speciality:
        print("SPECIALITY_UNKNOWN\t%s\t%s" % (args.speciality, ", ".join(s["Name"] for s in specialities(city)[:30])))
        return
    # There is no API filter for remote consultations, so --online asks for more doctors in the same
    # single request and keeps those with chat or phone consultations.
    params = {"city": city["Id"], "speciality": speciality["Id"], "start": 0,
              "count": 50 if args.online else max(args.limit * 3, 10),
              "order": ORDERS[args.order], "withSlots": 1, "slotsDays": args.days,
              "deti" if args.kids else "adults": 1}
    if args.max_price:
        params["max_price"] = args.max_price
    point = geocode(args.near, city["Name"]) if args.near else None
    if point:
        params.update(lat=point["lat"], lng=point["lon"], radius=args.radius)
    data = call("/doctor/list", params)
    doctors = data.get("DoctorList") or []
    print("SEARCH\t%s\t%s\t%s\tнайдено врачей: %s%s" % (
        speciality["Name"], city["Name"], args.near or "весь город", data.get("Total", len(doctors)),
        "" if point or not args.near else "\tместо не распознано, искали по всему городу"))
    shown = 0
    for doctor in doctors:
        slots = slots_of(doctor)
        if args.only_free and not slots:
            continue
        if args.online and not telemed(doctor):
            continue
        first_clinic = slots[0][1] if slots else None
        price = doctor.get("SpecialPrice") or doctor.get("Price") or 0
        specs = ", ".join(s.get("Name", "") for s in doctor.get("Specialities") or [] if isinstance(s, dict)) or speciality["Name"]
        print("DOCTOR\t%s\t%s\t%s\tрейтинг %s (%s отзывов)\t%s\t%s\tонлайн: %s\t%s\t%s" % (
            doctor.get("Name"), specs, years(doctor.get("ExperienceYear")), doctor.get("Rating") or "-",
            doctor.get("OpinionCount") or 0, "приём %s ₽" % price if price else "цена на сайте",
            clinic_text(doctor, first_clinic), telemed(doctor) or "нет",
            "; ".join(human_slot(s) for s, _ in slots[:4]) or "свободных окон нет",
            doctor_page(city, doctor.get("Alias")) if doctor.get("Alias") else "-"))
        shown += 1
        if shown >= args.limit:
            break
    if not shown:
        print("NO_RESULTS\t%s" % ("no doctors with remote consultations among the first 50; offer the SberHealth online service"
                                     if args.online else "no doctors with free slots for these filters; try more days or the whole city"))
    if args.online:
        print("ONLINE_SERVICE\t%s\tSberHealth's own online consultations with on-duty doctors" % ONLINE_SERVICE)
    print("NOTE\tthe link opens the doctor's page where the person picks a time and books; nothing is booked here")


def cmd_specialities(args):
    city = find_city(args.city)
    if not city:
        print("CITY_UNKNOWN\t%s" % args.city)
        return
    items = specialities(city)
    if args.search:
        wanted = norm(args.search)
        items = [i for i in items if wanted in norm(i["Name"])]
    print("SPECIALITIES\t%s\t%s" % (city["Name"], ", ".join(i["Name"] for i in items[:80])))


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    find = sub.add_parser("find")
    find.add_argument("--city", required=True)
    find.add_argument("--speciality", required=True)
    find.add_argument("--near")
    find.add_argument("--radius", type=int, default=3)
    find.add_argument("--order", choices=list(ORDERS), default="rating")
    find.add_argument("--kids", action="store_true")
    find.add_argument("--max-price", type=int)
    find.add_argument("--days", type=int, default=7)
    find.add_argument("--only-free", action="store_true")
    find.add_argument("--online", action="store_true", help="only doctors with chat or phone consultations")
    find.add_argument("--limit", type=int, default=3)
    spec = sub.add_parser("specialities")
    spec.add_argument("--city", required=True)
    spec.add_argument("--search")
    args = parser.parse_args()
    if args.command == "find":
        args.limit = max(1, min(args.limit, 8))
        args.days = max(1, min(args.days, 21))
        cmd_find(args)
    else:
        cmd_specialities(args)


if __name__ == "__main__":
    main()
