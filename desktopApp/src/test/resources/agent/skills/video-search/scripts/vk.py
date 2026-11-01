"""Public VK Video metadata through the runtime's honest Lightpanda browser."""
import argparse
import calendar
from datetime import datetime, timedelta, timezone
import json
import re
import subprocess
import sys
import time
from urllib.parse import parse_qs, urlencode, urlsplit


class SearchError(Exception):
    pass


def search_url(query):
    query = query.strip()
    if not query or len(query) > 500 or any(ord(c) < 32 for c in query):
        raise SearchError("Запрос должен содержать от 1 до 500 символов без управляющих знаков.")
    return "https://m.vkvideo.ru/?" + urlencode({"q": query, "action": "search"})


def canonical_video(url):
    u = urlsplit(url)
    if u.scheme != "https" or u.netloc not in ("vkvideo.ru", "m.vkvideo.ru"):
        return None
    if not re.fullmatch(r"/video-?[1-9]\d*_[1-9]\d*", u.path):
        return None
    return "https://vkvideo.ru" + u.path


class Browser:
    def __init__(self):
        self.deadline = time.monotonic() + 35

    def call(self, *args):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise SearchError("Истекло время поиска VK.")
        try:
            p = subprocess.run(["agent-browser", *args], capture_output=True, text=True,
                               timeout=min(25, remaining))
        except (OSError, subprocess.TimeoutExpired) as e:
            raise SearchError("Lightpanda недоступен или не ответил вовремя.") from e
        if p.returncode:
            raise SearchError("Операция браузера VK завершилась ошибкой.")
        return p.stdout

    def evaluate(self, source):
        try:
            return json.loads(self.call("eval", source))
        except (ValueError, TypeError) as e:
            raise SearchError("Браузер вернул некорректные данные страницы.") from e


# Only public DOM metadata is read. No cookies, tokens, internal stores or network APIs.
SEARCH_DOM = r"""(() => {
  const headings = Array.from(document.querySelectorAll('h1,h2,h3,h4,h5,h6,[role=heading]'));
  const heading = headings.find(e => /^(All videos|Все видео|Видеозаписи)$/i.test(e.textContent.trim()));
  return {
    url: location.href,
    query: document.querySelector('input[type=search],input[role=searchbox]')?.value,
    heading: !!heading,
    text: document.body.innerText.slice(0,1200),
    cards: heading ? Array.from(document.querySelectorAll('[data-testid=video_card_title]'))
      .filter(e => heading.compareDocumentPosition(e) & 4).slice(0,30)
      .map(e => ({title: e.textContent.trim(), url: e.querySelector('a[href]')?.href,
        dateText: e.closest('[data-testid=video_card_layout]')
          ?.querySelector('[data-testid=video_card_additional_info]')?.innerText
          .split('·').slice(1).join('·').trim() || null})) : []
  };
})()"""

def approximate_date(label, observed_at):
    """Estimate a UTC calendar date from VK's rounded age, never an exact timestamp."""
    if not isinstance(label, str):
        return None
    label = " ".join(label.lower().split())
    if label in ("сегодня", "только что", "today", "just now"):
        return observed_at.date().isoformat()
    if label in ("вчера", "yesterday"):
        return (observed_at - timedelta(days=1)).date().isoformat()
    match = re.fullmatch(r"(\d{1,6}|a|an)\s+([^\d]+?)\s+(?:назад|ago)", label)
    if not match:
        return None
    amount = 1 if match[1] in ("a", "an") else int(match[1])
    units = (
        ("seconds", r"секунд[ауы]?|seconds?"),
        ("minutes", r"минут[ауы]?|minutes?"),
        ("hours", r"час(?:а|ов)?|hours?"),
        ("days", r"день|дня|дней|days?"),
        ("weeks", r"недел[яиюь]|weeks?"),
        ("months", r"месяц(?:а|ев)?|months?"),
        ("years", r"год(?:а)?|лет|years?"),
    )
    try:
        for unit, pattern in units:
            if not re.fullmatch(pattern, match[2]):
                continue
            if unit in ("months", "years"):
                months = amount * (12 if unit == "years" else 1)
                year, month = divmod(observed_at.year * 12 + observed_at.month - 1 - months, 12)
                month += 1
                day = min(observed_at.day, calendar.monthrange(year, month)[1])
                return observed_at.replace(year=year, month=month, day=day).date().isoformat()
            return (observed_at - timedelta(**{unit: amount})).date().isoformat()
    except (ValueError, OverflowError):
        pass
    return None


def check_block(page):
    if page.get("cards"):
        return  # A video title may itself discuss captchas.
    text = page.get("text", "").lower()
    if re.search(r"captcha|капч|verifying your browser|подтвердите, что вы|проверка браузера|access denied|доступ ограничен", text):
        raise SearchError("VK показал проверку браузера или ограничение доступа; поиск остановлен.")


def read_search(browser, query, reloads=0):
    # The SPA sometimes stays on its loading skeleton without requesting results
    # (seen right after it refreshes the anonymous token); a reload recovers it.
    for load in range(reloads + 1):
        if load:
            browser.call("reload")
        for attempt in range(5):
            page = browser.evaluate(SEARCH_DOM)
            check_block(page)
            u = urlsplit(page.get("url", ""))
            if u.netloc != "m.vkvideo.ru" or parse_qs(u.query).get("q") != [query.strip()]:
                raise SearchError("Страница поиска сменилась или VK исказил запрос.")
            if page.get("query") is not None and page["query"] != query.strip():
                raise SearchError("В поисковой строке VK другой запрос.")
            if page.get("cards"):
                return page
            if re.search(r"ничего не найдено|no (?:videos|results) found", page.get("text", ""), re.I):
                return page
            if attempt < 4:
                time.sleep(1)
    raise SearchError("Выдача VK не загрузилась или изменилась разметка страницы.")


def click_named(browser, pattern):
    snapshot = browser.call("snapshot", "-i")
    for line in snapshot.splitlines():
        m = re.search(r'button "([^"]*)" \[ref=(e\d+)\]', line)
        if m and re.fullmatch(pattern, m[1].strip(), re.I):
            browser.call("click", "@" + m[2])
            return
    raise SearchError("Не удалось выбрать сортировку VK по релевантности.")


def collect(query, browser=None, now=None):
    browser = browser or Browser()
    search_url(query)
    page = read_search(browser, query, reloads=1)
    observed_at = now or datetime.now(timezone.utc)
    if not page.get("cards"):
        return {"query": query, "videos": [], "complete": False, "note": "VK сообщил, что видео не найдены."}
    click_named(browser, r"Sort by|Сортировка|Сортировать")
    click_named(browser, r"Most relevant|По релевантности|По соответствию")
    page = read_search(browser, query)
    videos, seen = [], set()
    for card in page["cards"]:
        link = canonical_video(card.get("url") or "")
        if link and link not in seen and card.get("title"):
            seen.add(link)
            date_text = card.get("dateText")
            published = approximate_date(date_text, observed_at)
            videos.append({"title": card["title"], "description": "",
                           "links": [{"type": "WEB", "url": link},
                                     {"type": "PLAY", "url": link.replace("https://vkvideo.ru/", "vkvideo://vk.com/", 1),
                                      "packageName": "com.vk.tv"}],
                           "publishedAt": published, "publishedAtApproximate": published is not None,
                           "dateText": date_text})
        if len(videos) == 5:
            break
    if not videos:
        raise SearchError("VK вернул карточки без распознаваемых ссылок на видео.")
    notes = []
    if len(videos) < 5:
        notes.append("В выдаче доступно меньше пяти различных видео.")
    notes.append("Описания VK не запрашиваются. Даты приблизительно рассчитаны из dateText относительно observedAt (UTC); неизвестные даты — null.")
    return {"query": query, "videos": videos, "complete": len(videos) == 5,
            "observedAt": observed_at.isoformat().replace("+00:00", "Z"),
            "order": "relevance", "note": " ".join(dict.fromkeys(notes))}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["url", "collect"])
    parser.add_argument("query")
    args = parser.parse_args()
    try:
        if args.command == "url":
            print(search_url(args.query), end="")
        else:
            print(json.dumps(collect(args.query), ensure_ascii=False))
        return 0
    except SearchError as e:
        print(json.dumps({"error": str(e)}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
