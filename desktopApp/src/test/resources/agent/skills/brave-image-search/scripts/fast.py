"""Prepare small vision previews and resolve selected originals without LLM bookkeeping."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time
from types import SimpleNamespace
import urllib.parse
import uuid

# Composite PYTHON steps start with system Python; reuse the installed Pillow environment.
if sys.executable != '/opt/souz/pdf-venv/bin/python':
    os.execv('/opt/souz/pdf-venv/bin/python', ['/opt/souz/pdf-venv/bin/python', __file__, *sys.argv[1:]])
sys.dont_write_bytecode = True
from PIL import Image, ImageDraw, ImageFont, ImageOps
import images

MERCHANDISE = re.compile(r'canvas|framed|wall\s*(?:art|decor|painting)|room aesthetic|signed poster|memorabilia|designed and sold by|gifts?', re.I)


def query_variants(query, kind):
    if kind == 'poster' and not re.search(r'poster|постер', query, re.I):
        query += ' poster'
    elif kind == 'cover' and not re.search(r'cover|обложк', query, re.I):
        query += ' cover'
    suffixes = ['', ' 2000 3000', ' high resolution xlg'] if kind in ('poster', 'cover') else ['', ' high resolution', ' large original photo']
    return list(dict.fromkeys((query + suffix).strip() for suffix in suffixes))


def candidate_order(item, kind):
    orientation = item['orientation']
    if not item.get('imageUrl') or item['sizeQuality'] == 'small':
        return None
    if kind == 'poster' and orientation not in ('portrait', 'unknown'):
        return None
    # Exclude product mockups from poster searches, not entire sites from web search.
    if kind in ('poster', 'cover') and MERCHANDISE.search(item.get('title') or ''):
        return None
    return (item['sizeQuality'] != 'large', item['relevanceRank'])


def retrieve(item, deadline, kind):
    started = time.monotonic()
    try:
        data, fmt, width, height = images.fetch_image(item['imageUrl'], min(deadline, started + 10))
        size = images.dimensions(width, height, images.MIN_SHORT_SIDE, images.MIN_LONG_SIDE)
        if size['sizeQuality'] != 'large':
            raise images.Failure('Actual original is below minimum resolution: %sx%s' % (width, height))
        if kind == 'poster' and size['orientation'] != 'portrait':
            raise images.Failure('Original is not a portrait image')
        return {'candidate': item, 'data': data, 'format': fmt, **size, 'downloadSeconds': round(time.monotonic() - started, 3)}
    except Exception as exc:
        return {'error': images.safe_error(exc), 'source': item.get('source')}


def make_preview(job, candidates):
    # One sheet, at most 1200x640. Original files are never overwritten or resized.
    sheet = Image.new('RGB', (400 * len(candidates), 640), 'white')
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 18)
    except OSError:
        font = ImageFont.load_default()
    for candidate in candidates:
        x = candidate['index'] * 400
        with Image.open(candidate['originalPath']) as original:
            preview = ImageOps.exif_transpose(original)
            preview.thumbnail((384, 576), Image.Resampling.LANCZOS)
            if preview.mode in ('RGBA', 'LA') or (preview.mode == 'P' and 'transparency' in preview.info):
                rgba = preview.convert('RGBA')
                sheet.paste(rgba, (x + (400 - rgba.width) // 2, 40 + (576 - rgba.height) // 2), rgba)
            else:
                sheet.paste(preview.convert('RGB'), (x + (400 - preview.width) // 2, 40 + (576 - preview.height) // 2))
        draw.text((x + 10, 8), '#%s  original %sx%s' % (candidate['index'], candidate['width'], candidate['height']), fill='black', font=font)
        host = urllib.parse.urlsplit(candidate['originalUrl']).hostname or ''
        draw.text((x + 10, 617), host[:31], fill='black', font=font)
    path = job / 'review.jpg'
    sheet.save(path, 'JPEG', quality=82, optimize=True)
    return path


def prepare(query, kind):
    started = time.monotonic()
    if not query.strip() or len(query) > 330 or len(query.split()) > 42:
        raise images.Failure('Query must contain 1–330 characters and at most 42 words')
    job = images.ROOT / uuid.uuid4().hex
    job.mkdir(parents=True, mode=0o700)
    deadline = started + 65
    seen_urls, seen_bytes = set(), set()
    candidates, errors, searches = [], [], []
    downloads = 0
    for query_text in query_variants(query, kind):
        if time.monotonic() > deadline - 5 or downloads >= 6:
            break
        search_start = time.monotonic()
        result = images.search(SimpleNamespace(query=query_text, count=40,
            language='ru' if re.search('[а-яА-Я]', query_text) else 'en',
            orientation='portrait' if kind == 'poster' else 'any',
            min_short_side=images.MIN_SHORT_SIDE, min_long_side=images.MIN_LONG_SIDE))
        searches.append({'query': query_text, 'seconds': round(time.monotonic() - search_start, 3)})
        ranked = []
        for item in result['results']:
            order = candidate_order(item, kind)
            if order is not None and item['imageUrl'] not in seen_urls:
                ranked.append((order, item))
        ranked.sort(key=lambda pair: pair[0])
        batch = [item for _, item in ranked[:min(3, 6 - downloads)]]
        seen_urls.update(item['imageUrl'] for item in batch)
        downloads += len(batch)
        with ThreadPoolExecutor(max_workers=3) as pool:
            retrieved = list(pool.map(lambda item: retrieve(item, deadline, kind), batch))
        for item in retrieved:
            if 'error' in item:
                errors.append(item)
                continue
            digest = hashlib.sha256(item['data']).hexdigest()
            if digest in seen_bytes:
                continue
            seen_bytes.add(digest)
            index = len(candidates)
            extension = {'JPEG': 'jpg', 'PNG': 'png', 'WEBP': 'webp', 'GIF': 'gif'}[item['format']]
            path = job / ('original-%s.%s' % (index, extension))
            with path.open('xb') as target:
                target.write(item['data'])
            source = item['candidate']
            candidates.append({'index': index, 'originalPath': str(path), 'originalUrl': source['imageUrl'],
                'pageUrl': source.get('pageUrl'), 'title': source.get('title'), 'width': item['width'],
                'height': item['height'], 'bytes': len(item['data']), 'sha256': digest})
        # One acceptable candidate is enough. A model can reject semantic mismatches from the preview.
        if candidates:
            break
    evidence = {'query': query, 'kind': kind, 'candidates': candidates, 'searches': searches, 'errors': errors,
                'prepareSeconds': round(time.monotonic() - started, 3)}
    if not candidates:
        (job / 'job.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
        raise images.Failure('No original met the resolution target within three searches and six downloads; refine the subject instead of lowering quality')
    preview = make_preview(job, candidates)
    evidence['previewPath'] = str(preview)
    evidence['previewBytes'] = preview.stat().st_size
    evidence['prepareSeconds'] = round(time.monotonic() - started, 3)
    (job / 'job.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
    return str(preview)


def selected(preview_path, index_text):
    preview = Path(preview_path).resolve()
    if not preview.is_relative_to(images.ROOT.resolve()) or preview.name != 'review.jpg' or not re.fullmatch('[0-9a-f]{32}', preview.parent.name):
        raise images.Failure('Use the preview path returned by find_and_review')
    job = json.loads(preview.with_name('job.json').read_text(encoding='utf-8'))
    try:
        index = int(index_text)
        if index < 0:
            raise ValueError()
        candidate = job['candidates'][index]
    except (ValueError, IndexError):
        raise images.Failure('Select an existing candidate number from the preview') from None
    path = Path(candidate['originalPath']).resolve(strict=True)
    if path.parent != preview.parent or path.name == 'review.jpg':
        raise images.Failure('Selected original is outside this result')
    return candidate


def channel_id(raw, requested):
    if requested not in ('telegram', 'vk'):
        raise images.Failure('Channel must be telegram or vk')
    value = json.loads(raw)
    if isinstance(value, str):
        value = json.loads(value)
    channels = [item for item in value['channels'] if item.get('channelType') == requested]
    if len(channels) != 1:
        raise images.Failure('Expected exactly one linked %s channel; resolve destination explicitly' % requested)
    return channels[0]['channelId']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    p = sub.add_parser('prepare')
    p.add_argument('query')
    p.add_argument('kind', choices=['poster', 'cover', 'photo', 'image'])
    for mode in ('path', 'original', 'caption'):
        p = sub.add_parser(mode)
        p.add_argument('preview')
        p.add_argument('index')
    p = sub.add_parser('channel')
    p.add_argument('channels_json')
    p.add_argument('requested')
    p = sub.add_parser('pack')
    p.add_argument('preview')
    p.add_argument('review')
    args = parser.parse_args()
    try:
        if args.mode == 'prepare':
            output = prepare(args.query, args.kind)
        elif args.mode == 'pack':
            try:
                review = json.loads(args.review)
            except ValueError:
                review = {'result': args.review}
            output = json.dumps({'preview': args.preview, 'review': review}, ensure_ascii=False)
        elif args.mode == 'channel':
            output = channel_id(args.channels_json, args.requested)
        else:
            item = selected(args.preview, args.index)
            if args.mode == 'path':
                output = item['originalPath']
            elif args.mode == 'caption':
                output = (item.get('title') or 'Image') + '\nИсточник: ' + (item.get('pageUrl') or item['originalUrl'])
            else:
                output = json.dumps({'originalUrl': item['originalUrl'], 'pageUrl': item.get('pageUrl'), 'width': item['width'], 'height': item['height'], 'sizeBytes': item['bytes']}, ensure_ascii=False)
        sys.stdout.write(output)  # Composite references require a bare path/ID, without a newline.
        return 0
    except Exception as exc:
        print(json.dumps({'error': images.safe_error(exc)}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
