"""Public cinema search: Wink HTML, Okko public API, other sites' rendered cards."""
import argparse
from datetime import datetime, timezone
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import parse_qs, urlencode, urljoin, urlsplit

SITES = {
    'okko': ('okko.tv', '/search', 'query', r'/(?:movie|serial)/[a-z0-9-]+'),
    'wink': ('wink.ru', '/search', 'query', r'/(?:movies|series)/[^/]+'),
    'start': ('start.ru', '/search', 'q', r'/watch/[^/]+'),
    'ivi': ('www.ivi.ru', '/search', 'ivi_search', r'/(?:en/)?watch/[^/]+'),
    'kion': ('kion.ru', '/search', 'searchValue', r'/(?:film|serial)/[^/]+'),
    'premier': ('premier.one', '/search', 'query', r'/show/[^/]+'),
    'kinopoisk': ('www.kinopoisk.ru', '/new-search/', 'text', r'/(?:film|series)/\d+/'),
}
EMPTY = r'ничего не найдено|ничего не нашли|ничего не нашлось|не удалось найти|нет результатов|no results|nothing found'
BLOCK = r'captcha|капч|подтвердите, что вы|проверка браузера|access denied|доступ ограничен|verifying your browser'


class SearchError(RuntimeError):
    pass


def search_url(platform, query):
    if platform not in SITES:
        raise SearchError('Неизвестный кинотеатр.')
    query = query.strip()
    if not query or len(query) > 500 or any(ord(c) < 32 for c in query):
        raise SearchError('Запрос должен содержать от 1 до 500 символов без управляющих знаков.')
    host, path, key, _ = SITES[platform]
    return 'https://' + host + path + ('?' + urlencode({key: query}) if key else '')


def check_page(platform, query, page, require_input=True):
    text = page.get('text', '')
    if not page.get('cards') and re.search(BLOCK, text, re.I):
        raise SearchError('Сайт показал проверку браузера или ограничение доступа; поиск остановлен.')
    u = urlsplit(page.get('url', ''))
    host, path, key, _ = SITES[platform]
    paths = {path}
    if platform == 'ivi':
        paths.add('/en/search')
    if u.scheme != 'https' or u.netloc != host or u.path not in paths:
        raise SearchError('Браузер ушёл со страницы поиска выбранного кинотеатра.')
    if key and parse_qs(u.query).get(key) != [query]:
        raise SearchError('На странице поиска другой запрос.')
    if platform == 'premier' and require_input and page.get('input') != query:
        raise SearchError('В поисковой строке PREMIER другой запрос.')


def make_result(platform, query, cards):
    videos, seen = [], set()
    host, _, _, pattern = SITES[platform]
    for card in cards:
        u = urlsplit(urljoin('https://' + host, card.get('url') or ''))
        if u.scheme != 'https' or u.netloc != host or not re.fullmatch(pattern, u.path):
            continue
        url = 'https://' + host + u.path
        title = ' '.join((card.get('title') or '').split())
        if not title or url in seen:
            continue
        seen.add(url)
        year = re.search(r'\b(?:18|19|20)\d{2}\b', card.get('yearText') or '')
        label = card.get('availabilityText') or ''
        availability = card.get('availability') or {'Бесплатно': 'free', 'Подписка': 'subscription'}.get(label, 'unknown')
        videos.append({'links': [{'type': 'WEB', 'url': url}], 'title': title,
                       'description': '', 'publishedAt': None,
                       'year': int(year[0]) if year else None,
                       'availability': availability, 'availabilityText': label or None})
        if len(videos) == 5:
            break
    if cards and not videos:
        raise SearchError('Карточки выдачи не распознаны; возможно, сайт изменил разметку.')
    notes = []
    if len(videos) < 5:
        notes.append('В доступной части выдачи найдено меньше пяти карточек фильмов и сериалов.')
    if platform in ('kion', 'premier', 'okko'):
        notes.append('Сайт может возвращать рекомендации при отсутствии совпадений; проверь соответствие названий запросу.')
    return {'query': query, 'platform': platform, 'order': 'site', 'videos': videos,
            'complete': len(videos) == 5,
            'note': ' '.join(notes) or None}


class WinkHTML(HTMLParser):
    """Read only the first search shelf, excluding other shelves and script data."""
    def __init__(self):
        super().__init__()
        self.cards, self.visible, self.stack = [], [], []
        self.heading, self.in_results, self.saw_results = '', False, False
        self.card = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        field = {'mic-name': 'title', 'mic-year': 'yearText'}.get(attrs.get('data-test'))
        if tag not in {'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr'}:
            self.stack.append((tag, field))
        if tag == 'h3':
            self.heading = ''
        if tag == 'a' and self.in_results and attrs.get('data-type') == 'media_item':
            self.card = {'url': attrs.get('href'), 'title': '', 'yearText': ''}

    def handle_data(self, data):
        tags = [tag for tag, _ in self.stack]
        if 'script' in tags or 'style' in tags:
            return
        self.visible.append(data)
        if 'h3' in tags:
            self.heading += data
        if self.card is not None:
            for _, field in reversed(self.stack):
                if field:
                    self.card[field] += data
                    break

    def handle_endtag(self, tag):
        if tag == 'h3':
            self.in_results = self.heading.strip() == 'Результаты поиска' and not self.saw_results
            self.saw_results |= self.in_results
        if tag == 'a' and self.card is not None:
            self.cards.append(self.card)
            self.card = None
        for i in range(len(self.stack) - 1, -1, -1):
            if self.stack[i][0] == tag:
                del self.stack[i:]
                break


def search_wink(query):
    url = search_url('wink', query)
    request = urllib.request.Request(url, headers={'User-Agent': 'souz-video-search/1.0'})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            body = response.read(3_000_001)
            final_url = response.url
        if len(body) > 3_000_000:
            raise SearchError('Страница Wink превысила допустимый размер.')
        parser = WinkHTML()
        parser.feed(body.decode('utf-8', 'replace'))
        page = {'url': final_url, 'text': ' '.join(parser.visible), 'cards': parser.cards}
        check_page('wink', query, page)
        if not parser.cards and not re.search(EMPTY, page['text'], re.I):
            raise SearchError('Выдача Wink не распознана; возможно, сайт изменил разметку.')
        return make_result('wink', query, parser.cards)
    except urllib.error.HTTPError as error:
        raise SearchError(f'Wink HTTP {error.code}; поиск не выполнен.') from None
    except (urllib.error.URLError, OSError):
        raise SearchError('Wink недоступен или не ответил вовремя.') from None


def okko_result(query, data):
    if not isinstance(data, dict) or type(data.get('status')) is not int or data.get('status') != 0 or 'antibot' in data:
        raise SearchError('Okko ограничил доступ или вернул ошибку поиска.')
    root = data.get('element', {})
    if root.get('id') != 'search' or root.get('type') != 'COLLECTION':
        raise SearchError('Ответ поиска Okko не распознан.')
    groups = root['collectionItems']['items']
    if not isinstance(groups, list):
        raise SearchError('Группы поиска Okko не распознаны.')
    cards, metadata, seen = [], {}, set()
    recognized = False
    for group in groups:
        group = group['element']
        if group.get('type') != 'SEARCH_RESULTS':
            continue
        recognized = True
        for item in group['collectionItems']['items']:
            item = item['element']
            kind = {'MOVIE': 'movie', 'SERIAL': 'serial'}.get(item.get('type'))
            alias, cid = item.get('alias'), item.get('id')
            if not kind or not isinstance(cid, str) or not cid or cid in seen:
                continue
            if not isinstance(alias, str) or not re.fullmatch(r'[a-z0-9-]+', alias):
                raise SearchError('Адрес карточки Okko не распознан.')
            if not isinstance(item.get('name'), str) or not item['name'].strip():
                raise SearchError('Название карточки Okko не распознано.')
            seen.add(cid)
            url = 'https://okko.tv/' + kind + '/' + alias
            release = None
            stamp = item.get('worldReleaseDate')
            if type(stamp) in (int, float) and 0 < stamp < 253402300800000:
                release = datetime.fromtimestamp(stamp / 1000, timezone.utc).date().isoformat()
            cards.append({'url': url, 'title': item['name'], 'yearText': release or ''})
            metadata[url] = {'id': cid, 'contentType': item['type'],
                             'description': item.get('promoText') or '', 'releaseDate': release,
                             'links': [{'type': 'WEB', 'url': url},
                                       {'type': 'OPEN', 'url': 'okko://movie?' + urlencode({'uid': cid, 'type': item['type']}),
                                        'packageName': 'ru.more.play'}]}
            if item['type'] == 'MOVIE':
                metadata[url]['links'].append({'type': 'PLAY', 'url': 'okko://?' + urlencode({'uid': cid, 'type': 'MOVIE'}),
                                               'packageName': 'ru.more.play'})
    if groups and not recognized:
        raise SearchError('Группы результатов Okko не распознаны.')
    answer = make_result('okko', query, cards)
    for video in answer['videos']:
        video.update(metadata[next(link['url'] for link in video['links'] if link['type'] == 'WEB')])
    return answer


def search_okko(query):
    search_url('okko', query)  # Apply the same query validation as other providers.
    url = 'https://api.okko.tv/screenapi/v2/noauth/groupedsearch/web/3?' + urlencode(
        {'keyword': query, 'maxResults': 5})
    request = urllib.request.Request(url, headers={'User-Agent': 'souz-video-search/1.0',
                                                  'Accept': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            if response.url != url:
                raise SearchError('Okko перенаправил запрос поиска; поиск остановлен.')
            body = response.read(3_000_001)
        if len(body) > 3_000_000:
            raise SearchError('Ответ поиска Okko слишком велик.')
        return okko_result(query, json.loads(body))
    except urllib.error.HTTPError as error:
        raise SearchError(f'Okko HTTP {error.code}; поиск не выполнен.') from None
    except (urllib.error.URLError, OSError):
        raise SearchError('Okko недоступен или не ответил вовремя.') from None


class Browser:
    def __init__(self):
        self.deadline = time.monotonic() + 25

    def call(self, *args):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise SearchError('Истекло время поиска кинотеатра.')
        try:
            result = subprocess.run(['agent-browser', *args], capture_output=True,
                                    text=True, timeout=min(15, remaining))
        except (OSError, subprocess.TimeoutExpired):
            raise SearchError('Lightpanda недоступен или не ответил вовремя.') from None
        if result.returncode:
            raise SearchError('Операция браузера завершилась ошибкой.')
        return result.stdout

    def read(self, platform):
        source = Path(__file__).with_name('cinema_dom.js').read_text()
        try:
            page = json.loads(self.call('eval', source + '(' + json.dumps(platform) + ')'))
            if not isinstance(page, dict) or not isinstance(page.get('cards'), list):
                raise ValueError()
            return page
        except (ValueError, TypeError):
            raise SearchError('Браузер вернул некорректные данные страницы.') from None


def collect(platform, query, browser=None):
    search_url(platform, query)
    if platform in ('wink', 'okko'):
        raise SearchError('Для этой площадки используйте команду http без браузера.')
    browser = browser or Browser()
    for attempt in range(7):
        page = browser.read(platform)
        check_page(platform, query, page, require_input=False)
        if platform == 'premier' and page.get('input') != query:
            pass  # Wait for hydration of the search input corresponding to the URL.
        elif re.search(EMPTY, page.get('text', ''), re.I) and not page['cards']:
            return make_result(platform, query, [])
        elif page['cards']:
            result = make_result(platform, query, page['cards'])
            if platform == 'ivi' and not result['complete']:
                # Ivi virtualizes its list. One bounded scroll exposes the next cards.
                browser.call('scroll', 'down', '600')
                time.sleep(.5)
                next_page = browser.read(platform)
                check_page(platform, query, next_page)
                result = make_result(platform, query, page['cards'] + next_page['cards'])
            return result
        if attempt < 6:
            time.sleep(.7)
    raise SearchError('Выдача не загрузилась или изменилась разметка; это не пустой результат.')


def main():
    parser = argparse.ArgumentParser(description='Public cinema search')
    parser.add_argument('action', choices=['url', 'http', 'collect'])
    parser.add_argument('platform', choices=SITES)
    parser.add_argument('query')
    args = parser.parse_args()
    query = args.query.strip()
    try:
        url = search_url(args.platform, query)
        if args.action == 'url':
            print(url)
            return 0
        if args.action == 'http':
            if args.platform not in ('wink', 'okko'):
                raise SearchError('Этой площадке требуется браузерный композит cinema_search.')
            result = search_okko(query) if args.platform == 'okko' else search_wink(query)
        else:
            result = collect(args.platform, query)
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except (SearchError, ValueError, TypeError, KeyError, AttributeError, IndexError):
        error = sys.exc_info()[1]
        message = str(error) if isinstance(error, SearchError) else 'Некорректные метаданные кинотеатра.'
        print(json.dumps({'error': message}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
