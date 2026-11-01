"""Read Kinowatch search cards using the owner's separately stored website session."""
import argparse
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

ORIGIN = "https://kino.watch"
ACCESS_FILE = Path(__file__).resolve().parents[2] / ".kinowatch-auth"
MAX_BODY = 2_000_000
VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}


class SearchError(Exception):
    pass


class Node:
    def __init__(self, tag, attrs=(), parent=None):
        self.tag, self.attrs, self.parent = tag, dict(attrs), parent
        self.children = []

    def walk(self):
        yield self
        for child in self.children:
            if isinstance(child, Node):
                yield from child.walk()

    def has_class(self, name):
        return name in (self.attrs.get("class") or "").split()

    def text(self):
        if self.tag in {"script", "style", "template"}:
            return ""
        return " ".join(" ".join(c.text() if isinstance(c, Node) else c for c in self.children).split())


class Page(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.root = self.current = Node("root")

    def handle_starttag(self, tag, attrs):
        node = Node(tag, attrs, self.current)
        self.current.children.append(node)
        if tag not in VOID:
            self.current = node

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in VOID:
            self.handle_endtag(tag)

    def handle_endtag(self, tag):
        node = self.current
        while node.parent:
            if node.tag == tag:
                self.current = node.parent
                return
            node = node.parent

    def handle_data(self, data):
        self.current.children.append(data)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    # urllib can otherwise forward a manually supplied Cookie header to another host.
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def load_session():
    try:
        data = json.loads(ACCESS_FILE.read_text(encoding="utf-8"))
        values = [data["cookie"], data["userAgent"]]
        if any(not isinstance(v, str) or not v.strip() or any(ord(c) < 32 or ord(c) == 127 for c in v) for v in values):
            raise ValueError()
        if data.get("origin", ORIGIN) != ORIGIN:
            raise ValueError()
        return {"cookie": values[0], "userAgent": values[1]}
    except (OSError, ValueError, KeyError, TypeError):
        raise SearchError("Сессия Kinowatch не настроена или файл доступа повреждён.") from None


def search_url(query):
    if not query.strip() or len(query) > 500 or any(ord(c) < 32 for c in query):
        raise SearchError("Запрос должен содержать от 1 до 500 символов без управляющих знаков.")
    return ORIGIN + "/item/search?" + urllib.parse.urlencode({"query": query.strip()})


def fetch_page(url, session):
    request = urllib.request.Request(url, headers={
        "Cookie": session["cookie"], "User-Agent": session["userAgent"],
        "Accept": "text/html", "Accept-Language": "ru-RU,ru;q=0.9",
    })
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=20) as response:
            if response.headers.get_content_type() != "text/html":
                raise SearchError("Kinowatch вернул неожиданный формат ответа.")
            body = response.read(MAX_BODY + 1)
            if len(body) > MAX_BODY:
                raise SearchError("Выдача Kinowatch превысила допустимый размер.")
            return body.decode("utf-8")
    except urllib.error.HTTPError as error:
        if error.code in (301, 302, 303, 307, 308, 401):
            raise SearchError("Kinowatch требует входа или перенаправляет запрос. Обнови сессию сайта.") from None
        if error.code in (403, 429):
            raise SearchError(f"Kinowatch ограничил доступ (HTTP {error.code}); повторные попытки остановлены.") from None
        raise SearchError(f"Kinowatch HTTP {error.code}.") from None
    except (urllib.error.URLError, OSError):
        raise SearchError("Kinowatch недоступен или истекло время ожидания.") from None
    except (UnicodeError, ValueError):
        raise SearchError("Kinowatch вернул некорректный ответ.") from None


def parse_page(html, query):
    if any(marker in html.lower() for marker in ("cf-chl-", "/cdn-cgi/challenge-platform/")):
        raise SearchError("Kinowatch запросил проверку браузера. Автоматический поиск остановлен.")
    page = Page()
    page.feed(html)
    nodes = list(page.root.walk())
    if any(n.tag == "input" and n.attrs.get("type") == "password" for n in nodes):
        raise SearchError("Сессия Kinowatch истекла. Требуется обновить cookie сайта.")
    # Scope metadata to the actual search area, excluding navigation and recommendations.
    view = next((n for n in nodes if n.attrs.get("id") == "view"), None)
    if view is None:
        raise SearchError("Не удалось распознать страницу поиска Kinowatch.")
    view_nodes = list(view.walk())
    if not any(n.tag == "form" and n.attrs.get("name") == "search" and n.attrs.get("action") == "/item/search" for n in view_nodes):
        raise SearchError("Не удалось распознать форму поиска Kinowatch.")
    headings = [n.text() for n in view_nodes if n.tag in {"h4", "h5", "h6"}]
    totals = [re.fullmatch(r"Найдено\s+(\d+)\s+совпадени[йяе]", h) for h in headings]
    total = next((int(m[1]) for m in totals if m),
                 1 if "Найдено одно совпадение" in headings else None)
    empty = any(n.tag == "p" and n.has_class("lead") and n.text() == "Нет результатов для " + query for n in view_nodes)
    if total is None and not empty:
        raise SearchError("Не удалось распознать результаты поиска Kinowatch.")
    videos, seen = [], set()
    lists = [n for n in view_nodes if n.has_class("item-list")]
    for listing in lists:
        for card in listing.walk():
            if not card.has_class("item"):
                continue
            match = re.fullmatch(r"item-([1-9]\d*)", card.attrs.get("data-id") or "")
            if not match:
                raise SearchError("Kinowatch вернул карточку без идентификатора.")
            item_id = match[1]
            if item_id in seen:
                continue
            parts = list(card.walk())
            title_node = next((n for n in parts if n.has_class("item-title")), None)
            anchor = next((n for n in title_node.walk() if n.tag == "a"), None) if title_node else None
            if not anchor or not anchor.text():
                raise SearchError("Kinowatch вернул карточку без названия.")
            link = urllib.parse.urlsplit(urllib.parse.urljoin(ORIGIN, anchor.attrs.get("href") or ""))
            if link.scheme != "https" or link.netloc != "kino.watch" or not re.fullmatch(r"/item/view/" + item_id + r"(?:/[^?#]*)?", link.path):
                raise SearchError("Kinowatch вернул неожиданную ссылку на карточку.")
            author_rows = [n for n in parts if n.has_class("item-author")]
            original = next((n.text() for row in author_rows for n in row.walk() if n.tag == "a" and "/item/view/" in (n.attrs.get("href") or "")), None)
            years = [re.match(r"^(\d{4})(?:,|\s|$)", row.text()) for row in author_rows]
            year = next((int(m[1]) for m in years if m), None)
            kind = next((n.text() for n in title_node.walk() if n.has_class("label")), None)
            genres = [n.text() for row in author_rows for n in row.walk() if n.tag == "a" and "genre=" in (n.attrs.get("href") or "")]
            url = f"{ORIGIN}/item/view/{item_id}/"
            videos.append({"id": item_id, "title": anchor.text(), "originalTitle": original,
                           "year": year, "type": kind, "genres": genres,
                           "url": url, "playbackUrl": url, "playbackPackage": "watch.kino",
                           "description": "", "publishedAt": None})
            seen.add(item_id)
            if len(videos) == 5:
                break
        if len(videos) == 5:
            break
    if (total is not None and len(videos) != min(5, total)) or (empty and videos):
        raise SearchError("Карточки Kinowatch не соответствуют результатам поиска.")
    return {"query": query, "platform": "kinopub", "videos": videos,
            "complete": len(videos) == 5, "totalFound": total if total is not None else 0,
            "order": "site", "note": None if len(videos) == 5 else "В выдаче Kinowatch меньше пяти результатов."}


def main():
    parser = argparse.ArgumentParser(description="Find up to five Kinowatch titles")
    parser.add_argument("query")
    args = parser.parse_args()
    try:
        url = search_url(args.query)
        result = parse_page(fetch_page(url, load_session()), args.query.strip())
    except SearchError as error:
        print(json.dumps({"error": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
