"""Places, place cards and routes from the 2GIS API. Called by maps.sh.

Commands: search (places near a point or in a city), card (one place in detail), route (A to B by
car, on foot, by bike, taxi or public transport), tour (several places in the best order) and loop
(a circular walk or run of about N km). Checked against the key on 2026-09-29: phones, sites,
photos and review texts are not available for it; the routing API ignores intermediate points in
one request, so multi-point routes are summed leg by leg from the distance matrix.
The key lives outside the skill bundle (ACCESS_FILE). No shebang on purpose: the bundle validator
flags a bare `/usr/bin/...` line.
"""
import argparse
import datetime
import itertools
import json
import math
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

CATALOG = "https://catalog.api.2gis.com"
ROUTING = "https://routing.api.2gis.com"
ACCESS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, os.pardir, ".2gis-auth")
MSK = datetime.timezone(datetime.timedelta(hours=3))
DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
DAYS_RU = ["пн", "вт", "ср", "чт", "пт", "сб", "вс"]
MODES = {"car": "driving", "walk": "walking", "bike": "bicycle", "taxi": "taxi"}
MODE_RU = {"car": "на машине", "walk": "пешком", "bike": "на велосипеде", "taxi": "на такси",
           "transit": "на общественном транспорте"}
PLACE_TYPES = ("branch,building,street,adm_div,attraction,station,station.metro,station_platform,"
               "crossroad,parking,gate,road")
CARD_FIELDS = ("items.point,items.address,items.schedule,items.reviews,items.rubrics,items.org,"
               "items.attribute_groups,items.links,items.dates,items.full_address_name")
LOOP_QUERIES = ("набережная", "парк", "сквер", "бульвар", "сад")
LOOP_NAMES = re.compile(r"набережн|парк|сквер|бульвар|сад\b|сад,|аллея|пруд|площадь", re.I)
LOOP_SKIP = re.compile(r"клуб|причал|кафе|ресторан|магазин|школа|парковк|аттракцион|обсерватори|прокат|"
                       r"детск|салон|студия|отель|хостел|каток|выставк|музей|туалет|вход", re.I)


def fail(text):
    print("Error: " + text)
    raise SystemExit(1)


def bad_request(text):
    """A request the skill can't take as asked (not a 2GIS outage), so the agent rephrases instead of
    telling the user that the map is down."""
    print("BAD_REQUEST\t" + text)
    raise SystemExit(0)


def norm(text):
    return re.sub(r"\s+", " ", re.sub(r"[^\w\s]", " ", (text or "").lower().replace("ё", "е"))).strip()


# Words that say what kind of place it is, not which one: a match on them alone proves nothing.
GENERIC = {"кафе", "ресторан", "бар", "кофейня", "улица", "ул", "проспект", "пр", "площадь", "пл", "переулок",
           "метро", "станция", "м", "вокзал", "город", "г", "парк", "сквер", "сад", "набережная", "магазин",
           "центр", "дом", "здание", "the", "и", "на", "у", "в", "возле", "около", "рядом"}


def meaningful_words(text):
    return [w for w in norm(text).split() if len(w) >= 3 and not w.isdigit() and w not in GENERIC]


def matches(text, item):
    """Does a found item look like what the person named? Word stems against name and address,
    so «Кафе Абырвалг 3000» doesn't turn into the nearest vegetarian cafe."""
    words = meaningful_words(text)
    if not words:
        return True
    if (item.get("name") or "").strip().lower() in ("", "объект", "none"):
        return True  # 2GIS keeps some squares and fields nameless («Объект»): the point is right, the label is not
    haystack = norm(" ".join(str(item.get(k) or "") for k in ("name", "full_name", "address_name", "full_address_name")))
    return any((w if len(w) <= 4 else w[:max(4, len(w) - 3)]) in haystack for w in words)


def display_name(item, fallback=""):
    """2GIS leaves some objects nameless (a playground) or calls them «Объект»: use the kind of place
    from the full name, or what the person said."""
    name = (item.get("name") or "").strip()
    if name and name.lower() not in ("объект", "none"):
        return name
    full = (item.get("full_name") or "").split(",")[-1].strip()
    if full and full.lower() not in ("объект", "none"):
        return full
    return fallback or item.get("purpose_name") or "место без названия"


def key():
    try:
        with open(ACCESS_FILE, encoding="utf-8") as handle:
            return json.load(handle)["key"]
    except (OSError, ValueError, KeyError):
        fail("2gis access file is missing or malformed")


def call(url, params=None, body=None):
    query = urllib.parse.urlencode(dict(params or {}, key=key()), doseq=True)
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(url + "?" + query, data=data, headers={
        "Content-Type": "application/json", "User-Agent": "souz-maps-skill/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=25) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        if error.code in (401, 403):
            fail("2gis refused the request (%s): this part of the API is closed for the key" % error.code)
        fail("2gis returned %s" % error.code)
    except (urllib.error.URLError, OSError) as error:
        fail("2gis unreachable: %s" % getattr(error, "reason", error))
    except ValueError:
        fail("2gis returned non-JSON")


PAGE_SIZE = 10  # the most this key accepts per request


def items(params, pages=1):
    """Search results, fetched page by page; API errors are reported, not swallowed."""
    found = []
    for page in range(1, pages + 1):
        data = call(CATALOG + "/3.0/items", dict(params, page_size=PAGE_SIZE, page=page))
        meta = data.get("meta") or {}
        if meta.get("code") == 404:
            break
        if meta.get("code") != 200:
            fail("2gis search error: %s" % (meta.get("error") or {}).get("message", meta.get("code")))
        batch = (data.get("result") or {}).get("items") or []
        found += batch
        if len(batch) < PAGE_SIZE:
            break
    return found


# --- places -------------------------------------------------------------------------------

CITY_ALIASES = {"спб": "санкт петербург", "питер": "санкт петербург", "петербург": "санкт петербург",
                "мск": "москва", "екб": "екатеринбург", "нск": "новосибирск", "нижний": "нижний новгород"}


ENDINGS = ("ом", "ой", "ах", "ях", "ем", "ий", "ей", "ый", "ая", "ое", "ые", "е", "у", "а", "я", "и", "ы", "ю", "о", "ь")


def stem(word):
    for ending in ENDINGS:
        if len(word) > 4 and word.endswith(ending):
            return word[:-len(ending)]
    return word


def same_city(asked, found):
    """«в Москве» = Москва, «Казани» = Казань, «Питер» = Санкт-Петербург, «Нижний» = Нижний Новгород,
    but «Кукуево» ≠ «Кукуевка»: word stems compared, not first letters."""
    a = norm(re.sub(r"^\s*(в|во|г\.?|город)\s+", "", asked or "", flags=re.I))
    a = CITY_ALIASES.get(a) or CITY_ALIASES.get(stem(a)) or a
    f = norm(found)
    return a == f or [stem(w) for w in a.split()] == [stem(w) for w in f.split()]


def find_region(city):
    """2GIS regions are big cities with their suburbs: «Подольск» comes back as «Москва». A town inside
    a region is found as a settlement there; a name that matches nothing exactly (2GIS turns
    «Кукуево» into Челябинск) stops with CITY_UNKNOWN and the closest names, so the agent asks."""
    data = call(CATALOG + "/2.0/region/search", {"q": city, "fields": "items.code,items.default_pos"})
    found = (data.get("result") or {}).get("items") or []
    if found and same_city(city, found[0].get("name")):
        return found[0]
    near_misses = []
    for region in found[:3]:
        towns = items({"q": city, "region_id": region["id"], "type": "adm_div.city,adm_div.settlement",
                       "fields": "items.point,items.full_name"})
        exact = [t for t in towns if t.get("point") and same_city(city, t.get("name"))]
        if exact:
            print("CITY\t%s\t%s\tрегион 2ГИС: %s" % (city, exact[0]["name"], region["name"]))
            return dict(region, settlement=exact[0])
        near_misses += ["%s (%s)" % (t.get("name"), region["name"]) for t in towns[:1] if t.get("name")]
    print("CITY_UNKNOWN\t%s\t%s" % (city, ", ".join(near_misses) or "-"))
    raise SystemExit(0)


def resolve(text, region):
    """A place, address, station or sight -> item with a point. Coordinates «55.73,37.60» pass as is."""
    match = re.fullmatch(r"\s*(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)\s*", text)
    if match:
        lat, lon = float(match.group(1)), float(match.group(2))
        return {"name": text, "point": {"lat": lat, "lon": lon}, "id": None, "type": "coordinates"}
    base = {"region_id": region["id"], "fields": "items.point,items.address,items.full_address_name,items.full_name"}
    # The first hit is not always the place: «Марсово поле» brings a building called «Объект» first,
    # a made-up cafe brings a real one. Take the first hit that looks like what was named.
    for params in (dict(base, q=text, type=PLACE_TYPES), dict(base, q=text)):
        found = [item for item in items(params) if item.get("point") and matches(text, item)]
        if found:
            return dict(found[0], name=display_name(found[0], text))
    geo = call(CATALOG + "/3.0/items/geocode", {"q": "%s, %s" % (region["name"], text),
                                                "fields": "items.point,items.full_name"})
    found = [item for item in (geo.get("result") or {}).get("items") or [] if item.get("point") and matches(text, item)]
    return dict(found[0], name=display_name(found[0], text)) if found else None


def resolve_or_stop(text, region):
    place = resolve(text, region)
    if not place:
        print("PLACE_UNKNOWN\t%s\t%s" % (text, region["name"]))
        raise SystemExit(0)
    return place


def distance_m(a, b):
    lat1, lon1, lat2, lon2 = map(math.radians, (a["lat"], a["lon"], b["lat"], b["lon"]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(h))


def bearing(a, b):
    y = math.sin(math.radians(b["lon"] - a["lon"])) * math.cos(math.radians(b["lat"]))
    x = (math.cos(math.radians(a["lat"])) * math.sin(math.radians(b["lat"]))
         - math.sin(math.radians(a["lat"])) * math.cos(math.radians(b["lat"])) * math.cos(math.radians(b["lon"] - a["lon"])))
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def human_distance(meters):
    return "%d м" % round(meters, -1) if meters < 1000 else ("%.1f км" % (meters / 1000)).replace(".", ",")


def human_duration(seconds):
    minutes = int(round(seconds / 60))
    hours, minutes = divmod(minutes, 60)
    return ("%d ч %d мин" % (hours, minutes) if minutes else "%d ч" % hours) if hours else "%d мин" % max(minutes, 1)


def weighted_rating(item):
    """Rating pulled towards 4.0 when there are few reviews, so 5.0 from three people doesn't win."""
    reviews = item.get("reviews") or {}
    score, votes = reviews.get("general_rating") or 0, reviews.get("general_review_count_with_stars") or 0
    return (score * votes + 4.0 * 20) / (votes + 20)


def rating_text(item):
    reviews = item.get("reviews") or {}
    if not reviews.get("general_rating"):
        return "-"
    return "%s (%s отзывов, %s оценок)" % (reviews["general_rating"], reviews.get("general_review_count") or 0,
                                           reviews.get("general_review_count_with_stars") or 0)


def hours_today(item, now):
    schedule = item.get("schedule") or {}
    if schedule.get("is_24x7"):
        return "круглосуточно"
    today = (schedule.get(DAYS[now.weekday()]) or {}).get("working_hours") or []
    if not today:
        return "сегодня закрыто" if schedule else "-"
    spans = ["%s–%s" % (span["from"], span["to"]) for span in today]
    clock = now.strftime("%H:%M")
    is_open = any(span["from"] <= clock < span["to"] or (span["to"] < span["from"] and (clock >= span["from"] or clock < span["to"]))
                  or span["to"] == "24:00" and clock >= span["from"] for span in today)
    return "сегодня %s, %s" % (", ".join(spans), "сейчас открыто" if is_open else "сейчас закрыто")


def week_schedule(item):
    schedule = item.get("schedule") or {}
    if schedule.get("is_24x7"):
        return "круглосуточно"
    days = []
    for code, name in zip(DAYS, DAYS_RU):
        spans = (schedule.get(code) or {}).get("working_hours") or []
        days.append("%s %s" % (name, ", ".join("%s–%s" % (s["from"], s["to"]) for s in spans) or "выходной"))
    return "; ".join(days) if schedule else "-"


def attributes(item, limit=None):
    names = [a["name"] for group in item.get("attribute_groups") or [] for a in group.get("attributes") or []
             if a.get("name") and a["name"] != "Данные актуальны"]
    return names[:limit] if limit else names


def place_link(region, item):
    if item.get("id") and item.get("type") == "branch":
        return "https://2gis.ru/%s/firm/%s" % (region["code"], item["id"].split("_")[0])
    if item.get("id"):
        return "https://2gis.ru/%s/geo/%s" % (region["code"], item["id"].split("_")[0])
    point = item["point"]
    return "https://2gis.ru/%s?m=%s,%s/17" % (region["code"], point["lon"], point["lat"])


def route_link(region, points):
    path = "|".join("%s,%s" % (p["lon"], p["lat"]) for p in points)
    return "https://2gis.ru/%s/directions/points/%s" % (region["code"], urllib.parse.quote(path, safe=""))


def cmd_search(args, region):
    now = datetime.datetime.now(MSK)
    params = {"q": args.query, "region_id": region["id"], "fields": CARD_FIELDS}
    anchor = None
    if not args.near and region.get("settlement"):
        # A town inside a bigger 2GIS region (Подольск in «Москва»): search around the town itself.
        town = region["settlement"]
        anchor = {"name": town.get("name"), "point": town["point"]}
        params.update(location="%s,%s" % (town["point"]["lon"], town["point"]["lat"]), radius=max(args.radius, 7000))
        args.radius = max(args.radius, 7000)
        if args.sort != "relevance":
            params["sort"] = "distance"
    if args.near:
        # 2GIS drops the radius when sorting by rating, so fetch by distance inside the radius
        # and rank by rating here.
        anchor = resolve_or_stop(args.near, region)
        params.update(location="%s,%s" % (anchor["point"]["lon"], anchor["point"]["lat"]), radius=args.radius)
        if args.sort != "relevance":
            params["sort"] = "distance"
    elif args.sort == "rating":
        params["sort"] = "rating"
    if args.open_now:
        params["work_time"] = "now"
    found = [item for item in items(params, pages=2 if args.limit <= 5 else 3) if item.get("point")]
    if anchor:
        found = [item for item in found if distance_m(anchor["point"], item["point"]) <= args.radius * 1.1]
    if args.min_rating:
        found = [item for item in found if ((item.get("reviews") or {}).get("general_rating") or 0) >= args.min_rating]
    if args.sort == "rating":
        found.sort(key=weighted_rating, reverse=True)
    print("SEARCH\t%s\t%s\t%s\t%s" % (args.query, region["name"], anchor["name"] if anchor else "-",
                                      "открытые сейчас" if args.open_now else "-"))
    if not found:
        print("NO_RESULTS\tnothing found, try a wider radius or another wording")
    for item in found[:args.limit]:
        rubric = next((r["name"] for r in item.get("rubrics") or [] if r.get("kind") == "primary"), "-")
        distance = human_distance(distance_m(anchor["point"], item["point"])) if anchor else "-"
        print("PLACE\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s" % (
            display_name(item, args.query), rubric, item.get("address_name") or item.get("full_address_name") or "-",
            distance, rating_text(item), hours_today(item, now), ", ".join(attributes(item, 4)) or "-",
            place_link(region, item)))
    print("LINK_ALL\thttps://2gis.ru/%s/search/%s" % (region["code"], urllib.parse.quote(args.query)))


def cmd_card(args, region):
    now = datetime.datetime.now(MSK)
    if args.id:
        data = call(CATALOG + "/3.0/items/byid", {"id": args.id, "fields": CARD_FIELDS})
        found = (data.get("result") or {}).get("items") or []
    else:
        params = {"q": args.name, "region_id": region["id"], "fields": CARD_FIELDS}
        if args.near:
            anchor = resolve_or_stop(args.near, region)
            params.update(location="%s,%s" % (anchor["point"]["lon"], anchor["point"]["lat"]), sort="distance")
        found = [item for item in items(params) if matches(args.name, item)]
    if not found:
        print("PLACE_UNKNOWN\t%s\t%s" % (args.name or args.id, region["name"]))
        return
    item = found[0]
    org = item.get("org") or {}
    print("CARD\t%s\t%s" % (display_name(item, args.name), ", ".join(r["name"] for r in item.get("rubrics") or [])))
    print("ADDRESS\t%s\t%s" % (item.get("full_address_name") or item.get("address_name") or "-", item.get("address_comment") or "-"))
    print("RATING\t%s" % rating_text(item))
    print("HOURS\t%s\t%s" % (hours_today(item, now), week_schedule(item)))
    print("FEATURES\t%s" % (", ".join(attributes(item)) or "-"))
    print("CHAIN\t%s" % ("%s точек сети «%s»" % (org["branch_count"], org.get("primary") or org.get("name"))
                         if org.get("branch_count", 0) > 1 else "не сеть"))
    print("ENTRANCES\t%d" % len(((item.get("links") or {}).get("entrances")) or []))
    print("UPDATED\t%s" % ((item.get("dates") or {}).get("updated_at") or "-")[:10])
    print("LINK\t%s" % place_link(region, item))
    print("NOTE\tphones, websites, photos and review texts are not available through this key")


def matrix(points, mode):
    body = {"points": [p["point"] for p in points], "sources": list(range(len(points))),
            "targets": list(range(len(points)))}
    if mode in ("walk", "bike"):
        body["transport"] = MODES[mode]
    else:
        body["type"] = "jam"
    data = call(ROUTING + "/get_dist_matrix", {"version": "2.0"}, body)
    table = {}
    for route in data.get("routes") or []:
        if route.get("status") == "OK":
            table[(route["source_id"], route["target_id"])] = (route["distance"], route["duration"])
    return table


def legs_of(points, mode):
    """Leg lengths along the given order, one routing call per leg (the API skips via points)."""
    legs = []
    for a, b in zip(points, points[1:]):
        data = call(ROUTING + "/routing/7.0.0/global", body={
            "points": [dict(a["point"], type="stop"), dict(b["point"], type="stop")],
            "transport": MODES[mode], "output": "summary"})
        result = (data.get("result") or [{}])[0]
        if data.get("status") not in (None, "OK") or not result.get("length"):
            legs.append(None)  # 2GIS answered ROUTE_NOT_FOUND or nothing: no route, not a zero one
        else:
            legs.append((result["length"], result.get("duration") or 0))
    return legs


def far_points(points, mode, limit_km=25):
    """A stop much farther than the rest usually means 2GIS picked a namesake far away
    («Хогвартс» is a kids' club outside the city): say it, so the agent checks with the person."""
    if mode not in ("walk", "bike") or len(points) < 2:
        return
    for p in points[1:]:
        km = distance_m(points[0]["point"], p["point"]) / 1000
        if km > limit_km:
            print("FAR\t%s\t%s от %s\t%s" % (p["name"], human_distance(km * 1000), points[0]["name"],
                                              p.get("full_address_name") or p.get("address_name") or "-"))


def cmd_route(args, region):
    stops = [args.origin] + (split_places(args.via) if args.via else []) + [args.destination]
    points = [resolve_or_stop(text, region) for text in stops]
    print("ROUTE\t%s\t%s" % (" → ".join(p["name"] for p in points), MODE_RU[args.mode]))
    far_points(points, args.mode)
    if args.mode == "transit":
        data = call(ROUTING + "/public_transport/2.0", body={
            "source": {"point": points[0]["point"]}, "target": {"point": points[-1]["point"]}, "locale": "ru",
            "transport": ["metro", "bus", "tram", "trolleybus", "shuttle_bus", "suburban_train", "monorail",
                          "funicular_railway", "river_transport", "mcd", "mcc"]})
        options = data if isinstance(data, list) else []
        if not options:
            print("NO_RESULTS\tno public transport route found")
        for number, option in enumerate(options[:2], 1):
            steps = []
            moves = option.get("movements") or []
            for index, move in enumerate(moves):
                if move.get("type") == "walkway" and move.get("distance"):
                    steps.append("пешком %s" % human_distance(move["distance"]))
                elif move.get("type") == "passage":
                    start = (move.get("waypoint") or {}).get("name") or ""
                    finish = ((moves[index + 1].get("waypoint") or {}).get("name") if index + 1 < len(moves) else "") or ""
                    metro = move.get("metro")
                    if metro:
                        details = [metro.get("ui_station_count"), metro.get("ui_direction_suggest")]
                        if metro.get("exit_entrance_number"):
                            details.append("выход %s%s" % (metro["exit_entrance_number"],
                                                           " — " + metro["exit_comment"] if metro.get("exit_comment") else ""))
                        steps.append("метро, %s, от «%s» до «%s» (%s)" % (
                            metro.get("line_name") or "линия", start, finish, ", ".join(d for d in details if d)))
                    else:
                        route = (move.get("routes") or [{}])[0]
                        line = "%s %s" % (route.get("subtype_name") or "транспорт", ", ".join(route.get("names") or []))
                        stops_count = len((move.get("platforms") or {}).get("names") or [])
                        steps.append("%s от «%s» до «%s»%s" % (line.strip(), start, finish,
                                                              ", %d ост." % stops_count if stops_count else ""))
            print("OPTION\t%d\t%s\t%d пересадок\tпешком %s\t%s" % (
                number, human_duration(option.get("total_duration") or 0), option.get("transfer_count") or 0,
                human_distance(option.get("total_walkway_distance_m") or sum(
                    m.get("distance") or 0 for m in moves if m.get("type") == "walkway")),
                " → ".join(steps)))
    else:
        legs = legs_of(points, args.mode)
        missing = [(a, b) for (a, b), leg in zip(zip(points, points[1:]), legs) if leg is None]
        if missing:
            for a, b in missing:
                print("NO_ROUTE\t%s → %s\t%s\t2GIS found no route between these points" % (a["name"], b["name"], MODE_RU[args.mode]))
            print("LINK\t%s" % route_link(region, [p["point"] for p in points]))
            return
        total = sum(length for length, _ in legs), sum(duration for _, duration in legs)
        print("TOTAL\t%s\t%s%s" % (human_distance(total[0]), human_duration(total[1]),
                                   "\tс учётом пробок" if args.mode in ("car", "taxi") else ""))
        if len(points) > 2:
            for (a, b), (length, duration) in zip(zip(points, points[1:]), legs):
                print("LEG\t%s → %s\t%s\t%s" % (a["name"], b["name"], human_distance(length), human_duration(duration)))
    print("LINK\t%s" % route_link(region, [p["point"] for p in points]))


def split_places(text):
    return [part.strip() for part in re.split(r"[;|]", text or "") if part.strip()]


def best_order(points, table, start_fixed=True, back_home=False):
    first, rest = (points[0], points[1:]) if start_fixed else (None, points)
    index = {id(p): i for i, p in enumerate(points)}
    best = None
    for order in itertools.permutations(rest):
        path = ([first] if first else []) + list(order) + ([first] if back_home and first else [])
        cost = 0
        for a, b in zip(path, path[1:]):
            leg = table.get((index[id(a)], index[id(b)]))
            if not leg:
                cost = None
                break
            cost += leg[1]
        if cost is not None and (best is None or cost < best[0]):
            best = (cost, path)
    return best[1] if best else points


def cmd_tour(args, region):
    places = split_places(args.places)
    if args.start:
        places = [args.start] + places
    if len(places) < 2:
        bad_request("tour needs a start and at least one place")
    if len(places) > 9:
        bad_request("tour takes the start plus at most 8 places, got %d; split it into two walks" % (len(places) - 1))
    points = [resolve_or_stop(text, region) for text in places]
    table = matrix(points, args.mode)
    path = best_order(points, table, start_fixed=True, back_home=args.back)
    index = {id(p): i for i, p in enumerate(points)}
    legs = [table.get((index[id(a)], index[id(b)])) for a, b in zip(path, path[1:])]
    print("TOUR\t%s\tмест: %d" % (MODE_RU[args.mode], len(points)))
    far_points(points, args.mode)
    for (a, b), leg in zip(zip(path, path[1:]), legs):
        if leg is None:
            print("NO_ROUTE\t%s → %s\t%s\t2GIS found no route between these points" % (a["name"], b["name"], MODE_RU[args.mode]))
    legs = [leg or (0, 0) for leg in legs]
    for number, place in enumerate(path, 1):
        print("STOP\t%d\t%s\t%s" % (number, place["name"], place.get("address_name") or "-"))
    print("TOTAL\t%s\t%s" % (human_distance(sum(l for l, _ in legs)), human_duration(sum(d for _, d in legs))))
    print("LINK\t%s" % route_link(region, loop_link_points(path) if args.back else [p["point"] for p in path]))


def loop_candidates(start, radius):
    found, seen = [], []
    for query in LOOP_QUERIES:
        for item in items({"q": query, "location": "%s,%s" % (start["point"]["lon"], start["point"]["lat"]),
                           "radius": int(radius), "sort": "distance",
                           "type": "street,attraction,adm_div.place,branch", "fields": "items.point"}, pages=2):
            name = item.get("name") or ""
            if not item.get("point") or not LOOP_NAMES.search(name) or LOOP_SKIP.search(name):
                continue
            # A business called «Парк, архитектурное бюро» is not a park: after the comma 2GIS
            # names the kind of place, and it has to be park-like too.
            kind = name.split(",", 1)[1] if "," in name else ""
            if item.get("type") == "branch" and kind and not LOOP_NAMES.search(kind):
                continue
            if not 250 <= distance_m(start["point"], item["point"]) <= radius * 1.5:
                continue  # the API sometimes ignores the radius outside big cities
            if any(distance_m(item["point"], other["point"]) < 300 for other in seen):
                continue
            seen.append(item)
            found.append(item)
    return found


def plan_loop(start, candidates, target):
    """Pick points spread around the start so the straight-line loop is close to the target."""
    detour = 1.3
    best = None
    for count in range(2, 7):
        sectors = {}
        for item in candidates:
            sector = int(bearing(start["point"], item["point"]) // (360 / count))
            sectors.setdefault(sector, []).append(item)
        for scale in (0.5, 0.7, 0.9, 1.1, 1.3):
            want_radius = target / (2 * math.pi) * scale
            chosen = []
            for sector in sorted(sectors):
                chosen.append(min(sectors[sector], key=lambda it: abs(distance_m(start["point"], it["point"]) - want_radius)))
            chosen.sort(key=lambda it: bearing(start["point"], it["point"]))
            path = [start] + chosen + [start]
            estimate = detour * sum(distance_m(a["point"], b["point"]) for a, b in zip(path, path[1:]))
            if best is None or abs(estimate - target) < abs(best[0] - target):
                best = (estimate, chosen)
    return best[1] if best else []


def loop_path(start, through):
    """Shortest circular order through the points, legs taken from the walking distance matrix."""
    points = [start] + through
    table = matrix(points, "walk")
    path = best_order(points, table, start_fixed=True, back_home=True)
    index = {id(p): i for i, p in enumerate(points)}
    legs = [table.get((index[id(a)], index[id(b)]), (0, 0)) for a, b in zip(path, path[1:])]
    return path, legs


def loop_link_points(path):
    """2GIS can't open a route whose finish equals its start («Проезд не найден»), so the link
    finishes about 50 m before the start, on the way in from the last point."""
    points = [p["point"] for p in path]
    last, start = points[-2], points[-1]
    gap = distance_m(last, start)
    share = max(0.0, 1 - 50 / gap) if gap > 100 else 0.5
    points[-1] = {"lon": round(last["lon"] + (start["lon"] - last["lon"]) * share, 6),
                  "lat": round(last["lat"] + (start["lat"] - last["lat"]) * share, 6)}
    return points


def cmd_loop(args, region):
    start = resolve_or_stop(args.start, region)
    target = args.km * 1000
    if args.through:
        through = [resolve_or_stop(text, region) for text in split_places(args.through)][:8]
        path, legs = loop_path(start, through)
    else:
        candidates = loop_candidates(start, max(800, target / 4))
        if not candidates:
            print("NO_RESULTS\tno parks or embankments found around the start, pass --through")
            return
        best, goal = None, target
        for _ in range(3):  # re-aim the straight-line plan by how far the real loop missed
            through = plan_loop(start, candidates, goal)
            if not through:
                break
            path, legs = loop_path(start, through)
            total = sum(length for length, _ in legs)
            if best is None or abs(total - target) < abs(best[2] - target):
                best = (path, legs, total)
            if not total or abs(total - target) <= target * 0.1:
                break
            goal = goal * target / total
        if not best:
            print("NO_RESULTS\tcould not build a loop here, pass --through")
            return
        path, legs = best[0], best[1]
    total = sum(length for length, _ in legs)
    print("LOOP\t%s\tцель %s\tполучилось %s" % (start["name"], human_distance(target), human_distance(total)))
    if any(length == 0 for length, _ in legs):
        print("NO_ROUTE\tsome legs of the loop have no walking route; pass other points via --through")
    for (a, b), (length, _) in zip(zip(path, path[1:]), legs):
        print("LEG\t%s → %s\t%s" % (a["name"], b["name"], human_distance(length)))
    pace = args.pace
    minutes, seconds = (int(x) for x in pace.split(":")) if ":" in pace else (int(pace), 0)
    run_time = total / 1000 * (minutes * 60 + seconds)
    walk_time = sum(duration for _, duration in legs)
    print("TIME\tбегом %s в темпе %s мин/км\tпешком %s" % (human_duration(run_time), pace, human_duration(walk_time)))
    if abs(total - target) > target * 0.15:
        print("OFF_TARGET\t%s differs from the target by more than 15%%; add or drop a point via --through" % human_distance(total))
    print("LINK\t%s" % route_link(region, loop_link_points(path)))
    print("NOTE\tdistance is along pedestrian paths with crossings; surface and elevation are unknown; "
          "the map link ends about 50 m before the start because 2GIS can't open a closed loop")


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    search = sub.add_parser("search")
    search.add_argument("--query", required=True)
    search.add_argument("--city", required=True)
    search.add_argument("--near")
    search.add_argument("--radius", type=int, default=1500)
    search.add_argument("--sort", choices=["distance", "rating", "relevance"], default="distance")
    search.add_argument("--open-now", action="store_true")
    search.add_argument("--min-rating", type=float)
    search.add_argument("--limit", type=int, default=5)
    card = sub.add_parser("card")
    card.add_argument("--city", required=True)
    card.add_argument("--name")
    card.add_argument("--id")
    card.add_argument("--near")
    route = sub.add_parser("route")
    route.add_argument("--city", required=True)
    route.add_argument("--from", dest="origin", required=True)
    route.add_argument("--to", dest="destination", required=True)
    route.add_argument("--via")
    route.add_argument("--mode", choices=list(MODE_RU), default="walk")
    tour = sub.add_parser("tour")
    tour.add_argument("--city", required=True)
    tour.add_argument("--places", required=True, help="places separated by ;")
    tour.add_argument("--start")
    tour.add_argument("--mode", choices=["walk", "car", "bike"], default="walk")
    tour.add_argument("--back", action="store_true")
    loop = sub.add_parser("loop")
    loop.add_argument("--city", required=True)
    loop.add_argument("--start", required=True)
    loop.add_argument("--km", type=float, required=True)
    loop.add_argument("--through", help="points to pass, separated by ;")
    loop.add_argument("--pace", default="6:00")
    args = parser.parse_args()

    if getattr(args, "limit", None):
        args.limit = max(1, min(args.limit, 15))
    if args.command == "card" and not (args.name or args.id):
        bad_request("card needs --name or --id")
    region = find_region(args.city)
    {"search": cmd_search, "card": cmd_card, "route": cmd_route, "tour": cmd_tour, "loop": cmd_loop}[args.command](args, region)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
