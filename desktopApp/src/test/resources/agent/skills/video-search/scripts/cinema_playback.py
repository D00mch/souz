"""Resolve public catalogue metadata into Android TV links; never contact a TV."""
import argparse
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import urlencode, urljoin, urlsplit

from cinemas import Browser, SearchError

PACKAGES = {'okko': 'ru.more.play', 'start': 'ru.start.androidtv', 'premier': 'one.premier.rustoretv', 'wink': 'ru.rt.video.app.tv', 'ivi': 'ru.ivi.client',
            'kinopoisk': 'ru.kinopoisk.tv', 'kion': 'ru.mts.mtstv'}
HOSTS = {'okko': 'okko.tv', 'start': 'start.ru', 'premier': 'premier.one', 'wink': 'wink.ru', 'ivi': 'www.ivi.ru', 'kion': 'kion.ru', 'kinopoisk': 'www.kinopoisk.ru'}
PATHS = {'okko': r'/(?:movie|serial)/[a-z0-9-]+', 'start': r'/watch/[a-z0-9-]+', 'premier': r'/show/[a-z0-9-]+', 'wink': r'/(?:movies|series)/[a-z0-9-]+', 'ivi': r'/(?:en/)?watch/[a-z0-9-]+',
         'kion': r'/(?:film|serial)/[a-z0-9-]+', 'kinopoisk': r'/(?:film|series)/[1-9][0-9]*/?'}
MAX_BODY = 7_000_000


def fail(message):
    raise SearchError(message)


def integer(value):
    s = str(value)
    if not re.fullmatch(r'[1-9][0-9]{0,9}', s) or int(s) > 2_147_483_647:
        fail('Некорректный числовой идентификатор каталога.')
    return s


def hd_id(value):
    if not isinstance(value, str) or not re.fullmatch(r'[a-f0-9]{32}', value):
        fail('ID онлайн-кинотеатра Кинопоиск не найден.')
    return value


def validate(platform, url, mode, season='', episode=''):
    if platform not in PACKAGES or mode not in ('open', 'play'):
        fail('Неподдерживаемая площадка или действие.')
    u = urlsplit(url)
    if u.scheme != 'https' or u.netloc != HOSTS[platform] or not re.fullmatch(PATHS[platform], u.path):
        fail('Нужна веб-ссылка выбранного произведения из каталога этой площадки.')
    if bool(season) != bool(episode):
        fail('Укажите вместе номер сезона и номер серии.')
    for n in (season, episode):
        if n and (not re.fullmatch(r'[1-9][0-9]{0,3}', str(n))):
            fail('Номера сезона и серии должны быть положительными числами до 9999.')
    if platform == 'kion' and season:
        fail('KION: выбор конкретной серии не поддержан; можно открыть карточку сериала '
             'или продолжить с последней непросмотренной серии (play без номеров).')
    if platform in ('start', 'premier', 'okko') and season and mode == 'open':
        fail('Для START/PREMIER/Okko точная серия поддержана только в режиме play.')
    path = u.path.replace('/en/watch/', '/watch/') if platform == 'ivi' else u.path
    return 'https://' + u.netloc + path.rstrip('/')


class SameHostRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        u, original = urlsplit(newurl), urlsplit(req.full_url)
        if u.scheme != 'https' or u.netloc != original.netloc:
            fail('Сайт перенаправил на другой домен; получение ссылки остановлено.')
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class Fetcher:
    def __init__(self):
        self.deadline = time.monotonic() + 40
        self.opener = urllib.request.build_opener(SameHostRedirect())

    def __call__(self, url):
        u = urlsplit(url)
        if u.scheme != 'https' or u.netloc not in ('wink.ru', 'www.ivi.ru', 'kion.ru', 'api2.ivi.ru', 'start.ru', 'premier.one', 'api.okko.tv'):
            fail('Недопустимый адрес метаданных.')
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            fail('Истекло время получения метаданных.')
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0', 'Accept-Encoding': 'identity'})
            with self.opener.open(req, timeout=min(12, remaining)) as r:
                body = r.read(MAX_BODY + 1)
                final = r.url
            if len(body) > MAX_BODY:
                fail('Страница каталога слишком велика.')
            if urlsplit(final).path.rstrip('/') != u.path.rstrip('/'):
                fail('Вместо выбранной карточки сайт открыл другую страницу.')
            return body.decode('utf-8', 'replace')
        except urllib.error.HTTPError as e:
            fail(f'Каталог вернул HTTP {e.code}; ссылка запуска не подготовлена.')
        except (urllib.error.URLError, OSError):
            fail('Каталог не ответил вовремя или недоступен.')


def assigned(body, variable):
    m = re.search(re.escape(variable) + r'\s*=\s*', body)
    if not m:
        fail('Данные карточки не найдены; возможна блокировка или изменение сайта.')
    return json.JSONDecoder().raw_decode(body[m.end():])[0]


class Page(HTMLParser):
    def __init__(self, body):
        super().__init__()
        self.scripts, self.links, self.current = {}, set(), None
        self.feed(body)

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == 'a' and a.get('href'):
            self.links.add(a['href'])
        if tag == 'script' and a.get('id'):
            self.current = a['id']
            self.scripts[self.current] = ''

    def handle_data(self, data):
        if self.current is not None:
            self.scripts[self.current] += data

    def handle_endtag(self, tag):
        if tag == 'script':
            self.current = None


def unique(items, message):
    items = list(items)
    if len(items) != 1:
        fail(message)
    return items[0]


def result(platform, url, title, mode, ids, season='', episode=''):
    if not isinstance(title, str) or not title.strip():
        fail('Название выбранного произведения не подтверждено.')
    if platform == 'wink':
        ids = {k: integer(v) for k, v in ids.items()}
        path = ids['content']
        if season:
            path += '/' + ids['season'] + '/' + ids['episode']
        uri = ('wink://watch_next/' if mode == 'play' else 'wink://media_items/') + path
        if mode == 'play':
            uri += '?launcher=watch_next'
    elif platform == 'ivi':
        ids = {k: integer(v) for k, v in ids.items()}
        video = ids.get('episode') or ids['content']
        uri = f'ruiviclient://play/movie/{video}' if mode == 'play' else f'ruiviclient://movie/open/{video}'
        if mode == 'open' and 'compilation' in ids and not season:
            uri = f"ruiviclient://compilation/open/{ids['compilation']}"
    elif platform == 'kion':
        ids = {'content': integer(ids['content'])}
        # For a series, action=PLAY resumes from the account's last unwatched episode.
        kind = 'serial' if urlsplit(url).path.startswith('/serial/') else 'movie'
        uri = f"mtstvapp://kion.ru/video/{kind}/{ids['content']}" + ('?action=PLAY' if mode == 'play' else '')
    elif platform == 'okko':
        target = ids.get('episode') or ids['content']
        kind = 'EPISODE' if season else ('SERIAL' if urlsplit(url).path.startswith('/serial/') else 'MOVIE')
        uri = ('okko://?' if mode == 'play' else 'okko://movie?') + urlencode({'uid': target, 'type': kind})
    elif platform == 'start':
        uri = 'startru://start' + urlsplit(url).path
        if season:
            uri = ('startru://start/stream/options/series/episode/' + ids['season'] + '/' + ids['episode']
                   + '?' + urlencode({'product_id': ids['content'], 'content_id': ids['episode']}))
    elif platform == 'premier':
        uri = 'one.premier://premier.one' + urlsplit(url).path
        if season:
            uri += '/season/' + str(season) + '/video/' + ids['episode']
    else:
        ids = {k: hd_id(v) for k, v in ids.items()}
        params = {'filmId': ids['content']}
        if season:
            params.update(seasonNumber=str(season), episodeId=ids['episode'])
        if mode == 'play':
            params['action'] = 'play'
        uri = 'kpatv://' + ('seasons' if season else 'film') + '?' + urlencode(params)
    return {'platform': platform, 'title': title.strip(), 'mode': mode,
            'season': int(season) if season else None, 'episode': int(episode) if episode else None,
            'ids': ids, 'links': [{'type': 'WEB', 'url': url},
                                  {'type': mode.upper(), 'url': uri, 'packageName': PACKAGES[platform]}]}


def uuid_id(value):
    if not isinstance(value, str) or not re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', value):
        fail('Некорректный UUID каталога.')
    return value


def okko(url, mode, season, episode, fetch):
    url = validate('okko', url, mode, season, episode)
    kind, alias = urlsplit(url).path.strip('/').split('/')
    kind = {'movie': 'MOVIE', 'serial': 'SERIAL'}[kind]
    if kind == 'MOVIE' and season:
        fail('У фильма Okko нельзя выбрать сезон и серию.')
    if kind == 'SERIAL' and mode == 'play' and not season:
        fail('Для запуска сериала Okko укажите сезон и серию.')
    endpoint = 'https://api.okko.tv/screenapi/v5/noauth/moviecard/web/1?'
    data = json.loads(fetch(endpoint + urlencode({'elementAlias': alias, 'elementType': kind})))
    if not isinstance(data, dict) or type(data.get('status')) is not int or data['status'] != 0 or 'antibot' in data:
        fail('Okko ограничил доступ или вернул ошибку карточки.')
    card = data['element']
    if card.get('type') != kind or card.get('alias') != alias:
        fail('Okko не подтвердил выбранное произведение.')
    ids = {'content': uuid_id(card['id'])}
    selected = [card]
    if season:
        def child(parent, number, child_type):
            children = parent['children']
            items = children['items']
            if not isinstance(items, list) or type(children.get('totalSize')) is not int or children['totalSize'] != len(items):
                fail('Okko вернул неполный список сезонов или серий.')
            return unique((i['element'] for i in items
                           if i['element'].get('type') == child_type
                           and type(i['element'].get('seqNo')) is int
                           and i['element']['seqNo'] == int(number)),
                          'Сезон или серия Okko не найдены либо неоднозначны.')
        chosen_season = child(card, season, 'SEASON')
        ep = child(chosen_season, episode, 'EPISODE')
        ids.update(season=uuid_id(chosen_season['id']), episode=uuid_id(ep['id']))
        selected.extend([chosen_season, ep])
    if mode == 'play' and any(x.get('releaseType') != 'RELEASE' for x in selected):
        fail('Okko не подтвердил выпуск выбранного видео; ссылка запуска не подготовлена.')
    answer = result('okko', url, card['name'], mode, ids, season, episode)
    if season:
        answer['episodeTitle'] = ep['name']
    return answer


def start(url, mode, season, episode, fetch):
    card = json.loads(Page(fetch(url)).scripts['__NEXT_DATA__'])['props']['pageProps']['data']
    if card.get('alias') != urlsplit(url).path.rsplit('/', 1)[1] or card.get('_cls') != 'Product.Series':
        fail('START не подтвердил выбранный сериал.')
    ids = {'content': uuid_id(card['uid'])}
    if not season and mode == 'play':
        fail('Для запуска сериала START укажите сезон и серию.')
    if season:
        selected = unique((s for s in card['items'] if str(s.get('number')) == str(season)),
                          'Сезон START не найден или неоднозначен.')
        ep = unique((e for e in selected['items'] if str(e.get('number')) == str(episode)),
                    'Серия START не найдена отдельно; объединённые серии не разбиваются.')
        for item in (card, selected, ep):
            if item.get('announcement') or item.get('unavailable_by_geo') or item.get('display') is False:
                fail('Выбранная серия START недоступна в публичном каталоге.')
            stamp = item.get('start_release_date')
            if type(stamp) in (int, float) and stamp > time.time() * 1000:
                fail('Выбранная серия START ещё не вышла.')
        ids.update(season=uuid_id(selected['uid']), episode=uuid_id(ep['uid']))
        expected = '/stream/options/series/episode/' + ids['season'] + '/' + ids['episode']
        if ep.get('playback_options') != expected:
            fail('START не подтвердил связь эпизода с сезоном.')
    answer = result('start', url, card['title'], mode, ids, season, episode)
    if season:
        answer['episodeTitle'] = ep['title']
    return answer


class NuxtRefs:
    """Read only named catalogue branches of Nuxt's devalue reference table."""
    def __init__(self, body):
        self.values = json.loads(Page(body).scripts['__NUXT_DATA__'])
        if not isinstance(self.values, list):
            fail('Метаданные PREMIER не распознаны.')

    def node(self, ref):
        for _ in range(8):
            if type(ref) is not int or ref < 0 or ref >= len(self.values):
                fail('Некорректная ссылка в метаданных PREMIER.')
            value = self.values[ref]
            if isinstance(value, list) and len(value) == 2 and value[0] in ('Reactive', 'ShallowReactive', 'Ref', 'ShallowRef'):
                ref = value[1]
            else:
                return value
        fail('Цикл в метаданных PREMIER.')

    def field(self, ref, key):
        return self.node(ref)[key]


def premier(url, mode, season, episode, fetch):
    slug = urlsplit(url).path.rsplit('/', 1)[1]
    page_url = url + ('/season/' + str(season) + '/episode/' + str(episode) if season else '')
    refs = NuxtRefs(fetch(page_url))
    data = refs.field(0, 'data')
    content = refs.field(data, 'show-catalog-meta-info-' + slug)
    value = lambda key: refs.node(refs.field(content, key))
    if value('slug') != slug:
        fail('PREMIER вернул другую карточку.')
    ids = {'content': integer(value('id'))}
    if not season and mode == 'play':
        fail('Для запуска PREMIER укажите сезон и серию.')
    if season:
        items = refs.node(refs.field(refs.field(content, 'videoEpisodes'), 'result'))
        matches = [ref for ref in items if refs.node(refs.field(ref, 'season')) == int(season)
                   and refs.node(refs.field(ref, 'episode')) == int(episode)]
        ep = unique(matches, 'Серия PREMIER не найдена или неоднозначна.')
        if refs.node(refs.field(ep, 'isClickable')) is not True:
            fail('Выбранная серия PREMIER ещё недоступна.')
        vid = refs.node(refs.field(ep, 'id'))
        if not isinstance(vid, str) or not re.fullmatch(r'[a-f0-9]{32}', vid):
            fail('Видео ID PREMIER не распознан.')
        if refs.node(refs.field(ep, 'videoUrl')) != 'https://uma.media/video/' + vid + '/':
            fail('PREMIER не подтвердил ID видео.')
        ids['episode'] = vid
    return result('premier', url, value('name'), mode, ids, season, episode)


def wink_queries(body):
    return assigned(body, 'window.__REACT_QUERY_STATE__')['queries']


def wink_data(queries, key):
    return unique((q['state']['data'] for q in queries if q.get('queryKey') == key),
                  'Нужные метаданные Wink отсутствуют или неоднозначны.')


def wink(url, mode, season, episode, fetch):
    queries = wink_queries(fetch(url))
    content = wink_data(queries, ['media_item', urlsplit(url).path.rsplit('/', 1)[1]])
    cid = integer(content['id'])
    if content.get('slug') != urlsplit(url).path.rsplit('/', 1)[1]:
        fail('Wink вернул другую карточку.')
    if content.get('type') not in ('movie', 'film', 'series'):
        fail('Wink вернул неподдерживаемый тип произведения.')
    ids = {'content': cid}
    if season:
        if content['type'] != 'series':
            fail('Выбрана карточка фильма, а не сериала.')
        seasons = wink_data(queries, ['seasons', int(cid)])['items']
        selected = unique((s for s in seasons if s.get('order_number') == int(season)), 'Сезон не найден в каталоге Wink.')
        sid = integer(selected['id'])
        if str(selected.get('series_id')) != cid:
            fail('Сезон Wink относится к другому сериалу.')
        if not any(q.get('queryKey') == ['episodes', int(sid)] for q in queries):
            slug = selected['slug']
            if not re.fullmatch(r'[a-z0-9-]+', slug):
                fail('Некорректная ссылка сезона Wink.')
            queries = wink_queries(fetch('https://wink.ru/seasons/' + slug))
        episodes = wink_data(queries, ['episodes', int(sid)])['items']
        ep = unique((e for e in episodes if e.get('order_number') == int(episode)), 'Серия не найдена в доступных метаданных Wink.')
        if str(ep.get('series_id')) != cid or str(ep.get('season_id')) != sid or ep.get('type') != 'episode':
            fail('Эпизод Wink относится к другому сезону или сериалу.')
        ids.update(season=sid, episode=integer(ep['id']))
    elif content['type'] == 'series' and mode == 'play':
        fail('Для запуска сериала укажите сезон и серию; история просмотра не запрашивается.')
    return result('wink', url, content['name'], mode, ids, season, episode)


def ivi_state(body):
    return assigned(body, 'window.__INITIAL_STATE__')


def ivi(url, mode, season, episode, fetch):
    body = fetch(url)
    data = ivi_state(body)
    watch = data['pages']['watch']
    content = watch['rawContentInfo']
    cid = integer(content['id'])
    slug = urlsplit(url).path.rsplit('/', 1)[1]
    if str(watch['main']['contentId']) != cid or slug not in (cid, content.get('hru')):
        fail('Иви вернул другую карточку.')
    kind = content.get('kind')
    if kind not in (1, 2):
        fail('Неизвестный тип произведения Иви.')
    ids = {'content': cid}
    if kind == 2:
        ids['compilation'] = cid
    if season:
        if kind != 2:
            fail('Выбрана карточка фильма, а не сериала.')
        selected = unique((s for s in content['seasons'] if s.get('number') == int(season)), 'Сезон не найден в каталоге Иви.')
        def matches(state):
            return [e for e in state['common']['content'].values()
                    if e.get('season') == int(season) and e.get('episode') == int(episode)
                    and str((e.get('compilation') or {}).get('id')) == cid and e.get('kind') == 1]
        found = matches(data)
        if not found:
            count = int(integer(selected['episode_count']))
            if count > 10000:
                fail('Слишком большой список эпизодов Иви.')
            params = {'app_version': 870, 'id': cid, 'season': season, 'from': 0, 'to': count - 1,
                      'fields': 'id,kind,episode,season,compilation'}
            episodes = json.loads(fetch('https://api2.ivi.ru/mobileapi/videofromcompilation/v7/?' + urlencode(params)))['result']
            if not isinstance(episodes, list):
                fail('Список эпизодов Иви не распознан.')
            found = matches({'common': {'content': {str(i): e for i, e in enumerate(episodes)}}})
        ep = unique(found, 'Серия не найдена в доступной части сезона Иви.')
        ids['episode'] = integer(ep['id'])
    elif kind == 2 and mode == 'play':
        fail('Для запуска сериала укажите сезон и серию; история просмотра не запрашивается.')
    return result('ivi', url, content['title'], mode, ids, season, episode)


def kion(url, mode, fetch):
    kind, slug = urlsplit(url).path.strip('/').split('/')
    serial = kind == 'serial'
    data = json.loads(Page(fetch(url)).scripts['kion-state'])
    key = 'http_requests:/mgw-hostess/api/content/' + ('series/' if serial else 'movies/') + slug
    body = data[key]['body']
    if body.get('slug') != slug or body.get('type') != ('SERIES' if serial else 'MOVIE') or body.get('isSoon'):
        fail('KION не подтвердил доступную карточку ' + ('сериала.' if serial else 'фильма.'))
    # hid is Huawei's numeric ID; gid, mediaId and digits in image URLs are different IDs.
    return result('kion', url, body['title'], mode, {'content': integer(body['hid'])})


def kp_read(browser, catalog_id=''):
    source = Path(__file__).with_name('kinopoisk_playback.js').read_text()
    page = json.loads(browser.call('eval', source + '(' + json.dumps(catalog_id) + ')'))
    if isinstance(page, str):
        page = json.loads(page)
    if not isinstance(page, dict):
        fail('Некорректный ответ браузера Кинопоиска.')
    return page


def kinopoisk(url, mode, season, episode, browser=None):
    browser = browser or Browser()
    browser.deadline = time.monotonic() + 40
    catalog_id = urlsplit(url).path.rstrip('/').rsplit('/', 1)[1]
    page = kp_read(browser, catalog_id)
    u = urlsplit(page.get('url', ''))
    if u.scheme != 'https' or u.netloc != HOSTS['kinopoisk'] or u.path.rstrip('/') != urlsplit(url).path.rstrip('/'):
        fail('Браузер находится не на выбранной карточке Кинопоиска.')
    if page.get('blocked'):
        fail('Кинопоиск ограничил доступ; получение ссылки остановлено.')
    cid = hd_id(page.get('contentId'))
    ids = {'content': cid}
    if season:
        hd_url = 'https://hd.kinopoisk.ru/film/' + cid + '?' + urlencode({'season': season, 'episode': episode})
        browser.call('open', hd_url)
        hd = kp_read(browser)
        h = urlsplit(hd.get('url', ''))
        if h.scheme != 'https' or h.netloc != 'hd.kinopoisk.ru' or not re.fullmatch(r'/(?:ru-[a-z]{2}/)?film/' + cid + '/?', h.path):
            fail('Онлайн-кинотеатр открыл другую страницу или проверку доступа.')
        if hd.get('blocked') or hd.get('contentId') != cid:
            fail('Онлайн-кинотеатр не подтвердил выбранный сериал.')
        selected = unique((s for s in hd.get('seasons', []) if s.get('number') == int(season)), 'Сезон не найден в онлайн-кинотеатре.')
        ep = unique((e for e in selected['episodes'] if e.get('number') == int(episode)), 'Эпизод не найден в онлайн-кинотеатре.')
        if ep.get('status') not in ('WATCHABLE', None):
            fail('Выбранная серия ещё недоступна для просмотра в каталоге.')
        ids['episode'] = hd_id(ep['id'])
    elif page.get('series') and mode == 'play':
        fail('Для запуска сериала укажите сезон и серию; история просмотра не запрашивается.')
    answer = result('kinopoisk', url, page['title'], mode, ids, season, episode)
    if page.get('series') and not season:
        next(link for link in answer['links'] if link['type'] == 'OPEN')['url'] = 'kpatv://seasons?filmId=' + cid
    return answer


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('command', choices=['http', 'url', 'collect'])
    p.add_argument('platform', choices=PACKAGES)
    p.add_argument('url')
    p.add_argument('mode', choices=['open', 'play'])
    p.add_argument('season', nargs='?', default='')
    p.add_argument('episode', nargs='?', default='')
    a = p.parse_args()
    try:
        url = validate(a.platform, a.url, a.mode, a.season, a.episode)
        if a.command == 'url':
            if a.platform != 'kinopoisk':
                fail('Для этой площадки используйте http.')
            print(url)
            return 0
        if a.platform == 'kinopoisk':
            if a.command != 'collect':
                fail('Кинопоиску нужен композит cinema_prepare.')
            value = kinopoisk(url, a.mode, a.season, a.episode)
        else:
            if a.command != 'http':
                fail('Для этой площадки используйте http.')
            fetch = Fetcher()
            if a.platform == 'wink':
                value = wink(url, a.mode, a.season, a.episode, fetch)
            elif a.platform == 'ivi':
                value = ivi(url, a.mode, a.season, a.episode, fetch)
            elif a.platform in ('start', 'premier', 'okko'):
                resolver = {'start': start, 'premier': premier, 'okko': okko}[a.platform]
                value = resolver(url, a.mode, a.season, a.episode, fetch)
            else:
                value = kion(url, a.mode, fetch)
        print(json.dumps(value, ensure_ascii=False))
        return 0
    except (SearchError, KeyError, ValueError, TypeError, AttributeError, IndexError):
        error = sys.exc_info()[1]
        message = str(error) if isinstance(error, SearchError) else 'Метаданные не распознаны; ссылка запуска не подготовлена.'
        print(json.dumps({'error': message}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
