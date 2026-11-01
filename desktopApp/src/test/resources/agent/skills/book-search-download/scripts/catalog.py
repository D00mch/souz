"""Read Librusec search, author and genre catalogues. No downloads or account changes."""
import sys
sys.dont_write_bytecode = True
import argparse
import json
import re
import sys
from html.parser import HTMLParser
from urllib.parse import parse_qs, urlsplit, urlencode
from download_book import Client, Failure, norm, safe_url

SORT = {'relevance': '', 'newest': 't', 'title': 'l', 'largest': 'z', 'smallest': 'r'}

class Node:
    def __init__(self, tag='', attrs=()):
        self.tag, self.attrs, self.children = tag, dict(attrs), []
    def text(self):
        return ' '.join(' '.join(c.text() if isinstance(c, Node) else c for c in self.children).split())
    def find(self, tag):
        for child in self.children:
            if isinstance(child, Node):
                if child.tag == tag:
                    yield child
                yield from child.find(tag)

class Document(HTMLParser):
    def __init__(self, text):
        super().__init__(convert_charrefs=True)
        self.root = Node()
        self.stack = [self.root]
        self.feed(text)
    def handle_starttag(self, tag, attrs):
        node = Node(tag, attrs)
        self.stack[-1].children.append(node)
        if tag not in {'area','base','br','col','embed','hr','img','input','link','meta','param','source','track','wbr'}:
            self.stack.append(node)
    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        self.handle_endtag(tag)
    def handle_endtag(self, tag):
        for i in range(len(self.stack)-1, 0, -1):
            if self.stack[i].tag == tag:
                del self.stack[i:]
                break
    def handle_data(self, data):
        if self.stack[-1].tag not in {'script', 'style'}:
            self.stack[-1].children.append(data)

def words(value):
    return re.findall(r'[^\W_]+', norm(value), re.UNICODE)

def contains_words(actual, expected):
    return set(words(expected)).issubset(words(actual))

def book_url(value):
    url = safe_url(value)
    p = urlsplit(url)
    if not re.fullmatch(r'/book/[a-f0-9]{1,80}', p.path) or p.query:
        raise Failure('Expected a stable book URL from catalogue results')
    return url

def listings(text):
    doc = Document(text).root
    results, seen = [], set()
    for para in doc.find('p'):
        for link in para.find('a'):
            href = link.attrs.get('href', '')
            if not href.startswith('/book/'):
                continue
            url = book_url(href)
            if url in seen:
                continue
            seen.add(url)
            bold = list(para.find('b'))
            small = list(para.find('small'))
            language = next((n.text().strip('[]') for n in para.find('span') if re.fullmatch(r'\[[\w-]+\]', n.text())), None)
            authors = next((n.text() for n in bold if 'result' in n.attrs.get('class','').split()), '')
            results.append({
                'url': url, 'title': link.text(), 'author': authors, 'language': language,
                'tags': [n.text() for n in para.find('code')],
                'size': next((n.text() for n in bold if n.attrs.get('title')=='размер'), None),
                'description': next((n.text() for n in small if not n.attrs.get('title')), ''),
                'source': next((n.attrs['title'] for n in small if n.attrs.get('title') and not n.attrs['title'].startswith('добавлено')), None),
                'evidence': para.text(),
            })
    match = re.search(r'всего\s+([\d,\s]+)', doc.text())
    total = int(re.sub(r'\D', '', match[1])) if match else None
    pages = []
    for form in doc.find('form'):
        fields = {n.attrs.get('name'): n.attrs.get('value','') for n in form.find('input')}
        if fields.get('page','').isdigit():
            pages.append(int(fields['page']))
    return results, total, sorted(set(pages))

def details(client, url):
    url = book_url(url)
    text = client.html('GET', url)
    doc = Document(text).root
    fields = {n.attrs.get('name'): n.attrs.get('value','') for n in doc.find('input')}
    title, author = fields.get('title'), fields.get('author')
    if not title or not author:
        raise Failure('Book metadata absent; login expired or page changed')
    controls = {}
    for form in doc.find('form'):
        path = form.attrs.get('action','')
        if path.startswith('/read/entry/dl/') and form.attrs.get('method','get').lower()=='get':
            fmt = re.search(r'\((FB2|TXT|EPUB|MOBI)\)', form.text(), re.I)
            if fmt:
                controls[fmt[1].lower()] = safe_url(path)
    for link in doc.find('a'):
        path = link.attrs.get('href','')
        fmt = parse_qs(urlsplit(path).query).get('f',[''])[0]
        if path.startswith('/read/entry/dl/') and fmt in {'fb2','txt','epub','mobi'}:
            controls.setdefault(fmt, safe_url(path))
    paragraphs = [p.text() for p in doc.find('p') if not any(True for _ in p.find('a'))]
    metadata = next((p for p in paragraphs if 'Размер:' in p or 'Теги:' in p), '')
    source=re.search(r'Источник:\s*(.*?)\s*(?:Размер:|$)',metadata)
    size=re.search(r'Размер:\s*(\S+)',metadata)
    genres=re.search(r'Теги:\s*(.*?)\s*(?:Источник:|Размер:|$)',metadata)
    return {'url': url, 'title': title, 'author': author, 'formats': list(controls),
            'tags':genres[1].split() if genres else [], 'source':source[1] if source else None,
            'size':size[1] if size else None, 'metadata': metadata}, controls

def search(client, query, page=0, pages=1, sort='relevance', author='', title='', language='', limit=50, source=None):
    results, seen, scanned, total, next_page = [], set(), [], None, None
    for index in range(pages):
        fields = {'page': str(page), 'order_by': SORT[sort]}
        if source:
            url, key, value = source
            fields[key] = value
            text = client.html('GET', url+'?'+urlencode(fields))
        else:
            fields['query'] = query
            text = client.html('POST', '/search', data=fields)
        hits, total, navigation = listings(text)
        scanned.append(page)
        for hit in hits:
            if author and not contains_words(hit['author'], author): continue
            if title and not contains_words(hit['title'], title): continue
            if language and norm(hit['language'] or '') != norm(language): continue
            if hit['url'] not in seen:
                seen.add(hit['url']); results.append(hit)
        next_page = next((n for n in navigation if n > page), None)
        if next_page is None: break
        page = next_page
    if sort=='relevance' and title:
        results.sort(key=lambda h: norm(h['title'])!=norm(title))
    return {'results': results[:limit], 'count': len(results[:limit]), 'matched_in_scanned_pages': len(results),
            'site_total': total, 'pages_scanned': scanned, 'next_page': next_page, 'sort': sort,
            'truncated': len(results)>limit, 'query': query}

def authors(client, query, limit=50):
    # Follow links in the site's prefix index, then surname index; names/IDs are never guessed.
    root = Document(client.html('GET','/collection/authors')).root
    prefix = norm(query)[:2]
    prefixes = [a for a in root.find('a') if a.attrs.get('href','').startswith('/collection/authors_f2/') and norm(a.text())==prefix]
    if not prefixes: return {'results': [], 'count': 0}
    branch = Document(client.html('GET',safe_url(prefixes[0].attrs['href']))).root
    links = [a for a in branch.find('a') if a.attrs.get('href','').startswith('/collection/authors_match/') and norm(query) in norm(a.text())]
    exact = [a for a in links if norm(a.text())==norm(query)]
    if exact: links = exact
    results, seen = [], set()
    # Bounded discovery; expose unvisited surname branches instead of hiding truncation.
    for link in links[:5]:
        leaf = Document(client.html('GET',safe_url(link.attrs['href']))).root
        for a in leaf.find('a'):
            href = a.attrs.get('href','')
            if not href.startswith('/collection/authorsearch?'): continue
            name = parse_qs(urlsplit(href).query).get('author',[''])[0]
            if name and name not in seen:
                seen.add(name); results.append({'author': name, 'url': safe_url(href)})
    return {'results': results[:limit], 'count': len(results[:limit]), 'truncated': len(results)>limit or len(links)>5,
            'surname_branches': [{'name':x.text(),'url':safe_url(x.attrs['href'])} for x in links]}

def tags(client, query='', limit=50, offset=0):
    doc = Document(client.html('GET','/collection/tags')).root
    results = []
    for p in doc.find('p'):
        for a in p.find('a'):
            href = a.attrs.get('href','')
            if not href.startswith('/collection/tagsearch?'): continue
            tag = parse_qs(urlsplit(href).query).get('tag',[''])[0]
            if query and norm(query) not in norm(tag): continue
            results.append({'tag':tag,'url':safe_url(href),'evidence':p.text()})
    return {'results':results[offset:offset+limit], 'total':len(results), 'next_offset':offset+limit if offset+limit<len(results) else None}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--credentials',default='/souz/state/librusec-books/credentials.json')
    sub=p.add_subparsers(dest='action',required=True)
    for action in ('search','browse'):
        s=sub.add_parser(action)
        s.add_argument('--query',default='');s.add_argument('--author',default='');s.add_argument('--title',default='')
        s.add_argument('--language',default='');s.add_argument('--sort',choices=SORT,default='relevance')
        s.add_argument('--page',type=int,default=0);s.add_argument('--pages',type=int,default=1)
        s.add_argument('--limit',type=int,default=50)
        if action=='browse': s.add_argument('--url',required=True)
    s=sub.add_parser('authors');s.add_argument('--query',required=True);s.add_argument('--limit',type=int,default=50)
    s=sub.add_parser('tags');s.add_argument('--query',default='');s.add_argument('--limit',type=int,default=50);s.add_argument('--offset',type=int,default=0)
    s=sub.add_parser('book');s.add_argument('--url',required=True)
    a=p.parse_args()
    if not 1<=getattr(a,'limit',50)<=100 or not 1<=getattr(a,'pages',1)<=5 or min(getattr(a,'page',0),getattr(a,'offset',0))<0:
        raise Failure('Use limit 1..100, pages 1..5 and nonnegative page/offset')
    source=None
    if a.action=='search':
        a.query=a.query.strip() or ' '.join(x for x in (a.title,a.author) if x)
        if not a.query.strip(): raise Failure('Supply query or author/title')
    if a.action=='authors' and len(a.query.strip())<2: raise Failure('Supply at least two letters of a surname')
    if a.action=='browse':
        u=urlsplit(safe_url(a.url));key={'/collection/authorsearch':'author','/collection/tagsearch':'tag'}.get(u.path)
        value=parse_qs(u.query).get(key,[''])[0]
        if not key or not value: raise Failure('Use an author/tag catalogue URL returned by authors or tags')
        source=(u.path,key,value)
    c=Client();c.login(a.credentials)
    try:
        if a.action in ('search','browse'): result=search(c,a.query,a.page,a.pages,a.sort,a.author,a.title,a.language,a.limit,source)
        elif a.action=='authors': result=authors(c,a.query.strip(),a.limit)
        elif a.action=='tags': result=tags(c,a.query,a.limit,a.offset)
        else: result=details(c,a.url)[0]
        print(json.dumps(result,ensure_ascii=False))
    finally: c.close()

if __name__=='__main__':
    try: main()
    except Exception as exc:
        print('Catalogue failed: '+(str(exc) if isinstance(exc,Failure) else type(exc).__name__),file=sys.stderr)
        sys.exit(1)
