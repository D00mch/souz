"""Hotels with live prices from Ostrovok for a city and dates. Called by ostrovok.sh.

Temporary: this uses the same search requests as ostrovok.ru pages (region suggest + serp session),
not the official partner API (ETG API needs a contract). It can change or get blocked without
notice, so the skill always also returns a link to the same search on the site.
Every requirement goes into the search itself. Filter formats were checked one by one on
2026-09-25: point_distance takes {lat, lng, max_distance in km}; hotel_name matches Latin names
better than Cyrillic; "near a place" searches with the place's own region id sorted by distance.
No shebang on purpose: a bare `/usr/bin/...` line trips the bundle validator.
"""
import argparse
import datetime
import difflib
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

SITE = "https://ostrovok.ru"
SERP = SITE + "/hotel/search/v2/site/serp"
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36"
HEADERS = {"User-Agent": UA, "Accept": "application/json", "Accept-Language": "ru", "Referer": SITE + "/hotel/"}
MSK = datetime.timezone(datetime.timedelta(hours=3))

ALIASES = {
    "питер": "Санкт-Петербург", "спб": "Санкт-Петербург", "петербург": "Санкт-Петербург",
    "мск": "Москва", "екб": "Екатеринбург", "нижний": "Нижний Новгород", "ростов": "Ростов-на-Дону",
    "минводы": "Минеральные Воды", "новосиб": "Новосибирск", "владик": "Владивосток",
    "поляна": "Красная Поляна",
}
SORTS = {"popular": None, "cheap": "price_asc", "expensive": "price_desc", "rating": None, "center": "distance"}
# Ostrovok's own rating sort puts 10/10 with one or two reviews first; "rating" instead asks for
# well-rated places and ranks them by a score that also weighs the number of reviews.
RATING_PRIOR, RATING_WEIGHT = 8.0, 30
KINDS = {
    "hotel": ["hotel", "boutique", "mini_hotel"],
    "apartment": ["apart", "apartment", "apart_hotel"],
    "hostel": ["hostel"],
    "guesthouse": ["guesthouse"],
    "sanatorium": ["sanatorium"],
    "resort": ["resort"],
    "cottage": ["cottage_villa_bungalow"],
    "glamping": ["glamping", "camping"],
}
AMENITIES = {
    "pool": "has_pool", "parking": "has_parking", "pets": "has_pets", "gym": "has_fitness",
    "spa": "has_spa", "jacuzzi": "has_jacuzzi", "kitchen": "kitchen", "ac": "air-conditioning",
    "balcony": "balcony", "view": "with-view", "beach": "beach", "transfer": "has_airport_transfer",
    "kids": "has_kids", "wifi": "has_internet", "washer": "has_washer",
    "private-bathroom": "private-bathroom", "accessible": "has_disabled_support",
    "smoking": "has_smoking", "ev-charger": "has_ecar_charger", "ski": "has_skiing",
    "business": "has_busyness",
}
MEALS = {"breakfast": ["breakfast", "half_board", "full_board", "all_inclusive"],
         "half-board": ["half_board", "full_board", "all_inclusive"],
         "full-board": ["full_board", "all_inclusive"], "all-inclusive": ["all_inclusive"]}
PLACE_TYPES = ("Subway", "Subway (Entrace)", "Railway Station", "Airport", "Point of Interest", "Street",
               "Neighborhood", "Bus Station", "Seaport")
KIND_RU = {
    "hotel": "отель", "apart": "апартаменты", "apartment": "апартаменты", "apart_hotel": "апарт-отель",
    "aparthotel": "апарт-отель", "apartments": "апартаменты", "hostel": "хостел",
    "guesthouse": "гостевой дом", "guest_house": "гостевой дом", "mini_hotel": "мини-отель",
    "bnb": "гостевой дом", "sanatorium": "санаторий", "resort": "курорт", "villa": "вилла",
    "cottage": "коттедж", "cottage_villa_bungalow": "коттедж", "camping": "кемпинг",
    "glamping": "глэмпинг", "boutique": "бутик-отель", "boutique_and_design": "бутик-отель",
}
MEAL_RU = {
    "nomeal": "без питания", "breakfast": "завтрак", "breakfast-buffet": "завтрак",
    "continental-breakfast": "завтрак", "english-breakfast": "завтрак", "american-breakfast": "завтрак",
    "half-board": "полупансион", "full-board": "полный пансион", "all-inclusive": "всё включено",
}


def fail(text):
    print("Error: " + text)
    raise SystemExit(1)


def fetch(url, body=None, timeout=30):
    headers = dict(HEADERS)
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers), timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        fail("ostrovok returned %s" % error.code)
    except (urllib.error.URLError, OSError) as error:
        fail("ostrovok unreachable: %s" % getattr(error, "reason", error))
    except json.JSONDecodeError:
        fail("ostrovok returned non-JSON")


def suggest(query):
    return fetch(SITE + "/api/site/multicomplete.json?" + urllib.parse.urlencode({"query": query, "locale": "ru"})).get("regions") or []


def norm(text):
    return re.sub(r"\s+", " ", (text or "").lower().replace("ё", "е")).strip()


def find_city(city):
    regions = [r for r in suggest(ALIASES.get(norm(city), city.strip())) if r.get("type") == "City"]
    return next((r for r in regions if r.get("country_code") == "RU"), regions[0] if regions else None)


def find_place(place, city):
    """A metro station, railway station, airport or sight inside the city."""
    city_name, city_slug = norm(city["name"]).split(",")[0], city.get("slug") or ""
    for query in (place, "%s %s" % (place, city_name)):
        candidates = [r for r in suggest(query) if r.get("type") in PLACE_TYPES]
        local = [r for r in candidates if city_name in norm(r.get("name"))
                 or (r.get("slug") or "").startswith(city_slug + "_")]
        if local:
            return local[0]
    return None


TRANSLIT = dict(zip("абвгдеёзийклмнопрстуфыэ", "abvgdeeziiklmnoprstufye"),
                ж="zh", х="kh", ц="ts", ч="ch", ш="sh", щ="sch", ъ="", ь="", ю="yu", я="ya")


def latin(text):
    return "".join(TRANSLIT.get(ch, ch) for ch in norm(text))


def pick(names, wanted):
    """Best match of a user phrase among named options: substring first, then fuzzy.
    Chains are written in Latin on Ostrovok («AZIMUT Hotels»), so the phrase is also tried
    transliterated («азимут» -> «azimut»)."""
    for variant in dict.fromkeys((norm(wanted), latin(wanted))):
        for item in names:
            if variant in norm(item["name"]):
                return item
        close = difflib.get_close_matches(variant, [norm(item["name"]) for item in names], n=1, cutoff=0.6)
        found = next((item for item in names if close and norm(item["name"]) == close[0]), None)
        if found:
            return found
    return None


def open_session(params):
    first = dict(params, search_uuid=str(uuid.uuid4()))
    return fetch(SERP + "?body=" + urllib.parse.quote(json.dumps({"session_params": first}, separators=(",", ":"))))


PAGE_SIZE = 20


def results(session, params, extra, wanted):
    """Pages of 20 in Ostrovok's order until `wanted` hotels are collected (at most 3 pages)."""
    hotels, result = [], {}
    for page in range(1, min(3, -(-wanted // PAGE_SIZE)) + 1):
        body = dict({"session_params": params, "page": page}, **extra)
        result = fetch(SERP + "?session=" + session, body)
        # The first answer can come before all suppliers replied; one more poll fills the page.
        if page == 1 and not result.get("search_finished") and len(result.get("hotels") or []) < PAGE_SIZE:
            time.sleep(1.5)
            result = fetch(SERP + "?session=" + session, body)
        hotels += result.get("hotels") or []
        if len(result.get("hotels") or []) < PAGE_SIZE:
            break
    result["hotels"] = hotels
    return result


def cheapest_rate(hotel):
    best = None
    for rate in hotel.get("rates") or []:
        for payment in (rate.get("payment_options") or {}).get("payment_types") or []:
            try:
                amount = float(payment.get("show_amount") or payment.get("amount"))
            except (TypeError, ValueError):
                continue
            if best is None or amount < best[0]:
                best = (amount, rate, payment.get("type"))
    return best


def free_cancel_until(rate):
    for policy in (rate.get("cancellation_info") or {}).get("policies") or []:
        penalty = float((policy.get("penalty") or {}).get("amount") or 0)
        if penalty == 0 and policy.get("end_at"):
            moment = datetime.datetime.fromisoformat(policy["end_at"].replace("Z", "+00:00")).astimezone(MSK)
            return "бесплатная отмена до " + moment.strftime("%d.%m %H:%M")
    return "без бесплатной отмены"


def parse_day(text, name):
    try:
        return datetime.date.fromisoformat(text)
    except ValueError:
        fail("%s must be YYYY-MM-DD, got %s" % (name, text))


def split_list(text):
    return [part.strip() for part in re.split(r"[,;]", text or "") if part.strip()]


def build_paxes(adults, rooms, children):
    rooms = max(1, min(rooms, adults))
    paxes = [{"adults": adults // rooms + (1 if index < adults % rooms else 0)} for index in range(rooms)]
    if children:
        paxes[0]["children"] = children
    return paxes


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--city", required=True)
    parser.add_argument("--checkin", required=True)
    parser.add_argument("--checkout")
    parser.add_argument("--adults", type=int, default=2)
    parser.add_argument("--children", help="ages, e.g. 5,9")
    parser.add_argument("--rooms", type=int, default=1)
    parser.add_argument("--near", help="metro station, railway station, airport or sight")
    parser.add_argument("--radius", type=float, help="km from --near point or from the city centre")
    parser.add_argument("--district")
    parser.add_argument("--sort", choices=list(SORTS), default="popular")
    parser.add_argument("--price-min", type=int)
    parser.add_argument("--price-max", type=int)
    parser.add_argument("--total-min", type=int)
    parser.add_argument("--total-max", type=int)
    parser.add_argument("--stars")
    parser.add_argument("--min-rating", type=int, choices=[5, 6, 7, 8, 9])
    parser.add_argument("--type", choices=list(KINDS))
    parser.add_argument("--breakfast", action="store_true")
    parser.add_argument("--meal", choices=list(MEALS))
    parser.add_argument("--amenities", help="comma list: " + ",".join(AMENITIES))
    parser.add_argument("--free-cancel", action="store_true")
    parser.add_argument("--pay-at-hotel", action="store_true")
    parser.add_argument("--no-card", action="store_true")
    parser.add_argument("--no-deposit", action="store_true")
    parser.add_argument("--metro", action="store_true")
    parser.add_argument("--bedrooms", type=int)
    parser.add_argument("--beds", type=int)
    parser.add_argument("--bed", choices=["double", "separate"])
    parser.add_argument("--room-size", type=int, help="minimum room area, m2")
    parser.add_argument("--superhost", action="store_true")
    parser.add_argument("--chain")
    parser.add_argument("--hotel-name")
    parser.add_argument("--limit", type=int, default=5)
    parser.add_argument("--skip", type=int, default=0, help="how many were already shown, for «show more»")
    args = parser.parse_args()
    limit = max(1, min(args.limit, 20))
    skip = max(0, min(args.skip, 40))

    checkin = parse_day(args.checkin, "checkin")
    checkout = parse_day(args.checkout, "checkout") if args.checkout else checkin + datetime.timedelta(days=1)
    if checkin < datetime.date.today():
        fail("checkin %s is in the past" % checkin)
    if checkout <= checkin:
        fail("checkout must be after checkin")
    nights = (checkout - checkin).days
    adults = max(1, min(args.adults, 12))
    children = [int(age) for age in split_list(args.children) if age.isdigit() and int(age) < 18]
    paxes = build_paxes(adults, args.rooms, children)

    city = find_city(args.city)
    if not city:
        print("CITY_UNKNOWN\t%s" % args.city)
        return
    city_title = (city.get("name") or args.city).split(",")[0]
    anchor = city
    if args.near:
        anchor = find_place(args.near, city)
        if not anchor:
            print("PLACE_UNKNOWN\t%s\t%s" % (args.near, city_title))
            return

    params = {"arrival_date": checkin.isoformat(), "departure_date": checkout.isoformat(), "currency": "RUB",
              "language": "ru", "paxes": paxes, "region_id": anchor["id"]}
    opened = open_session(params)
    related = opened["session_info"]["region_related"]
    unmatched = []

    filters = {}
    if args.price_min is not None or args.price_max is not None:
        filters["price_per_night"] = [args.price_min or 0, args.price_max or 10000000]
    if args.total_min is not None or args.total_max is not None:
        filters["price"] = [args.total_min or 0, args.total_max or 100000000]
    if args.stars:
        filters["stars"] = sorted({int(s) for s in re.findall(r"[1-5]", args.stars)})
    if args.min_rating or args.sort == "rating":
        filters["min_rating"] = max(args.min_rating or 0, 8 if args.sort == "rating" else 0)
    if args.type:
        filters["kinds"] = KINDS[args.type]
    if args.meal or args.breakfast:
        filters["meals"] = MEALS[args.meal or "breakfast"]
    if args.amenities:
        wanted = [AMENITIES[a] for a in split_list(args.amenities) if a in AMENITIES]
        unmatched += ["amenity:" + a for a in split_list(args.amenities) if a not in AMENITIES]
        if wanted:
            filters["amenities"] = wanted
    for flag, key in ((args.free_cancel, "free_cancellation"), (args.no_card, "nocc_only"),
                      (args.no_deposit, "no_deposit"), (args.metro, "metro_nearby"),
                      (args.superhost, "superhost")):
        if flag:
            filters[key] = True
    if args.pay_at_hotel:
        filters["payment_types"] = ["hotel"]
    if args.bedrooms:
        filters["min_bedrooms_count"] = args.bedrooms
    if args.beds:
        filters["min_beds_count"] = args.beds
    if args.bed:
        filters["beddings"] = ["double" if args.bed == "double" else "single"]
    if args.room_size:
        filters["room_size"] = [args.room_size, 1000]
    if args.hotel_name:
        filters["hotel_name"] = args.hotel_name
    if args.radius:
        center = (related.get("region") or {}).get("center") or {}
        if center.get("lat") is not None:
            filters["point_distance"] = {"lat": center["lat"], "lng": center["lng"], "max_distance": args.radius}
    if args.district:
        district = pick(related.get("neighbourhoods") or [], args.district)
        if district:
            filters["neighborhoods"] = [district["id"]]
        else:
            unmatched.append("district:" + args.district)
    if args.chain:
        chain = pick(related.get("otachains") or [], args.chain)
        if chain:
            filters["chain_ids"] = [chain["otachain_id"]]
        elif not args.hotel_name:
            # Not among the city's chains: search it by name instead («Азимут» -> «azimut»).
            filters["hotel_name"] = latin(args.chain)

    extra = {"filters": filters} if filters else {}
    sort = SORTS[args.sort] or ("distance" if args.near else None)
    if sort:
        extra["sort"] = sort
    wanted = skip + limit + 5  # a few spare: some hotels come without a bookable rate
    result = results(opened["session_info"]["session"]["id"], params, extra, wanted)
    if not result.get("hotels") and re.search("[а-яё]", filters.get("hotel_name", ""), re.I):
        # Brand names are often stored in Latin; one more try transliterated.
        filters["hotel_name"] = latin(filters["hotel_name"])
        opened = open_session(params)
        result = results(opened["session_info"]["session"]["id"], params, extra, wanted)
    hotels = result.get("hotels") or []
    if args.sort == "rating":
        def weighted(hotel):
            info = hotel.get("static_vm") or {}
            score, votes = info.get("rating_total") or 0, (info.get("rating") or {}).get("count") or 0
            return (score * votes + RATING_PRIOR * RATING_WEIGHT) / (votes + RATING_WEIGHT)
        hotels = sorted(hotels, key=weighted, reverse=True)

    dates = "%s-%s" % (checkin.strftime("%d.%m.%Y"), checkout.strftime("%d.%m.%Y"))
    query = urllib.parse.urlencode({"q": city["id"], "dates": dates, "guests": adults})
    applied = [f["name"] for f in result.get("available_filter_params") or []
               if any(value.get("applied") for value in f.get("values") or [])]
    sort_label = args.sort if args.sort != "popular" else ("near" if args.near else "popular")
    print("SEARCH\t%s\t%s\t%s\t%d\t%d\t%s\t%s\t%s" % (
        city_title, checkin, checkout, nights, adults + len(children), result.get("total_av_hotels"),
        sort_label, ",".join(applied) or "-"))
    if args.near:
        print("NEAR\t%s\t%s" % (anchor.get("name"), anchor.get("type")))
    for item in unmatched:
        print("NOT_APPLIED\t%s" % item)

    bookable = [hotel for hotel in hotels if cheapest_rate(hotel)]
    shown = 0
    for hotel in bookable[skip:]:
        best = cheapest_rate(hotel)
        amount, rate, pay_type = best
        info = hotel.get("static_vm") or {}
        stars = info.get("star_rating") or 0
        rating = info.get("rating") or {}
        score = "%s (%s отзывов)" % (info.get("rating_total"), rating.get("count")) if info.get("rating_total") else "-"
        metro = (info.get("nearest_subways") or [None])[0]
        metro_text = "%s %d м" % (metro["name"], metro["distance"]) if metro else "-"
        meals = rate.get("meal") or []
        distance = info.get("search_region_center_distance")
        slug = info.get("region_catalog_slug") or city.get("slug")
        link = "%s/hotel/%s/mid%s/%s/?%s" % (SITE, slug, info.get("master_id"), info.get("otahotel_id"), query)
        print("HOTEL\t%s\t%s\t%s\t%s\t%d\t%d\t%s\t%s\t%s\t%s\t%s\t%s\t%s" % (
            info.get("name"), KIND_RU.get((info.get("kind") or "").lower().replace("-", "_"), info.get("kind") or "-"),
            "%d★" % (stars // 10) if stars else "-", score, round(amount), round(amount / nights),
            "%.1f км" % distance if distance is not None else "-", metro_text,
            re.sub(r"\s*\(.*?\)", "", rate.get("room_name") or "-"),
            MEAL_RU.get(meals[0], meals[0]) if meals else "-", free_cancel_until(rate),
            "оплата в отеле" if pay_type == "hotel" else "оплата онлайн", link))
        shown += 1
        if shown >= limit:
            break
    if not shown:
        print("NO_RESULTS\t%s" % ("no more options beyond those already shown" if skip
                                     else "nothing fits, try softer filters or other dates"))
    elif result.get("total_av_hotels") and skip + shown < result["total_av_hotels"]:
        print("MORE\tshown %d-%d of %s; for more call again with --skip %d" % (
            skip + 1, skip + shown, result["total_av_hotels"], skip + shown))
    print("LINK_ALL\t%s/hotel/%s/?%s" % (SITE, city.get("slug"), query))
    print("NOTE\tprices in rubles for the whole stay for all guests and rooms, from Ostrovok today; "
          "distance is to %s; the final price is confirmed on the site when booking" % (
              "the place asked for" if args.near else "the city centre"))


if __name__ == "__main__":
    main()
