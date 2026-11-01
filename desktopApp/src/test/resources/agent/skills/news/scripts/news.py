"""Fetches fresh headlines from publisher RSS feeds. Called by news.sh, not by the model directly.

Returns compact tab-separated material the model retells in its own words. Deliberately prints no
URLs and no publisher names: the product rule is a digest without sources and without verbatim
quotes from the article body. No shebang on purpose: a bare `/usr/bin/...` line trips the validator.
"""
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone, timedelta
from email.utils import parsedate_to_datetime

PREFS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, os.pardir, ".news-prefs.json")
AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/128 Safari/537.36"
MOSCOW = timezone(timedelta(hours=3))

# Few curated feeds beat many: wide lists fill up with reposts and junk. Each topic keeps several
# publishers so one dead feed never empties the digest.
FEEDS = {
    # ТАСС и mos.ru сознательно не берём: первый отдаёт статьи только через защиту от ботов и в
    # ленте нет анонса, второй подмешивает в текст навигацию. Дайджест из одних заголовков
    # бесполезен — он говорит, что что-то изменилось, не говоря что именно.
    "россия": [
        "https://lenta.ru/rss/news/russia",
        "https://ria.ru/export/rss2/archive/index.xml",
        "https://www.interfax.ru/rss.asp",
        "https://rssexport.rbc.ru/rbcnews/news/30/full.rss",
        "https://www.kommersant.ru/RSS/news.xml",
    ],
    "мир": [
        "https://lenta.ru/rss/news/world",
        "https://www.kommersant.ru/RSS/news.xml",
        "https://www.interfax.ru/rss.asp",
    ],
    "спорт": [
        "https://lenta.ru/rss/news/sport",
        "https://www.sports.ru/rss/all_news.xml",
        "https://www.championat.com/rss/news/",
    ],
    "петербург": [
        "https://piter.tv/rss/",
        "https://moika78.ru/feed/",
    ],
    "москва": [
        "https://www.kommersant.ru/RSS/news.xml",
        "https://rssexport.rbc.ru/rbcnews/news/30/full.rss",
        "https://www.interfax.ru/rss.asp",
    ],
    "экономика": [
        "https://www.vedomosti.ru/rss/news",
        "https://rssexport.rbc.ru/rbcnews/news/30/full.rss",
        "https://www.kommersant.ru/RSS/news.xml",
    ],
}
# Topics whose feeds are federal: keep only items that actually mention the city.
CITY_FILTER = {"москва": ("москв", "столиц"), "петербург": ("петербург", "спб", "питер", "ленобласт")}
TOPIC_ALIASES = {
    "russia": "россия", "рф": "россия", "страна": "россия",
    "world": "мир", "заграниц": "мир", "мировые": "мир",
    "sport": "спорт", "футбол": "спорт", "хоккей": "спорт",
    "spb": "петербург", "питер": "петербург", "санкт-петербург": "петербург",
    "moscow": "москва", "мск": "москва",
    "economy": "экономика", "бизнес": "экономика", "финансы": "экономика",
}
TAG = re.compile(r"<[^>]+>")
SPACE = re.compile(r"\s+")


def fail(text):
    print("Error: " + text)
    raise SystemExit(1)


def clean(raw):
    text = (raw or "").replace("<![CDATA[", " ").replace("]]>", " ")
    text = TAG.sub(" ", text)
    for entity, char in (("&nbsp;", " "), ("&quot;", '"'), ("&amp;", "&"), ("&lt;", "<"),
                         ("&gt;", ">"), ("&laquo;", "«"), ("&raquo;", "»"), ("&mdash;", "—"),
                         ("&ndash;", "–"), ("&#39;", "'"), ("&apos;", "'")):
        text = text.replace(entity, char)
    text = re.sub(r"&#(\d+);", lambda m: chr(int(m.group(1))), text)
    return SPACE.sub(" ", text).strip()


def fetch(url, timeout):
    request = urllib.request.Request(url, headers={"User-Agent": AGENT})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read().decode("utf-8", "replace")


def field(block, name):
    match = re.search(r"<%s[^>]*>(.*?)</%s>" % (name, name), block, re.S | re.I)
    return clean(match.group(1)) if match else ""


def parse_items(xml_text):
    items = []
    for block in re.findall(r"<item[^>]*>(.*?)</item>", xml_text, re.S | re.I):
        title = field(block, "title")
        if not title:
            continue
        stamp = None
        raw_date = field(block, "pubDate")
        if raw_date:
            try:
                stamp = parsedate_to_datetime(raw_date)
            except (TypeError, ValueError):
                stamp = None
        items.append({"title": title, "lead": field(block, "description"),
                      "link": field(block, "link") or field(block, "guid"), "at": stamp})
    return items


SCRIPTS = re.compile(r"<(script|style|noscript)[^>]*>.*?</\1>", re.S | re.I)
PARAGRAPH = re.compile(r"<p[^>]*>(.*?)</p>", re.S | re.I)
JUNK = re.compile(r"(подписыв|читайте так|материал дополня|все новости|фото:|реклама|18\+|телеграм)", re.I)
LD_BODY = re.compile(r'"articleBody"\s*:\s*"((?:[^"\\]|\\.)*)"', re.S)
BLOCK = re.compile(r'<div[^>]+class="[^"]*(?:article|text|content|body)[^"]*"[^>]*>(.*?)</div>', re.S | re.I)
META = re.compile(r'<meta[^>]+(?:property="og:description"|name="description")[^>]+content="([^"]{60,})"', re.I)


def looks_like_prose(text):
    """Cuts navigation and link lists: real prose has sentence punctuation and normal word flow."""
    if "." not in text and "," not in text:
        return False
    words = text.split()
    if len(words) < 18:
        return False
    capitalized = sum(1 for w in words if w[:1].isupper())
    return capitalized / len(words) < 0.4


def unescape_ld(raw):
    out = raw.replace('\\"', '"').replace("\\n", " ").replace("\\t", " ").replace("\\/", "/")
    out = re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), out)
    return SPACE.sub(" ", out).strip()


def article_body(url, timeout, budget):
    """First real paragraphs of the article.

    The RSS lead is often empty or a teaser, and a digest built on headlines alone says that
    something changed without saying what — which is exactly what makes it useless.
    """
    if not url.startswith("http"):
        return ""
    try:
        html = fetch(url, timeout)[:400000]
    except (urllib.error.URLError, urllib.error.HTTPError, OSError, ValueError):
        return ""
    # Structured body first: many publishers ship the full text in JSON-LD, and it is already clean.
    found = LD_BODY.search(html)
    if found:
        body = unescape_ld(found.group(1))
        if len(body) >= 200:
            return body[:budget]
    html = SCRIPTS.sub(" ", html)
    picked = []
    for raw in PARAGRAPH.findall(html):
        text = clean(raw)
        if len(text) < 120 or JUNK.search(text) or not looks_like_prose(text):
            continue
        if text in picked:
            continue
        picked.append(text)
        if sum(len(t) for t in picked) >= budget:
            break
    text = " ".join(picked)
    if len(text) < 200:
        # Sites that build the article out of divs instead of paragraphs (RIA and friends).
        chunks = []
        for raw in BLOCK.findall(html)[:12]:
            chunk = clean(raw)
            if len(chunk) >= 200 and not JUNK.search(chunk) and looks_like_prose(chunk) and chunk not in chunks:
                chunks.append(chunk)
            if sum(len(c) for c in chunks) >= budget:
                break
        if sum(len(c) for c in chunks) > len(text):
            text = " ".join(chunks)
    if len(text) < 200:
        meta = META.search(html)
        if meta:
            text = clean(meta.group(1))
    return text[:budget]


def key_of(title):
    """Normalized headline for dedup: same story from three publishers must collapse into one."""
    words = re.findall(r"[а-яёa-z0-9]+", title.lower())
    return " ".join(sorted(w for w in words if len(w) > 3)[:8])


def resolve_topic(raw):
    topic = (raw or "").strip().lower()
    topic = TOPIC_ALIASES.get(topic, topic)
    if topic in FEEDS:
        return topic
    for name in FEEDS:
        if topic and (topic.startswith(name[:5]) or name.startswith(topic[:5])):
            return name
    return ""


def read_prefs():
    try:
        with open(PREFS_FILE, encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return {}


def write_prefs(prefs):
    try:
        with open(PREFS_FILE, "w", encoding="utf-8") as handle:
            json.dump(prefs, handle, ensure_ascii=False)
        os.chmod(PREFS_FILE, 0o600)
    except OSError:
        pass


def main():
    parser = argparse.ArgumentParser(description="Fresh headlines by topic")
    parser.add_argument("--topic", help="россия | мир | спорт | петербург | москва | экономика")
    parser.add_argument("--limit", type=int, default=5, help="how many items (1..10)")
    parser.add_argument("--hours", type=int, default=24, help="how fresh, in hours")
    parser.add_argument("--brief", action="store_true",
                        help="headlines only, do not open articles (faster, much less substance)")
    parser.add_argument("--set-city", help="remember the user's city and exit")
    parser.add_argument("--no-remember", action="store_true",
                        help="do not learn the city from this request")
    parser.add_argument("--show-city", action="store_true", help="print the remembered city and exit")
    args = parser.parse_args()

    if args.set_city:
        city = resolve_topic(args.set_city) or args.set_city.strip().lower()
        prefs = read_prefs()
        prefs["city"] = city
        write_prefs(prefs)
        print("CITY_SAVED\t%s" % city)
        return
    if args.show_city:
        print("CITY\t%s" % (read_prefs().get("city") or "unknown"))
        return

    limit = max(1, min(args.limit, 10))
    topic = resolve_topic(args.topic) if args.topic else ""
    asked_city = ""
    if topic:
        # Learn the city from the request itself. Asking the user and hoping the model calls back
        # with --set-city next turn does not survive a real conversation: it forgets.
        if topic in CITY_FILTER and not args.no_remember and read_prefs().get("city") != topic:
            prefs = read_prefs()
            prefs["city"] = topic
            write_prefs(prefs)
            asked_city = topic
    else:
        city = read_prefs().get("city")
        topic = resolve_topic(city) if city else ""
    if not topic:
        # Still nothing: federal digest, never a dead end, and tell the model it may offer a city.
        topic = "россия"
        print("CITY_UNKNOWN\tno city remembered yet; offer to remember one for local news")

    deadline = datetime.now(timezone.utc) - timedelta(hours=max(1, args.hours))
    collected, dead = [], []
    for url in FEEDS[topic]:
        try:
            collected += parse_items(fetch(url, 12))
        except (urllib.error.URLError, urllib.error.HTTPError, OSError, ValueError):
            dead.append(url.split("/")[2])
    if not collected:
        fail("no feed responded for topic %s" % topic)

    words = CITY_FILTER.get(topic)
    seen, rows, skipped = set(), [], 0
    for item in sorted(collected, key=lambda i: i["at"] or datetime.min.replace(tzinfo=timezone.utc), reverse=True):
        if item["at"] and item["at"] < deadline:
            continue
        haystack = (item["title"] + " " + item["lead"]).lower()
        if words and not any(w in haystack for w in words):
            continue
        key = key_of(item["title"])
        if key in seen:
            continue
        seen.add(key)
        when = item["at"].astimezone(MOSCOW).strftime("%d.%m %H:%M") if item["at"] else "-"
        body = "" if args.brief else article_body(item["link"], 10, 900)
        detail = body or item["lead"][:400]
        if len(detail) < 120 and not args.brief:
            # Headline without substance: skip it rather than make the model guess what changed.
            skipped += 1
            continue
        rows.append("\t".join(["ITEM", when, item["title"], detail]))
        if len(rows) >= limit:
            break

    print("TOPIC\t%s\tfeeds_ok\t%d" % (topic, len(FEEDS[topic]) - len(dead)))
    if asked_city:
        print("CITY_LEARNED\t%s" % asked_city)
    if skipped:
        print("SKIPPED_THIN\t%d" % skipped)
    if dead:
        # Silent feed failures are the classic cause of a half-empty digest — say it out loud.
        print("FEEDS_DOWN\t%d" % len(dead))
    if not rows:
        print("NO_FRESH\tnothing newer than %d hours for %s" % (args.hours, topic))
        return
    for row in rows:
        print(row)
    print("NOTE\tretell in your own words; no sources, no verbatim sentences; always say WHAT "
          "exactly changed — the rule itself, who it affects, from when, the numbers")


if __name__ == "__main__":
    main()
