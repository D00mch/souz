"""Bounded mail data for LLM composition; no notification wording or network calls."""
import base64
from email.header import decode_header, make_header
from email.message import Message
from html import unescape
from html.parser import HTMLParser


def clip(text, limit):
    return text.encode('utf-8')[:limit].decode('utf-8', errors='ignore')


def header(value, limit):
    try:
        value = str(make_header(decode_header(value)))
    except (LookupError, ValueError, UnicodeError):
        pass
    return clip(' '.join(value.split()), limit)


class HtmlText(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.hidden = 0
        self.text = []

    def handle_starttag(self, tag, attrs):
        if tag in {'head', 'script', 'style'}:
            self.hidden += 1
        if tag in {'p', 'br', 'div', 'li', 'tr'} and not self.hidden:
            self.text.append('\n')

    def handle_endtag(self, tag):
        if tag in {'head', 'script', 'style'}:
            self.hidden = max(0, self.hidden - 1)
        if tag in {'p', 'div', 'li', 'tr'} and not self.hidden:
            self.text.append('\n')

    def handle_data(self, data):
        if not self.hidden:
            self.text.append(data)


def email_context(message):
    payload = message.get('payload', {})
    headers = {item['name'].lower(): item['value'] for item in payload.get('headers', [])}
    plain, html, stack = [], [], [payload]
    incomplete = False
    for _ in range(100):
        if not stack:
            break
        part = stack.pop()
        part_headers = {item['name'].lower(): item['value'] for item in part.get('headers', [])}
        if part.get('filename') or part_headers.get('content-disposition', '').lower().startswith('attachment'):
            continue
        stack.extend(reversed(part.get('parts', [])))
        kind = part.get('mimeType')
        if kind not in {'text/plain', 'text/html'}:
            continue
        data = part.get('body', {}).get('data')
        if not data:
            incomplete |= bool(part.get('body', {}).get('attachmentId'))
            continue
        content_type = Message()
        content_type['content-type'] = part_headers.get('content-type', kind)
        raw = base64.urlsafe_b64decode(data + '=' * (-len(data) % 4))
        try:
            text = raw.decode(content_type.get_content_charset() or 'utf-8', errors='replace')
        except LookupError:
            text = raw.decode('utf-8', errors='replace')
        if kind == 'text/html':
            parser = HtmlText()
            parser.feed(text)
            html.append(''.join(parser.text))
        else:
            plain.append(text)
    text = '\n'.join(plain or html).strip()
    source = 'body' if text else 'snippet'
    text = text or unescape(message.get('snippet', ''))
    bounded = clip(text, 4800)
    return {'untrusted': True, 'from': header(headers.get('from', ''), 320),
            'subject': header(headers.get('subject', ''), 600), 'date': header(headers.get('date', ''), 100),
            'body': bounded, 'contentSource': source,
            'contentTruncated': bool(stack) or incomplete or bounded != text}
