"""What is on from the Afisha partner API v3: ideas where to go, films, plays, concerts, shows. Called by afisha.sh.

The API has no search: it hands out a city's catalogue (/creations/page) and schedules
(/creations/{id}/schedule, /places/{id}/schedule), so the catalogue is cached on disk and matched here.
Afisha asks not to pull schedules more often than once in 30 minutes, hence TTL_SCHEDULE.
Access is the partner key header plus the widget key parameter (both in the access file outside the
bundle), and only from IPs Afisha has whitelisted: otherwise every call answers 403 with code 3 or 4.
Orders and payment are not used: each event gets one link, its page on afisha.ru, where the person
picks the session and seats and pays (Valera, 2026-10-16: «нам достаточно ссылки на событие на Афише»).

Checked against the live API on 2026-10-16 (the docs are silent on these):
- DateTo is a date, exclusive: a day is DateFrom=<day>T00:00 & DateTo=<day+1>T00:00, «<day>T23:59» gives 0.
- a popular film's schedule is ~6000 sessions for a week (6 MB); for films ask by cinema day
  (CinemaFormatDateFrom/To, inclusive, night sessions until 06:00 belong to the day before).
- Moscow is ~9400 creations (65 MB): one type after another it takes 35-60 s, so a catalogue is fetched
  type by type in parallel (~15 s) with pages of 2000; a single day of Moscow is ~1300 creations.
- AfishaCreationUrl is filled in /creations/page (about 98%), empty in /creations. Without it the link goes
  to the event's schedule in the Afisha widget, built the way afisha-widget-manager 2.0 does it
  (constructWidgetURL: id through mapi /convert, prefix «w» plus the A/B group from mapi /settings).
- «0+» is on almost half of a day's events, adult exhibitions and lectures too: children's events are the
  Afisha «Дети» category and children's genres, the age label only cuts off what is too old.
No shebang on purpose: the bundle validator flags a bare `/usr/bin/...` line.
"""
import argparse
import concurrent.futures
import datetime
import difflib
import hashlib
import json
import math
import os
import re
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.afisha.ru/v3"
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, os.pardir, os.pardir)
ACCESS_FILE = os.path.join(ROOT, ".afisha-auth")
CACHE_DIR = os.path.join(ROOT, ".afisha-cache")
GIS_FILE = os.path.join(ROOT, ".2gis-auth")
MAPI = "https://mapi.afisha.ru/api/v21"
WIDGET_HOST = "https://www.afisha.ru/"
RETURN_URL = urllib.parse.quote("https://www.afisha.ru", safe="")
EMBED = "redirect"
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36"
TTL_CONVERT = 86400
TTL_CITIES = 86400
TTL_PLACES = 6 * 3600
TTL_CATALOG = 3 * 3600
TTL_SCHEDULE = 1800
PAGE_SIZE = 2000
LOAD_BUDGET = 25  # seconds for paging through a catalogue; the skill call itself times out at 40
ALL_TYPES = ("Concert", "Performance", "Event", "Movie", "Excursion", "UserEvent", "SportEvent")

TYPES = {"movie": "Movie", "кино": "Movie", "фильм": "Movie", "фильмы": "Movie",
         "concert": "Concert", "концерт": "Concert", "концерты": "Concert",
         "theatre": "Performance", "theater": "Performance", "театр": "Performance",
         "спектакль": "Performance", "спектакли": "Performance", "performance": "Performance",
         "sport": "SportEvent", "спорт": "SportEvent", "матч": "SportEvent",
         "excursion": "Excursion", "экскурсия": "Excursion", "экскурсии": "Excursion",
         "event": "Event", "событие": "Event", "события": "Event", "выставка": "Event", "выставки": "Event",
         "шоу": "Event", "фестиваль": "Event", "лекция": "Event",
         "userevent": "UserEvent", "masterclass": "UserEvent", "мастер-класс": "UserEvent",
         "мастер класс": "UserEvent", "мастер-классы": "UserEvent", "all": None, "все": None}
TYPE_WORDS = {"Movie": "кино", "Concert": "концерт", "Performance": "спектакль", "SportEvent": "спорт",
              "Excursion": "экскурсия", "Event": "событие", "UserEvent": "мастер-класс", "Admission": "событие"}
AGE_YEARS = {"Audience0": 0, "Audience3": 3, "Audience6": 6, "Audience12": 12, "Audience14": 14,
             "Audience16": 16, "Audience18": 18}
# Order in which types take turns in a set of ideas, so it is not five cartoons or five concerts in a row.
IDEA_ORDER = {"kids": ("Performance", "Event", "Movie", "UserEvent", "Concert", "Excursion", "SportEvent"),
              "adults": ("Concert", "Performance", "Event", "Movie", "Excursion", "UserEvent", "SportEvent")}
PLACE_KINDS = {"cinema": "Cinema", "кинотеатр": "Cinema", "кино": "Cinema", "theatre": "Theatre",
               "театр": "Theatre", "concert_hall": "ConcertHall", "концертный зал": "ConcertHall",
               "club": "Club", "клуб": "Club", "museum": "Museum", "музей": "Museum",
               "sport": "SportBuilding", "стадион": "SportBuilding", "park": "Park", "парк": "Park",
               "gallery": "Gallery", "галерея": "Gallery", "exhibition": "Exhibition", "выставка": "Exhibition"}
AGES = {"Audience0": "0+", "Audience3": "3+", "Audience6": "6+", "Audience12": "12+",
        "Audience14": "14+", "Audience16": "16+", "Audience18": "18+"}
VIDEO = {"TwoDimensional": "2D", "ThreeDimensional": "3D", "ThreeDimensionalPlus": "3D+", "Imax": "IMAX",
         "FourDx": "4DX", "Hfr": "HFR", "RealD": "RealD", "Luxe": "LUXE", "Muvik": "Мувик", "Auro": "Auro",
         "DVD": "DVD", "SixtyFps": "60 fps", "ScreenX": "ScreenX", "DBox": "D-BOX"}
SOUND = {"OriginalLanguage": "на языке оригинала", "OriginalSubtitles": "дубляж с субтитрами оригинала",
         "RussianSubtitles": "на языке оригинала с субтитрами", "VoiceOverTranslation": "закадровый перевод",
         "SimultaneousTranslation": "синхронный перевод", "GoblinTranslation": "перевод Гоблина",
         "EnglishSubtitles": "с английскими субтитрами", "DolbyAtmos": "Dolby Atmos",
         "OriginalLanguageAndSubtitles": "на языке оригинала с субтитрами",
         "OriginalLanguageEnglishSubtitles": "на языке оригинала с английскими субтитрами",
         "AudioDescription": "с тифлокомментарием"}
FORMATS = {"2d": {"TwoDimensional"}, "3d": {"ThreeDimensional", "ThreeDimensionalPlus", "RealD"},
           "imax": {"Imax"}, "4dx": {"FourDx"}, "screenx": {"ScreenX"},
           "original": {"OriginalLanguage", "RussianSubtitles", "OriginalLanguageAndSubtitles",
                        "OriginalLanguageEnglishSubtitles"}}
KIDS_GENRES = {"ForChildreen", "Cartoons", "FairyTales", "PuppetShows", "NewYearForKids"}
GENRE_SYNONYMS = {"стендап": ["Humor"], "стендапы": ["Humor"], "юмор": ["Humor"],
                  "мультик": ["Cartoons"], "мультики": ["Cartoons"], "мультфильмы": ["Cartoons"],
                  "детский": ["ForChildreen"], "детские": ["ForChildreen"], "ужасы": ["HorrorMovies", "HorrorsAndThrillers"],
                  "классика": ["ClassicalMusic", "ClassicalDrama"], "классическая": ["ClassicalMusic"],
                  "балет": ["Balley"], "фантастика": ["Scifi"], "мюзикл": ["Musicals"],
                  "стендап-концерт": ["Humor"], "иммерсивный": ["Immersive"], "елка": ["NewYearForKids"]}
# Genre names as Afisha labels them (Models.V3.Shared.Genre in the v3 spec).
GENRES = {"Shorts": "Короткометражный", "Mystical": "Мистика", "Cartoons": "Мультфильм",
          "Westerns": "Вестерн", "Biopics": "Биография", "Mystery": "Детектив", "ActionFilms": "Боевик",
          "Documentaries": "Документальный", "HistoricalFilms": "Исторический", "Adventure": "Приключение",
          "Thrillers": "Триллер", "Scifi": "Фантастика", "Anime": "Аниме", "Fantasy": "Фэнтези",
          "Balley": "Балет", "Comedies": "Комедия", "Opera": "Опера", "Musicals": "Мюзикл",
          "TvSeries": "ТВ сериал", "Melodramas": "Мелодрама", "HorrorMovies": "Ужасы",
          "EroticFilms": "Эротика", "ScreenAdaptations": "Экранизация", "Dancing": "Танцы",
          "FairyTales": "Сказка", "ComedyDramas": "Трагикомедия", "Premiere": "Премьеры",
          "DramaFilms": "Драма", "Musical": "Музыкальный", "DramaPlays": "Драматический",
          "ForChildreen": "Детский", "War": "Военный", "Crime": "Криминальный", "Romance": "Ромком",
          "Noir": "Нуар", "TVDrama": "Фильм-спектакль", "UndubbedFilms": "Недублированный",
          "Architecture": "Архитектура", "Rocknroll": "Рок-н-ролл", "Humor": "Юмор",
          "ArtSongs": "Авторская песня", "Blueses": "Блюз", "Ethno": "Этно", "Jazz": "Джаз", "Pop": "Поп",
          "Rock": "Рок", "TradeExhibition": "Выставки-продажи", "ArtsAndCrafts": "Декоративно-прикладная",
          "NaturalScienceExhibitions": "Естественно-научная", "HistoricalExhibitions": "Историческая",
          "IndustrialExhibitions": "Промышленные", "BooksExhibitions": "Книжные", "PuppetShows": "Кукольный",
          "NewYearForKids": "Детские елки", "ClassicalMusic": "Классическая музыка",
          "DancePlays": "Танцевальный", "Performance": "Перформанс", "Recitals": "Творческий вечер",
          "InteractivePlays": "Интерактивная", "PlaysReadings": "Читки пьес", "Operetty": "Оперетта",
          "SoloPerformance": "Моноспектакль", "Hiphop": "Хип-хоп", "Estrada": "Эстрада", "Folk": "Фолк",
          "Avangard": "Авангард", "HeavyRock": "Тяжелый рок", "RussianRock": "Русский рок",
          "Electronic": "Электроника", "ElectroPop": "Электропоп", "TripPop": "Трип-хоп", "Indie": "Инди",
          "Reggae": "Регги", "Ska": "Ска", "Shanson": "Шансон", "Punk": "Панк", "Rnb": "R&B",
          "Bosanova": "Босанова", "BritPop": "Бритпоп", "Dub": "Даб", "Disco": "Диско", "Dnb": "Драм-н-бейс",
          "Country": "Кантри", "Latino": "Латино", "Lounge": "Лаунж", "SynthPop": "Синтипоп", "Soul": "Соул",
          "SoulfulHouse": "Соулфул-хаус", "Techno": "Техно", "Trance": "Транс", "Funk": "Фанк",
          "AcidJazz": "Эйсид-джаз", "Hous": "Хаус", "Flamenco": "Фламенко", "Noise": "Нойз",
          "Gospel": "Госпел", "Postpunk": "Постпанк", "FrenchChanson": "Французский шансон",
          "Klezmer": "Клезмер", "Balkan": "Балканская музыка", "Neoclassic": "Неоклассика",
          "Acoustics": "Акустика", "Ambient": "Эмбиент", "PostRock": "Построк", "Cover": "Кавер",
          "SportsFilm": "Спортивный", "LiveMusic": "Живая музыка", "AnimalExhibition": "Выставки животных",
          "A3209": "Дизайн", "Painting": "Живопись", "Sculpture": "Скульптура", "Video": "Видео",
          "Festivals": "Фестиваль", "GraphicArts": "Графика", "Installations": "Инсталляция",
          "NewMedia": "Новые медиа", "Objects": "Объект", "Lectures": "Лекции", "Excursion": "Экскурсии",
          "MasterClasses": "Мастер-классы", "StreetArt": "Стрит-арт", "DesignMarket": "Дизайн-маркет",
          "MixedTechnique": "Смешанные техники", "ContempDance": "Современный танец",
          "Storytelling": "Сторителлинг", "PlasticPlays": "Пластический", "Immersive": "Иммерсивный",
          "ModernArt": "Современное искусство", "ClassicalDrama": "Классическая драма",
          "ModernDrama": "Современная драма", "ClassicalArt": "Классическое искусство", "Ice": "Ледовые",
          "Circus": "Цирк", "Photo": "Фотография", "AlternativeRock": "Альтернативный рок",
          "Literature": "Литература", "DjSet": "DJ-сет", "Vecherinki": "Вечеринки", "A3250": "Жанр кино",
          "ConcertGenre": "Жанр концерта", "PlayGenre": "Жанр спектакля",
          "TheatreAwards": "Театральные премии", "Experimental": "Экспериментальный",
          "ExGenre": "Жанр выставок", "HorrorsAndThrillers": "Ужасы и триллеры"}
WEEKDAYS = ["пн", "вт", "ср", "чт", "пт", "сб", "вс"]
ALIASES = {"питер": "Санкт-Петербург", "спб": "Санкт-Петербург", "петербург": "Санкт-Петербург",
           "мск": "Москва", "екб": "Екатеринбург", "нижний": "Нижний Новгород", "нн": "Нижний Новгород",
           "ростов": "Ростов-на-Дону", "новосиб": "Новосибирск", "челны": "Набережные Челны"}


def fail(text):
    print("Error: " + text)
    raise SystemExit(1)


def keys():
    try:
        with open(ACCESS_FILE, encoding="utf-8") as handle:
            data = json.load(handle)
        return str(data["partner_key"]), str(data["widget_key"])
    except (OSError, ValueError, KeyError):
        fail("afisha access file is missing or malformed")


def error_info(error):
    try:
        data = json.loads(error.read().decode("utf-8"))
        return str(data.get("Code", "")), str(data.get("Message", ""))
    except (OSError, ValueError, AttributeError):
        return "", ""


def call(path, params=None, widget=False):
    partner, widget_key = keys()
    query = {k: v for k, v in (params or {}).items() if v not in (None, "")}
    if widget:
        query["WidgetKey"] = widget_key
    url = API + path + ("?" + urllib.parse.urlencode(query, doseq=True) if query else "")
    request = urllib.request.Request(url, headers={
        "X-ApiAuth-PartnerKey": partner, "Accept": "application/json", "User-Agent": UA})
    for attempt in (1, 2):
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            code, message = error_info(error)
            if (error.code == 408 or error.code >= 500) and attempt == 1:
                time.sleep(1.5)
                continue
            if code in ("3", "4"):
                fail("afisha has not opened access for this server yet (IP is not in their list)")
            if code == "17" or error.code == 404:
                return None
            if code in ("2", "6"):
                fail("afisha rejected the partner key: %s" % message)
            fail("afisha returned %s %s %s" % (error.code, code, message))
        except (urllib.error.URLError, OSError, ValueError) as error:
            if attempt == 1:
                time.sleep(1.5)
                continue
            fail("afisha unreachable: %s" % getattr(error, "reason", error))
    return None


def cached(key, ttl, loader):
    """Disk cache, one file per key. Cache trouble (another user owns the folder) never breaks a search."""
    path = os.path.join(CACHE_DIR, hashlib.sha1(key.encode("utf-8")).hexdigest()[:20] + ".json")
    try:
        if time.time() - os.path.getmtime(path) < ttl:
            with open(path, encoding="utf-8") as handle:
                return json.load(handle)["data"]
    except (OSError, ValueError, KeyError):
        pass
    data, keep = loader()
    if data is None:
        return None
    try:
        os.makedirs(CACHE_DIR, exist_ok=True)
        now = time.time()
        for name in os.listdir(CACHE_DIR):
            old = os.path.join(CACHE_DIR, name)
            if now - os.path.getmtime(old) > 2 * 86400:
                os.remove(old)
        handle, tmp = tempfile.mkstemp(dir=CACHE_DIR, suffix=".tmp")
        with os.fdopen(handle, "w", encoding="utf-8") as out:
            json.dump({"key": key, "data": data}, out, ensure_ascii=False)
        os.replace(tmp, path)
        if keep < ttl:  # a partial load lives shorter: back-date the file
            os.utime(path, (now - (ttl - keep), now - (ttl - keep)))
    except OSError:
        pass
    return data


def norm(text):
    text = (text or "").lower().replace("ё", "е")
    return re.sub(r"\s+", " ", re.sub(r"[^\w+]+", " ", text)).strip()


def stem(word):
    return word[:-2] if len(word) > 6 else word[:-1] if len(word) > 4 else word


def words_in(words, text):
    """Every query word starts some word of the text, endings ignored («Маргариту» finds «Маргарита»).
    Short words and numbers must match whole: «Би-2» is «би» + «2», not «бисер» and «битва»."""
    tokens = text.split()
    return bool(words) and all(any(t == w if len(w) <= 3 or w.isdigit() else t.startswith(stem(w)) for t in tokens)
                               for w in words)


def money(value):
    return "{:,}".format(int(math.ceil(value))).replace(",", " ")


def plural(n, one, few, many):
    if 11 <= n % 100 <= 14:
        return many
    return {1: one, 2: few, 3: few, 4: few}.get(n % 10, many)


# --- dates -------------------------------------------------------------------------------------------

WEEKDAY_NAMES = {"понедельник": 0, "пн": 0, "monday": 0, "вторник": 1, "вт": 1, "tuesday": 1,
                 "среда": 2, "среду": 2, "ср": 2, "wednesday": 2, "четверг": 3, "чт": 3, "thursday": 3,
                 "пятница": 4, "пятницу": 4, "пт": 4, "friday": 4, "суббота": 5, "субботу": 5, "сб": 5,
                 "saturday": 5, "воскресенье": 6, "вс": 6, "sunday": 6}


def parse_day(text, today):
    value = norm(text)
    named = {"today": 0, "сегодня": 0, "tomorrow": 1, "завтра": 1, "послезавтра": 2}
    if value in named:
        return today + datetime.timedelta(days=named[value])
    weekday = re.sub(r"^(в|во|on|this|next|ближайш\w*)\s+", "", value)
    if weekday in WEEKDAY_NAMES:  # «суббота», «в субботу», «saturday»: the nearest one, today included
        return today + datetime.timedelta(days=(WEEKDAY_NAMES[weekday] - today.weekday()) % 7)
    raw = text.strip()
    for pattern in ("%Y-%m-%d", "%d.%m.%Y", "%d.%m"):
        try:
            day = datetime.datetime.strptime(raw, pattern).date()
        except ValueError:
            continue
        if pattern == "%d.%m":
            day = day.replace(year=today.year)
            if day < today - datetime.timedelta(days=30):
                day = day.replace(year=today.year + 1)
        return day
    print("BAD_DATE\t%s\tнужно today, tomorrow, weekend, день недели, ГГГГ-ММ-ДД или ДД.ММ" % text)
    raise SystemExit(0)


def date_range(args, default_days):
    today = datetime.date.today()
    if args.date:
        if norm(args.date) in ("weekend", "выходные", "на выходных", "в выходные", "these weekend", "this weekend"):
            if today.weekday() == 6:
                return today, today
            saturday = today + datetime.timedelta(days=(5 - today.weekday()) % 7)
            return saturday, saturday + datetime.timedelta(days=1)
        day = parse_day(args.date, today)
        return day, day
    start = parse_day(args.date_from, today) if args.date_from else today
    end = parse_day(args.date_to, today) if args.date_to else start + datetime.timedelta(days=(args.days or default_days) - 1)
    return max(start, today), max(end, start)


def range_text(start, end):
    if start == end:
        return start.strftime("%d.%m")
    return "%s–%s" % (start.strftime("%d.%m"), end.strftime("%d.%m"))


def clock(text):
    if not text:
        return None
    match = re.match(r"^\s*(\d{1,2})(?:[:.](\d{2}))?\s*$", text)
    if not match:
        print("BAD_TIME\t%s\tнужно ЧЧ:ММ" % text)
        raise SystemExit(0)
    return datetime.time(int(match.group(1)) % 24, int(match.group(2) or 0))


def moment(session, field="SessionDateTime"):
    try:
        return datetime.datetime.fromisoformat(session.get(field) or "")
    except ValueError:
        return None


def day_of(session):
    """The day a session belongs to: cinemas count a night show until 06:00 to the evening before."""
    if session.get("CreationType") == "Movie":
        cinema = moment(session, "CinemaFormatSessionDateTime")
        if cinema:
            return cinema.date()
    when = moment(session)
    return when.date() if when else None


def day_word(day):
    today = datetime.date.today()
    if day == today:
        return "сегодня"
    if day == today + datetime.timedelta(days=1):
        return "завтра"
    if (day - today).days > 150 or day.year != today.year and (day - today).days > 60:
        # «пт 02.07» sounds like a date gone by: far dates carry the year
        return "%s %s" % (WEEKDAYS[day.weekday()], day.strftime("%d.%m.%Y"))
    return "%s %s" % (WEEKDAYS[day.weekday()], day.strftime("%d.%m"))


def human_moment(session):
    when, day = moment(session), day_of(session)
    if not when:
        return "-"
    night = " ночью" if day and day != when.date() else ""
    return "%s%s %s" % (day_word(day or when.date()), night, when.strftime("%H:%M"))


def api_dates(start, end):
    """DateTo is taken as a date and is exclusive, so a range ends at midnight after its last day."""
    return {"DateFrom": "%sT00:00:00" % start, "DateTo": "%sT00:00:00" % (end + datetime.timedelta(days=1))}


# --- reference data ----------------------------------------------------------------------------------

def cities():
    return cached("cities-v2", TTL_CITIES, lambda: (
        [{"id": c.get("Id"), "name": c.get("Name"), "lat": (c.get("Coordinates") or {}).get("Latitude"),
          "lng": (c.get("Coordinates") or {}).get("Longitude")} for c in call("/cities", widget=True) or []],
        TTL_CITIES))


def city_center(city):
    """The city's own point: without «near», ideas pick the venue closest to it, not one 100 km out
    (Moscow's catalogue has Serpukhov and Shchyolkovo in it)."""
    if city.get("lat") is None or city.get("lng") is None:
        return None
    return {"lat": city["lat"], "lon": city["lng"]}


def find_city(name):
    items = cities() or []
    wanted = norm(ALIASES.get(norm(name), name))
    for city in items:
        if norm(city["name"]) == wanted:
            return city
    close = difflib.get_close_matches(wanted, [norm(c["name"]) for c in items], n=1, cutoff=0.8)
    return next((c for c in items if close and norm(c["name"]) == close[0]), None)


def city_or_stop(name):
    city = find_city(name)
    if not city:
        names = [c["name"] for c in cities() or []]
        close = difflib.get_close_matches(name, names, n=5, cutoff=0.75)  # 0.5 offered «Ноябрьск» for New York
        print("CITY_UNKNOWN\t%s\t%s" % (name, ", ".join(close) or "в этом городе Афиша не продаёт билеты"))
        raise SystemExit(0)
    return city


def creation_type(text):
    if not text:
        return None
    value = norm(text)
    if value not in TYPES:
        print("TYPE_UNKNOWN\t%s\tmovie, concert, theatre, sport, excursion, event" % text)
        raise SystemExit(0)
    return TYPES[value]


def genre_keys(text):
    if not text:
        return set()
    value = norm(text)
    if value in GENRE_SYNONYMS:
        return set(GENRE_SYNONYMS[value])
    found = {k for k, label in GENRES.items() if norm(label) == value or norm(label).startswith(stem(value))}
    found |= {k for k, label in GENRES.items() if words_in(value.split(), norm(label))}
    if not found:
        print("GENRE_UNKNOWN\t%s\tпопробуй без жанра или другим словом" % text)
        raise SystemExit(0)
    return found


def short_text(c):
    """One line about the event: the short description, or else the first sentence of the long one
    (only ~8% of events have a short description)."""
    text = c.get("ShortDescription") or ""
    if not text or BOILERPLATE.search(text):
        text = ""
        long_text = re.sub(r"<[^>]+>", " ", c.get("Description") or "")
        long_text = re.sub(r"\s+", " ", long_text).strip()
        for sentence in re.split(r"(?<=[.!?…])\s", long_text):
            if BOILERPLATE.search(sentence):
                continue
            text = (text + " " + sentence).strip()
            if len(text) >= 40:  # «СЕНС.» alone is not a description
                break
    return text[:160].rstrip() + ("…" if len(text) > 160 else "")


# Service notes that open many descriptions instead of saying what the event is.
BOILERPLATE = re.compile(r"(строго\s*\d+\+|паспорт|организатор\w* (?:может|могут|вправе|оставля)|возрастн\w+ огранич"
                         r"|график работы|место встречи|продолжительность:|зарегистрируйтесь|обратите внимание"
                         r"|обращаем (?:ваше )?внимание|уважаемые (?:посетители|зрители|гости)|временно закрыт"
                         r"|выиграйте|розыгрыш|^\W*\d{1,2} (?:января|февраля|марта|апреля|мая|июня|июля|августа"
                         r"|сентября|октября|ноября|декабря))", re.I)


def slim_creation(c):
    people = [n for group in c.get("Participants") or [] for n in group.get("Names") or []]
    return {"id": str(c.get("Id")), "type": c.get("Type"), "name": c.get("Name") or "",
            "orig": c.get("OriginalName") or "", "genres": c.get("Genres") or [], "age": c.get("AgeRestriction"),
            "rating": c.get("Rating"), "prio": c.get("AfishaPriority") or 0, "url": c.get("AfishaCreationUrl"),
            "short": short_text(c), "minutes": c.get("Duration"),
            "length": c.get("DurationDescription"), "people": people[:10], "cats": c.get("Categories") or [],
            "country": c.get("Country"), "year": c.get("ProductionYear")}


def catalog(city_id, start=None, end=None, kind=None):
    """All creations on sale in the city (optionally with sessions in [start, end]). Without a type the
    types load in parallel, each with its own cache, so a later typed question reuses them."""
    if not kind:
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(ALL_TYPES)) as pool:
            parts = list(pool.map(lambda k: catalog(city_id, start, end, k), ALL_TYPES))
        return [item for part in parts for item in part]
    key = "catalog:%s:%s:%s:%s" % (city_id, start or "-", end or "-", kind)

    def load():
        items, cursor, began = [], None, time.time()
        while True:
            params = {"CityId": city_id, "CreationType": kind, "Limit": PAGE_SIZE, "Cursor": cursor}
            if start and end:
                params.update(api_dates(start, end))
            page = call("/creations/page", params, widget=True) or {}
            batch = page.get("Creations") or []
            items += [slim_creation(c) for c in batch]
            cursor = page.get("Cursor")
            if not cursor or not batch:
                return items, TTL_CATALOG
            if time.time() - began > LOAD_BUDGET:
                print("NOTE\tкаталог Афиши загружен не полностью, повтори запрос через минуту")
                return items, 600
    return cached(key, TTL_CATALOG, load) or []


def places(city_id):
    def load():
        found = []
        for p in call("/places", {"CityId": city_id}, widget=True) or []:
            coords = p.get("Coordinates") or {}
            found.append({"id": p.get("Id"), "name": p.get("Name") or "", "type": p.get("Type"),
                          "address": p.get("Address") or "", "lat": coords.get("Latitude"),
                          "lng": coords.get("Longitude"),
                          "metro": list(dict.fromkeys(m.get("Name") for m in p.get("MetroStations") or []
                                                      if m.get("Name")))[:2],
                          "chain": (p.get("CinemaChain") or {}).get("Name") or ""})
        return found, TTL_PLACES
    return cached("places:%s" % city_id, TTL_PLACES, load) or []


SESSION_FIELDS = ("Id", "CreationType", "SessionDateTime", "CinemaFormatSessionDateTime", "SaleEndDateTime",
                  "HallName", "VideoFormat", "SoundFormat", "EventFormat", "IsSaleAvailable", "MinPrice", "MaxPrice",
                  "AvailableTicketsCount", "IsPushkinCardAvailable", "FreeEntry", "SberLoyaltyPaymentAvailable")
PLACE_FIELDS = ("Id", "Type", "Name", "Address", "Coordinates", "MetroStations")


def slim_block(block, inner):
    """Schedules are megabytes (a film in Moscow: ~150 cinemas, ~1100 sessions a day): keep what is printed."""
    slim = {"Sessions": [{k: s.get(k) for k in SESSION_FIELDS} for s in block.get("Sessions") or []]}
    if inner == "Place":
        slim["Place"] = {k: (block.get("Place") or {}).get(k) for k in PLACE_FIELDS}
    else:
        slim["Creation"] = {k: v for k, v in (block.get("Creation") or {}).items()
                            if k not in ("Images", "Videos", "MainPhotoCrops", "Description")}
    return slim


def schedule_dates(start, end, cinema):
    if cinema:
        return {"CinemaFormatDateFrom": "%sT00:00:00" % start, "CinemaFormatDateTo": "%sT00:00:00" % end}
    return api_dates(start, end)


def creation_schedule(item, city_id, start, end):
    cinema = item.get("type") == "Movie"
    params = dict(schedule_dates(start, end, cinema), CityId=city_id)
    return cached("schedule:%s:%s:%s:%s" % (item["id"], city_id, start, end), TTL_SCHEDULE, lambda: (
        [slim_block(b, "Place") for b in call("/creations/%s/schedule" % item["id"], params, widget=True) or []],
        TTL_SCHEDULE)) or []


def place_schedule(place, start, end):
    params = schedule_dates(start, end, place.get("type") == "Cinema")
    return cached("place-schedule:%s:%s:%s" % (place["id"], start, end), TTL_SCHEDULE, lambda: (
        [slim_block(b, "Creation") for b in call("/places/%s/schedule" % place["id"], params, widget=True) or []],
        TTL_SCHEDULE)) or []


def creation_by_id(creation_id):
    data = cached("creation:%s" % creation_id, TTL_CATALOG, lambda: (
        call("/creations/%s" % creation_id, widget=True), TTL_CATALOG))
    return slim_creation(data) if data else None


# --- geography ---------------------------------------------------------------------------------------

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
    """Address, metro or district -> point via 2GIS, the same way the maps and doctor skills do it."""
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


def km(point, lat, lng):
    if not point or lat is None or lng is None:
        return None
    rad = math.radians
    dlat, dlng = rad(lat - point["lat"]), rad(lng - point["lon"])
    a = math.sin(dlat / 2) ** 2 + math.cos(rad(point["lat"])) * math.cos(rad(lat)) * math.sin(dlng / 2) ** 2
    return 6371 * 2 * math.asin(math.sqrt(a))


def near_point(args, city):
    if not args.near:
        return None
    point = geocode(args.near, city["name"])
    if not point:
        print("NEAR_UNKNOWN\t%s\tищу по всему городу" % args.near)
    return point


# --- formatting --------------------------------------------------------------------------------------

def price_text(session):
    if session.get("FreeEntry"):
        return "вход свободный"
    low, high = session.get("MinPrice"), session.get("MaxPrice")
    if not low and not high:
        return "цена на сайте"
    if low and high and math.ceil(high) > math.ceil(low):
        return "%s–%s ₽" % (money(low), money(high))
    return "%s ₽" % money(low or high)


def formats_text(session):
    parts = [VIDEO.get(v, v) for v in session.get("VideoFormat") or []]
    parts += [SOUND.get(s, s) for s in session.get("SoundFormat") or []]
    if "Online" in (session.get("EventFormat") or []):
        parts.append("онлайн")
    return ", ".join(parts) or "-"


def extras_text(session):
    parts = []
    left = session.get("AvailableTicketsCount")
    if isinstance(left, int) and 0 < left <= 20:
        parts.append("осталось %d %s" % (left, plural(left, "билет", "билета", "билетов")))
    if session.get("IsPushkinCardAvailable"):
        parts.append("Пушкинская карта")
    if session.get("SberLoyaltyPaymentAvailable"):
        parts.append("можно СберСпасибо")
    return ", ".join(parts) or "-"


def place_text(place):
    if not place:
        return "-"
    metro = place.get("MetroStations") or [{"Name": m} for m in place.get("metro") or []]
    names = list(dict.fromkeys(m.get("Name") for m in metro if m.get("Name")))[:2]  # a station on two lines comes twice
    bits = [place.get("Name") or place.get("name"), place.get("Address") or place.get("address")]
    if names:
        bits.append("м. " + ", ".join(names))
    return ", ".join(b for b in bits if b)


def age_text(item):
    return AGES.get(item.get("age") or "", "-")


def length_text(item):
    if item.get("length"):
        return item["length"]
    minutes = item.get("minutes")
    if not minutes:
        return "-"
    hours, rest = divmod(int(minutes), 60)
    return ("%d ч %02d мин" % (hours, rest)) if hours else ("%d мин" % rest)


def rating_text(item):
    try:
        value = float(item.get("rating") or 0)
    except ValueError:
        value = 0
    return ("рейтинг %.1f" % value).replace(".", ",") if value else "-"


def genres_text(item):
    return ", ".join(GENRES.get(g, g) for g in item.get("genres") or [] if g in GENRES)[:80] or "-"


# --- widget links (afisha-widget-manager 2.0, constructWidgetURL) -----------------------------------

def mapi(path):
    _, widget_key = keys()
    request = urllib.request.Request(MAPI + path, headers={
        "X-Application-key": widget_key, "Accept": "application/json", "User-Agent": UA})
    try:
        with urllib.request.urlopen(request, timeout=8) as response:
            return json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, ValueError):
        return None


def widget_stage():
    """Path prefix and query of the A/B group Afisha puts the widget key in («w» when there is none)."""
    def load():
        experiment = (mapi("/settings") or {}).get("experiment") or {}
        group, exp_id = experiment.get("groupId"), experiment.get("experimentId")
        if group and exp_id:
            return {"prefix": "w" + str(group)[5:], "query": {"experimentId": exp_id, "groupId": group}}, TTL_CONVERT
        return {"prefix": "w", "query": {}}, TTL_CONVERT
    return cached("widget-stage", TTL_CONVERT, load) or {"prefix": "w", "query": {}}


def convert(kind, value):
    """Widget id for an API id; on any failure the original id, as the library does."""
    if value in (None, ""):
        return value
    found = cached("convert:%s:%s" % (kind, value), TTL_CONVERT, lambda: (
        (mapi("/convert/%s/%s" % (kind, urllib.parse.quote(str(value), safe=""))) or {}).get(kind + "Id") or value,
        TTL_CONVERT))
    return found or value


def page_url(item):
    """The Afisha page of an event. Venue schedules come without it, the event card (/creations/{id}) has it."""
    if item.get("url"):
        return item["url"]
    full = creation_by_id(item["id"])
    return (full or {}).get("url")


def prefetch_links(items):
    """Fill in missing Afisha pages from the event cards at once; the widget is the last resort."""
    missing = [item for item in items if not item.get("url")]
    if missing:
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            urls = list(pool.map(page_url, missing))
            for item, url in zip(missing, urls):
                item["url"] = url
            list(pool.map(lambda item: convert("creation", item["id"]), [i for i in missing if not i.get("url")]))


def widget_url(parts, extra=None):
    _, widget_key = keys()
    stage = widget_stage()
    query = dict({"embedType": EMBED}, **stage["query"])
    query.update({k: v for k, v in (extra or {}).items() if v not in (None, "")})
    path = "/".join(str(p) for p in [stage["prefix"]] + parts + [widget_key, RETURN_URL])
    return WIDGET_HOST + path + "?" + urllib.parse.urlencode(query)


def creation_link(item, city_id):
    """The one link per event: its Afisha page, where the person picks the session, seats and pays."""
    if item.get("url"):
        return item["url"]
    return widget_url(["creations", (item.get("type") or "event").lower(), convert("creation", item["id"]), city_id])


def kid_oriented(item):
    return "ForKids" in (item.get("cats") or []) or bool(KIDS_GENRES & set(item.get("genres") or []))


def kids_fit(item, age=None):
    """A children's event: the «Дети» category or a children's genre, or a film or play for 0+…6+.
    With the child's age, anything rated older is out. From 9 on a child goes where grown-ups go if the
    rating allows: a ten- or fourteen-year-old does not want «Буратино» and baby cartoons."""
    years = AGE_YEARS.get(item.get("age") or "")
    if age is not None and years is not None and years > age:
        return False
    if age is not None and age >= 9:
        return years is not None and years <= age and (years >= 6 or not kid_oriented(item) or age < 10)
    return kid_oriented(item) or (years is not None and years <= 6 and item.get("type") in ("Movie", "Performance"))


def is_kids(item):
    return kids_fit(item)


def minutes_of(session):
    """Minutes since the start of the session's day; a night show at 00:30 counts as 24:30."""
    when, day = moment(session), day_of(session)
    return when.hour * 60 + when.minute + (1440 if day and day != when.date() else 0)


def session_ok(session, start, end, args):
    when, day = moment(session), day_of(session)
    if not when or not day or not start <= day <= end:
        return False
    now = datetime.datetime.now(when.tzinfo)
    if when < now or (moment(session, "SaleEndDateTime") or when) < now:
        return False
    if not session.get("IsSaleAvailable") and not session.get("FreeEntry"):
        return False
    after, before = clock(getattr(args, "after", None)), clock(getattr(args, "before", None))
    if after and minutes_of(session) < after.hour * 60 + after.minute:
        return False
    if before and minutes_of(session) > before.hour * 60 + before.minute:
        return False
    return format_ok(session, args)


def format_ok(session, args):
    wanted = FORMATS.get(norm(getattr(args, "format", None) or ""))
    if wanted is None:
        return True
    return bool(wanted & (set(session.get("VideoFormat") or []) | set(session.get("SoundFormat") or [])))


def score(item, query):
    words = query.split()
    name, orig = norm(item["name"]), norm(item["orig"])
    if query in (name, orig):
        return 100
    if name.startswith(query):
        return 85
    if words_in(words, name):
        return 70
    if orig and words_in(words, orig):
        return 65
    if words_in(words, norm(" ".join(item.get("people") or []))):
        return 60
    if difflib.SequenceMatcher(None, query, name).ratio() >= 0.8:
        return 50
    return 0


def wants_kids(args):
    return bool(getattr(args, "kids", False) or getattr(args, "age", None) is not None)


def rating_value(item):
    try:
        return float(item.get("rating") or 0)
    except ValueError:
        return 0.0


def pick(items, args, query=None):
    kind = creation_type(args.type)
    genres = genre_keys(getattr(args, "genre", None))
    chosen = []
    for item in items:
        if kind and item.get("type") != kind:
            continue
        if genres and not genres & set(item.get("genres") or []):
            continue
        if wants_kids(args) and not kids_fit(item, getattr(args, "age", None)):
            continue
        rank = score(item, query) if query else 1
        if rank:
            chosen.append((rank, standup_last(item, kind, genres), item.get("prio") or 0, rating_value(item), item))
    chosen.sort(key=lambda row: (-row[0], row[1], -row[2], -row[3]))
    return [row[4] for row in chosen]


def standup_last(item, kind, genres):
    """Afisha files stand-up under concerts: «подбери концерт» should bring music first, stand-up on request."""
    return int(kind == "Concert" and not genres and "Humor" in (item.get("genres") or []))


# --- commands ----------------------------------------------------------------------------------------

def distance_text(value):
    return ("%.1f км" % value).replace(".", ",") if value is not None else "-"


def upcoming(blocks, start, end, args, place_id=None):
    """(first session, place, sessions) per place with sessions on sale that pass the filters."""
    rows = []
    for block in blocks:
        place = block.get("Place") or {}
        if place_id and str(place.get("Id")) != str(place_id):
            continue
        sessions = sorted((s for s in block.get("Sessions") or [] if session_ok(s, start, end, args)), key=moment)
        if sessions:
            rows.append((moment(sessions[0]), place, sessions))
    return rows


def next_session_text(blocks, args):
    """The nearest session on sale without the date and time filters (format and venue still apply):
    what to offer when the asked day is empty."""
    best = None
    for block in blocks:
        place = block.get("Place") or {}
        if args.place_id and str(place.get("Id")) != str(args.place_id):
            continue
        for session in block.get("Sessions") or []:
            when = moment(session)
            if (when and when >= datetime.datetime.now(when.tzinfo) and format_ok(session, args)
                    and (session.get("IsSaleAvailable") or session.get("FreeEntry")) and (not best or when < best[0])):
                best = (when, session, place)
    if not best:
        return "в ближайшую неделю сеансов в продаже нет" + (" в этом формате" if args.format else "")
    return "%s, %s" % (human_moment(best[1]), place_text(best[2]))


def nearest_text(item, city_id, start, end, args):
    rows = upcoming(creation_schedule(item, city_id, start, end), start, end, args)
    sessions = sorted(((moment(s), place, s) for _, place, group in rows for s in group), key=lambda row: row[0])
    if not sessions:
        return "нет сеансов в продаже на эти даты"
    shown = ["%s, %s, %s" % (human_moment(s), place_text(place), price_text(s)) for _, place, s in sessions[:3]]
    more = len(sessions) - len(shown)
    return "; ".join(shown) + (" (и ещё %d)" % more if more > 0 else "")


def parallel(fn, items, workers=8):
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        return list(pool.map(fn, items))


def cmd_find(args):
    city = city_or_stop(args.city)
    kind = creation_type(args.type)
    query = norm(args.query) if args.query else None
    dated = bool(args.date or args.date_from or args.date_to or args.days)
    start, end = date_range(args, 14)
    if query and not dated:
        items = catalog(city["id"], kind=kind)  # a named play or concert can be months ahead
        start, end = datetime.date.today(), datetime.date.today() + datetime.timedelta(days=365)
    else:
        items = catalog(city["id"], start, end, kind)
    found = pick(items, args, query)
    if query and found and score(found[0], query) >= 85:
        found = [i for i in found if score(i, query) >= 60]
    print("SEARCH\t%s\t%s\t%s\t%s\tнайдено %d" % (
        city["name"], range_text(start, end) if dated or not query else "все даты",
        TYPE_WORDS.get(kind, "всё"), args.query or args.genre or "-", len(found)))
    if not found:
        print("NO_RESULTS\tничего не нашлось: убери жанр, расширь даты или поищи другим словом")
        return
    shown = found[:args.limit]
    prefetch_links(shown)
    nearest = parallel(lambda item: nearest_text(item, city["id"], start, end, args)
                       if item.get("type") != "Movie" or args.with_dates else "сеансы: команда sessions", shown)
    for item, when in zip(shown, nearest):
        print("\t".join([
            "EVENT", item["id"], TYPE_WORDS.get(item.get("type"), "событие"), item["name"], genres_text(item),
            age_text(item), rating_text(item), length_text(item), (item.get("short") or "-").replace("\t", " "),
            when, creation_link(item, city["id"])]))


def resolve_creation(args, city):
    if args.id:
        item = creation_by_id(args.id)
        if not item:
            print("NOT_FOUND\t%s\tтакого события у Афиши нет, найди его через find" % args.id)
            raise SystemExit(0)
        return item, []
    if not args.query:
        print("Error: sessions needs --id or --query")
        raise SystemExit(2)
    query = norm(args.query)
    found = pick(catalog(city["id"], kind=creation_type(args.type)), args, query)
    if not found:
        print("NO_RESULTS\t%s\tв городе %s Афиша не продаёт на это билеты" % (args.query, city["name"]))
        raise SystemExit(0)
    best = score(found[0], query)
    # With an exact title, «also» is only other titles like it (a film and a play of the same name),
    # not every film whose cast has the word («Майкл» matched actors named Michael).
    return found[0], [i for i in found[1:4] if score(i, query) >= (85 if best >= 85 else 60)]


def cmd_sessions(args):
    city = city_or_stop(args.city)
    item, also = resolve_creation(args, city)
    start, end = date_range(args, 1 if item.get("type") == "Movie" else 90)
    point = near_point(args, city)
    blocks = creation_schedule(item, city["id"], start, end)
    rows = upcoming(blocks, start, end, args, args.place_id)
    placed = []
    for first, place, sessions in rows:
        coords = place.get("Coordinates") or {}
        placed.append((km(point, coords.get("Latitude"), coords.get("Longitude")), first, place, sessions))
    if point:
        placed.sort(key=lambda r: (r[0] is None, r[0] or 0, r[1]))
    else:
        placed.sort(key=lambda r: r[1])
    prefetch_links([item])
    print("\t".join(["EVENT", item["id"], TYPE_WORDS.get(item.get("type"), "событие"), item["name"], age_text(item),
                     length_text(item), range_text(start, end), "площадок %d" % len(placed),
                     creation_link(item, city["id"])]))
    for other in also:
        print("ALSO\t%s\t%s\t%s\t%s" % (other["id"], TYPE_WORDS.get(other.get("type"), "событие"), other["name"],
                                       genres_text(other)))
    if not placed:
        later_end = max(end, datetime.date.today() + datetime.timedelta(days=6))
        later = creation_schedule(item, city["id"], start, later_end) if later_end != end else blocks
        print("NO_SESSIONS\tна эти даты и время сеансов нет\tближайший: %s" % next_session_text(later, args))
        return
    for dist, _, place, sessions in placed[:args.places]:
        print("\t".join(["PLACE", str(place.get("Id")), place_text(place), distance_text(dist),
                         "сеансов %d" % len(sessions)]))
        for session in sessions[:args.per_place]:
            print("\t".join(["SESSION", human_moment(session), session.get("HallName") or "-",
                             formats_text(session), price_text(session), extras_text(session)]))


def budget_ok(session, args):
    if getattr(args, "free", False):
        return bool(session.get("FreeEntry"))
    budget = getattr(args, "budget", None)
    if not budget:
        return True
    low = session.get("MinPrice")
    return bool(session.get("FreeEntry")) or (low is not None and low <= budget)


def low_price_text(sessions):
    if any(s.get("FreeEntry") for s in sessions):
        return "вход свободный"
    lows = [s.get("MinPrice") for s in sessions if s.get("MinPrice")]
    return "от %s ₽" % money(min(lows)) if lows else "цена на сайте"


def cmd_ideas(args):
    """Where to go: a varied set of events on sale on the asked days, fit for who is going, each with
    the nearest session that passes the time, budget and distance filters."""
    city = city_or_stop(args.city)
    start, end = date_range(args, 1)
    kids = wants_kids(args)
    kinds = [k for k in (creation_type(t) for t in (args.type or "").split(",") if t.strip()) if k]
    genres = genre_keys(args.genre)
    pool = []
    for item in catalog(city["id"], start, end):
        if kinds and item.get("type") not in kinds:
            continue
        if genres and not genres & set(item.get("genres") or []):
            continue
        if kids:
            if not kids_fit(item, args.age):
                continue
        elif kid_oriented(item):
            continue  # a couple or friends do not need cartoons and puppet shows
        pool.append(item)
    by_type = {}
    for item in pool:
        by_type.setdefault(item.get("type"), []).append(item)
    for kind, group in by_type.items():
        # Afisha's own priority is on different scales for different types, so rank inside a type only,
        # then spread the genres: Saint Petersburg's top concerts were four stand-ups in a row.
        asked_concerts = "Concert" in kinds
        little = kids and (args.age is None or args.age < 9)
        group.sort(key=lambda i: (-(kid_oriented(i) if little else 0),
                                  standup_last(i, "Concert" if asked_concerts else None, genres),
                                  -(i.get("prio") or 0), -rating_value(i)))
        fresh, later, seen = [], [], set()
        for item in group:
            genre = (item.get("genres") or ["-"])[0]
            (later if genre in seen else fresh).append(item)
            seen.add(genre)
        by_type[kind] = fresh + later
    order = IDEA_ORDER["kids" if kids else "adults"]
    types = [t for t in order if t in by_type] + [t for t in by_type if t not in order]
    ranked, depth = [], 0
    while any(depth < len(by_type[t]) for t in types):
        ranked += [by_type[t][depth] for t in types if depth < len(by_type[t])]
        depth += 1
    near = near_point(args, city)
    point = near or city_center(city)
    radius = args.radius if args.radius is not None else (10 if near else None)

    def check(item):
        best = None
        for _, place, sessions in upcoming(creation_schedule(item, city["id"], start, end), start, end, args):
            sessions = [s for s in sessions if budget_ok(s, args)]
            if not sessions:
                continue
            coords = place.get("Coordinates") or {}
            dist = km(point, coords.get("Latitude"), coords.get("Longitude"))
            if radius and dist is not None and dist > radius:
                continue
            rank = (dist if dist is not None else 0, moment(sessions[0]))
            if best is None or rank < best[0]:
                best = (rank, place, sessions, dist if near else None)
        return best

    # Check candidates in batches until enough pass the time, budget and distance filters: «after 22:00»
    # or «free» leave few of the top ones, so going one batch deep gave a single idea or none.
    picked, offset, batch, budget = [], 0, args.limit * 3, time.time() + 20
    while len(picked) < args.limit and offset < len(ranked) and offset < 120 and time.time() < budget:
        chunk = ranked[offset:offset + batch]
        picked += [(item, best) for item, best in zip(chunk, parallel(check, chunk)) if best]
        offset += batch
    picked = picked[:args.limit]
    who = ("с ребёнком %d лет" % args.age if args.age is not None else "с детьми") if kids else "взрослые"
    print("IDEAS\t%s\t%s\t%s\tв продаже подходящих: %d" % (city["name"], range_text(start, end), who, len(pool)))
    if not picked:
        if args.free:
            print("NO_RESULTS\tбесплатного в продаже на Афише нет: она продаёт билеты. Бесплатные парки, прогулки "
                  "и музейные дни ищи в картах или предложи недорогое через --budget")
        else:
            print("NO_RESULTS\tничего не подошло: расширь даты, время или бюджет, убери тип или жанр")
        return
    prefetch_links([item for item, _ in picked])
    for item, (_, place, sessions, dist) in picked:
        when = human_moment(sessions[0]) + (" (и ещё %d)" % (len(sessions) - 1) if len(sessions) > 1 else "")
        print("\t".join(["IDEA", item["id"], TYPE_WORDS.get(item.get("type"), "событие"), item["name"],
                         age_text(item), genres_text(item), when, place_text(place), distance_text(dist),
                         low_price_text(sessions), (item.get("short") or "-").replace("\t", " "),
                         creation_link(item, city["id"])]))


def place_kind(text):
    if not text:
        return None
    value = norm(text)
    if value not in PLACE_KINDS:
        print("KIND_UNKNOWN\t%s\tcinema, theatre, concert_hall, club, museum, sport, park, gallery" % text)
        raise SystemExit(0)
    return PLACE_KINDS[value]


def choose_places(args, city):
    items = places(city["id"])
    kind = place_kind(args.kind)
    if kind:
        items = [p for p in items if p.get("type") == kind]
    if args.name:
        query = norm(args.name)
        scored = []
        for place in items:
            name, full = norm(place["name"]), norm("%s %s" % (place["chain"], place["name"]))
            rank = (100 if query in (name, full) else 80 if words_in(query.split(), full)
                    else 60 if difflib.SequenceMatcher(None, query, name).ratio() >= 0.75 else 0)
            if rank:
                scored.append((rank, place))
        scored.sort(key=lambda row: -row[0])
        if not scored:
            print("PLACE_UNKNOWN\t%s\tтакой площадки с билетами на Афише нет" % args.name)
            raise SystemExit(0)
        for _, other in scored[1:4]:
            print("ALSO_PLACE\t%s\t%s" % (other["id"], place_text(other)))
        return [(None, scored[0][1])]
    if not args.near:
        print("Error: place needs --name or --near")
        raise SystemExit(2)
    point = near_point(args, city)
    if not point:
        raise SystemExit(0)
    ranked = sorted(((km(point, p["lat"], p["lng"]), p) for p in items if p.get("lat") is not None),
                    key=lambda row: row[0])
    return ranked[:args.places]


def cmd_place(args):
    city = city_or_stop(args.city)
    kind = creation_type(args.type)
    for dist, place in choose_places(args, city):
        start, end = date_range(args, 1 if place.get("type") == "Cinema" else 7)
        shows = []
        for block in place_schedule(place, start, end):
            item = slim_creation(block.get("Creation") or {})
            if (kind and item.get("type") != kind) or (wants_kids(args) and not kids_fit(item, args.age)):
                continue
            sessions = sorted((s for s in block.get("Sessions") or [] if session_ok(s, start, end, args)), key=moment)
            if sessions:
                shows.append((moment(sessions[0]), item, sessions))
        shows.sort(key=lambda row: row[0])
        prefetch_links([item for _, item, _ in shows[:args.limit]])
        print("\t".join(["PLACE", str(place["id"]), place_text(place), distance_text(dist), range_text(start, end)]))
        if not shows:
            print("NO_SESSIONS\tна эти даты здесь ничего нет")
            continue
        for _, item, sessions in shows[:args.limit]:
            times = "; ".join(" · ".join(part for part in (human_moment(s), formats_text(s), price_text(s))
                                         if part != "-") for s in sessions[:args.per_show])
            more = len(sessions) - args.per_show
            print("\t".join(["SHOW", item["id"], TYPE_WORDS.get(item.get("type"), "событие"), item["name"],
                             age_text(item), genres_text(item), rating_text(item),
                             times + (" (и ещё %d)" % more if more > 0 else ""),
                             creation_link(item, city["id"])]))


def cmd_cities(args):
    items = cities() or []
    query = norm(args.search or "")
    shown = [c for c in items if not query or norm(c["name"]).startswith(query)]
    print("CITIES\t%d" % len(shown))
    for city in shown[:60]:
        print("CITY\t%s\t%s" % (city["id"], city["name"]))


def add_dates(parser):
    parser.add_argument("--date", help="today, tomorrow, weekend, YYYY-MM-DD or DD.MM")
    parser.add_argument("--from", dest="date_from")
    parser.add_argument("--to", dest="date_to")
    parser.add_argument("--days", type=int)


def add_time(parser):
    parser.add_argument("--after")
    parser.add_argument("--before")
    parser.add_argument("--format")


def main():
    parser = argparse.ArgumentParser(description="Afisha: where to go, films, plays, concerts and shows")
    sub = parser.add_subparsers(dest="command", required=True)

    ideas = sub.add_parser("ideas")
    ideas.add_argument("--city", required=True)
    ideas.add_argument("--kids", action="store_true")
    ideas.add_argument("--age", type=int)
    ideas.add_argument("--type", help="one or several, comma separated: concert,theatre,movie,event,...")
    ideas.add_argument("--genre")
    ideas.add_argument("--budget", type=int)
    ideas.add_argument("--free", action="store_true")
    ideas.add_argument("--near")
    ideas.add_argument("--radius", type=float)
    ideas.add_argument("--limit", type=int, default=6)
    ideas.add_argument("--place-id")
    add_time(ideas)
    add_dates(ideas)
    ideas.set_defaults(run=cmd_ideas)

    find = sub.add_parser("find")
    find.add_argument("--city", required=True)
    find.add_argument("--query")
    find.add_argument("--type")
    find.add_argument("--genre")
    find.add_argument("--kids", action="store_true")
    find.add_argument("--age", type=int)
    find.add_argument("--with-dates", action="store_true")
    find.add_argument("--limit", type=int, default=5)
    add_dates(find)
    find.set_defaults(run=cmd_find)

    sessions = sub.add_parser("sessions")
    sessions.add_argument("--city", required=True)
    sessions.add_argument("--id")
    sessions.add_argument("--query")
    sessions.add_argument("--type")
    sessions.add_argument("--near")
    sessions.add_argument("--place-id")
    sessions.add_argument("--places", type=int, default=3)
    sessions.add_argument("--per-place", type=int, default=4)
    add_time(sessions)
    add_dates(sessions)
    sessions.set_defaults(run=cmd_sessions)

    place = sub.add_parser("place")
    place.add_argument("--city", required=True)
    place.add_argument("--name")
    place.add_argument("--near")
    place.add_argument("--kind")
    place.add_argument("--type")
    place.add_argument("--kids", action="store_true")
    place.add_argument("--age", type=int)
    place.add_argument("--places", type=int, default=2)
    place.add_argument("--limit", type=int, default=8)
    place.add_argument("--per-show", type=int, default=4)
    add_time(place)
    add_dates(place)
    place.set_defaults(run=cmd_place)

    city_list = sub.add_parser("cities")
    city_list.add_argument("--search")
    city_list.set_defaults(run=cmd_cities)

    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
