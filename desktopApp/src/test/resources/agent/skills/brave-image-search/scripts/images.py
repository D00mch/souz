"""Brave image discovery and bounded downloads for a personal Souz skill."""
import argparse
import io
import ipaddress
import json
import os
from pathlib import Path
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import warnings

from PIL import Image

ROOT = Path('/souz/workspace/brave-image-search')
API = 'https://api.search.brave.com/res/v1/images/search'
USER_AGENT = 'SouzImageSearch/1.0 (https://novaopenclaw.duckdns.org)'
MAX_BYTES = 15_000_000
MIN_SHORT_SIDE = 1000
MIN_LONG_SIDE = 1400
Image.MAX_IMAGE_PIXELS = 30_000_000
warnings.simplefilter('error', Image.DecompressionBombWarning)


class Failure(Exception):
    pass


def public_url(url):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password:
        raise Failure('Expected a public HTTP(S) URL without credentials')
    try:
        addresses = socket.getaddrinfo(parsed.hostname, parsed.port or (443 if parsed.scheme == 'https' else 80), type=socket.SOCK_STREAM)
    except (OSError, ValueError):
        raise Failure('Image hostname could not be resolved') from None
    if not addresses or any(not ipaddress.ip_address(a[4][0]).is_global for a in addresses):
        raise Failure('Image URL does not resolve exclusively to public addresses')
    return url


class PublicRedirects(urllib.request.HTTPRedirectHandler):
    max_redirections = 4
    max_repeats = 2

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        public_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch_image(url, deadline):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise Failure('Download time budget exhausted')
    req = urllib.request.Request(public_url(url), headers={'User-Agent': USER_AGENT, 'Accept': 'image/*'})
    # This opener has no Brave authorization header or cookie jar. Never reuse API credentials for sources.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), PublicRedirects())
    with opener.open(req, timeout=min(12, remaining)) as response:
        mime = response.headers.get_content_type()
        if mime not in ('image/png', 'image/jpeg', 'image/webp', 'image/gif', 'application/octet-stream'):
            raise Failure('Source did not return a supported image content type')
        length = response.headers.get('Content-Length')
        if length and int(length) > MAX_BYTES:
            raise Failure('Image exceeds 15 MB')
        data = bytearray()
        while True:
            if time.monotonic() >= deadline:
                raise Failure('Download time budget exhausted')
            chunk = response.read(min(65536, MAX_BYTES + 1 - len(data)))
            if not chunk:
                break
            data.extend(chunk)
            if len(data) > MAX_BYTES:
                raise Failure('Image exceeds 15 MB')
    with Image.open(io.BytesIO(data)) as im:
        fmt, width, height = im.format, im.width, im.height
        if fmt not in ('PNG', 'JPEG', 'WEBP', 'GIF'):
            raise Failure('Unsupported image format')
        im.verify()
    # Decode the first frame as well; signatures and dimensions alone are not validation.
    with Image.open(io.BytesIO(data)) as im:
        im.load()
    return bytes(data), fmt, width, height


def safe_error(exc):
    # Exception strings can contain remote URLs or request data. Return bounded categories only.
    if isinstance(exc, urllib.error.HTTPError):
        return 'HTTP ' + str(exc.code)
    if isinstance(exc, Failure):
        return str(exc)
    if isinstance(exc, (TimeoutError, socket.timeout)):
        return 'Network timeout'
    if isinstance(exc, urllib.error.URLError):
        return 'Network or TLS error'
    return 'Invalid image or response (' + type(exc).__name__ + ')'


def dimensions(width, height, min_short, min_long):
    try:
        width, height = int(width), int(height)
        if width <= 0 or height <= 0:
            raise ValueError()
    except (TypeError, ValueError):
        return {'width': None, 'height': None, 'megapixels': None, 'sizeQuality': 'unknown', 'orientation': 'unknown'}
    return {'width': width, 'height': height, 'megapixels': round(width * height / 1_000_000, 2),
            'sizeQuality': 'large' if min(width, height) >= min_short and max(width, height) >= min_long else 'small',
            'orientation': 'portrait' if height > width * 1.1 else 'landscape' if width > height * 1.1 else 'square'}


def search(args):
    query = args.query.strip()
    if not query or len(query) > 400 or len(query.split()) > 50:
        raise Failure('Query must contain 1–400 characters and at most 50 words')
    key = os.environ.get('BRAVE_SEARCH_API_KEY', '').strip()
    if not key:
        raise Failure('BRAVE_SEARCH_API_KEY is not available to this Skill; configure backend environment forwarding')
    params = urllib.parse.urlencode({'q': query, 'count': args.count, 'country': 'ALL', 'search_lang': args.language, 'safesearch': 'strict'})
    request = urllib.request.Request(API + '?' + params, headers={'X-Subscription-Token': key, 'Accept': 'application/json', 'User-Agent': USER_AGENT})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoApiRedirects())
    try:
        with opener.open(request, timeout=20) as response:
            body = response.read(2_000_001)
        if len(body) > 2_000_000:
            raise Failure('Brave response exceeds 2 MB')
        raw = json.loads(body)
    except urllib.error.HTTPError as exc:
        hints = {401: 'Check the Brave API key', 403: 'Check key permissions and Image Search subscription', 429: 'Rate limit or quota reached; retry later'}
        raise Failure('Brave HTTP %s: %s' % (exc.code, hints.get(exc.code, 'Search request failed'))) from None
    if raw.get('type') != 'images' or not isinstance(raw.get('results'), list):
        raise Failure('Unexpected Brave response schema')
    results = []
    for rank, item in enumerate(raw['results'][:args.count]):
        props, thumb = item.get('properties') or {}, item.get('thumbnail') or {}
        if not props.get('url') and not thumb.get('src'):
            continue
        results.append({'relevanceRank': rank + 1, 'title': item.get('title'), 'pageUrl': item.get('url'), 'source': item.get('source'), 'imageUrl': props.get('url'), 'thumbnailUrl': thumb.get('src'),
                        **dimensions(props.get('width'), props.get('height'), args.min_short_side, args.min_long_side)})
    # Prefer suitable large originals; retain Brave's relevance order within a quality group.
    def quality_order(item):
        orientation_ok = args.orientation == 'any' or item['orientation'] in (args.orientation, 'unknown')
        size_rank = {'large': 0, 'unknown': 1, 'small': 2}[item['sizeQuality']]
        return (not orientation_ok, size_rank, item['relevanceRank'])
    results.sort(key=quality_order)
    for index, item in enumerate(results):
        item['index'] = index
    out = ROOT / uuid.uuid4().hex
    out.mkdir(parents=True, mode=0o700)
    manifest = out / 'results.json'
    preferred = sum(i['sizeQuality'] == 'large' and (args.orientation == 'any' or i['orientation'] == args.orientation) for i in results)
    result = {'query': query, 'resultsFile': str(manifest), 'qualityTarget': {'minShortSide': args.min_short_side, 'minLongSide': args.min_long_side, 'orientation': args.orientation}, 'preferredLargeCount': preferred, 'results': results}
    manifest.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    return result


class NoApiRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise Failure('Unexpected Brave API redirect; authorization was not forwarded')


def download(args):
    manifest = Path(args.results_file).resolve()
    if not manifest.is_relative_to(ROOT.resolve()) or manifest.name != 'results.json':
        raise Failure('Use the resultsFile path returned by search')
    payload = json.loads(manifest.read_text(encoding='utf-8'))
    try:
        indexes = list(dict.fromkeys(int(v) for v in args.indexes.split(',')))
    except ValueError:
        raise Failure('Indexes must be comma-separated integers') from None
    if not 1 <= len(indexes) <= 3 or any(i < 0 or i >= len(payload['results']) for i in indexes):
        raise Failure('Select one to three existing result indexes')
    files, errors = [], []
    quality = payload.get('qualityTarget') or {}
    min_short = quality.get('minShortSide', MIN_SHORT_SIDE)
    min_long = quality.get('minLongSide', MIN_LONG_SIDE)
    overall_deadline = time.monotonic() + 90
    for index in indexes:
        candidate = payload['results'][index]
        attempts = []
        deadline = min(overall_deadline, time.monotonic() + 28)
        variants = [('original', candidate.get('imageUrl'))]
        if args.allow_thumbnail:
            variants.append(('thumbnail', candidate.get('thumbnailUrl')))
        for variant, url in variants:
            if not url:
                continue
            try:
                data, fmt, width, height = fetch_image(url, deadline)
                size = dimensions(width, height, min_short, min_long)
                if size['sizeQuality'] == 'small' and not args.allow_small:
                    raise Failure('Actual resolution %sx%s is below target: short side >=%s and long side >=%s; choose another original or refine search' % (width, height, min_short, min_long))
                path = manifest.parent / ('image-%s-%s.%s' % (index, uuid.uuid4().hex[:8], {'JPEG': 'jpg', 'PNG': 'png', 'WEBP': 'webp', 'GIF': 'gif'}[fmt]))
                with path.open('xb') as output:
                    output.write(data)
                files.append({'index': index, 'path': str(path), 'title': candidate.get('title'), 'pageUrl': candidate.get('pageUrl'), 'imageUrl': url, 'variant': variant, 'format': fmt, **size, 'bytes': len(data), 'failedAttempts': attempts})
                break
            except Exception as exc:
                attempts.append({'variant': variant, 'error': safe_error(exc)})
        else:
            errors.append({'index': index, 'attempts': attempts})
    return {'qualityTarget': {'minShortSide': min_short, 'minLongSide': min_long}, 'files': files, 'errors': errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest='action', required=True)
    p = subs.add_parser('search')
    p.add_argument('query')
    p.add_argument('--count', type=int, choices=range(1, 51), default=20, metavar='1..50')
    p.add_argument('--language', choices=['en', 'ru', 'de', 'fr', 'es', 'it', 'pt', 'ja', 'ko', 'zh-hans'], default='en')
    p.add_argument('--orientation', choices=['any', 'portrait', 'landscape', 'square'], default='any')
    p.add_argument('--min-short-side', type=int, choices=range(1, 10001), default=MIN_SHORT_SIDE, metavar='PIXELS')
    p.add_argument('--min-long-side', type=int, choices=range(1, 10001), default=MIN_LONG_SIDE, metavar='PIXELS')
    p = subs.add_parser('download')
    p.add_argument('results_file')
    p.add_argument('--indexes', required=True)
    p.add_argument('--allow-small', action='store_true', help='Only when the user accepts a smaller image')
    p.add_argument('--allow-thumbnail', action='store_true', help='Only when the user accepts a Brave preview')
    args = parser.parse_args()
    try:
        result = search(args) if args.action == 'search' else download(args)
        print(json.dumps(result, ensure_ascii=False))
        return 0 if args.action == 'search' or result['files'] else 1
    except Exception as exc:
        print(json.dumps({'error': safe_error(exc)}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
